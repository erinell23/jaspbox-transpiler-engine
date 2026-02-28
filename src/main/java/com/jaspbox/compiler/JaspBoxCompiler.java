package com.jaspbox.compiler;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import net.sf.jasperreports.components.table.BaseCell;
import net.sf.jasperreports.components.table.BaseColumn;
import net.sf.jasperreports.components.table.Cell;
import net.sf.jasperreports.components.table.Column;
import net.sf.jasperreports.components.table.ColumnGroup;
import net.sf.jasperreports.components.table.Row;
import net.sf.jasperreports.components.table.TableComponent;
import net.sf.jasperreports.engine.JRBand;
import net.sf.jasperreports.engine.JRBoxContainer;
import net.sf.jasperreports.engine.JRChild;
import net.sf.jasperreports.engine.JRCommonElement;
import net.sf.jasperreports.engine.JRCommonGraphicElement;
import net.sf.jasperreports.engine.JRDataset;
import net.sf.jasperreports.engine.JRDatasetRun;
import net.sf.jasperreports.engine.JRElement;
import net.sf.jasperreports.engine.JRElementGroup;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExpression;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.JRFrame;
import net.sf.jasperreports.engine.JRGraphicElement;
import net.sf.jasperreports.engine.JRImage;
import net.sf.jasperreports.engine.JRLine;
import net.sf.jasperreports.engine.JRLineBox;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JRRectangle;
import net.sf.jasperreports.engine.JRSection;
import net.sf.jasperreports.engine.JRStaticText;
import net.sf.jasperreports.engine.JRStyle;
import net.sf.jasperreports.engine.JRTextElement;
import net.sf.jasperreports.engine.JRTextField;
import net.sf.jasperreports.engine.JRVariable;
import net.sf.jasperreports.engine.component.Component;
import net.sf.jasperreports.engine.design.JRDesignComponentElement;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.type.HorizontalImageAlignEnum;
import net.sf.jasperreports.engine.type.HorizontalTextAlignEnum;
import net.sf.jasperreports.engine.type.LineDirectionEnum;
import net.sf.jasperreports.engine.type.ModeEnum;
import net.sf.jasperreports.engine.type.PositionTypeEnum;
import net.sf.jasperreports.engine.type.ScaleImageEnum;
import net.sf.jasperreports.engine.type.TextAdjustEnum;
import net.sf.jasperreports.engine.type.VerticalImageAlignEnum;
import net.sf.jasperreports.engine.type.VerticalTextAlignEnum;
import net.sf.jasperreports.engine.util.StyleResolver;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public class JaspBoxCompiler {

    private static final Pattern PARAM_REF = Pattern.compile("^\\$P\\{([\\w$.]+)}$");
    private static final Pattern FIELD_REF = Pattern.compile("^\\$F\\{([\\w$.]+)}$");
    private static final Pattern VAR_REF = Pattern.compile("^\\$V\\{([\\w$.]+)}$");
    private static final Pattern PARAM_REF_ANY = Pattern.compile("\\$P\\{([\\w$.]+)}");
    private static final Pattern PRINT_SIMPLE_COMPARE =
            Pattern.compile("^\\$(P|F|V)\\{([\\w$.]+)}\\s*(==|!=)\\s*(.+)$");
    private static final Pattern BASE64_CHARS = Pattern.compile("^[A-Za-z0-9+/=\\s]+$");

    public Path transpile(Path jrxmlPath, Path outputDir, String packageName, String className)
            throws IOException, JRException {
        try (InputStream inputStream = Files.newInputStream(jrxmlPath)) {
            JasperDesign design = JRXmlLoader.load(inputStream);
            return transpile(design, outputDir, packageName, className);
        }
    }

    public Path transpile(JasperDesign design, Path outputDir, String packageName, String className)
            throws IOException {
        Map<String, String> parameterTypes = collectParameterTypes(design);
        Map<String, String> fieldTypes = collectFieldTypes(design);
        Map<String, String> variableTypes = collectVariableTypes(design);
        Map<String, String> variableExpressions = collectVariableExpressions(design);
        Map<String, String> importAliases = collectImportAliases(design);
        Map<String, Base64Constant> base64Constants = collectBase64ParameterConstants(design);
        boolean repeatPageHeaderOnNewPage =
                resolveBooleanDesignProperty(design, "jaspbox.repeatPageHeaderOnNewPage", true);
        boolean repeatColumnHeaderOnNewPage =
                resolveBooleanDesignProperty(design, "jaspbox.repeatColumnHeaderOnNewPage", true);
        boolean repeatBackgroundOnNewPage =
                resolveBooleanDesignProperty(design, "jaspbox.repeatBackgroundOnNewPage", true);
        boolean repeatPageFooterOnNewPage =
                resolveBooleanDesignProperty(design, "jaspbox.repeatPageFooterOnNewPage", true);
        boolean repeatColumnFooterOnNewPage =
                resolveBooleanDesignProperty(design, "jaspbox.repeatColumnFooterOnNewPage", true);

        GenerationContext context =
                new GenerationContext(
                        design,
                        parameterTypes,
                        fieldTypes,
                        variableTypes,
                        variableExpressions,
                        importAliases,
                        base64Constants,
                        repeatPageHeaderOnNewPage,
                        repeatColumnHeaderOnNewPage,
                        repeatBackgroundOnNewPage,
                        repeatPageFooterOnNewPage,
                        repeatColumnFooterOnNewPage);

        TypeSpec.Builder generatedClass =
                TypeSpec.classBuilder(className)
                        .superclass(ClassName.get("com.jaspbox.runtime", "AbstractTemplateRuntime"))
                        .addModifiers(Modifier.PUBLIC);

        for (Base64Constant constant : base64Constants.values()) {
            generatedClass.addField(
                    FieldSpec.builder(
                                    String.class,
                                    constant.constantName,
                                    Modifier.PUBLIC,
                                    Modifier.STATIC,
                                    Modifier.FINAL)
                            .initializer("$S", constant.value)
                            .build());
        }

        generatedClass.addField(buildExpressionCacheField());
        generatedClass.addMethod(buildParameterTypeLookupMethod(parameterTypes));
        generatedClass.addMethod(buildFieldTypeLookupMethod(fieldTypes));
        generatedClass.addMethod(buildVariableTypeLookupMethod(variableTypes));
        generatedClass.addMethod(buildTranslateExpressionMethod(context));
        generatedClass.addMethod(buildCompileExpressionMethod(context));
        generatedClass.addMethod(buildEvalCompiledExpressionMethod());
        generatedClass.addMethod(buildEvalAsObjectMethod());
        generatedClass.addMethod(buildEvalAsStringMethod());
        generatedClass.addMethod(buildEvalPrintWhenMethod());
        generatedClass.addMethod(buildRenderRepeatedPageBandsMethod(context));
        generatedClass.addMethod(buildRenderTrailingPageBandsMethod(context));
        generatedClass.addMethod(buildBuildBytesMethod());
        generatedClass.addMethod(buildBuildBytesWithDataSourceMethod(context));
        generatedClass.addMethod(buildBuildMethod());
        generatedClass.addMethod(buildBuildWithDataSourceMethod());

        JavaFile javaFile = JavaFile.builder(packageName, generatedClass.build())
                .skipJavaLangImports(true)
                .indent("    ")
                .build();

        Path packageDir = outputDir.resolve(packageName.replace('.', '/'));
        Files.createDirectories(packageDir);

        Path targetFile = packageDir.resolve(className + ".java");
        try (Writer writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            javaFile.writeTo(writer);
        }
        return targetFile;
    }

    private MethodSpec buildBuildBytesMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);

        return MethodSpec.methodBuilder("buildBytes")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(mapType, "data")
                .returns(byte[].class)
                .addException(IOException.class)
                .addStatement("return buildBytes(data, null)")
                .build();
    }

    private MethodSpec buildBuildBytesWithDataSourceMethod(GenerationContext context) {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);
        ParameterizedTypeName listOfMapType =
                ParameterizedTypeName.get(ClassName.get(List.class), mapType);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("buildBytes")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(mapType, "data")
                        .addParameter(listOfMapType, "dataSource")
                        .returns(byte[].class)
                        .addException(IOException.class);

        methodBuilder.beginControlFlow("if (data == null)")
                .addStatement("throw new IllegalArgumentException($S)", "data no puede ser null")
                .endControlFlow();

        methodBuilder.addStatement("data.put($S, Integer.valueOf(1))", "PAGE_NUMBER");
        methodBuilder.addStatement("data.putIfAbsent($S, Integer.valueOf(1))", "REPORT_COUNT");
        for (Map.Entry<String, String> variableEntry : context.variableExpressions.entrySet()) {
            methodBuilder.addStatement(
                    "data.put($S, evalAsObject(data, data, $S))",
                    variableEntry.getKey(),
                    variableEntry.getValue());
        }

        String detailRowsVar = context.nextVar("detailRows");
        methodBuilder.addStatement(
                "$T<$T<$T, Object>> $L",
                List.class,
                Map.class,
                String.class,
                detailRowsVar);
        methodBuilder.beginControlFlow("if (dataSource != null && !dataSource.isEmpty())");
        methodBuilder.addStatement("$L = dataSource", detailRowsVar);
        methodBuilder.nextControlFlow("else");
        methodBuilder.addStatement("$L = new $T<>()", detailRowsVar, ArrayList.class);
        methodBuilder.addStatement("$L.add(data)", detailRowsVar);
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("$T document = new $T()", PDDocument.class, PDDocument.class);
        methodBuilder.addStatement("$T contentStream = null", PDPageContentStream.class);
        methodBuilder.beginControlFlow("try");
        methodBuilder.addStatement(
                "$T page = new $T(new $T($Lf, $Lf))",
                PDPage.class,
                PDPage.class,
                PDRectangle.class,
                (float) context.design.getPageWidth(),
                (float) context.design.getPageHeight());
        methodBuilder.addStatement("document.addPage(page)");
        methodBuilder.addStatement("contentStream = new $T(document, page)", PDPageContentStream.class);
        methodBuilder.addStatement("float pageHeight = page.getMediaBox().getHeight()");

        emitReportContent(context, methodBuilder, detailRowsVar);

        methodBuilder.beginControlFlow("if (contentStream != null)");
        methodBuilder.addStatement("contentStream.close()");
        methodBuilder.addStatement("contentStream = null");
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("$T out = new $T()", java.io.ByteArrayOutputStream.class, java.io.ByteArrayOutputStream.class);
        methodBuilder.addStatement("document.save(out)");
        methodBuilder.addStatement("return out.toByteArray()");
        methodBuilder.nextControlFlow("finally");
        methodBuilder.beginControlFlow("if (contentStream != null)");
        methodBuilder.addStatement("contentStream.close()");
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("document.close()");
        methodBuilder.endControlFlow();

        return methodBuilder.build();
    }

    private MethodSpec buildBuildMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("build")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(mapType, "data")
                        .addParameter(String.class, "outputPath")
                        .addException(IOException.class);

        methodBuilder.beginControlFlow("if (outputPath == null || outputPath.isBlank())")
                .addStatement("throw new IllegalArgumentException($S)", "outputPath no puede ser null o vacío")
                .endControlFlow();
        methodBuilder.addStatement("build(data, null, outputPath)");

        return methodBuilder.build();
    }

    private MethodSpec buildBuildWithDataSourceMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);
        ParameterizedTypeName listOfMapType =
                ParameterizedTypeName.get(ClassName.get(List.class), mapType);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("build")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(mapType, "data")
                        .addParameter(listOfMapType, "dataSource")
                        .addParameter(String.class, "outputPath")
                        .addException(IOException.class);

        methodBuilder.beginControlFlow("if (outputPath == null || outputPath.isBlank())")
                .addStatement("throw new IllegalArgumentException($S)", "outputPath no puede ser null o vacío")
                .endControlFlow();
        methodBuilder.addStatement("byte[] pdfBytes = buildBytes(data, dataSource)");
        methodBuilder.addStatement("$T output = $T.get(outputPath)", java.nio.file.Path.class, java.nio.file.Paths.class);
        methodBuilder.addStatement("$T parent = output.getParent()", java.nio.file.Path.class);
        methodBuilder.beginControlFlow("if (parent != null)");
        methodBuilder.addStatement("$T.createDirectories(parent)", java.nio.file.Files.class);
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("$T.write(output, pdfBytes)", java.nio.file.Files.class);

        return methodBuilder.build();
    }

    private void emitReportContent(
            GenerationContext context, MethodSpec.Builder methodBuilder, String detailRowsVar) {
        float leftMargin = context.design.getLeftMargin();
        float topMargin = context.design.getTopMargin();
        float bottomMargin = context.design.getBottomMargin();
        float trailingReservedHeight =
                resolveBandHeight(context.design.getColumnFooter())
                        + Math.max(
                                resolveBandHeight(context.design.getPageFooter()),
                                resolveBandHeight(context.design.getLastPageFooter()));
        String cursorYVar = context.nextVar("cursorY");
        String maxCursorYVar = context.nextVar("maxCursorY");
        methodBuilder.addStatement("float $L = $Lf", cursorYVar, topMargin);
        methodBuilder.addStatement(
                "float $L = Math.max(pageHeight - $Lf - $Lf, $Lf)",
                maxCursorYVar,
                bottomMargin,
                trailingReservedHeight,
                topMargin);

        if (context.design.getTitle() != null) {
            String consumedVar =
                    emitBand(
                    context,
                    methodBuilder,
                    context.design.getTitle(),
                    floatLiteral(leftMargin),
                    cursorYVar,
                    "data");
            methodBuilder.addStatement("$L += $L", cursorYVar, consumedVar);
        }

        methodBuilder.addStatement(
                "$L = renderRepeatedPageBands(document, contentStream, pageHeight, $L, data, false)",
                cursorYVar,
                cursorYVar);

        JRSection detailSection = context.design.getDetailSection();
        if (detailSection != null && detailSection.getBands() != null) {
            String detailRowVar = context.nextVar("detailRow");
            String detailIndexVar = context.nextVar("detailIndex");
            float detailBandsEstimatedHeight = 0f;
            for (JRBand detailBand : detailSection.getBands()) {
                if (detailBand != null) {
                    detailBandsEstimatedHeight += detailBand.getHeight();
                }
            }
            methodBuilder.addStatement("int $L = 0", detailIndexVar);
            methodBuilder.beginControlFlow("for ($T<$T, Object> $L : $L)", Map.class, String.class, detailRowVar, detailRowsVar);
            methodBuilder.addStatement("data.put($S, Integer.valueOf(++$L))", "REPORT_COUNT", detailIndexVar);
            if (detailBandsEstimatedHeight > 0f) {
                String currentPageVar = context.nextVar("currentPage");
                methodBuilder.beginControlFlow(
                        "if ($L + $Lf > $L)",
                        cursorYVar,
                        detailBandsEstimatedHeight,
                        maxCursorYVar);
                methodBuilder.addStatement(
                        "$L = renderTrailingPageBands(document, contentStream, pageHeight, $L, data, false)",
                        cursorYVar,
                        cursorYVar);
                methodBuilder.addStatement("contentStream.close()");
                methodBuilder.addStatement(
                        "int $L = ((Number) readTypedValue(data, $S, $S)).intValue()",
                        currentPageVar,
                        "PAGE_NUMBER",
                        "java.lang.Integer");
                methodBuilder.addStatement("data.put($S, Integer.valueOf($L + 1))", "PAGE_NUMBER", currentPageVar);
                methodBuilder.addStatement(
                        "page = new $T(new $T($Lf, $Lf))",
                        PDPage.class,
                        PDRectangle.class,
                        (float) context.design.getPageWidth(),
                        (float) context.design.getPageHeight());
                methodBuilder.addStatement("document.addPage(page)");
                methodBuilder.addStatement(
                        "contentStream = new $T(document, page)",
                        PDPageContentStream.class);
                methodBuilder.addStatement("pageHeight = page.getMediaBox().getHeight()");
                methodBuilder.addStatement("$L = pageHeight - $Lf", maxCursorYVar, bottomMargin);
                methodBuilder.addStatement(
                        "$L = renderRepeatedPageBands(document, contentStream, pageHeight, $Lf, data, true)",
                        cursorYVar,
                        topMargin);
                methodBuilder.endControlFlow();
            }
            for (JRBand detailBand : detailSection.getBands()) {
                String consumedVar =
                        emitBand(
                                context,
                                methodBuilder,
                                detailBand,
                                floatLiteral(leftMargin),
                                cursorYVar,
                                detailRowVar);
                methodBuilder.addStatement("$L += $L", cursorYVar, consumedVar);
            }
            methodBuilder.endControlFlow();
        }

        if (context.design.getSummary() != null) {
            String consumedVar =
                    emitBand(
                    context,
                    methodBuilder,
                    context.design.getSummary(),
                    floatLiteral(leftMargin),
                    cursorYVar,
                    "data");
            methodBuilder.addStatement("$L += $L", cursorYVar, consumedVar);
        }

        if (context.design.getNoData() != null) {
            emitBand(
                    context,
                    methodBuilder,
                    context.design.getNoData(),
                    floatLiteral(leftMargin),
                    cursorYVar,
                    "data");
        }

        methodBuilder.addStatement(
                "$L = renderTrailingPageBands(document, contentStream, pageHeight, $L, data, true)",
                cursorYVar,
                cursorYVar);
    }

    private String emitBand(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRBand band,
            String xBaseExpr,
            String yBaseExpr,
            String dataRef) {
        if (band == null) {
            return "0f";
        }

        String consumedVar = context.nextVar("bandConsumed");
        methodBuilder.addStatement("float $L = 0f", consumedVar);
        CodeBlock bandCondition = buildPrintWhenCondition(context, band.getPrintWhenExpression(), dataRef);
        if (bandCondition != null) {
            methodBuilder.beginControlFlow("if ($L)", bandCondition);
        }

        if (band.getElements() == null || band.getElements().length == 0) {
            methodBuilder.addStatement("$L = $Lf", consumedVar, (float) band.getHeight());
        } else {
            String collapsedVar = context.nextVar("bandCollapsed");
            String renderedBottomVar = context.nextVar("bandRenderedBottom");
            methodBuilder.addStatement("float $L = 0f", collapsedVar);
            methodBuilder.addStatement("float $L = 0f", renderedBottomVar);
            String previousBandRenderedBottomVar = context.currentBandRenderedBottomVar;
            context.currentBandRenderedBottomVar = renderedBottomVar;
            emitBandElementsWithCompaction(
                    context,
                    methodBuilder,
                    Arrays.asList(band.getElements()),
                    xBaseExpr,
                    yBaseExpr,
                    dataRef,
                    collapsedVar);
            context.currentBandRenderedBottomVar = previousBandRenderedBottomVar;
            methodBuilder.addStatement(
                    "$L = Math.max(Math.max($Lf - $L, $L), 0f)",
                    consumedVar,
                    (float) band.getHeight(),
                    collapsedVar,
                    renderedBottomVar);
        }

        if (bandCondition != null) {
            methodBuilder.endControlFlow();
        }

        return consumedVar;
    }

    private void emitBandElementsWithCompaction(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            List<JRElement> elements,
            String xBaseExpr,
            String yBaseExpr,
            String dataRef,
            String collapsedVar) {
        if (elements == null || elements.isEmpty()) {
            return;
        }

        String collapseStartVar = context.nextVar("collapseStartY");
        methodBuilder.addStatement("float $L = -1f", collapseStartVar);
        String visualCollapsedVar = context.nextVar("visualCollapsedY");
        methodBuilder.addStatement("float $L = 0f", visualCollapsedVar);

        List<List<JRElement>> rows = new ArrayList<>();
        List<JRElement> currentRow = new ArrayList<>();
        int currentY = Integer.MIN_VALUE;
        for (JRElement element : elements) {
            if (currentRow.isEmpty()) {
                currentRow.add(element);
                currentY = element.getY();
                continue;
            }
            if (element.getY() != currentY) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentY = element.getY();
            }
            currentRow.add(element);
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        int maxRowYSeen = Integer.MIN_VALUE;
        for (List<JRElement> rowElements : rows) {
            int rowY = rowElements.get(0).getY();
            boolean overlayRow = rowY < maxRowYSeen;
            maxRowYSeen = Math.max(maxRowYSeen, rowY);
            boolean rowHasRemovableElement = hasRemovableElement(rowElements);
            String rowVisibleVar = context.nextVar("rowVisible");
            methodBuilder.addStatement("boolean $L = false", rowVisibleVar);

            Map<JRElement, String> elementVisibilityVars = new LinkedHashMap<>();
            for (JRElement rowElement : rowElements) {
                CodeBlock visibilityCondition =
                        buildElementVisibilityCondition(context, rowElement, dataRef);
                String elementVisibleVar = context.nextVar("elementVisible");
                methodBuilder.addStatement(
                        "boolean $L = ($L)", elementVisibleVar, visibilityCondition);
                methodBuilder.addStatement(
                        "$L = $L || $L", rowVisibleVar, rowVisibleVar, elementVisibleVar);
                elementVisibilityVars.put(rowElement, elementVisibleVar);
            }

            boolean rowRemovable = isRowRemovable(rowElements);
            if (rowRemovable) {
                float collapseHeight = resolveRowCollapseHeight(context, rowElements);
                methodBuilder.beginControlFlow("if (!$L)", rowVisibleVar);
                if (!overlayRow) {
                    methodBuilder.beginControlFlow("if ($L < 0f)", collapseStartVar);
                    methodBuilder.addStatement("$L = $Lf", collapseStartVar, (float) rowY);
                    methodBuilder.endControlFlow();
                    methodBuilder.addStatement("$L += $Lf", collapsedVar, collapseHeight);
                }
                methodBuilder.nextControlFlow("else");
            } else if (rowHasRemovableElement) {
                float visualCollapseHeight = Math.min(resolveRowCollapseHeight(context, rowElements), 10f);
                methodBuilder.beginControlFlow("if (!$L)", rowVisibleVar);
                if (!overlayRow) {
                    methodBuilder.beginControlFlow("if ($L < 0f)", collapseStartVar);
                    methodBuilder.addStatement("$L = $Lf", collapseStartVar, (float) rowY);
                    methodBuilder.endControlFlow();
                    methodBuilder.addStatement("$L += $Lf", visualCollapsedVar, visualCollapseHeight);
                }
                methodBuilder.nextControlFlow("else");
            }

            for (JRElement rowElement : rowElements) {
                String elementVisibleVar = elementVisibilityVars.get(rowElement);
                methodBuilder.beginControlFlow("if ($L)", elementVisibleVar);

                String elementShiftVar = context.nextVar("elementShift");
                methodBuilder.addStatement(
                        "float $L = ($L < 0f || $Lf < $L) ? 0f : ($L + $L)",
                        elementShiftVar,
                        collapseStartVar,
                        (float) rowElement.getY(),
                        collapseStartVar,
                        collapsedVar,
                        visualCollapsedVar);
                String adjustedBaseYVar = context.nextVar("adjustedBaseY");
                methodBuilder.addStatement(
                        "float $L = $L - $L",
                        adjustedBaseYVar,
                        yBaseExpr,
                        elementShiftVar);

                String previousElementShiftVar = context.currentElementShiftVar;
                context.currentElementShiftVar = elementShiftVar;
                emitElementInternal(
                        context,
                        methodBuilder,
                        rowElement,
                        xBaseExpr,
                        adjustedBaseYVar,
                        dataRef);
                context.currentElementShiftVar = previousElementShiftVar;
                if (context.currentBandRenderedBottomVar != null) {
                    methodBuilder.addStatement(
                            "$L = Math.max($L, $Lf - $L + $Lf)",
                            context.currentBandRenderedBottomVar,
                            context.currentBandRenderedBottomVar,
                            (float) rowElement.getY(),
                            elementShiftVar,
                            (float) rowElement.getHeight());
                }
                methodBuilder.endControlFlow();
            }

            if (rowRemovable) {
                methodBuilder.endControlFlow();
            } else if (rowHasRemovableElement) {
                methodBuilder.endControlFlow();
            }
        }
    }

    private void emitElementList(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            List<JRElement> elements,
            String xBaseExpr,
            String yBaseExpr,
            String dataRef) {
        if (elements == null || elements.isEmpty()) {
            return;
        }

        List<JRElement> ordered = new ArrayList<>(elements);
        ordered.sort(Comparator.comparingInt(JRElement::getY).thenComparingInt(JRElement::getX));

        String floatCursorVar = context.nextVar("floatCursorY");
        methodBuilder.addStatement("float $L = $L", floatCursorVar, yBaseExpr);

        for (JRElement element : ordered) {
            boolean isFloat = PositionTypeEnum.FLOAT.equals(element.getPositionTypeValue());
            if (!isFloat) {
                emitElement(context, methodBuilder, element, xBaseExpr, yBaseExpr, dataRef);
                continue;
            }

            String naturalYExpr = plus(yBaseExpr, element.getY());
            String actualYVar = context.nextVar("floatElemY");
            methodBuilder.addStatement(
                    "float $L = Math.max($L, $L)",
                    actualYVar,
                    naturalYExpr,
                    floatCursorVar);
            String adjustedBaseYExpr = "(" + actualYVar + " - " + floatLiteral(element.getY()) + ")";

            CodeBlock condition =
                    buildPrintWhenCondition(context, element.getPrintWhenExpression(), dataRef);
            if (condition != null) {
                methodBuilder.beginControlFlow("if ($L)", condition);
            }
            emitElementInternal(
                    context,
                    methodBuilder,
                    element,
                    xBaseExpr,
                    adjustedBaseYExpr,
                    dataRef);
            methodBuilder.addStatement(
                    "$L = Math.max($L, $L + $Lf)",
                    floatCursorVar,
                    floatCursorVar,
                    actualYVar,
                    (float) element.getHeight());
            if (condition != null) {
                methodBuilder.endControlFlow();
            }
        }
    }

    private void emitElement(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRElement element,
            String xBaseExpr,
            String yBaseExpr,
            String dataRef) {
        CodeBlock condition = buildPrintWhenCondition(context, element.getPrintWhenExpression(), dataRef);
        if (condition != null) {
            methodBuilder.beginControlFlow("if ($L)", condition);
        }

        emitElementInternal(context, methodBuilder, element, xBaseExpr, yBaseExpr, dataRef);

        if (condition != null) {
            methodBuilder.endControlFlow();
        }
    }

    private boolean isRowRemovable(List<JRElement> rowElements) {
        if (rowElements == null || rowElements.isEmpty()) {
            return false;
        }
        for (JRElement rowElement : rowElements) {
            if (!rowElement.isRemoveLineWhenBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRemovableElement(List<JRElement> rowElements) {
        if (rowElements == null || rowElements.isEmpty()) {
            return false;
        }
        for (JRElement rowElement : rowElements) {
            if (rowElement.isRemoveLineWhenBlank()) {
                return true;
            }
        }
        return false;
    }

    private float resolveRowCollapseHeight(GenerationContext context, List<JRElement> rowElements) {
        float textMaxHeight = 0f;
        float nonTextMaxHeight = 0f;
        for (JRElement rowElement : rowElements) {
            if (rowElement instanceof JRTextElement) {
                textMaxHeight = Math.max(textMaxHeight, rowElement.getHeight());
            } else {
                nonTextMaxHeight = Math.max(nonTextMaxHeight, rowElement.getHeight());
            }
        }

        if (nonTextMaxHeight > 0f) {
            return Math.max(nonTextMaxHeight, Math.min(textMaxHeight, 20f));
        }
        if (textMaxHeight > 0f) {
            if (textMaxHeight >= 30f) {
                return textMaxHeight;
            }
            return Math.min(textMaxHeight, 20f);
        }
        return 0f;
    }

    private CodeBlock buildElementVisibilityCondition(
            GenerationContext context, JRElement element, String dataRef) {
        CodeBlock printCondition = buildPrintWhenCondition(context, element.getPrintWhenExpression(), dataRef);
        if (!element.isRemoveLineWhenBlank()) {
            return printCondition == null ? CodeBlock.of("true") : printCondition;
        }

        if (element instanceof JRTextField) {
            JRTextField textField = (JRTextField) element;
            CodeBlock textCode = resolveExpressionAsStringCode(context, textField.getExpression(), dataRef);
            if (printCondition == null) {
                return CodeBlock.of("!sanitizeText($L).trim().isEmpty()", textCode);
            }
            return CodeBlock.of(
                    "($L) && !sanitizeText($L).trim().isEmpty()",
                    printCondition,
                    textCode);
        }

        if (element instanceof JRStaticText) {
            String staticText = safeString(((JRStaticText) element).getText());
            boolean hasText = !staticText.trim().isEmpty();
            if (printCondition == null) {
                return CodeBlock.of("$L", hasText);
            }
            return CodeBlock.of("($L) && $L", printCondition, hasText);
        }

        return printCondition == null ? CodeBlock.of("true") : printCondition;
    }

    private void emitElementInternal(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRElement element,
            String xBaseExpr,
            String yBaseExpr,
            String dataRef) {
        Integer previousElementY = context.currentElementY;
        context.currentElementY = element.getY();
        String xExpr = plus(xBaseExpr, element.getX());
        String yExpr = plus(yBaseExpr, element.getY());

        if (element instanceof JRStaticText) {
            emitStaticText(context, methodBuilder, (JRStaticText) element, xExpr, yExpr);
        } else if (element instanceof JRTextField) {
            emitTextField(context, methodBuilder, (JRTextField) element, xExpr, yExpr, dataRef);
        } else if (element instanceof JRImage) {
            emitImage(context, methodBuilder, (JRImage) element, xExpr, yExpr, dataRef);
        } else if (element instanceof JRFrame) {
            emitFrame(context, methodBuilder, (JRFrame) element, xExpr, yExpr, dataRef);
        } else if (element instanceof JRRectangle) {
            emitRectangle(context, methodBuilder, (JRRectangle) element, xExpr, yExpr);
        } else if (element instanceof JRLine) {
            emitLine(context, methodBuilder, (JRLine) element, xExpr, yExpr);
        } else if (element instanceof JRDesignComponentElement) {
            emitComponentElement(
                    context, methodBuilder, (JRDesignComponentElement) element, xExpr, yExpr, dataRef);
        }
        context.currentElementY = previousElementY;
    }

    private void emitStaticText(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRStaticText staticText,
            String xExpr,
            String yExpr) {
        CodeBlock textCode = CodeBlock.of("$S", safeString(staticText.getText()));
        emitTextElement(context, methodBuilder, staticText, textCode, xExpr, yExpr);
    }

    private void emitTextField(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRTextField textField,
            String xExpr,
            String yExpr,
            String dataRef) {
        CodeBlock textCode = resolveExpressionAsStringCode(context, textField.getExpression(), dataRef);
        emitTextElement(context, methodBuilder, textField, textCode, xExpr, yExpr);
    }

    private void emitTextElement(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRTextElement textElement,
            CodeBlock textCode,
            String xExpr,
            String yExpr) {
        float width = textElement.getWidth();
        float height = textElement.getHeight();
        String yPdfVar = context.nextVar("yPdf");
        methodBuilder.addStatement(
                "float $L = toPdfY(pageHeight, $L, $Lf)", yPdfVar, yExpr, height);

        emitOpaqueBackground(context, methodBuilder, textElement, xExpr, yPdfVar, width, height);
        emitLineBoxBorders(context, methodBuilder, textElement.getLineBox(), xExpr, yPdfVar, width, height);

        String textVar = context.nextVar("txt");
        methodBuilder.addStatement("String $L = sanitizeText($L)", textVar, textCode);

        String resolvedFontName = safeString(context.styleResolver.getFontName(textElement));
        if (resolvedFontName.isBlank()) {
            resolvedFontName = "Helvetica";
        }
        float fontSize = Math.max(1.0f, context.styleResolver.getFontsize(textElement));
        boolean bold = context.styleResolver.isBold(textElement);
        boolean italic = context.styleResolver.isItalic(textElement);
        float leftPadding = safePadding(context.styleResolver.getLeftPadding(textElement.getLineBox()));
        float rightPadding = safePadding(context.styleResolver.getRightPadding(textElement.getLineBox()));
        float topPadding = safePadding(context.styleResolver.getTopPadding(textElement.getLineBox()));
        float bottomPadding = safePadding(context.styleResolver.getBottomPadding(textElement.getLineBox()));
        boolean stretchHeight =
                textElement instanceof JRTextField
                        && (((JRTextField) textElement).isStretchWithOverflow()
                                || TextAdjustEnum.STRETCH_HEIGHT.equals(
                                        ((JRTextField) textElement).getTextAdjust()));

        HorizontalTextAlignEnum horizontalTextAlign =
                context.styleResolver.getHorizontalTextAlign(textElement);
        String alignName = horizontalTextAlign == null ? "LEFT" : horizontalTextAlign.name();
        VerticalTextAlignEnum verticalTextAlign =
                context.styleResolver.getVerticalTextAlign(textElement);
        String verticalAlignName = verticalTextAlign == null ? "TOP" : verticalTextAlign.name();

        String fontVar = context.nextVar("font");
        methodBuilder.addStatement(
                "$T $L = resolveFont($S, $L, $L)",
                PDFont.class,
                fontVar,
                resolvedFontName,
                bold,
                italic);

        String linesVar = context.nextVar("lines");
        methodBuilder.addStatement(
                "$T<String> $L = wrapText($L, $Lf, $L, Math.max($Lf, 1f))",
                List.class,
                linesVar,
                fontVar,
                fontSize,
                textVar,
                width - leftPadding - rightPadding);
        methodBuilder.beginControlFlow("if ($L.isEmpty())", linesVar);
        methodBuilder.addStatement("$L.add($S)", linesVar, "");
        methodBuilder.endControlFlow();

        String lineHeightVar = context.nextVar("lineHeight");
        methodBuilder.addStatement("float $L = Math.max($Lf * 1.18f, 1f)", lineHeightVar, fontSize);
        String lineCountVar = context.nextVar("lineCount");
        methodBuilder.addStatement("int $L = Math.max($L.size(), 1)", lineCountVar, linesVar);
        String textBlockHeightVar = context.nextVar("textBlockHeight");
        methodBuilder.addStatement("float $L = $L * $L", textBlockHeightVar, lineHeightVar, lineCountVar);
        String availableHeightVar = context.nextVar("availableHeight");
        methodBuilder.addStatement(
                "float $L = Math.max($Lf - $Lf - $Lf, $L)",
                availableHeightVar,
                height,
                topPadding,
                bottomPadding,
                lineHeightVar);
        String topBaselineVar = context.nextVar("topBaseline");
        methodBuilder.addStatement(
                "float $L = $L + $Lf - $Lf - $Lf",
                topBaselineVar,
                yPdfVar,
                height,
                topPadding,
                fontSize);
        String layoutCompensationVar = context.nextVar("layoutComp");
        methodBuilder.addStatement(
                "float $L = Math.max($L - $Lf, 0f)",
                layoutCompensationVar,
                lineHeightVar,
                fontSize);
        String baselineStartVar = context.nextVar("baselineStart");
        if (stretchHeight) {
            methodBuilder.addStatement(
                    "float $L = $L - $L",
                    baselineStartVar,
                    topBaselineVar,
                    layoutCompensationVar);
        } else if ("TOP".equals(verticalAlignName)) {
            methodBuilder.addStatement("float $L = $L", baselineStartVar, topBaselineVar);
        } else if ("BOTTOM".equals(verticalAlignName)) {
            methodBuilder.addStatement(
                    "float $L = $L - Math.max($L - $L, 0f) - $L",
                    baselineStartVar,
                    topBaselineVar,
                    availableHeightVar,
                    textBlockHeightVar,
                    layoutCompensationVar);
        } else {
            methodBuilder.addStatement(
                    "float $L = $L - (Math.max($L - $L, 0f) / 2f) - $L",
                    baselineStartVar,
                    topBaselineVar,
                    availableHeightVar,
                    textBlockHeightVar,
                    layoutCompensationVar);
        }
        if (stretchHeight && context.currentBandRenderedBottomVar != null && context.currentElementY != null) {
            String shiftExpr = context.currentElementShiftVar == null ? "0f" : context.currentElementShiftVar;
            methodBuilder.addStatement(
                    "$L = Math.max($L, $Lf - $L + $Lf + Math.max($L - $L, 0f))",
                    context.currentBandRenderedBottomVar,
                    context.currentBandRenderedBottomVar,
                    (float) context.currentElementY,
                    shiftExpr,
                    height,
                    textBlockHeightVar,
                    availableHeightVar);
        }

        methodBuilder.addStatement("contentStream.saveGraphicsState()");
        methodBuilder.addStatement(
                "contentStream.addRect($L + $Lf, 0f, $Lf, pageHeight)",
                xExpr,
                leftPadding,
                Math.max(width - leftPadding - rightPadding, 1f));
        methodBuilder.addStatement("contentStream.clip()");

        Color foreColor = defaultColor(context.styleResolver.getForecolor(textElement), Color.BLACK);
        methodBuilder.addStatement(
                "contentStream.setNonStrokingColor(new $T($L, $L, $L))",
                Color.class,
                foreColor.getRed(),
                foreColor.getGreen(),
                foreColor.getBlue());
        methodBuilder.beginControlFlow("for (int i = 0; i < $L.size(); i++)", linesVar);
        String lineVar = context.nextVar("line");
        methodBuilder.addStatement("String $L = $L.get(i)", lineVar, linesVar);
        String textXVar = context.nextVar("textX");
        methodBuilder.addStatement(
                "float $L = alignTextX($L, $Lf, $L, $L + $Lf, $Lf, $S)",
                textXVar,
                fontVar,
                fontSize,
                lineVar,
                xExpr,
                leftPadding,
                width - leftPadding - rightPadding,
                alignName);
        String baselineYVar = context.nextVar("baselineY");
        methodBuilder.addStatement(
                "float $L = $L - (i * $L)",
                baselineYVar,
                baselineStartVar,
                lineHeightVar);
        methodBuilder.addStatement("contentStream.beginText()");
        methodBuilder.addStatement("contentStream.setFont($L, $Lf)", fontVar, fontSize);
        methodBuilder.addStatement("contentStream.newLineAtOffset($L, $L)", textXVar, baselineYVar);
        methodBuilder.addStatement("contentStream.showText($L)", lineVar);
        methodBuilder.addStatement("contentStream.endText()");
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("contentStream.restoreGraphicsState()");
        methodBuilder.addStatement("contentStream.setNonStrokingColor($T.BLACK)", Color.class);
    }

    private void emitImage(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRImage image,
            String xExpr,
            String yExpr,
            String dataRef) {
        float width = image.getWidth();
        float height = image.getHeight();
        String yPdfVar = context.nextVar("yPdf");
        methodBuilder.addStatement(
                "float $L = toPdfY(pageHeight, $L, $Lf)", yPdfVar, yExpr, height);

        emitOpaqueBackground(context, methodBuilder, image, xExpr, yPdfVar, width, height);
        emitLineBoxBorders(context, methodBuilder, image.getLineBox(), xExpr, yPdfVar, width, height);

        CodeBlock imageSourceCode = resolveExpressionAsObjectCode(context, image.getExpression(), dataRef);
        String fallbackBase64Expr = "null";
        String referencedParameter = extractParameterReference(image.getExpression());
        if (referencedParameter != null && context.base64Constants.containsKey(referencedParameter)) {
            fallbackBase64Expr = context.base64Constants.get(referencedParameter).constantName;
        } else if (image.getExpression() != null && image.getExpression().getText() != null) {
            Matcher variableMatcher = VAR_REF.matcher(image.getExpression().getText().trim());
            if (variableMatcher.matches()) {
                String variableKey = variableMatcher.group(1);
                String variableExpression = context.variableExpressions.get(variableKey);
                if (variableExpression != null) {
                    Matcher paramMatcher = PARAM_REF_ANY.matcher(variableExpression);
                    if (paramMatcher.find()) {
                        String variableParameter = paramMatcher.group(1);
                        String expectedType =
                                context.parameterTypes.getOrDefault(
                                        variableParameter, "java.lang.Object");
                        fallbackBase64Expr =
                                "asText(readTypedValue(data, \""
                                        + variableParameter
                                        + "\", \""
                                        + expectedType
                                        + "\"))";
                    }
                }
            }
        }
        CodeBlock fallbackBase64Code = CodeBlock.of("$L", fallbackBase64Expr);
        ScaleImageEnum scaleImageEnum = context.styleResolver.getScaleImageValue(image);
        String scaleImageName = scaleImageEnum == null ? "RETAIN_SHAPE" : scaleImageEnum.name();
        HorizontalImageAlignEnum horizontalImageAlign =
                context.styleResolver.getHorizontalImageAlign(image);
        String horizontalImageAlignName =
                horizontalImageAlign == null ? "LEFT" : horizontalImageAlign.name();
        VerticalImageAlignEnum verticalImageAlign =
                context.styleResolver.getVerticalImageAlign(image);
        String verticalImageAlignName =
                verticalImageAlign == null ? "TOP" : verticalImageAlign.name();

        methodBuilder.addStatement(
                "drawImage(document, contentStream, $L, $L, $L, $L, $Lf, $Lf, $S, $S, $S, $S)",
                imageSourceCode,
                fallbackBase64Code,
                xExpr,
                yPdfVar,
                width,
                height,
                "img_" + context.sequence,
                scaleImageName,
                horizontalImageAlignName,
                verticalImageAlignName);
    }

    private void emitFrame(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRFrame frame,
            String xExpr,
            String yExpr,
            String dataRef) {
        float width = frame.getWidth();
        float height = frame.getHeight();
        String yPdfVar = context.nextVar("yPdf");
        methodBuilder.addStatement(
                "float $L = toPdfY(pageHeight, $L, $Lf)", yPdfVar, yExpr, height);
        emitOpaqueBackground(context, methodBuilder, frame, xExpr, yPdfVar, width, height);
        emitLineBoxBorders(context, methodBuilder, frame.getLineBox(), xExpr, yPdfVar, width, height);

        float childOffsetX = safePadding(context.styleResolver.getLeftPadding(frame.getLineBox()));
        float childOffsetY = safePadding(context.styleResolver.getTopPadding(frame.getLineBox()));
        String childBaseXExpr = plus(xExpr, childOffsetX);
        String childBaseYExpr = plus(yExpr, childOffsetY);

        if (frame.getElements() != null) {
            emitElementList(
                    context,
                    methodBuilder,
                    Arrays.asList(frame.getElements()),
                    childBaseXExpr,
                    childBaseYExpr,
                    dataRef);
        }
    }

    private void emitRectangle(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRRectangle rectangle,
            String xExpr,
            String yExpr) {
        float width = rectangle.getWidth();
        float height = rectangle.getHeight();
        String yPdfVar = context.nextVar("yPdf");
        methodBuilder.addStatement(
                "float $L = toPdfY(pageHeight, $L, $Lf)", yPdfVar, yExpr, height);
        emitOpaqueBackground(context, methodBuilder, rectangle, xExpr, yPdfVar, width, height);

        JRGraphicElement graphicElement = rectangle;
        float lineWidth = safeLineWidth(context.styleResolver.getLineWidth(graphicElement.getLinePen(), 0f));
        if (lineWidth > 0f) {
            Color lineColor =
                    defaultColor(context.styleResolver.getLineColor(graphicElement.getLinePen(), Color.BLACK), Color.BLACK);
            methodBuilder.addStatement("contentStream.setLineWidth($Lf)", lineWidth);
            methodBuilder.addStatement(
                    "contentStream.setStrokingColor(new $T($L, $L, $L))",
                    Color.class,
                    lineColor.getRed(),
                    lineColor.getGreen(),
                    lineColor.getBlue());
            methodBuilder.addStatement("contentStream.addRect($L, $L, $Lf, $Lf)", xExpr, yPdfVar, width, height);
            methodBuilder.addStatement("contentStream.stroke()");
            methodBuilder.addStatement("contentStream.setStrokingColor($T.BLACK)", Color.class);
            methodBuilder.addStatement("contentStream.setLineWidth(1f)");
        }
    }

    private void emitLine(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRLine line,
            String xExpr,
            String yExpr) {
        float width = line.getWidth();
        float height = line.getHeight();
        String yPdfVar = context.nextVar("yPdf");
        methodBuilder.addStatement(
                "float $L = toPdfY(pageHeight, $L, $Lf)", yPdfVar, yExpr, height);

        JRCommonGraphicElement graphicElement = line;
        float lineWidth = safeLineWidth(context.styleResolver.getLineWidth(graphicElement.getLinePen(), 0f));
        if (lineWidth <= 0f) {
            return;
        }

        Color lineColor =
                defaultColor(context.styleResolver.getLineColor(graphicElement.getLinePen(), Color.BLACK), Color.BLACK);
        methodBuilder.addStatement("contentStream.setLineWidth($Lf)", lineWidth);
        methodBuilder.addStatement(
                "contentStream.setStrokingColor(new $T($L, $L, $L))",
                Color.class,
                lineColor.getRed(),
                lineColor.getGreen(),
                lineColor.getBlue());

        String xEndExpr = plus(xExpr, width);
        if (line.getDirectionValue() == LineDirectionEnum.BOTTOM_UP) {
            methodBuilder.addStatement("contentStream.moveTo($L, $L)", xExpr, yPdfVar);
            methodBuilder.addStatement(
                    "contentStream.lineTo($L, $L + $Lf)", xEndExpr, yPdfVar, height);
        } else {
            methodBuilder.addStatement("contentStream.moveTo($L, $L + $Lf)", xExpr, yPdfVar, height);
            methodBuilder.addStatement("contentStream.lineTo($L, $L)", xEndExpr, yPdfVar);
        }

        methodBuilder.addStatement("contentStream.stroke()");
        methodBuilder.addStatement("contentStream.setStrokingColor($T.BLACK)", Color.class);
        methodBuilder.addStatement("contentStream.setLineWidth(1f)");
    }

    private void emitComponentElement(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRDesignComponentElement componentElement,
            String xExpr,
            String yExpr,
            String dataRef) {
        Component component = componentElement.getComponent();
        if (component instanceof TableComponent) {
            emitTable(context, methodBuilder, (TableComponent) component, xExpr, yExpr, dataRef);
        }
    }

    private void emitTable(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            TableComponent table,
            String xExpr,
            String yExpr,
            String dataRef) {
        List<Column> leafColumns = new ArrayList<>();
        flattenColumns(table.getColumns(), leafColumns);
        if (leafColumns.isEmpty()) {
            return;
        }

        int tableIndex = ++context.tableSequence;
        String tableRowsKey = resolveTableRowsKey(table, tableIndex);

        int tableHeaderHeight = resolveMaxCellHeight(leafColumns, CellSelector.TABLE_HEADER);
        int columnHeaderHeight = resolveMaxCellHeight(leafColumns, CellSelector.COLUMN_HEADER);
        int detailRowHeight = resolveMaxCellHeight(leafColumns, CellSelector.DETAIL_CELL);

        String tableXVar = context.nextVar("tableStartX");
        String tableYVar = context.nextVar("tableCursorY");
        methodBuilder.addStatement("float $L = $L", tableXVar, xExpr);
        methodBuilder.addStatement("float $L = $L", tableYVar, yExpr);

        if (tableHeaderHeight > 0) {
            CodeBlock rowCondition =
                    buildPrintWhenCondition(
                            context,
                            table.getTableHeader() == null ? null : table.getTableHeader().getPrintWhenExpression(),
                            dataRef);
            if (rowCondition != null) {
                methodBuilder.beginControlFlow("if ($L)", rowCondition);
            }
            emitTableRow(
                    context,
                    methodBuilder,
                    leafColumns,
                    CellSelector.TABLE_HEADER,
                    tableXVar,
                    tableYVar,
                    tableHeaderHeight,
                    dataRef);
            methodBuilder.addStatement("$L += $Lf", tableYVar, (float) tableHeaderHeight);
            if (rowCondition != null) {
                methodBuilder.endControlFlow();
            }
        }

        if (columnHeaderHeight > 0) {
            CodeBlock rowCondition =
                    buildPrintWhenCondition(
                            context,
                            table.getColumnHeader() == null ? null : table.getColumnHeader().getPrintWhenExpression(),
                            dataRef);
            if (rowCondition != null) {
                methodBuilder.beginControlFlow("if ($L)", rowCondition);
            }
            emitTableRow(
                    context,
                    methodBuilder,
                    leafColumns,
                    CellSelector.COLUMN_HEADER,
                    tableXVar,
                    tableYVar,
                    columnHeaderHeight,
                    dataRef);
            methodBuilder.addStatement("$L += $Lf", tableYVar, (float) columnHeaderHeight);
            if (rowCondition != null) {
                methodBuilder.endControlFlow();
            }
        }

        String rowsObjVar = context.nextVar("tableRowsObj");
        String rowsVar = context.nextVar("tableRows");
        String rowVar = context.nextVar("tableRow");
        methodBuilder.addStatement("Object $L = data.get($S)", rowsObjVar, tableRowsKey);
        methodBuilder.addStatement(
                "$T<$T<$T, Object>> $L = asRowList($L, $S)",
                List.class,
                Map.class,
                String.class,
                rowsVar,
                rowsObjVar,
                tableRowsKey);

        methodBuilder.beginControlFlow("for ($T<$T, Object> $L : $L)", Map.class, String.class, rowVar, rowsVar);
        CodeBlock detailCondition =
                buildPrintWhenCondition(
                        context,
                        table.getDetail() == null ? null : table.getDetail().getPrintWhenExpression(),
                        rowVar);
        if (detailCondition != null) {
            methodBuilder.beginControlFlow("if ($L)", detailCondition);
        }
        if (detailRowHeight > 0) {
            emitTableRow(
                    context,
                    methodBuilder,
                    leafColumns,
                    CellSelector.DETAIL_CELL,
                    tableXVar,
                    tableYVar,
                    detailRowHeight,
                    rowVar);
            methodBuilder.addStatement("$L += $Lf", tableYVar, (float) detailRowHeight);
        }
        if (detailCondition != null) {
            methodBuilder.endControlFlow();
        }
        methodBuilder.endControlFlow();
    }

    private void emitTableRow(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            List<Column> columns,
            CellSelector selector,
            String tableXVar,
            String tableYVar,
            int rowHeight,
            String dataRef) {
        float offsetX = 0f;
        for (Column column : columns) {
            int width = safeInt(column.getWidth(), 0);
            BaseCell cell = selector.select(column);
            if (width <= 0) {
                continue;
            }

            if (cell != null) {
                String cellXExpr = plus(tableXVar, offsetX);
                emitTableCell(context, methodBuilder, cell, cellXExpr, tableYVar, width, rowHeight, dataRef);
            }
            offsetX += width;
        }
    }

    private void emitTableCell(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            BaseCell cell,
            String cellXExpr,
            String cellYExpr,
            int cellWidth,
            int rowHeight,
            String dataRef) {
        int effectiveHeight = safeInt(cell.getHeight(), rowHeight);
        String yPdfVar = context.nextVar("cellYPdf");
        methodBuilder.addStatement(
                "float $L = toPdfY(pageHeight, $L, $Lf)",
                yPdfVar,
                cellYExpr,
                (float) effectiveHeight);

        JRStyle style = cell.getStyle();
        if (style != null
                && style.getModeValue() == ModeEnum.OPAQUE
                && style.getBackcolor() != null) {
            Color back = style.getBackcolor();
            methodBuilder.addStatement(
                    "contentStream.setNonStrokingColor(new $T($L, $L, $L))",
                    Color.class,
                    back.getRed(),
                    back.getGreen(),
                    back.getBlue());
            methodBuilder.addStatement(
                    "contentStream.addRect($L, $L, $Lf, $Lf)",
                    cellXExpr,
                    yPdfVar,
                    (float) cellWidth,
                    (float) effectiveHeight);
            methodBuilder.addStatement("contentStream.fill()");
            methodBuilder.addStatement("contentStream.setNonStrokingColor($T.BLACK)", Color.class);
        }

        emitLineBoxBorders(
                context,
                methodBuilder,
                cell.getLineBox(),
                cellXExpr,
                yPdfVar,
                (float) cellWidth,
                (float) effectiveHeight);

        for (JRElement child : cell.getElements()) {
            emitElement(context, methodBuilder, child, cellXExpr, cellYExpr, dataRef);
        }
    }

    private void emitOpaqueBackground(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRCommonElement element,
            String xExpr,
            String yPdfVar,
            float width,
            float height) {
        ModeEnum mode = context.styleResolver.getMode(element, ModeEnum.TRANSPARENT);
        Color backColor = context.styleResolver.getBackcolor(element);
        if (mode == ModeEnum.OPAQUE && backColor != null) {
            methodBuilder.addStatement(
                    "contentStream.setNonStrokingColor(new $T($L, $L, $L))",
                    Color.class,
                    backColor.getRed(),
                    backColor.getGreen(),
                    backColor.getBlue());
            methodBuilder.addStatement("contentStream.addRect($L, $L, $Lf, $Lf)", xExpr, yPdfVar, width, height);
            methodBuilder.addStatement("contentStream.fill()");
            methodBuilder.addStatement("contentStream.setNonStrokingColor($T.BLACK)", Color.class);
        }
    }

    private void emitLineBoxBorders(
            GenerationContext context,
            MethodSpec.Builder methodBuilder,
            JRLineBox lineBox,
            String xExpr,
            String yPdfVar,
            float width,
            float height) {
        if (lineBox == null) {
            return;
        }

        float topWidth = safeLineWidth(context.styleResolver.getLineWidth(lineBox.getTopPen(), 0f));
        float rightWidth = safeLineWidth(context.styleResolver.getLineWidth(lineBox.getRightPen(), 0f));
        float bottomWidth = safeLineWidth(context.styleResolver.getLineWidth(lineBox.getBottomPen(), 0f));
        float leftWidth = safeLineWidth(context.styleResolver.getLineWidth(lineBox.getLeftPen(), 0f));

        boolean hasAnyBorder = topWidth > 0f || rightWidth > 0f || bottomWidth > 0f || leftWidth > 0f;
        if (!hasAnyBorder) {
            return;
        }

        String xRightExpr = plus(xExpr, width);
        String yTopExpr = plus(yPdfVar, height);

        if (topWidth > 0f) {
            Color topColor =
                    defaultColor(context.styleResolver.getLineColor(lineBox.getTopPen(), Color.BLACK), Color.BLACK);
            emitStrokeLine(
                    methodBuilder,
                    topWidth,
                    topColor,
                    xExpr,
                    yTopExpr,
                    xRightExpr,
                    yTopExpr);
        }
        if (rightWidth > 0f) {
            Color rightColor =
                    defaultColor(
                            context.styleResolver.getLineColor(lineBox.getRightPen(), Color.BLACK), Color.BLACK);
            emitStrokeLine(
                    methodBuilder,
                    rightWidth,
                    rightColor,
                    xRightExpr,
                    yPdfVar,
                    xRightExpr,
                    yTopExpr);
        }
        if (bottomWidth > 0f) {
            Color bottomColor =
                    defaultColor(
                            context.styleResolver.getLineColor(lineBox.getBottomPen(), Color.BLACK), Color.BLACK);
            emitStrokeLine(
                    methodBuilder,
                    bottomWidth,
                    bottomColor,
                    xExpr,
                    yPdfVar,
                    xRightExpr,
                    yPdfVar);
        }
        if (leftWidth > 0f) {
            Color leftColor =
                    defaultColor(context.styleResolver.getLineColor(lineBox.getLeftPen(), Color.BLACK), Color.BLACK);
            emitStrokeLine(
                    methodBuilder,
                    leftWidth,
                    leftColor,
                    xExpr,
                    yPdfVar,
                    xExpr,
                    yTopExpr);
        }

        methodBuilder.addStatement("contentStream.setStrokingColor($T.BLACK)", Color.class);
        methodBuilder.addStatement("contentStream.setLineWidth(1f)");
    }

    private void emitStrokeLine(
            MethodSpec.Builder methodBuilder,
            float lineWidth,
            Color color,
            String x1Expr,
            String y1Expr,
            String x2Expr,
            String y2Expr) {
        methodBuilder.addStatement("contentStream.setLineWidth($Lf)", lineWidth);
        methodBuilder.addStatement(
                "contentStream.setStrokingColor(new $T($L, $L, $L))",
                Color.class,
                color.getRed(),
                color.getGreen(),
                color.getBlue());
        methodBuilder.addStatement("contentStream.moveTo($L, $L)", x1Expr, y1Expr);
        methodBuilder.addStatement("contentStream.lineTo($L, $L)", x2Expr, y2Expr);
        methodBuilder.addStatement("contentStream.stroke()");
    }

    private CodeBlock resolveExpressionAsStringCode(
            GenerationContext context, JRExpression expression, String dataRef) {
        if (expression == null || expression.getText() == null || expression.getText().trim().isEmpty()) {
            return CodeBlock.of("$S", "");
        }

        String raw = expression.getText().trim();
        Matcher paramMatcher = PARAM_REF.matcher(raw);
        if (paramMatcher.matches()) {
            String key = paramMatcher.group(1);
            String type = context.parameterTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("asText(readTypedValue(data, $S, $S))", key, type);
        }

        Matcher fieldMatcher = FIELD_REF.matcher(raw);
        if (fieldMatcher.matches()) {
            String key = fieldMatcher.group(1);
            String type = context.fieldTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("asText(readTypedValue($L, $S, $S))", dataRef, key, type);
        }

        Matcher variableMatcher = VAR_REF.matcher(raw);
        if (variableMatcher.matches()) {
            String key = variableMatcher.group(1);
            String type = context.variableTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("asText(readTypedValue(data, $S, $S))", key, type);
        }

        String quoted = tryExtractQuotedLiteral(raw);
        if (quoted != null) {
            return CodeBlock.of("$S", quoted);
        }

        return CodeBlock.of("evalAsString($L, data, $S)", dataRef, raw);
    }

    private CodeBlock resolveExpressionAsObjectCode(
            GenerationContext context, JRExpression expression, String dataRef) {
        if (expression == null || expression.getText() == null || expression.getText().trim().isEmpty()) {
            return CodeBlock.of("null");
        }

        String raw = expression.getText().trim();
        Matcher paramMatcher = PARAM_REF.matcher(raw);
        if (paramMatcher.matches()) {
            String key = paramMatcher.group(1);
            String type = context.parameterTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("readTypedValue(data, $S, $S)", key, type);
        }

        Matcher fieldMatcher = FIELD_REF.matcher(raw);
        if (fieldMatcher.matches()) {
            String key = fieldMatcher.group(1);
            String type = context.fieldTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("readTypedValue($L, $S, $S)", dataRef, key, type);
        }

        Matcher variableMatcher = VAR_REF.matcher(raw);
        if (variableMatcher.matches()) {
            String key = variableMatcher.group(1);
            String type = context.variableTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("readTypedValue(data, $S, $S)", key, type);
        }

        String quoted = tryExtractQuotedLiteral(raw);
        if (quoted != null) {
            return CodeBlock.of("$S", quoted);
        }

        if ("null".equals(raw)) {
            return CodeBlock.of("null");
        }

        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return CodeBlock.of("$L", raw.toLowerCase(Locale.ROOT));
        }

        return CodeBlock.of("evalAsObject($L, data, $S)", dataRef, raw);
    }

    private CodeBlock buildPrintWhenCondition(
            GenerationContext context, JRExpression expression, String dataRef) {
        if (expression == null || expression.getText() == null || expression.getText().trim().isEmpty()) {
            return null;
        }

        String raw = expression.getText().trim();

        Matcher singleParam = PARAM_REF.matcher(raw);
        if (singleParam.matches()) {
            String key = singleParam.group(1);
            String type = context.parameterTypes.getOrDefault(key, "java.lang.Object");
            if (isBooleanType(type)) {
                return CodeBlock.of(
                        "$T.TRUE.equals(($T) readTypedValue(data, $S, $S))",
                        Boolean.class,
                        Boolean.class,
                        key,
                        type);
            }
            return CodeBlock.of("readTypedValue(data, $S, $S) != null", key, type);
        }

        Matcher singleField = FIELD_REF.matcher(raw);
        if (singleField.matches()) {
            String key = singleField.group(1);
            String type = context.fieldTypes.getOrDefault(key, "java.lang.Object");
            if (isBooleanType(type)) {
                return CodeBlock.of(
                        "$T.TRUE.equals(($T) readTypedValue($L, $S, $S))",
                        Boolean.class,
                        Boolean.class,
                        dataRef,
                        key,
                        type);
            }
            return CodeBlock.of("readTypedValue($L, $S, $S) != null", dataRef, key, type);
        }

        Matcher singleVariable = VAR_REF.matcher(raw);
        if (singleVariable.matches()) {
            String key = singleVariable.group(1);
            String type = context.variableTypes.getOrDefault(key, "java.lang.Object");
            if (isBooleanType(type)) {
                return CodeBlock.of(
                        "$T.TRUE.equals(($T) readTypedValue(data, $S, $S))",
                        Boolean.class,
                        Boolean.class,
                        key,
                        type);
            }
            return CodeBlock.of("readTypedValue(data, $S, $S) != null", key, type);
        }

        return CodeBlock.of("evalPrintWhen($L, data, $S)", dataRef, raw);
    }

    private CodeBlock resolveOperandCode(GenerationContext context, String operand, String dataRef) {
        String trimmed = operand.trim();
        if ("null".equals(trimmed)) {
            return CodeBlock.of("null");
        }

        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return CodeBlock.of("$L", trimmed.toLowerCase(Locale.ROOT));
        }

        Matcher paramMatcher = PARAM_REF.matcher(trimmed);
        if (paramMatcher.matches()) {
            String key = paramMatcher.group(1);
            String type = context.parameterTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("readTypedValue(data, $S, $S)", key, type);
        }

        Matcher fieldMatcher = FIELD_REF.matcher(trimmed);
        if (fieldMatcher.matches()) {
            String key = fieldMatcher.group(1);
            String type = context.fieldTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("readTypedValue($L, $S, $S)", dataRef, key, type);
        }

        Matcher variableMatcher = VAR_REF.matcher(trimmed);
        if (variableMatcher.matches()) {
            String key = variableMatcher.group(1);
            String type = context.variableTypes.getOrDefault(key, "java.lang.Object");
            return CodeBlock.of("readTypedValue(data, $S, $S)", key, type);
        }

        String quoted = tryExtractQuotedLiteral(trimmed);
        if (quoted != null) {
            return CodeBlock.of("$S", quoted);
        }

        return CodeBlock.of("evalAsObject($L, data, $S)", dataRef, trimmed);
    }

    private MethodSpec buildParameterTypeLookupMethod(Map<String, String> parameterTypes) {
        return buildTypeLookupMethod("parameterType", parameterTypes);
    }

    private MethodSpec buildFieldTypeLookupMethod(Map<String, String> fieldTypes) {
        return buildTypeLookupMethod("fieldType", fieldTypes);
    }

    private MethodSpec buildVariableTypeLookupMethod(Map<String, String> variableTypes) {
        return buildTypeLookupMethod("variableType", variableTypes);
    }

    private MethodSpec buildTypeLookupMethod(String methodName, Map<String, String> types) {
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder(methodName)
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(String.class)
                        .addParameter(String.class, "key");

        methodBuilder.beginControlFlow("if (key == null)")
                .addStatement("return $S", "java.lang.Object")
                .endControlFlow();

        methodBuilder.beginControlFlow("switch (key)");
        for (Map.Entry<String, String> entry : types.entrySet()) {
            methodBuilder.addCode("case $S:\n", entry.getKey());
            methodBuilder.addStatement("    return $S", entry.getValue());
        }
        methodBuilder.addCode("default:\n");
        methodBuilder.addStatement("    return $S", "java.lang.Object");
        methodBuilder.endControlFlow();

        return methodBuilder.build();
    }

    private FieldSpec buildExpressionCacheField() {
        ParameterizedTypeName cacheType =
                ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        ClassName.get(String.class),
                        ClassName.get(java.lang.reflect.Method.class));
        return FieldSpec.builder(
                        cacheType,
                        "EXPRESSION_CACHE",
                        Modifier.PRIVATE,
                        Modifier.STATIC,
                        Modifier.FINAL)
                .initializer("new $T<>()", java.util.concurrent.ConcurrentHashMap.class)
                .build();
    }

    private MethodSpec buildTranslateExpressionMethod(GenerationContext context) {
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("translateExpressionForJava")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(String.class)
                        .addParameter(String.class, "expression");

        methodBuilder.beginControlFlow("if (expression == null || expression.trim().isEmpty())");
        methodBuilder.addStatement("return expression");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement(
                "$T matcher = $T.compile($S).matcher(expression)",
                Matcher.class,
                Pattern.class,
                "\\$([PFV])\\{([\\w$.]+)}");
        methodBuilder.addStatement("StringBuffer translatedBuffer = new StringBuffer()");
        methodBuilder.beginControlFlow("while (matcher.find())");
        methodBuilder.addStatement("String scope = matcher.group(1)");
        methodBuilder.addStatement("String key = matcher.group(2)");
        methodBuilder.addStatement("String type");
        methodBuilder.addStatement("String replacement");

        methodBuilder.beginControlFlow("if ($S.equals(scope))", "P");
        methodBuilder.addStatement("type = parameterType(key)");
        methodBuilder.addStatement(
                "replacement = \"((\" + type + \") params.get(\\\"\" + key + \"\\\"))\"");
        methodBuilder.nextControlFlow("else if ($S.equals(scope))", "F");
        methodBuilder.addStatement("type = fieldType(key)");
        methodBuilder.addStatement(
                "replacement = \"((\" + type + \") rowData.get(\\\"\" + key + \"\\\"))\"");
        methodBuilder.nextControlFlow("else");
        methodBuilder.addStatement("type = variableType(key)");
        methodBuilder.addStatement(
                "replacement = \"((\" + type + \") params.get(\\\"\" + key + \"\\\"))\"");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("matcher.appendReplacement(translatedBuffer, $T.quoteReplacement(replacement))", Matcher.class);
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("matcher.appendTail(translatedBuffer)");
        methodBuilder.addStatement("String translated = translatedBuffer.toString()");

        for (Map.Entry<String, String> importAlias : context.importAliases.entrySet()) {
            methodBuilder.addStatement(
                    "translated = replaceWord(translated, $S, $S)",
                    importAlias.getKey(),
                    importAlias.getValue());
        }

        methodBuilder.addStatement(
                "translated = replaceWord(translated, $S, $S)",
                "ByteArrayInputStream",
                "java.io.ByteArrayInputStream");
        methodBuilder.addStatement(
                "translated = replaceWord(translated, $S, $S)",
                "Arrays",
                "java.util.Arrays");
        methodBuilder.addStatement(
                "translated = replaceWord(translated, $S, $S)",
                "DecimalFormat",
                "java.text.DecimalFormat");
        methodBuilder.addStatement(
                "translated = replaceWord(translated, $S, $S)",
                "Locale",
                "java.util.Locale");
        methodBuilder.addStatement("return translated");
        return methodBuilder.build();
    }

    private MethodSpec buildCompileExpressionMethod(GenerationContext context) {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("compileExpression")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(java.lang.reflect.Method.class)
                        .addParameter(String.class, "expression")
                        .addException(Exception.class);

        methodBuilder.addStatement(
                "$T compiler = $T.getSystemJavaCompiler()",
                javax.tools.JavaCompiler.class,
                javax.tools.ToolProvider.class);
        methodBuilder.beginControlFlow("if (compiler == null)");
        methodBuilder.addStatement("throw new IllegalStateException($S)", "JavaCompiler no disponible");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("String translated = translateExpressionForJava(expression)");
        methodBuilder.addStatement("String packageName = $S", "com.jaspbox.expr");
        methodBuilder.addStatement(
                "String className = $S + $T.abs(expression.hashCode()) + $S + $T.nanoTime()",
                "Expr_",
                Math.class,
                "_",
                System.class);
        methodBuilder.addStatement("String fullName = packageName + $S + className", ".");
        methodBuilder.addStatement(
                "$T root = $T.get($T.getProperty($S), $S)",
                java.nio.file.Path.class,
                java.nio.file.Paths.class,
                System.class,
                "java.io.tmpdir",
                "jaspbox_expr_cache");
        methodBuilder.addStatement("$T.createDirectories(root)", Files.class);
        methodBuilder.addStatement(
                "$T packageDir = root.resolve(packageName.replace('.', '/'))", java.nio.file.Path.class);
        methodBuilder.addStatement("$T.createDirectories(packageDir)", Files.class);
        methodBuilder.addStatement("$T sourceFile = packageDir.resolve(className + $S)", java.nio.file.Path.class, ".java");

        methodBuilder.addStatement(
                "String source = $S + packageName + $S + className + $S + translated + $S",
                "package ",
                ";\npublic class ",
                " {\n  public static Object eval(java.util.Map<String,Object> rowData, java.util.Map<String,Object> params) throws Exception {\n    return ",
                ";\n  }\n}\n");
        methodBuilder.addStatement("$T.writeString(sourceFile, source, $T.UTF_8)", Files.class, StandardCharsets.class);

        methodBuilder.addStatement(
                "$T<$T> diagnostics = new $T<>()",
                javax.tools.DiagnosticCollector.class,
                javax.tools.JavaFileObject.class,
                javax.tools.DiagnosticCollector.class);
        methodBuilder.beginControlFlow(
                "try ($T fileManager = compiler.getStandardFileManager(diagnostics, $T.ROOT, $T.UTF_8))",
                javax.tools.StandardJavaFileManager.class,
                Locale.class,
                StandardCharsets.class);
        methodBuilder.addStatement(
                "$T<? extends $T> units = fileManager.getJavaFileObjects(sourceFile.toFile())",
                Iterable.class,
                javax.tools.JavaFileObject.class);
        methodBuilder.addStatement("String cp = $T.getProperty($S)", System.class, "java.class.path");
        methodBuilder.addStatement("String sep = $T.getProperty($S)", System.class, "path.separator");
        for (String importedClass : context.importAliases.values()) {
            methodBuilder.beginControlFlow("try");
            methodBuilder.addStatement(
                    "$T depClass = $T.forName($S, false, $T.currentThread().getContextClassLoader())",
                    Class.class,
                    Class.class,
                    importedClass,
                    Thread.class);
            methodBuilder.beginControlFlow(
                    "if (depClass.getProtectionDomain() != null && depClass.getProtectionDomain().getCodeSource() != null && depClass.getProtectionDomain().getCodeSource().getLocation() != null)");
            methodBuilder.addStatement(
                    "String depPath = $T.get(depClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()",
                    java.nio.file.Paths.class);
            methodBuilder.beginControlFlow("if (cp == null || cp.isBlank())");
            methodBuilder.addStatement("cp = depPath");
            methodBuilder.nextControlFlow("else if (!cp.contains(depPath))");
            methodBuilder.addStatement("cp = cp + sep + depPath");
            methodBuilder.endControlFlow();
            methodBuilder.endControlFlow();
            methodBuilder.nextControlFlow("catch ($T ignored)", Exception.class);
            methodBuilder.addStatement("// optional dependency not visible");
            methodBuilder.endControlFlow();
        }
        methodBuilder.addStatement(
                "$T<String> options = $T.of($S, cp, $S, root.toString())",
                List.class,
                List.class,
                "-classpath",
                "-d");
        methodBuilder.addStatement(
                "$T.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, units)",
                javax.tools.JavaCompiler.class);
        methodBuilder.addStatement("boolean success = $T.TRUE.equals(task.call())", Boolean.class);
        methodBuilder.beginControlFlow("if (!success)");
        methodBuilder.addStatement("StringBuilder errors = new StringBuilder($S)", "No se pudo compilar expresion Jasper: ");
        methodBuilder.beginControlFlow(
                "for ($T<? extends $T> diagnostic : diagnostics.getDiagnostics())",
                javax.tools.Diagnostic.class,
                javax.tools.JavaFileObject.class);
        methodBuilder.addStatement(
                "errors.append($S).append(diagnostic.getMessage($T.ROOT))",
                " | ",
                Locale.class);
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("throw new IllegalArgumentException(errors.toString())");
        methodBuilder.endControlFlow();
        methodBuilder.endControlFlow();

        methodBuilder.addStatement(
                "$T loader = new $T(new $T[] {root.toUri().toURL()}, $T.class.getClassLoader())",
                URLClassLoader.class,
                URLClassLoader.class,
                URL.class,
                Object.class);
        methodBuilder.addStatement("Class<?> clazz = Class.forName(fullName, true, loader)");
        methodBuilder.addStatement(
                "return clazz.getMethod($S, $T.class, $T.class)",
                "eval",
                Map.class,
                Map.class);

        return methodBuilder.build();
    }

    private MethodSpec buildEvalCompiledExpressionMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("evalCompiledExpression")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(Object.class)
                        .addParameter(mapType, "rowData")
                        .addParameter(mapType, "params")
                        .addParameter(String.class, "expression");

        methodBuilder.beginControlFlow("if (expression == null || expression.trim().isEmpty())");
        methodBuilder.addStatement("return null");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("try");
        methodBuilder.addStatement("$T method = EXPRESSION_CACHE.get(expression)", java.lang.reflect.Method.class);
        methodBuilder.beginControlFlow("if (method == null)");
        methodBuilder.beginControlFlow("synchronized (EXPRESSION_CACHE)");
        methodBuilder.addStatement("method = EXPRESSION_CACHE.get(expression)");
        methodBuilder.beginControlFlow("if (method == null)");
        methodBuilder.addStatement("method = compileExpression(expression)");
        methodBuilder.addStatement("EXPRESSION_CACHE.put(expression, method)");
        methodBuilder.endControlFlow();
        methodBuilder.endControlFlow();
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("return method.invoke(null, rowData, params)");
        methodBuilder.nextControlFlow("catch ($T ignored)", Exception.class);
        methodBuilder.addStatement("return null");
        methodBuilder.endControlFlow();
        return methodBuilder.build();
    }

    private MethodSpec buildEvalAsObjectMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("evalAsObject")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(Object.class)
                        .addParameter(mapType, "rowData")
                        .addParameter(mapType, "params")
                        .addParameter(String.class, "expression");

        methodBuilder.beginControlFlow("if (expression == null)");
        methodBuilder.addStatement("return null");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("String expr = expression.trim()");
        methodBuilder.beginControlFlow("if (expr.isEmpty())");
        methodBuilder.addStatement("return null");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("String paramKey = extractReferenceKey(expr, $S)", "P");
        methodBuilder.beginControlFlow("if (paramKey != null)");
        methodBuilder.addStatement("return readTypedValue(params, paramKey, parameterType(paramKey))");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("String fieldKey = extractReferenceKey(expr, $S)", "F");
        methodBuilder.beginControlFlow("if (fieldKey != null)");
        methodBuilder.addStatement("return readTypedValue(rowData, fieldKey, fieldType(fieldKey))");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("String variableKey = extractReferenceKey(expr, $S)", "V");
        methodBuilder.beginControlFlow("if (variableKey != null)");
        methodBuilder.addStatement("return readTypedValue(params, variableKey, variableType(variableKey))");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow(
                "if (expr.length() >= 2 && expr.startsWith($S) && expr.endsWith($S))", "\"", "\"");
        methodBuilder.addStatement("return unquote(expr)");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("if ($S.equalsIgnoreCase(expr) || $S.equalsIgnoreCase(expr))", "true", "false");
        methodBuilder.addStatement("return $T.valueOf(expr)", Boolean.class);
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("if ($S.equals(expr))", "null");
        methodBuilder.addStatement("return null");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("try");
        methodBuilder.beginControlFlow("if (expr.contains($S))", ".");
        methodBuilder.addStatement("return $T.valueOf(expr)", Double.class);
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("return $T.valueOf(expr)", Long.class);
        methodBuilder.nextControlFlow("catch ($T ignored)", NumberFormatException.class);
        methodBuilder.addStatement("// continue");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("if (params != null && params.containsKey(expr))");
        methodBuilder.addStatement("return params.get(expr)");
        methodBuilder.endControlFlow();
        methodBuilder.beginControlFlow("if (rowData != null && rowData.containsKey(expr))");
        methodBuilder.addStatement("return rowData.get(expr)");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("Object compiled = evalCompiledExpression(rowData, params, expr)");
        methodBuilder.beginControlFlow("if (compiled != null)");
        methodBuilder.addStatement("return compiled");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("if (expr.indexOf('+') > -1)");
        methodBuilder.addStatement("$T<String> tokens = splitConcat(expr)", List.class);
        methodBuilder.beginControlFlow("if (tokens.size() > 1)");
        methodBuilder.addStatement("StringBuilder out = new StringBuilder()");
        methodBuilder.beginControlFlow("for (String token : tokens)");
        methodBuilder.addStatement("out.append(asText(evalAsObject(rowData, params, token)))");
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("return out.toString()");
        methodBuilder.endControlFlow();
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("return null");
        return methodBuilder.build();
    }

    private MethodSpec buildEvalAsStringMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("evalAsString")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(String.class)
                        .addParameter(mapType, "rowData")
                        .addParameter(mapType, "params")
                        .addParameter(String.class, "expression");
        methodBuilder.addStatement("return sanitizeText(asText(evalAsObject(rowData, params, expression)))");
        return methodBuilder.build();
    }

    private MethodSpec buildEvalPrintWhenMethod() {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("evalPrintWhen")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(boolean.class)
                        .addParameter(mapType, "rowData")
                        .addParameter(mapType, "params")
                        .addParameter(String.class, "expression");

        methodBuilder.beginControlFlow("if (expression == null || expression.trim().isEmpty())");
        methodBuilder.addStatement("return true");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("String expr = expression.trim()");
        methodBuilder.addStatement("Object compiled = evalCompiledExpression(rowData, params, expr)");
        methodBuilder.beginControlFlow("if (compiled instanceof Boolean)");
        methodBuilder.addStatement("return (Boolean) compiled");
        methodBuilder.endControlFlow();
        methodBuilder.beginControlFlow("if (compiled != null)");
        methodBuilder.addStatement("return !($T.FALSE.equals(compiled))", Boolean.class);
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("if (expr.startsWith($S))", "!");
        methodBuilder.addStatement("return !evalPrintWhen(rowData, params, expr.substring(1).trim())");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("int opIndex = findOperatorOutsideQuotes(expr, $S)", "==");
        methodBuilder.addStatement("String operator = $S", "==");
        methodBuilder.beginControlFlow("if (opIndex < 0)");
        methodBuilder.addStatement("opIndex = findOperatorOutsideQuotes(expr, $S)", "!=");
        methodBuilder.addStatement("operator = $S", "!=");
        methodBuilder.endControlFlow();

        methodBuilder.beginControlFlow("if (opIndex >= 0)");
        methodBuilder.addStatement("String leftExpr = expr.substring(0, opIndex).trim()");
        methodBuilder.addStatement("String rightExpr = expr.substring(opIndex + 2).trim()");
        methodBuilder.addStatement("Object left = evalAsObject(rowData, params, leftExpr)");
        methodBuilder.addStatement("Object right = evalAsObject(rowData, params, rightExpr)");
        methodBuilder.addStatement("boolean equals = $T.equals(left, right)", Objects.class);
        methodBuilder.addStatement("return $S.equals(operator) ? equals : !equals", "==");
        methodBuilder.endControlFlow();

        methodBuilder.addStatement("Object value = evalAsObject(rowData, params, expr)");
        methodBuilder.beginControlFlow("if (value instanceof Boolean)");
        methodBuilder.addStatement("return (Boolean) value");
        methodBuilder.endControlFlow();
        methodBuilder.addStatement("return value != null");
        return methodBuilder.build();
    }

    private MethodSpec buildRenderRepeatedPageBandsMethod(GenerationContext context) {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("renderRepeatedPageBands")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(float.class)
                        .addException(IOException.class)
                        .addParameter(PDDocument.class, "document")
                        .addParameter(PDPageContentStream.class, "contentStream")
                        .addParameter(float.class, "pageHeight")
                        .addParameter(float.class, "startY")
                        .addParameter(mapType, "data")
                        .addParameter(boolean.class, "overflowPage");

        methodBuilder.addStatement("float cursorY = startY");
        float leftMargin = context.design.getLeftMargin();

        JRBand background = context.design.getBackground();
        if (background != null) {
            methodBuilder.beginControlFlow(
                    "if (!overflowPage || $L)", context.repeatBackgroundOnNewPage);
            emitBand(context, methodBuilder, background, "0f", "0f", "data");
            methodBuilder.endControlFlow();
        }

        if (context.design.getPageHeader() != null) {
            methodBuilder.beginControlFlow(
                    "if (!overflowPage || $L)", context.repeatPageHeaderOnNewPage);
            String consumedVar =
                    emitBand(
                            context,
                            methodBuilder,
                            context.design.getPageHeader(),
                            floatLiteral(leftMargin),
                            "cursorY",
                            "data");
            methodBuilder.addStatement("cursorY += $L", consumedVar);
            methodBuilder.endControlFlow();
        }

        if (context.design.getColumnHeader() != null) {
            methodBuilder.beginControlFlow(
                    "if (!overflowPage || $L)", context.repeatColumnHeaderOnNewPage);
            String consumedVar =
                    emitBand(
                            context,
                            methodBuilder,
                            context.design.getColumnHeader(),
                            floatLiteral(leftMargin),
                            "cursorY",
                            "data");
            methodBuilder.addStatement("cursorY += $L", consumedVar);
            methodBuilder.endControlFlow();
        }

        methodBuilder.addStatement("return cursorY");
        return methodBuilder.build();
    }

    private MethodSpec buildRenderTrailingPageBandsMethod(GenerationContext context) {
        ParameterizedTypeName mapType =
                ParameterizedTypeName.get(Map.class, String.class, Object.class);
        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("renderTrailingPageBands")
                        .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                        .returns(float.class)
                        .addException(IOException.class)
                        .addParameter(PDDocument.class, "document")
                        .addParameter(PDPageContentStream.class, "contentStream")
                        .addParameter(float.class, "pageHeight")
                        .addParameter(float.class, "startY")
                        .addParameter(mapType, "data")
                        .addParameter(boolean.class, "lastPage");

        methodBuilder.addStatement("float cursorY = startY");
        float leftMargin = context.design.getLeftMargin();

        if (context.design.getColumnFooter() != null) {
            methodBuilder.beginControlFlow(
                    "if (lastPage || $L)", context.repeatColumnFooterOnNewPage);
            String columnFooterConsumedVar =
                    emitBand(
                            context,
                            methodBuilder,
                            context.design.getColumnFooter(),
                            floatLiteral(leftMargin),
                            "cursorY",
                            "data");
            methodBuilder.addStatement("cursorY += $L", columnFooterConsumedVar);
            methodBuilder.endControlFlow();
        }

        if (context.design.getLastPageFooter() != null) {
            methodBuilder.beginControlFlow("if (lastPage)");
            String lastPageFooterConsumedVar =
                    emitBand(
                            context,
                            methodBuilder,
                            context.design.getLastPageFooter(),
                            floatLiteral(leftMargin),
                            "cursorY",
                            "data");
            methodBuilder.addStatement("cursorY += $L", lastPageFooterConsumedVar);
            methodBuilder.nextControlFlow("else if ($L)", context.repeatPageFooterOnNewPage);
            if (context.design.getPageFooter() != null) {
                String pageFooterConsumedVar =
                        emitBand(
                                context,
                                methodBuilder,
                                context.design.getPageFooter(),
                                floatLiteral(leftMargin),
                                "cursorY",
                                "data");
                methodBuilder.addStatement("cursorY += $L", pageFooterConsumedVar);
            }
            methodBuilder.endControlFlow();
        } else if (context.design.getPageFooter() != null) {
            methodBuilder.beginControlFlow("if (lastPage || $L)", context.repeatPageFooterOnNewPage);
            String pageFooterConsumedVar =
                    emitBand(
                            context,
                            methodBuilder,
                            context.design.getPageFooter(),
                            floatLiteral(leftMargin),
                            "cursorY",
                            "data");
            methodBuilder.addStatement("cursorY += $L", pageFooterConsumedVar);
            methodBuilder.endControlFlow();
        }

        methodBuilder.addStatement("return cursorY");
        return methodBuilder.build();
    }

    private Map<String, String> collectParameterTypes(JasperDesign design) {
        Map<String, String> parameterTypes = new LinkedHashMap<>();
        for (JRParameter parameter : design.getParametersList()) {
            if (parameter.isSystemDefined()) {
                continue;
            }
            parameterTypes.put(parameter.getName(), normalizeType(parameter.getValueClassName()));
        }
        return parameterTypes;
    }

    private Map<String, String> collectFieldTypes(JasperDesign design) {
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        for (JRField field : design.getFieldsList()) {
            fieldTypes.put(field.getName(), normalizeType(field.getValueClassName()));
        }
        if (design.getDatasetsList() != null) {
            for (JRDataset dataset : design.getDatasetsList()) {
                if (dataset == null || dataset.getFields() == null) {
                    continue;
                }
                for (JRField field : dataset.getFields()) {
                    fieldTypes.putIfAbsent(field.getName(), normalizeType(field.getValueClassName()));
                }
            }
        }
        return fieldTypes;
    }

    private Map<String, String> collectVariableTypes(JasperDesign design) {
        Map<String, String> variableTypes = new LinkedHashMap<>();
        for (JRVariable variable : design.getVariablesList()) {
            if (variable.isSystemDefined()) {
                continue;
            }
            variableTypes.put(variable.getName(), normalizeType(variable.getValueClassName()));
        }
        if (design.getDatasetsList() != null) {
            for (JRDataset dataset : design.getDatasetsList()) {
                if (dataset == null || dataset.getVariables() == null) {
                    continue;
                }
                for (JRVariable variable : dataset.getVariables()) {
                    if (variable == null || variable.isSystemDefined()) {
                        continue;
                    }
                    variableTypes.putIfAbsent(
                            variable.getName(), normalizeType(variable.getValueClassName()));
                }
            }
        }
        variableTypes.putIfAbsent("REPORT_COUNT", "java.lang.Integer");
        variableTypes.putIfAbsent("PAGE_NUMBER", "java.lang.Integer");
        variableTypes.putIfAbsent("PAGE_COUNT", "java.lang.Integer");
        variableTypes.putIfAbsent("COLUMN_COUNT", "java.lang.Integer");
        variableTypes.putIfAbsent("COLUMN_NUMBER", "java.lang.Integer");
        return variableTypes;
    }

    private Map<String, String> collectVariableExpressions(JasperDesign design) {
        Map<String, String> variableExpressions = new LinkedHashMap<>();
        for (JRVariable variable : design.getVariablesList()) {
            if (variable == null || variable.isSystemDefined()) {
                continue;
            }
            JRExpression expression = variable.getExpression();
            if (expression == null || expression.getText() == null) {
                continue;
            }
            String text = expression.getText().trim();
            if (!text.isEmpty()) {
                variableExpressions.put(variable.getName(), text);
            }
        }
        return variableExpressions;
    }

    private Map<String, String> collectImportAliases(JasperDesign design) {
        Map<String, String> aliases = new LinkedHashMap<>();
        String[] imports = design.getImports();
        if (imports == null) {
            return aliases;
        }
        for (String importedClass : imports) {
            if (importedClass == null) {
                continue;
            }
            String trimmed = importedClass.trim();
            if (trimmed.isEmpty() || trimmed.endsWith(".*")) {
                continue;
            }
            int lastDot = trimmed.lastIndexOf('.');
            if (lastDot < 0 || lastDot >= trimmed.length() - 1) {
                continue;
            }
            String simpleName = trimmed.substring(lastDot + 1);
            aliases.putIfAbsent(simpleName, trimmed);
        }
        return aliases;
    }

    private Map<String, Base64Constant> collectBase64ParameterConstants(JasperDesign design) {
        Map<String, Base64Constant> constants = new LinkedHashMap<>();
        Set<String> constantNames = new LinkedHashSet<>();

        for (JRParameter parameter : design.getParametersList()) {
            if (parameter.isSystemDefined()) {
                continue;
            }

            String literal = extractLiteralString(parameter.getDefaultValueExpression());
            if (literal == null || !looksLikeBase64(literal)) {
                continue;
            }

            String baseName =
                    ("PARAM_" + sanitizeIdentifier(parameter.getName()).toUpperCase(Locale.ROOT) + "_BASE64");
            String constantName = uniqueName(baseName, constantNames);
            constants.put(
                    parameter.getName(),
                    new Base64Constant(parameter.getName(), constantName, literal));
        }
        return constants;
    }

    private String resolveTableRowsKey(TableComponent table, int tableIndex) {
        JRDatasetRun datasetRun = table.getDatasetRun();
        if (datasetRun != null && datasetRun.getDataSourceExpression() != null) {
            String expression = datasetRun.getDataSourceExpression().getText();
            if (expression != null) {
                Matcher matcher = PARAM_REF_ANY.matcher(expression.trim());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return "table_" + tableIndex + "_rows";
    }

    private void flattenColumns(List<BaseColumn> columns, List<Column> out) {
        if (columns == null) {
            return;
        }
        for (BaseColumn baseColumn : columns) {
            if (baseColumn instanceof Column) {
                out.add((Column) baseColumn);
            } else if (baseColumn instanceof ColumnGroup) {
                flattenColumns(((ColumnGroup) baseColumn).getColumns(), out);
            }
        }
    }

    private int resolveMaxCellHeight(List<Column> columns, CellSelector selector) {
        int max = 0;
        for (Column column : columns) {
            BaseCell cell = selector.select(column);
            if (cell == null) {
                continue;
            }
            max = Math.max(max, safeInt(cell.getHeight(), 0));
        }
        return max;
    }

    private String extractParameterReference(JRExpression expression) {
        if (expression == null || expression.getText() == null) {
            return null;
        }
        Matcher matcher = PARAM_REF.matcher(expression.getText().trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private String normalizeType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "java.lang.Object";
        }
        return rawType.trim();
    }

    private boolean resolveBooleanDesignProperty(
            JasperDesign design, String propertyName, boolean defaultValue) {
        if (design == null || propertyName == null || propertyName.isBlank()) {
            return defaultValue;
        }
        String raw = null;
        if (design.getPropertiesMap() != null) {
            raw = design.getPropertiesMap().getProperty(propertyName);
        }
        if (raw == null) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return defaultValue;
        }
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String extractLiteralString(JRExpression expression) {
        if (expression == null || expression.getText() == null) {
            return null;
        }
        return tryExtractQuotedLiteral(expression.getText().trim());
    }

    private String tryExtractQuotedLiteral(String expressionText) {
        if (expressionText == null) {
            return null;
        }
        String trimmed = expressionText.trim();
        if (trimmed.length() < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) {
            return null;
        }

        String body = trimmed.substring(1, trimmed.length() - 1);
        StringBuilder out = new StringBuilder(body.length());
        boolean escaping = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    default:
                        out.append(c);
                        break;
                }
                escaping = false;
                continue;
            }
            if (c == '\\') {
                escaping = true;
                continue;
            }
            out.append(c);
        }
        if (escaping) {
            out.append('\\');
        }
        return out.toString();
    }

    private boolean looksLikeBase64(String candidate) {
        if (candidate == null) {
            return false;
        }
        String compact = candidate.replaceAll("\\s+", "");
        if (compact.length() < 64 || compact.length() % 4 != 0) {
            return false;
        }
        if (!BASE64_CHARS.matcher(compact).matches()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(compact);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String sanitizeIdentifier(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "VALUE";
        }
        String cleaned = rawName.replaceAll("[^A-Za-z0-9_]", "_");
        if (!Character.isJavaIdentifierStart(cleaned.charAt(0))) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    private String uniqueName(String baseName, Set<String> existingNames) {
        String candidate = baseName;
        int suffix = 2;
        while (existingNames.contains(candidate)) {
            candidate = baseName + "_" + suffix++;
        }
        existingNames.add(candidate);
        return candidate;
    }

    private String plus(String base, float delta) {
        if (Math.abs(delta) < 0.0001f) {
            return "(" + base + ")";
        }
        return "(" + base + " + " + floatLiteral(delta) + ")";
    }

    private static String floatLiteral(float value) {
        if (Math.abs(value - Math.round(value)) < 0.0001f) {
            return ((int) Math.round(value)) + "f";
        }
        return String.format(Locale.US, "%.3ff", value);
    }

    private static boolean isBooleanType(String type) {
        return "java.lang.Boolean".equals(type) || "boolean".equals(type);
    }

    private static float safeLineWidth(Float width) {
        if (width == null || width <= 0f) {
            return 0f;
        }
        return width;
    }

    private static float safePadding(Integer padding) {
        return padding == null ? 0f : padding;
    }

    private static int safeInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static float resolveBandHeight(JRBand band) {
        return band == null ? 0f : band.getHeight();
    }

    private static Color defaultColor(Color color, Color fallback) {
        return color == null ? fallback : color;
    }

    private enum CellSelector {
        TABLE_HEADER {
            @Override
            BaseCell select(Column column) {
                return column.getTableHeader();
            }
        },
        COLUMN_HEADER {
            @Override
            BaseCell select(Column column) {
                return column.getColumnHeader();
            }
        },
        DETAIL_CELL {
            @Override
            BaseCell select(Column column) {
                return column.getDetailCell();
            }
        };

        abstract BaseCell select(Column column);
    }

    private static final class Base64Constant {
        private final String parameterName;
        private final String constantName;
        private final String value;

        private Base64Constant(String parameterName, String constantName, String value) {
            this.parameterName = parameterName;
            this.constantName = constantName;
            this.value = value;
        }
    }

    private static final class GenerationContext {
        private final JasperDesign design;
        private final Map<String, String> parameterTypes;
        private final Map<String, String> fieldTypes;
        private final Map<String, String> variableTypes;
        private final Map<String, String> variableExpressions;
        private final Map<String, String> importAliases;
        private final Map<String, Base64Constant> base64Constants;
        private final boolean repeatPageHeaderOnNewPage;
        private final boolean repeatColumnHeaderOnNewPage;
        private final boolean repeatBackgroundOnNewPage;
        private final boolean repeatPageFooterOnNewPage;
        private final boolean repeatColumnFooterOnNewPage;
        private final StyleResolver styleResolver;
        private int sequence;
        private int tableSequence;
        private String currentBandRenderedBottomVar;
        private Integer currentElementY;
        private String currentElementShiftVar;

        private GenerationContext(
                JasperDesign design,
                Map<String, String> parameterTypes,
                Map<String, String> fieldTypes,
                Map<String, String> variableTypes,
                Map<String, String> variableExpressions,
                Map<String, String> importAliases,
                Map<String, Base64Constant> base64Constants,
                boolean repeatPageHeaderOnNewPage,
                boolean repeatColumnHeaderOnNewPage,
                boolean repeatBackgroundOnNewPage,
                boolean repeatPageFooterOnNewPage,
                boolean repeatColumnFooterOnNewPage) {
            this.design = design;
            this.parameterTypes = parameterTypes;
            this.fieldTypes = fieldTypes;
            this.variableTypes = variableTypes;
            this.variableExpressions = variableExpressions;
            this.importAliases = importAliases;
            this.base64Constants = base64Constants;
            this.repeatPageHeaderOnNewPage = repeatPageHeaderOnNewPage;
            this.repeatColumnHeaderOnNewPage = repeatColumnHeaderOnNewPage;
            this.repeatBackgroundOnNewPage = repeatBackgroundOnNewPage;
            this.repeatPageFooterOnNewPage = repeatPageFooterOnNewPage;
            this.repeatColumnFooterOnNewPage = repeatColumnFooterOnNewPage;
            this.styleResolver = StyleResolver.getInstance();
        }

        private String nextVar(String prefix) {
            sequence++;
            return prefix + "_" + sequence;
        }
    }
}
