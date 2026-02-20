# JaspBox Transpiler Engine

Herramienta CLI en Java 11+ que convierte plantillas JasperReports (`.jrxml`) a clase Java standalone (PDFBox) y genera el PDF final a partir de datos JSON.

## Qué hace

1. Lee un `JRXML`.
2. Genera una clase Java transpileada en `target/generated-sources/jaspbox`.
3. Compila esa clase en `target/generated-classes/jaspbox`.
4. Ejecuta el método `build(Map<String,Object>, String)` de la clase generada para producir el PDF.

Opcionalmente puede comparar el resultado contra Jasper (`--compare`).

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
  run <archivo.jrxml> <archivo.json> [--compare] [--class NombreClase] [--out salida.pdf]
```

También soporta forma corta:

```bash
java -jar target/jaspbox-transpiler-engine-1.0.0-SNAPSHOT-all.jar \
  <archivo.jrxml> <archivo.json> [--compare] [--class NombreClase] [--out salida.pdf]
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

## Uso con Maven (sin jar)

```bash
mvn -q compile exec:java -Dexec.args="run local-input/payment-report.jrxml local-input/payment-report.json"
```

## Salidas

- Clase Java generada: `target/generated-sources/jaspbox/com/jaspbox/generated/*.java`
- Clase compilada: `target/generated-classes/jaspbox`
- PDF generado: `target/output/*.pdf`
- PDF Jasper (si `--compare`): `target/output/*-jasper.pdf`

## Formato JSON esperado

El JSON debe ser un objeto raíz (`Map<String,Object>`) con claves que coincidan con los parámetros/campos usados por el JRXML.

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
- Apache PDFBox 2.0.x (render del PDF final)

## Tests

Ejecutar tests:

```bash
mvn test
```

Incluye smoke tests de CLI en `src/test/java/com/jaspbox/demo/MainTest.java`.
