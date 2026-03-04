# Compatibilidad JRXML para renderizado correcto

Esta guía resume ajustes prácticos que se han necesitado en plantillas JRXML para que el resultado entre Jasper y el template generado sea consistente.

## 1) Base64 en expresiones Java

Recomendación: usar `java.util.Base64` en lugar de `org.apache.commons.codec.binary.Base64` dentro de expresiones JRXML.

Patrón recomendado:

```java
Base64.getDecoder().decode($P{IMGLOGO}.getBytes("UTF-8"))
```

En vez de:

```java
Base64.decodeBase64($P{IMGLOGO}.getBytes("UTF-8"))
```

Notas:
- Si usas `Base64` sin nombre totalmente calificado, agrega `<import value="java.util.Base64"/>` en el JRXML.
- Esto simplifica la evaluación de expresiones y evita incompatibilidades por API.

## 2) Validaciones por `toString()` de listas/mapas

Cuando se evalúan condiciones como:

```java
java.util.Arrays.toString($P{APROBADORES}.toArray()).toLowerCase().contains("...")
```

recuerda que el formato típico de `Map.toString()` en Java usa `clave=valor` (sin comillas), por ejemplo `level=2`.

Caso real:
- No usar: `contains("level='2'")`
- Usar: `contains("level=2")`

## 3) Logo con parámetro nulo o vacío

Si `IMGLOGO` puede venir `null` o vacío, haz null-safe la variable de imagen para evitar errores de evaluación:

```xml
<variable name="IMGFIRMA64" class="java.awt.Image" resetType="None">
  <variableExpression><![CDATA[
($P{IMGLOGO} == null || $P{IMGLOGO}.trim().isEmpty())
  ? null
  : ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode($P{IMGLOGO}.getBytes("UTF-8"))))
]]></variableExpression>
</variable>
```

## 4) `defaultValueExpression` de parámetros

Si quieres que Jasper use el `defaultValueExpression`:
- evita enviar explícitamente valores vacíos (`""`) o `null` cuando no aplique,
- o asegúrate de que el runtime respete defaults al normalizar parámetros.

En este repositorio ya se ajustó el runner para priorizar defaults literales del JRXML cuando un parámetro llega ausente o `null`.

## 5) Tablas de aprobadores cuando no hay filas

Cuando el dataset de aprobadores está vacío o ausente, Jasper puede no renderizar la tabla completa.  
El engine de este repositorio ya fue ajustado para alinear ese comportamiento y no dibujar headers/detalle de la tabla sin filas.

## Automatización de ajustes

Existe un script para aplicar automáticamente estos cambios sobre uno o varios JRXML:

```bash
scripts/fix-jrxml-compat.sh local-input/payment-report.jrxml
```

Aplicar sobre una carpeta completa:

```bash
scripts/fix-jrxml-compat.sh local-input/
```

Modo validación (sin escribir cambios):

```bash
scripts/fix-jrxml-compat.sh --check local-input/
```

---

Si aparece un nuevo caso de diferencia Jasper vs template generado, agrega aquí:
- fragmento JRXML original,
- fragmento JRXML corregido,
- comando usado para reproducir,
- resultado observado.
