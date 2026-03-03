# JaspBox Transpiler Engine

Herramienta CLI en Java 11+ que convierte plantillas JasperReports (`.jrxml`) a clase Java standalone (PDFBox) y genera el PDF final a partir de datos JSON.

## Qué hace

1. Lee un `JRXML`.
2. Genera una clase Java transpileada en `target/generated-sources/jaspbox`.
3. Compila esa clase en `target/generated-classes/jaspbox`.
4. Ejecuta la clase generada para producir el PDF (`build(...)` o `buildBytes(...)`).

Opcionalmente puede comparar el resultado contra Jasper (`--compare`).
También permite ejecutar una clase generada/editada manualmente sin volver a transpilar (`run-class`).

## Requisitos

- JDK 11+
- Maven 3.8+

## Build e instalación

Compilar, testear y empaquetar:

```bash
mvn clean package
```

Esto genera un jar ejecutable (fat jar):

- `target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar`

## Uso CLI

### Ayuda

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar --help
```

### Comando principal

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  run <archivo.jrxml> <archivo.json> [--datasource archivo-datasource.json] [--compare] [--class NombreClase] [--out salida.pdf]
```

### Ejecutar solo clase generada (sin retranspilar)

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  run-class <template.java> <archivo.json> [--datasource archivo-datasource.json] [--class FQCN|NombreClase] [--out salida.pdf]
```

También soporta forma corta:

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  <archivo.jrxml> <archivo.json> [--datasource archivo-datasource.json] [--compare] [--class NombreClase] [--out salida.pdf]
```

### Ejemplos

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  run local-input/payment-report.jrxml local-input/payment-report.json
```

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  run local-input/payment-report.jrxml local-input/payment-report.json \
  --class PaymentReportTemplate --out payment-generated.pdf --compare
```

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  run local-input/payment-report.jrxml local-input/payment-report.json \
  --datasource local-input/payment-report-datasource.json
```

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  run-class target/generated-sources/jaspbox/com/jaspbox/generated/PaymentTemplate.java \
  local-input/payment-report.json \
  --datasource local-input/payment-report-datasource.json \
  --out payment-manual.pdf
```

Si no indicas `--datasource`, el CLI intenta autodetectar:
- `<archivo-json>-datasource.json` en la misma carpeta.

## Uso con Maven (sin jar)

```bash
mvn -q compile exec:java -Dexec.args="run local-input/payment-report.jrxml local-input/payment-report.json"
```

```bash
mvn -q compile exec:java -Dexec.args="run-class target/generated-sources/jaspbox/com/jaspbox/generated/PaymentTemplate.java local-input/payment-report.json --datasource local-input/payment-report-datasource.json --out payment-manual.pdf"
```

## Salidas

- Clase Java generada: `target/generated-sources/jaspbox/com/jaspbox/generated/*.java`
- Clase compilada: `target/generated-classes/jaspbox`
- Clase compilada manual (`run-class`): `target/generated-classes/jaspbox-manual`
- PDF generado: `target/output/*.pdf`
- PDF Jasper (si `--compare`): `target/output/*-jasper.pdf`

## Formato JSON esperado

El JSON principal debe ser un objeto raíz (`Map<String,Object>`) con claves que coincidan con los parámetros del JRXML.

Para el datasource principal (`detail`), puedes usar:
- `--datasource archivo.json` (recomendado), o
- autodetección de `<archivo-json>-datasource.json` en la misma carpeta.

Ejemplo:

```json
{
  "TITLE": "Reporte",
  "SHOW_SUBTITLE": true,
  "TABLE_ROWS": [
    {"ITEM": "Servicio A", "AMOUNT": 100.5}
  ]
}
```

## Insumos fuera del repositorio

Para evitar versionar archivos de entrada, usa `local-input/`.

Ya está ignorado en `.gitignore`:

- `local-input/`

## Stack técnico

- Java 11+
- Maven
- JasperReports (parse/validación del JRXML)
- JavaPoet (generación de fuente Java)
- Apache PDFBox (render del PDF final)

### Compatibilidad JasperReports 7

Esta rama usa JasperReports `7.x` para parseo/compilación del `JRXML`.

- Si tu template fue creado en JasperReports/Jaspersoft Studio `6.x`, debes migrarlo a formato `7.x` antes de usar `run`.
- Recomendado: abrir el `.jrxml` en Jaspersoft Studio 7 y guardarlo nuevamente.
- Si intentas cargar un template legacy 6.x, el CLI mostrará un error explícito de migración.

## API de la clase generada

La clase transpileada expone estas variantes:

- `build(Map<String,Object> data, String outputPath)`
- `build(Map<String,Object> data, List<Map<String,Object>> dataSource, String outputPath)`
- `buildBytes(Map<String,Object> data)`
- `buildBytes(Map<String,Object> data, List<Map<String,Object>> dataSource)`

## Paginación multipágina estilo Jasper

Cuando el `detail` supera el espacio disponible:

- se crea nueva página automáticamente,
- se repiten encabezados configurables (`pageHeader`, `columnHeader`, `background`),
- se procesan footers de salida de página (`columnFooter`, `pageFooter`) y `lastPageFooter` en la última página.

Puedes controlar el comportamiento por `JRXML` con propiedades opcionales:

```xml
<property name="jaspbox.repeatBackgroundOnNewPage" value="true"/>
<property name="jaspbox.repeatPageHeaderOnNewPage" value="true"/>
<property name="jaspbox.repeatColumnHeaderOnNewPage" value="true"/>
<property name="jaspbox.repeatColumnFooterOnNewPage" value="true"/>
<property name="jaspbox.repeatPageFooterOnNewPage" value="true"/>
```

Si no defines estas propiedades, el valor por defecto es `true`.

## Tests

Ejecutar tests:

```bash
mvn test
```

Incluye smoke tests de CLI en `src/test/java/com/jaspbox/demo/MainTest.java`.
