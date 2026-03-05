package com.jaspbox.runtime;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public abstract class AbstractTemplateRuntime {

    protected static float toPdfY(float pageHeight, float yJasper, float elementHeight) {
        return pageHeight - yJasper - elementHeight;
    }

    protected static Object readTypedValue(
            Map<String, Object> source, String key, String expectedType) {
        Object value = source == null ? null : source.get(key);
        if (value == null || expectedType == null || "java.lang.Object".equals(expectedType)) {
            return value;
        }

        switch (expectedType) {
            case "java.lang.String":
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException(
                            "Tipo invalido para clave "
                                    + key
                                    + ". Esperado "
                                    + expectedType
                                    + ", recibido "
                                    + value.getClass().getName());
                }
                return value;
            case "java.lang.Boolean":
                if (!(value instanceof Boolean)) {
                    throw new IllegalArgumentException(
                            "Tipo invalido para clave "
                                    + key
                                    + ". Esperado "
                                    + expectedType
                                    + ", recibido "
                                    + value.getClass().getName());
                }
                return value;
            case "java.lang.Integer":
            case "java.lang.Long":
            case "java.lang.Double":
            case "java.lang.Float":
            case "java.math.BigDecimal":
            case "java.math.BigInteger":
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException(
                            "Tipo invalido para clave "
                                    + key
                                    + ". Esperado numerico "
                                    + expectedType
                                    + ", recibido "
                                    + value.getClass().getName());
                }
                return value;
            case "java.util.List":
            case "java.util.ArrayList":
                if (!(value instanceof List)) {
                    throw new IllegalArgumentException(
                            "Tipo invalido para clave "
                                    + key
                                    + ". Se esperaba List y se recibio "
                                    + value.getClass().getName());
                }
                return value;
            default:
                try {
                    Class<?> expectedClass = Class.forName(expectedType);
                    if (!expectedClass.isInstance(value)) {
                        throw new IllegalArgumentException(
                                "Tipo invalido para clave "
                                        + key
                                        + ". Esperado "
                                        + expectedType
                                        + ", recibido "
                                        + value.getClass().getName());
                    }
                    return value;
                } catch (ClassNotFoundException ignored) {
                    return value;
                }
        }
    }

    protected static String asText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    protected static String sanitizeText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ");
        StringBuilder clean = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= 32 || c == 9) {
                clean.append(c);
            } else {
                clean.append(' ');
            }
        }
        return clean.toString();
    }

    protected static PDFont resolveFont(String fontName, boolean bold, boolean italic) {
        Standard14Fonts.FontName selected;
        if (bold && italic) {
            selected = Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE;
        } else if (bold) {
            selected = Standard14Fonts.FontName.HELVETICA_BOLD;
        } else if (italic) {
            selected = Standard14Fonts.FontName.HELVETICA_OBLIQUE;
        } else {
            selected = Standard14Fonts.FontName.HELVETICA;
        }
        return new PDType1Font(selected);
    }

    protected static float alignTextX(
            PDFont font,
            float fontSize,
            String text,
            float boxX,
            float boxWidth,
            String align)
            throws IOException {
        if (text == null || text.isEmpty()) {
            return boxX;
        }

        float textWidth = (font.getStringWidth(text) / 1000f) * fontSize;
        if ("CENTER".equalsIgnoreCase(align)) {
            return boxX + Math.max((boxWidth - textWidth) / 2f, 0f);
        }

        if ("RIGHT".equalsIgnoreCase(align)) {
            return boxX + Math.max(boxWidth - textWidth, 0f);
        }

        return boxX;
    }

    protected static float textWidth(PDFont font, float fontSize, String value) throws IOException {
        if (value == null || value.isEmpty()) {
            return 0f;
        }
        return (font.getStringWidth(value) / 1000f) * fontSize;
    }

    protected static List<String> wrapText(
            PDFont font, float fontSize, String text, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }

        String normalized = text.replace("\r", " ").replace("\n", " ");
        if (maxWidth <= 1f) {
            lines.add(normalized);
            return lines;
        }

        int start = 0;
        int lastBreak = -1;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c) || c == '-') {
                lastBreak = i;
            }

            String probe = normalized.substring(start, i + 1).trim();
            if (probe.isEmpty()) {
                continue;
            }

            if (textWidth(font, fontSize, probe) > maxWidth) {
                int breakPos = lastBreak >= start ? lastBreak + 1 : i;
                if (breakPos <= start) {
                    breakPos = i + 1;
                }
                String line = normalized.substring(start, breakPos).trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
                start = breakPos;
                while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                    start++;
                }
                lastBreak = -1;
                i = start - 1;
            }
        }

        if (start < normalized.length()) {
            String tail = normalized.substring(start).trim();
            if (!tail.isEmpty()) {
                lines.add(tail);
            }
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        if (lines.size() == 1 && maxWidth <= 90f && normalized.indexOf(' ') > 0) {
            String single = lines.get(0);
            float singleWidth = textWidth(font, fontSize, single);
            if (singleWidth > (maxWidth * 0.92f)) {
                int center = single.length() / 2;
                int leftBreak = single.lastIndexOf(' ', center);
                int rightBreak = single.indexOf(' ', center + 1);
                int split = -1;
                if (leftBreak > 0 && rightBreak > 0) {
                    split = (center - leftBreak) <= (rightBreak - center) ? leftBreak : rightBreak;
                } else if (leftBreak > 0) {
                    split = leftBreak;
                } else if (rightBreak > 0) {
                    split = rightBreak;
                }
                if (split > 0) {
                    String first = single.substring(0, split).trim();
                    String second = single.substring(split + 1).trim();
                    if (!first.isEmpty() && !second.isEmpty()) {
                        lines.clear();
                        lines.add(first);
                        lines.add(second);
                    }
                }
            }
        }

        return lines;
    }

    protected static boolean isLikelyBase64(String value) {
        if (value == null) {
            return false;
        }
        String compact = value.replaceAll("\\s+", "");
        if (compact.length() < 16) {
            return false;
        }
        if ((compact.length() % 4) != 0) {
            return false;
        }
        return compact.matches("^[A-Za-z0-9+/]+={0,2}$");
    }

    protected static byte[] decodeImageBytes(Object imageValue, String fallbackBase64)
            throws IOException {
        if (imageValue instanceof byte[]) {
            return (byte[]) imageValue;
        }

        if (imageValue instanceof RenderedImage) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write((RenderedImage) imageValue, "png", out)) {
                throw new IOException("No se pudo codificar RenderedImage");
            }
            return out.toByteArray();
        }

        if (imageValue instanceof Image) {
            Image image = (Image) imageValue;
            int width = Math.max(image.getWidth(null), 1);
            int height = Math.max(image.getHeight(null), 1);
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = buffered.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(buffered, "png", out)) {
                throw new IOException("No se pudo codificar Image");
            }
            return out.toByteArray();
        }

        if (imageValue instanceof String) {
            String raw = ((String) imageValue).trim();
            if (raw.startsWith("data:")) {
                int comma = raw.indexOf(',');
                if (comma > -1) {
                    raw = raw.substring(comma + 1);
                }
            }

            if (!raw.isEmpty() && isLikelyBase64(raw)) {
                try {
                    return Base64.getDecoder().decode(raw.replaceAll("\\s+", ""));
                } catch (IllegalArgumentException decodeError) {
                    throw new IOException("Base64 invalido en imagen: " + decodeError.getMessage(), decodeError);
                }
            }

            if (!raw.isEmpty()) {
                Path path = Paths.get(raw);
                if (Files.exists(path)) {
                    return Files.readAllBytes(path);
                }
            }
        }

        if (fallbackBase64 != null && !fallbackBase64.trim().isEmpty()) {
            try {
                return Base64.getDecoder().decode(fallbackBase64.replaceAll("\\s+", ""));
            } catch (IllegalArgumentException decodeError) {
                throw new IOException("Base64 fallback invalido: " + decodeError.getMessage(), decodeError);
            }
        }

        return null;
    }

    protected static void drawImage(
            PDDocument document,
            PDPageContentStream contentStream,
            Object imageValue,
            String fallbackBase64,
            float x,
            float y,
            float width,
            float height,
            String imageKey,
            String scaleImage,
            String horizontalAlign,
            String verticalAlign)
            throws IOException {
        byte[] bytes = decodeImageBytes(imageValue, fallbackBase64);
        if (bytes == null || bytes.length == 0) {
            return;
        }

        PDImageXObject image = PDImageXObject.createFromByteArray(document, bytes, imageKey);
        float drawX = x;
        float drawY = y;
        float drawWidth = width;
        float drawHeight = height;

        if ("RETAIN_SHAPE".equalsIgnoreCase(scaleImage)) {
            float scaleX = width / Math.max(1f, image.getWidth());
            float scaleY = height / Math.max(1f, image.getHeight());
            float scale = Math.min(scaleX, scaleY);
            drawWidth = Math.max(1f, image.getWidth() * scale);
            drawHeight = Math.max(1f, image.getHeight() * scale);
        } else if ("CLIP".equalsIgnoreCase(scaleImage)) {
            drawWidth = Math.max(1f, image.getWidth());
            drawHeight = Math.max(1f, image.getHeight());
        }

        if ("RIGHT".equalsIgnoreCase(horizontalAlign)) {
            drawX = x + (width - drawWidth);
        } else if ("CENTER".equalsIgnoreCase(horizontalAlign)) {
            drawX = x + ((width - drawWidth) / 2f);
        }

        if ("TOP".equalsIgnoreCase(verticalAlign)) {
            drawY = y + (height - drawHeight);
        } else if ("MIDDLE".equalsIgnoreCase(verticalAlign)) {
            drawY = y + ((height - drawHeight) / 2f);
        }

        if ("CLIP".equalsIgnoreCase(scaleImage)) {
            contentStream.saveGraphicsState();
            contentStream.addRect(x, y, width, height);
            contentStream.clip();
            contentStream.drawImage(image, drawX, drawY, drawWidth, drawHeight);
            contentStream.restoreGraphicsState();
        } else {
            contentStream.drawImage(image, drawX, drawY, drawWidth, drawHeight);
        }
    }

    protected static String decapitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() == 1) {
            return value.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> beanToMap(Object bean, String key) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (bean == null) {
            return row;
        }

        if (bean instanceof Map) {
            return (Map<String, Object>) bean;
        }

        try {
            for (java.lang.reflect.Method method : bean.getClass().getMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                String name = method.getName();
                if ("getClass".equals(name)) {
                    continue;
                }

                String property = null;
                if (name.startsWith("get") && name.length() > 3) {
                    property = decapitalize(name.substring(3));
                } else if (name.startsWith("is") && name.length() > 2) {
                    property = decapitalize(name.substring(2));
                }

                if (property == null || property.isEmpty()) {
                    continue;
                }

                Object value = method.invoke(bean);
                row.put(property, value);
            }
            return row;
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "No se pudo leer bean para clave " + key + " de tipo " + bean.getClass().getName(),
                    ex);
        }
    }

    protected static String replaceWord(String source, String word, String replacement) {
        if (source == null || word == null || word.isEmpty()) {
            return source;
        }
        String regex = "(?<![\\w$.])" + Pattern.quote(word) + "(?![\\w$])";
        return source.replaceAll(regex, Matcher.quoteReplacement(replacement));
    }

    protected static int findOperatorOutsideQuotes(String expression, String operator) {
        boolean inQuotes = false;
        for (int i = 0; i <= expression.length() - operator.length(); i++) {
            char c = expression.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes && expression.startsWith(operator, i)) {
                return i;
            }
        }
        return -1;
    }

    protected static List<String> splitConcat(String expression) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
                continue;
            }
            if (!inQuotes && c == '+') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    protected static String extractReferenceKey(String expression, String scope) {
        if (expression == null || scope == null) {
            return null;
        }
        String trimmed = expression.trim();
        String prefix = "$" + scope + "{";
        if (!trimmed.startsWith(prefix) || !trimmed.endsWith("}")) {
            return null;
        }
        String candidate = trimmed.substring(prefix.length(), trimmed.length() - 1);
        if (!candidate.matches("[\\w$.]+")) {
            return null;
        }
        return candidate;
    }

    protected static String unquote(String quoted) {
        if (quoted == null || quoted.length() < 2) {
            return quoted;
        }

        String body = quoted.substring(1, quoted.length() - 1);
        StringBuilder result = new StringBuilder(body.length());
        boolean escaped = false;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (!escaped && c == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                switch (c) {
                    case 'n':
                        result.append('\n');
                        break;
                    case 'r':
                        result.append('\r');
                        break;
                    case 't':
                        result.append('\t');
                        break;
                    case '"':
                        result.append('"');
                        break;
                    case '\\':
                        result.append('\\');
                        break;
                    default:
                        result.append(c);
                        break;
                }
                escaped = false;
            } else {
                result.append(c);
            }
        }

        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> asRowList(Object rows, String key) {
        if (rows == null) {
            return Collections.emptyList();
        }

        if (!(rows instanceof List)) {
            throw new IllegalArgumentException(
                    "La clave de tabla "
                            + key
                            + " debe contener una lista, recibido "
                            + rows.getClass().getName());
        }

        List<?> list = (List<?>) rows;
        List<Map<String, Object>> normalized = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (entry == null) {
                normalized.add(new LinkedHashMap<>());
                continue;
            }
            if (entry instanceof Map) {
                normalized.add((Map<String, Object>) entry);
                continue;
            }
            normalized.add(beanToMap(entry, key));
        }

        return normalized;
    }
}
