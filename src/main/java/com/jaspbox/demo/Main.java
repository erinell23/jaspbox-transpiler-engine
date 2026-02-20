package com.jaspbox.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaspbox.compiler.JaspBoxCompiler;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

public final class Main {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_RUNTIME_ERROR = 1;
    private static final int EXIT_INVALID_USAGE = 2;

    private Main() {}

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != EXIT_SUCCESS) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        if (args == null || args.length == 0) {
            printUsage(System.err);
            return EXIT_INVALID_USAGE;
        }

        if (isHelpArg(args[0])) {
            printUsage(System.out);
            return EXIT_SUCCESS;
        }

        ParsedArgs parsed;
        try {
            parsed = ParsedArgs.parse(args);
        } catch (IllegalArgumentException usageError) {
            System.err.println("Error de uso: " + usageError.getMessage());
            printUsage(System.err);
            return EXIT_INVALID_USAGE;
        }

        try {
            Path projectRoot = Paths.get("").toAbsolutePath();
            executeRun(projectRoot, parsed);
            return EXIT_SUCCESS;
        } catch (Exception runtimeError) {
            System.err.println("Error ejecutando convertidor: " + runtimeError.getMessage());
            runtimeError.printStackTrace(System.err);
            return EXIT_RUNTIME_ERROR;
        }
    }

    private static void executeRun(Path projectRoot, ParsedArgs parsed) throws Exception {
        Path jrxmlPath = resolveInputPath(projectRoot, parsed.jrxmlArg);
        Path jsonPath = resolveInputPath(projectRoot, parsed.jsonArg);

        validateInputFile(jrxmlPath, "JRXML");
        validateInputFile(jsonPath, "JSON");

        String templateBaseName = stripExtension(jrxmlPath.getFileName().toString());
        String resolvedClassName =
                parsed.className == null || parsed.className.isBlank()
                        ? toJavaClassName(templateBaseName) + "Template"
                        : parsed.className;
        String resolvedOutputPdf =
                parsed.outputPdfName == null || parsed.outputPdfName.isBlank()
                        ? templateBaseName + "-generated.pdf"
                        : parsed.outputPdfName;

        Map<String, Object> data = loadDataFromJson(jsonPath);
        ensureAllParametersPresent(jrxmlPath, data);

        TranspileResult generatedResult =
                runTranspiledReport(projectRoot, jrxmlPath, resolvedClassName, resolvedOutputPdf, data);

        System.out.println("Template generado en: " + generatedResult.generatedJava);
        System.out.println("Template compilado en: " + generatedResult.generatedClasses);
        System.out.println("PDF clase generada en: " + generatedResult.outputPdf);

        if (!parsed.compareWithJasper) {
            return;
        }

        String jasperOutputName = templateBaseName + "-jasper.pdf";
        Path jasperPdfPath = projectRoot.resolve("target/output/" + jasperOutputName);
        Files.createDirectories(jasperPdfPath.getParent());

        try {
            generatePdfWithJasper(jrxmlPath, data, jasperPdfPath);
            PdfComparisonResult comparisonResult = comparePdfs(jasperPdfPath, generatedResult.outputPdf);
            System.out.println("PDF Jasper generado en: " + jasperPdfPath);
            System.out.println("Comparación:");
            System.out.println("  - bytes iguales: " + comparisonResult.bytesEqual);
            System.out.println("  - páginas iguales: " + comparisonResult.pagesEqual);
            System.out.println("  - texto igual: " + comparisonResult.textEqual);
            System.out.println(
                    String.format(
                            Locale.US,
                            "  - diferencia visual promedio: %.4f%%",
                            comparisonResult.visualDifferenceRatio * 100d));
            System.out.println("  - equivalencia general: " + comparisonResult.equivalent);
        } catch (Exception compareError) {
            System.err.println(
                    "No fue posible comparar contra Jasper para " + jrxmlPath + ": "
                            + compareError.getMessage());
        }
    }

    private static boolean isHelpArg(String arg) {
        if (arg == null) {
            return false;
        }
        String normalized = arg.trim().toLowerCase(Locale.ROOT);
        return "-h".equals(normalized)
                || "--help".equals(normalized)
                || "help".equals(normalized)
                || "ayuda".equals(normalized);
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("Uso:");
        out.println("  run <archivo.jrxml> <archivo.json> [--compare] [--class NombreClase] [--out salida.pdf]");
        out.println();
        out.println("También soporta forma corta:");
        out.println("  <archivo.jrxml> <archivo.json> [--compare] [--class NombreClase] [--out salida.pdf]");
        out.println();
        out.println("Ejemplos:");
        out.println("  run local-input/payment-report.jrxml local-input/payment-report.json");
        out.println(
                "  run local-input/payment-report.jrxml local-input/payment-report.json --compare --class PaymentTemplate --out payment.pdf");
    }

    private static Path resolveInputPath(Path projectRoot, String input) {
        Path path = Paths.get(input);
        if (!path.isAbsolute()) {
            path = projectRoot.resolve(path);
        }
        return path.normalize();
    }

    private static void validateInputFile(Path path, String label) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " no encontrado: " + path);
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    private static String toJavaClassName(String rawName) {
        String sanitized = rawName.replaceAll("[^A-Za-z0-9]+", " ").trim();
        if (sanitized.isEmpty()) {
            return "Report";
        }

        StringBuilder builder = new StringBuilder();
        for (String part : sanitized.split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        if (builder.length() == 0 || !Character.isJavaIdentifierStart(builder.charAt(0))) {
            builder.insert(0, 'R');
        }

        for (int i = 1; i < builder.length(); i++) {
            if (!Character.isJavaIdentifierPart(builder.charAt(i))) {
                builder.setCharAt(i, '_');
            }
        }

        return builder.toString();
    }

    private static Map<String, Object> loadDataFromJson(Path jsonPath) throws IOException {
        Map<String, Object> parsed =
                OBJECT_MAPPER.readValue(jsonPath.toFile(), new TypeReference<Map<String, Object>>() {});
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(parsed);
    }

    private static TranspileResult runTranspiledReport(
            Path projectRoot,
            Path jrxmlPath,
            String className,
            String outputPdfName,
            Map<String, Object> data)
            throws Exception {
        Path generatedSources = projectRoot.resolve("target/generated-sources/jaspbox");
        Path generatedClasses = projectRoot.resolve("target/generated-classes/jaspbox");
        Path outputPdf = projectRoot.resolve("target/output/" + outputPdfName);

        Files.createDirectories(generatedSources);
        Files.createDirectories(generatedClasses);
        Files.createDirectories(outputPdf.getParent());

        JaspBoxCompiler compiler = new JaspBoxCompiler();
        Path generatedJava =
                compiler.transpile(jrxmlPath, generatedSources, "com.jaspbox.generated", className);
        compileGeneratedSource(generatedJava, generatedClasses);
        invokeTemplateBuild(generatedClasses, "com.jaspbox.generated." + className, data, outputPdf);

        return new TranspileResult(generatedJava, generatedClasses, outputPdf);
    }

    private static void generatePdfWithJasper(Path jrxmlPath, Map<String, Object> data, Path outputPdfPath)
            throws JRException, IOException {
        JasperDesign design;
        try (var inputStream = Files.newInputStream(jrxmlPath)) {
            design = JRXmlLoader.load(inputStream);
        }
        JasperReport report = JasperCompileManager.compileReport(design);

        Map<String, Object> parameters = new HashMap<>(data);
        List<Map<String, ?>> mainRows = buildMainRowsForJasper(design, data);
        JRMapCollectionDataSource dataSource = new JRMapCollectionDataSource(mainRows);

        JasperPrint print = JasperFillManager.fillReport(report, parameters, dataSource);
        JasperExportManager.exportReportToPdfFile(print, outputPdfPath.toString());
    }

    private static List<Map<String, ?>> buildMainRowsForJasper(JasperDesign design, Map<String, Object> data) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (JRField field : design.getFields()) {
            String fieldName = field.getName();
            Object value = data.get(fieldName);
            if (value == null) {
                value = "";
            }
            row.put(fieldName, value);
        }
        return Collections.singletonList(row);
    }

    private static void ensureAllParametersPresent(Path jrxmlPath, Map<String, Object> data)
            throws IOException, JRException {
        JasperDesign design;
        try (var inputStream = Files.newInputStream(jrxmlPath)) {
            design = JRXmlLoader.load(inputStream);
        }

        for (JRParameter parameter : design.getParameters()) {
            if (parameter.isSystemDefined() || data.containsKey(parameter.getName())) {
                continue;
            }

            String type = parameter.getValueClassName();
            Object fallback;
            if ("java.lang.String".equals(type)) {
                fallback = "";
            } else if ("java.lang.Integer".equals(type)) {
                fallback = 0;
            } else if ("java.lang.Double".equals(type)) {
                fallback = 0d;
            } else if ("java.lang.Boolean".equals(type)) {
                fallback = Boolean.FALSE;
            } else if ("java.util.ArrayList".equals(type) || "java.util.List".equals(type)) {
                fallback = new ArrayList<>();
            } else if ("net.sf.jasperreports.engine.data.JRBeanCollectionDataSource".equals(type)) {
                fallback = new JRBeanCollectionDataSource(Collections.emptyList());
            } else {
                fallback = null;
            }
            data.put(parameter.getName(), fallback);
        }
    }

    private static PdfComparisonResult comparePdfs(Path jasperPdf, Path generatedPdf) throws IOException {
        boolean bytesEqual = filesAreByteEqual(jasperPdf, generatedPdf);

        try (PDDocument jasperDoc = Loader.loadPDF(jasperPdf.toFile());
                PDDocument generatedDoc = Loader.loadPDF(generatedPdf.toFile())) {
            int jasperPages = jasperDoc.getNumberOfPages();
            int generatedPages = generatedDoc.getNumberOfPages();
            boolean pagesEqual = jasperPages == generatedPages;

            String jasperText = extractNormalizedText(jasperDoc);
            String generatedText = extractNormalizedText(generatedDoc);
            boolean textEqual = Objects.equals(jasperText, generatedText);

            double visualDifferenceRatio = calculateVisualDifference(jasperDoc, generatedDoc);
            boolean equivalent = pagesEqual && textEqual && visualDifferenceRatio < 0.02d;

            return new PdfComparisonResult(
                    bytesEqual, pagesEqual, textEqual, visualDifferenceRatio, equivalent);
        }
    }

    private static boolean filesAreByteEqual(Path leftPath, Path rightPath) throws IOException {
        if (Files.size(leftPath) != Files.size(rightPath)) {
            return false;
        }
        try (var left = Files.newInputStream(leftPath); var right = Files.newInputStream(rightPath)) {
            byte[] leftBuffer = new byte[8192];
            byte[] rightBuffer = new byte[8192];
            while (true) {
                int leftRead = left.read(leftBuffer);
                int rightRead = right.read(rightBuffer);
                if (leftRead != rightRead) {
                    return false;
                }
                if (leftRead == -1) {
                    return true;
                }
                for (int i = 0; i < leftRead; i++) {
                    if (leftBuffer[i] != rightBuffer[i]) {
                        return false;
                    }
                }
            }
        }
    }

    private static String extractNormalizedText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(document);
        return text.replaceAll("\\s+", " ").trim();
    }

    private static double calculateVisualDifference(PDDocument leftDoc, PDDocument rightDoc)
            throws IOException {
        int pageCount = Math.min(leftDoc.getNumberOfPages(), rightDoc.getNumberOfPages());
        if (pageCount == 0) {
            return leftDoc.getNumberOfPages() == rightDoc.getNumberOfPages() ? 0d : 1d;
        }

        PDFRenderer leftRenderer = new PDFRenderer(leftDoc);
        PDFRenderer rightRenderer = new PDFRenderer(rightDoc);

        long differentPixels = 0L;
        long totalPixels = 0L;
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            BufferedImage leftImage = leftRenderer.renderImageWithDPI(pageIndex, 72f, ImageType.RGB);
            BufferedImage rightImage = rightRenderer.renderImageWithDPI(pageIndex, 72f, ImageType.RGB);

            int width = Math.max(leftImage.getWidth(), rightImage.getWidth());
            int height = Math.max(leftImage.getHeight(), rightImage.getHeight());
            totalPixels += (long) width * height;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int leftRgb =
                            x < leftImage.getWidth() && y < leftImage.getHeight()
                                    ? leftImage.getRGB(x, y)
                                    : 0xFFFFFFFF;
                    int rightRgb =
                            x < rightImage.getWidth() && y < rightImage.getHeight()
                                    ? rightImage.getRGB(x, y)
                                    : 0xFFFFFFFF;
                    if (colorDistance(leftRgb, rightRgb) > 10) {
                        differentPixels++;
                    }
                }
            }
        }

        if (leftDoc.getNumberOfPages() != rightDoc.getNumberOfPages()) {
            int extraPages = Math.abs(leftDoc.getNumberOfPages() - rightDoc.getNumberOfPages());
            differentPixels += extraPages * 100_000L;
            totalPixels += extraPages * 100_000L;
        }

        if (totalPixels == 0L) {
            return 0d;
        }
        return (double) differentPixels / (double) totalPixels;
    }

    private static int colorDistance(int leftRgb, int rightRgb) {
        int lr = (leftRgb >> 16) & 0xFF;
        int lg = (leftRgb >> 8) & 0xFF;
        int lb = leftRgb & 0xFF;
        int rr = (rightRgb >> 16) & 0xFF;
        int rg = (rightRgb >> 8) & 0xFF;
        int rb = rightRgb & 0xFF;
        return Math.abs(lr - rr) + Math.abs(lg - rg) + Math.abs(lb - rb);
    }

    private static void compileGeneratedSource(Path javaFile, Path outputClassesDir) throws IOException {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        if (javaCompiler == null) {
            throw new IllegalStateException(
                    "No se encontro JavaCompiler. Ejecuta con un JDK 11+ (no solo JRE).");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                javaCompiler.getStandardFileManager(
                        diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromFiles(List.of(javaFile.toFile()));
            List<String> options =
                    List.of("-classpath", buildCompilerClasspath(), "-d", outputClassesDir.toString());

            JavaCompiler.CompilationTask task =
                    javaCompiler.getTask(null, fileManager, diagnostics, options, null, units);
            boolean ok = Boolean.TRUE.equals(task.call());
            if (!ok) {
                String errors =
                        diagnostics.getDiagnostics().stream()
                                .map(Main::formatDiagnostic)
                                .collect(Collectors.joining(System.lineSeparator()));
                throw new IllegalStateException(
                        "Fallo la compilacion del Template generado:" + System.lineSeparator() + errors);
            }
        }
    }

    private static String buildCompilerClasspath() {
        Set<String> entries = new LinkedHashSet<>();
        String runtimeClasspath = System.getProperty("java.class.path");
        if (runtimeClasspath != null && !runtimeClasspath.isBlank()) {
            entries.add(runtimeClasspath);
        }
        entries.add(resolveClasspathEntry(Main.class));
        entries.add(resolveClasspathEntry(PDDocument.class));
        return String.join(System.getProperty("path.separator"), entries);
    }

    private static String resolveClasspathEntry(Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            return Paths.get(location.toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "No se pudo resolver el classpath para " + type.getName(), e);
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        String source = diagnostic.getSource() == null ? "unknown" : diagnostic.getSource().getName();
        return source
                + ":"
                + diagnostic.getLineNumber()
                + ":"
                + diagnostic.getColumnNumber()
                + " "
                + diagnostic.getKind()
                + " "
                + diagnostic.getMessage(Locale.ROOT);
    }

    private static void invokeTemplateBuild(
            Path generatedClasses, String templateClassName, Map<String, Object> data, Path outputPdf)
            throws Exception {
        URL[] urls = {generatedClasses.toUri().toURL()};
        try (URLClassLoader classLoader = new URLClassLoader(urls, Main.class.getClassLoader())) {
            Class<?> templateClass = Class.forName(templateClassName, true, classLoader);
            Object template = templateClass.getDeclaredConstructor().newInstance();
            Method buildMethod = templateClass.getMethod("build", Map.class, String.class);
            try {
                buildMethod.invoke(template, data, outputPdf.toString());
            } catch (InvocationTargetException invocationTargetException) {
                Throwable cause = invocationTargetException.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw invocationTargetException;
            }
        }
    }

    private static final class ParsedArgs {
        private final String jrxmlArg;
        private final String jsonArg;
        private final boolean compareWithJasper;
        private final String className;
        private final String outputPdfName;

        private ParsedArgs(
                String jrxmlArg,
                String jsonArg,
                boolean compareWithJasper,
                String className,
                String outputPdfName) {
            this.jrxmlArg = jrxmlArg;
            this.jsonArg = jsonArg;
            this.compareWithJasper = compareWithJasper;
            this.className = className;
            this.outputPdfName = outputPdfName;
        }

        private static ParsedArgs parse(String[] args) {
            int index = 0;
            String first = args[0].trim();
            if ("run".equalsIgnoreCase(first) || "render".equalsIgnoreCase(first)) {
                index = 1;
            } else if (!first.toLowerCase(Locale.ROOT).endsWith(".jrxml")) {
                throw new IllegalArgumentException(
                        "Comando no reconocido. Usa 'run <jrxml> <json>' o '<jrxml> <json>'.");
            }

            if (args.length < index + 2) {
                throw new IllegalArgumentException(
                        "Debes indicar <archivo.jrxml> y <archivo.json>.");
            }

            String jrxmlArg = args[index];
            String jsonArg = args[index + 1];
            boolean compare = false;
            String className = null;
            String outputName = null;

            for (int i = index + 2; i < args.length; i++) {
                String arg = args[i];
                if ("--compare".equalsIgnoreCase(arg)) {
                    compare = true;
                    continue;
                }
                if ("--class".equalsIgnoreCase(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Falta valor para --class");
                    }
                    className = args[++i];
                    continue;
                }
                if ("--out".equalsIgnoreCase(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Falta valor para --out");
                    }
                    outputName = args[++i];
                    continue;
                }
                throw new IllegalArgumentException("Argumento no soportado: " + arg);
            }

            return new ParsedArgs(jrxmlArg, jsonArg, compare, className, outputName);
        }
    }

    private static final class TranspileResult {
        private final Path generatedJava;
        private final Path generatedClasses;
        private final Path outputPdf;

        private TranspileResult(Path generatedJava, Path generatedClasses, Path outputPdf) {
            this.generatedJava = generatedJava;
            this.generatedClasses = generatedClasses;
            this.outputPdf = outputPdf;
        }
    }

    private static final class PdfComparisonResult {
        private final boolean bytesEqual;
        private final boolean pagesEqual;
        private final boolean textEqual;
        private final double visualDifferenceRatio;
        private final boolean equivalent;

        private PdfComparisonResult(
                boolean bytesEqual,
                boolean pagesEqual,
                boolean textEqual,
                double visualDifferenceRatio,
                boolean equivalent) {
            this.bytesEqual = bytesEqual;
            this.pagesEqual = pagesEqual;
            this.textEqual = textEqual;
            this.visualDifferenceRatio = visualDifferenceRatio;
            this.equivalent = equivalent;
        }
    }
}
