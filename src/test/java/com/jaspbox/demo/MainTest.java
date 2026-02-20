package com.jaspbox.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        String jrxmlContent =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"\n"
                        + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                        + " xsi:schemaLocation=\"http://jasperreports.sourceforge.net/jasperreports http://jasperreports.sourceforge.net/xsd/jasperreport.xsd\"\n"
                        + " name=\"testMinimal\" pageWidth=\"595\" pageHeight=\"842\" columnWidth=\"555\"\n"
                        + " leftMargin=\"20\" rightMargin=\"20\" topMargin=\"20\" bottomMargin=\"20\">\n"
                        + "  <parameter name=\"TITLE\" class=\"java.lang.String\"/>\n"
                        + "  <title>\n"
                        + "    <band height=\"40\">\n"
                        + "      <textField>\n"
                        + "        <reportElement x=\"0\" y=\"0\" width=\"300\" height=\"20\"/>\n"
                        + "        <textFieldExpression><![CDATA[$P{TITLE}]]></textFieldExpression>\n"
                        + "      </textField>\n"
                        + "    </band>\n"
                        + "  </title>\n"
                        + "  <detail>\n"
                        + "    <band height=\"1\"/>\n"
                        + "  </detail>\n"
                        + "</jasperReport>\n";
        Files.writeString(jrxml, jrxmlContent, StandardCharsets.UTF_8);

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
