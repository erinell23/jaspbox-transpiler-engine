package com.jaspbox.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.sf.jasperreports.engine.design.JRDesignBand;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignParameter;
import net.sf.jasperreports.engine.design.JRDesignSection;
import net.sf.jasperreports.engine.design.JRDesignTextField;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlWriter;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void shouldReturnInvalidUsageWhenNoArgs() {
        int exitCode = Main.run(new String[0]);
        assertEquals(2, exitCode);
    }

    @Test
    void shouldReturnSuccessForHelp() {
        int exitCode = Main.run(new String[] {"--help"});
        assertEquals(0, exitCode);
    }

    @Test
    void shouldGeneratePdfFromJrxmlAndJson() throws Exception {
        Path jrxml = Files.createTempFile("jaspbox-main-test-", ".jrxml");
        JasperDesign design = new JasperDesign();
        design.setName("testMinimal");
        design.setPageWidth(595);
        design.setPageHeight(842);
        design.setLeftMargin(20);
        design.setRightMargin(20);
        design.setTopMargin(20);
        design.setBottomMargin(20);
        design.setColumnWidth(555);

        JRDesignParameter titleParam = new JRDesignParameter();
        titleParam.setName("TITLE");
        titleParam.setValueClass(String.class);
        design.addParameter(titleParam);

        JRDesignBand titleBand = new JRDesignBand();
        titleBand.setHeight(40);
        JRDesignTextField titleField = new JRDesignTextField();
        titleField.setX(0);
        titleField.setY(0);
        titleField.setWidth(300);
        titleField.setHeight(20);
        titleField.setExpression(new JRDesignExpression("$P{TITLE}"));
        titleBand.addElement(titleField);
        design.setTitle(titleBand);

        JRDesignBand detailBand = new JRDesignBand();
        detailBand.setHeight(1);
        ((JRDesignSection) design.getDetailSection()).addBand(detailBand);

        JRXmlWriter.writeReport(design, jrxml.toString(), StandardCharsets.UTF_8.name());

        Path json = Files.createTempFile("jaspbox-main-test-", ".json");
        String jsonContent =
                "{\n"
                        + "  \"TITLE\": \"Reporte Test\"\n"
                        + "}\n";
        Files.writeString(json, jsonContent, StandardCharsets.UTF_8);

        String outputName = "main-test-generated.pdf";
        Path outputPath = Paths.get("target/output/" + outputName).toAbsolutePath();
        Files.deleteIfExists(outputPath);

        int exitCode =
                Main.run(
                        new String[] {
                            "run", jrxml.toString(), json.toString(), "--out", outputName
                        });

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outputPath), "Debe generar el PDF de salida");
    }
}
