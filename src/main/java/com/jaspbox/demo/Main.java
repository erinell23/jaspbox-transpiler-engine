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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

public class Main {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path projectRoot = Paths.get("").toAbsolutePath();

        if (isDynamicRun(args)) {
            runDynamicFromArgs(projectRoot, args);
            return;
        }

        RunMode runMode = RunMode.fromArgs(args);

        if (runMode == RunMode.PAYMENT_COMPARE) {
            runPaymentComparison(projectRoot);
            return;
        }

        if (runMode == RunMode.ROBUST) {
            TranspileResult result =
                    runTranspiledReport(
                            projectRoot,
                            "src/main/resources/example-robust.jrxml",
                            "ExampleRobustTemplate",
                            "example-robust-output.pdf",
                            buildRobustData());
            System.out.println("Template generado en: " + result.generatedJava);
            System.out.println("Template compilado en: " + result.generatedClasses);
            System.out.println("PDF generado en: " + result.outputPdf);
            return;
        }

        TranspileResult result =
                runTranspiledReport(
                        projectRoot,
                        "src/main/resources/test.jrxml",
                        "Template",
                        "template-output.pdf",
                        buildDemoData());
        System.out.println("Template generado en: " + result.generatedJava);
        System.out.println("Template compilado en: " + result.generatedClasses);
        System.out.println("PDF generado en: " + result.outputPdf);
    }

    private static boolean isDynamicRun(String[] args) {
        if (args.length < 2) {
            return false;
        }
        String first = args[0].trim().toLowerCase(Locale.ROOT);
        if ("run".equals(first) || "render".equals(first)) {
            return true;
        }
        return first.endsWith(".jrxml");
    }

    private static void runDynamicFromArgs(Path projectRoot, String[] args) throws Exception {
        int index = 0;
        if ("run".equalsIgnoreCase(args[0]) || "render".equalsIgnoreCase(args[0])) {
            index = 1;
        }

        if (args.length < index + 2) {
            throw new IllegalArgumentException(
                    "Uso: run <archivo.jrxml> <archivo.json> [--compare] [--class NombreClase] [--out salida.pdf]");
        }

        String jrxmlArg = args[index];
        String jsonArg = args[index + 1];
        boolean compareWithJasper = false;
        String className = null;
        String outputPdfName = null;

        for (int i = index + 2; i < args.length; i++) {
            String arg = args[i];
            if ("--compare".equalsIgnoreCase(arg)) {
                compareWithJasper = true;
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
                outputPdfName = args[++i];
                continue;
            }
            throw new IllegalArgumentException("Argumento no soportado: " + arg);
        }

        Path jrxmlPath = resolveInputPath(projectRoot, jrxmlArg);
        Path jsonPath = resolveInputPath(projectRoot, jsonArg);
        validateInputFile(jrxmlPath, "JRXML");
        validateInputFile(jsonPath, "JSON");

        String templateBaseName = stripExtension(jrxmlPath.getFileName().toString());
        String resolvedClassName =
                className == null || className.isBlank()
                        ? toJavaClassName(templateBaseName) + "Template"
                        : className;
        String resolvedOutputPdf =
                outputPdfName == null || outputPdfName.isBlank()
                        ? templateBaseName + "-generated.pdf"
                        : outputPdfName;

        Map<String, Object> data = loadDataFromJson(jsonPath);
        ensureAllParametersPresent(jrxmlPath, data);

        TranspileResult generatedResult =
                runTranspiledReport(
                        projectRoot, jrxmlPath, resolvedClassName, resolvedOutputPdf, data);

        System.out.println("Template generado en: " + generatedResult.generatedJava);
        System.out.println("Template compilado en: " + generatedResult.generatedClasses);
        System.out.println("PDF clase generada en: " + generatedResult.outputPdf);

        if (!compareWithJasper) {
            return;
        }

        String jasperOutputName = templateBaseName + "-jasper.pdf";
        Path jasperPdfPath = projectRoot.resolve("target/output/" + jasperOutputName);
        Files.createDirectories(jasperPdfPath.getParent());
        try {
            generatePdfWithJasper(jrxmlPath, data, jasperPdfPath);
            PdfComparisonResult comparisonResult =
                    comparePdfs(jasperPdfPath, generatedResult.outputPdf);
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

    private static void runPaymentComparison(Path projectRoot) throws Exception {
        Path jrxmlPath = projectRoot.resolve("src/main/resources/payment-report.jrxml");
        Map<String, Object> data = buildPaymentReportData();
        ensureAllParametersPresent(jrxmlPath, data);

        TranspileResult generatedResult =
                runTranspiledReport(
                        projectRoot,
                        "src/main/resources/payment-report.jrxml",
                        "PaymentReportTemplate",
                        "payment-report-generated.pdf",
                        data);

        Path jasperPdfPath = projectRoot.resolve("target/output/payment-report-jasper.pdf");
        Files.createDirectories(jasperPdfPath.getParent());
        generatePdfWithJasper(jrxmlPath, data, jasperPdfPath);

        PdfComparisonResult comparisonResult =
                comparePdfs(jasperPdfPath, generatedResult.outputPdf);

        System.out.println("Template generado en: " + generatedResult.generatedJava);
        System.out.println("Template compilado en: " + generatedResult.generatedClasses);
        System.out.println("PDF Jasper generado en: " + jasperPdfPath);
        System.out.println("PDF Clase generada en: " + generatedResult.outputPdf);
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
    }

    private static TranspileResult runTranspiledReport(
            Path projectRoot,
            String jrxmlRelativePath,
            String className,
            String outputPdfName,
            Map<String, Object> data)
            throws Exception {
        Path jrxmlPath = projectRoot.resolve(jrxmlRelativePath).normalize();
        return runTranspiledReport(projectRoot, jrxmlPath, className, outputPdfName, data);
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

        try (PDDocument jasperDoc = PDDocument.load(jasperPdf.toFile());
                PDDocument generatedDoc = PDDocument.load(generatedPdf.toFile())) {
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
            BufferedImage leftImage =
                    leftRenderer.renderImageWithDPI(pageIndex, 72f, ImageType.RGB);
            BufferedImage rightImage =
                    rightRenderer.renderImageWithDPI(pageIndex, 72f, ImageType.RGB);

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
                    List.of(
                            "-classpath",
                            buildCompilerClasspath(),
                            "-d",
                            outputClassesDir.toString());

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

    private static Map<String, Object> buildDemoData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("TITLE", "Estado de cuenta - Cliente Demo");
        data.put("SHOW_SUBTITLE", Boolean.TRUE);

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Suscripcion mensual", 49.90d));
        rows.add(row("Soporte premium", 19.50d));
        rows.add(row("Consumo adicional", 12.30d));
        data.put("TABLE_ROWS", rows);

        return data;
    }

    private static Map<String, Object> buildRobustData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("IMGLOGO", "iVBORw0KGgoAAAANSUhEUgAAAPAAAAAqCAMAAAC6LibpAAAC7lBMVEUAAAAAAAAAAABVAABAQEAzMzMrKyskJCQgICA5HBwzMzMuLi4rKysnJyckJCQzIiIwMDAtLS0rKysoKCgmJiYxJCQuLi4sLCwpKSknJycvJiYuLiQsLCwrKyspKSkwKCguJyctLSYsLCwrKyspKSkvKCguJyctLSYsLCwrKysqKiouKSktKCgsLCcrKysrKysqKiouKSktKCgsLCcrKysrKysqKiouKSktKCgsLCgrKycrKysuKiotKSktKCgsLCgrKycrKysuKiotKSksKSksLCgrKygrKystKiotKSksKSksLCgrKygrKystKiotKSksKSksLCgrKygrKystKiosKiosKSkrKSkrKygtKygtKiosKiosKSkrKSkrKygtKygtKiosKiosKSkrKSkrKygtKygtKiosKiosKSkrKSkrKyktKygsKiosKiosKSkrKSkrKyksKigsKiosKSkrKSktKyktKygsKigsKiosKSkrKSktKyktKygsKigsKiorKiorKSktKyksKyksKigsKiorKiorKSktKyksKyksKigsKiorKiorKSktKyksKyksKigsKigrKiotKSksKyksKyksKiksKigrKiotKSksKyksKyksKiksKigrKiotKSksKyksKyksKiksKigrKiotKSksKyksKyksKiksKiktKigsKiosKSksKykrKiktKigsKiosKSksKyksKikrKiktKigsKiosKSksKikrKiktKigsKiosKSksKyksKikrKiksKiksKiosKSksKyksKiktKiksKiksKigsKSksKyksKiktKiksKiksKigsKyksKiktKiksKiksKiksKiosKykrKiksKiksKiksKiksKiosKykrKiksKiksKiksKiksKigsKyktKiksKiksKiksKigsKiksKiksKiksKiksKiksKyksKiksKiksKiksKiksKiksKyksKiksKiksKiksKiksKiksKyksKiksKiksKin///9eCUvOAAAA+HRSTlMAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2Bh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");
        data.put("TITULAR", "Acme Corp");
        data.put("MONEDA", "USD");
        data.put("USUARIO", "usuario.demo");
        data.put("FECHA", "2026-02-20 09:30:00");
        data.put("id", 1001);
        data.put("transaction_type", "Transferencias internacionales");
        data.put("origin_account", "000123456789");
        data.put("origin_amount", 15420.75d);
        data.put("voucher", 987654);
        data.put("lote_id", "L-2026-0001");
        data.put("description", "Pago de proveedores internacionales");
        data.put("detail", "Detalle de pago internacional de prueba");
        data.put("creation_date", "2026-02-20 09:15:00");
        data.put("status", "Pendiente aprobación");
        data.put("prepare_user", "prep.demo");
        data.put("beneficiary_name", "Proveedor Demo LLC");
        data.put("beneficiary_account", "987654321000");
        data.put("beneficiary_bank_name", "Banco Destino Demo");
        data.put("economic_group_id", 501);
        data.put("preparation_type", "L");
        data.put("origin_account_name", "Cuenta Corriente Principal");
        data.put("origin_account_type", "CC");
        data.put("origin_currency", "USD");
        data.put("frequency_code", "WK");
        data.put("frequency_count", "12");
        data.put("start_date", "2026-03-01");
        data.put("finish_date", "2026-05-31");
        data.put("scheduled_time", "14:30");
        data.put("beneficiary_email", "beneficiario.demo@example.com");
        data.put("execution_type", "SCHEDULED");
        data.put("total_transactions", "3");
        data.put("debit_type", "D");
        data.put("ISPRE", Boolean.TRUE);
        data.put("destination_amount_prepare", "15420.75");
        data.put("destination_currency", "EUR");
        data.put("destination_bank_swift_code", "DEMOESMMXXX");
        data.put("user", "usuario.final");
        data.put("prepare_user_fullname", "Preparador Demo Completo");
        data.put("reference", "REF-2026-0001");
        data.put("frequency_day", "Lunes");
        data.put("intermediary_bank_name", "Banco Intermediario Demo");
        data.put("intermediary_bank_swift_code", "INTBICMMXXX");
        data.put("charge", "OUR");
        data.put("message_error", "");
        data.put("execution_date", "2026-03-01 14:30:00");
        data.put("frequency_value", "1");
        data.put("numberPending", "1");

        data.put("beneficiaryBank", "Banco Beneficiario Demo");
        data.put("beneficiaryAccount", "987654321000");
        data.put("transactionDescription", "Transferencia internacional de prueba");
        data.put("statusDescription", "Sin novedad");
        data.put("beneficiaryName", "Proveedor Demo LLC");
        data.put("transactionAmount", "EUR 15,420.75");
        data.put("beneficiaryBankCode", "BICDESTXXX");

        ArrayList<Map<String, Object>> aprobadores = buildApproversAsMaps();
        data.put("APROBADORES", aprobadores);
        data.put("levelone", aprobadores);
        data.put("leveltwo", aprobadores);

        for (int i = 1; i <= 8; i++) {
            data.put("table_" + i + "_rows", aprobadores);
        }

        return data;
    }

    private static Map<String, Object> buildPaymentReportData() {
        Map<String, Object> data = buildRobustData();

        ArrayList<Approver> approverBeans = new ArrayList<>();
        approverBeans.add(new Approver("Aprobador Nivel 0", "Aprobada", "2026-02-20 09:35", "0"));
        approverBeans.add(new Approver("Aprobador Nivel 1", "Aprobada", "2026-02-20 09:40", "1"));
        approverBeans.add(new Approver("Aprobador Nivel 2", "Aprobada", "2026-02-20 09:45", "2"));
        approverBeans.add(new Approver("Aprobador Nivel 3", "Aprobada", "2026-02-20 09:50", "3"));

        data.put("APROBADORES", approverBeans);
        data.put("levelone", new JRBeanCollectionDataSource(approverBeans));
        data.put("leveltwo", new JRBeanCollectionDataSource(approverBeans));
        return data;
    }

    private static ArrayList<Map<String, Object>> buildApproversAsMaps() {
        ArrayList<Map<String, Object>> aprobadores = new ArrayList<>();
        aprobadores.add(approverMap("Aprobador Nivel 0", "Aprobada", "2026-02-20 09:35", "0"));
        aprobadores.add(approverMap("Aprobador Nivel 1", "Aprobada", "2026-02-20 09:40", "1"));
        aprobadores.add(approverMap("Aprobador Nivel 2", "Aprobada", "2026-02-20 09:45", "2"));
        aprobadores.add(approverMap("Aprobador Nivel 3", "Aprobada", "2026-02-20 09:50", "3"));
        return aprobadores;
    }

    private static Map<String, Object> approverMap(
            String name, String status, String fecha, String level) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("status", status);
        item.put("fecha", fecha);
        item.put("level", level);
        return item;
    }

    private static Map<String, Object> row(String item, Double amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ITEM", item);
        row.put("AMOUNT", amount);
        return row;
    }

    private enum RunMode {
        DEFAULT,
        ROBUST,
        PAYMENT_COMPARE;

        private static RunMode fromArgs(String[] args) {
            if (args.length == 0) {
                return DEFAULT;
            }
            String flag = args[0].trim().toLowerCase(Locale.ROOT);
            if ("robust".equals(flag)) {
                return ROBUST;
            }
            if ("payment-compare".equals(flag) || "payment".equals(flag)) {
                return PAYMENT_COMPARE;
            }
            return DEFAULT;
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

    public static final class Approver {
        private final String name;
        private final String status;
        private final String fecha;
        private final String level;

        public Approver(String name, String status, String fecha, String level) {
            this.name = name;
            this.status = status;
            this.fecha = fecha;
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public String getFecha() {
            return fecha;
        }

        public String getLevel() {
            return level;
        }
    }

}
