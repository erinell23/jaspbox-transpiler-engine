package com.jaspbox.compiler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlLoader;

/**
 * Carga JRXML compatible con Jasper 6/7.
 *
 * <p>Jasper 7 usa un loader basado en Jackson que no detecta reportes con
 * namespace por defecto en la raíz. Se intenta carga directa primero y, si
 * falla con "Unable to load report", se aplica una normalización mínima.
 */
public final class JasperDesignLoader {

    private static final String DEFAULT_JASPER_NS =
            "xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"";
    private static final String XMLNS_XSI = "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";
    private static final String[] SINGLE_BAND_SECTIONS = {
        "background", "title", "pageHeader", "columnHeader", "columnFooter", "pageFooter",
        "lastPageFooter", "summary", "noData"
    };

    private JasperDesignLoader() {}

    public static JasperDesign load(Path jrxmlPath) throws IOException, JRException {
        byte[] sourceBytes = Files.readAllBytes(jrxmlPath);
        return load(sourceBytes);
    }

    private static JasperDesign load(byte[] sourceBytes) throws JRException {
        try {
            return JRXmlLoader.load(new ByteArrayInputStream(sourceBytes));
        } catch (JRException ex) {
            if (!isUnableToLoadReport(ex)) {
                throw ex;
            }
            byte[] normalized = normalizeForJasper7(sourceBytes);
            try {
                return JRXmlLoader.load(new ByteArrayInputStream(normalized));
            } catch (Exception normalizedEx) {
                throw mapNormalizedFailure(sourceBytes, normalizedEx);
            }
        } catch (Exception ex) {
            if (looksLikeLegacyJasper6Template(sourceBytes)) {
                throw legacyTemplateException(ex);
            }
            if (ex instanceof JRException) {
                throw (JRException) ex;
            }
            throw new JRException(ex);
        }
    }

    private static boolean isUnableToLoadReport(JRException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("Unable to load report");
    }

    private static boolean looksLikeLegacyJasper6Template(byte[] sourceBytes) {
        String xml = new String(sourceBytes, StandardCharsets.UTF_8);
        return xml.contains("xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"")
                || xml.contains("<title>")
                || xml.contains("<pageHeader>")
                || xml.contains("<columnHeader>")
                || xml.contains("<detail>");
    }

    private static JRException mapNormalizedFailure(byte[] sourceBytes, Exception normalizedEx) {
        if (looksLikeLegacyJasper6Template(sourceBytes)) {
            return legacyTemplateException(normalizedEx);
        }
        if (normalizedEx instanceof JRException) {
            return (JRException) normalizedEx;
        }
        return new JRException(normalizedEx);
    }

    private static JRException legacyTemplateException(Throwable cause) {
        return new JRException(
                "El JRXML parece estar en formato JasperReports 6.x. "
                        + "Con JasperReports 7 debes migrar el template a formato v7 "
                        + "(por ejemplo, abriéndolo y guardándolo con Jaspersoft Studio 7).",
                cause);
    }

    private static byte[] normalizeForJasper7(byte[] sourceBytes) {
        String xml = new String(sourceBytes, StandardCharsets.UTF_8);
        String normalized = xml;
        if (normalized.contains(DEFAULT_JASPER_NS)) {
            normalized = normalized.replaceFirst(DEFAULT_JASPER_NS, "");
        }
        if (normalized.contains(XMLNS_XSI)) {
            normalized = normalized.replaceFirst(XMLNS_XSI, "");
        }
        normalized =
                normalized.replaceFirst("\\s+xsi:schemaLocation\\s*=\\s*\"[^\"]*\"", "");
        normalized = flattenLegacySingleBandSections(normalized);

        if (normalized.equals(xml)) {
            return sourceBytes;
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private static String flattenLegacySingleBandSections(String xml) {
        String result = xml;
        for (String section : SINGLE_BAND_SECTIONS) {
            result = flattenSection(result, section);
        }
        return result;
    }

    private static String flattenSection(String xml, String section) {
        String escaped = Pattern.quote(section);

        Pattern fullBandPattern =
                Pattern.compile(
                        "(?s)<" + escaped + "(\\s[^>]*)?>\\s*<band(\\s[^>]*)?>(.*?)</band>\\s*</"
                                + escaped + ">");
        Matcher fullMatcher = fullBandPattern.matcher(xml);
        StringBuffer fullBuffer = new StringBuffer();
        while (fullMatcher.find()) {
            String sectionAttrs = safeAttrs(fullMatcher.group(1));
            String bandAttrs = safeAttrs(fullMatcher.group(2));
            String content = fullMatcher.group(3);
            String replacement =
                    "<" + section + sectionAttrs + bandAttrs + ">" + content + "</" + section + ">";
            fullMatcher.appendReplacement(fullBuffer, Matcher.quoteReplacement(replacement));
        }
        fullMatcher.appendTail(fullBuffer);
        String afterFull = fullBuffer.toString();

        Pattern selfClosingPattern =
                Pattern.compile(
                        "(?s)<" + escaped + "(\\s[^>]*)?>\\s*<band(\\s[^>]*)?/>\\s*</" + escaped + ">");
        Matcher selfMatcher = selfClosingPattern.matcher(afterFull);
        StringBuffer selfBuffer = new StringBuffer();
        while (selfMatcher.find()) {
            String sectionAttrs = safeAttrs(selfMatcher.group(1));
            String bandAttrs = safeAttrs(selfMatcher.group(2));
            String replacement = "<" + section + sectionAttrs + bandAttrs + "/>";
            selfMatcher.appendReplacement(selfBuffer, Matcher.quoteReplacement(replacement));
        }
        selfMatcher.appendTail(selfBuffer);
        return selfBuffer.toString();
    }

    private static String safeAttrs(String attrs) {
        return attrs == null ? "" : attrs;
    }
}
