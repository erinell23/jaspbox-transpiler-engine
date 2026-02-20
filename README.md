# JaspBox Transpiler Engine

Motor en Java 11+ que transpila plantillas **JasperReports (`.jrxml`)** a una clase Java standalone que renderiza PDF con **Apache PDFBox 2.0.x**.

El objetivo es eliminar JasperReports en tiempo de ejecución del reporte generado.

## Stack

- Java 11+
- Maven
- JasperReports (`net.sf.jasperreports:jasperreports`) solo para parseo/compilación del diseño
- JavaPoet (`com.squareup:javapoet`) para emitir el `.java`
- PDFBox (`org.apache.pdfbox:pdfbox:2.0.x`) para render final

## Estructura actual del proyecto

- Compilador: `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine/src/main/java/com/jaspbox/compiler/JaspBoxCompiler.java`
- Demo/runner: `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine/src/main/java/com/jaspbox/demo/Main.java`
- Plantillas de ejemplo:
  - `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine/src/main/resources/test.jrxml`
  - `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine/src/main/resources/example-robust.jrxml`
  - `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine/src/main/resources/payment-report.jrxml`

## Requisitos

- JDK 11+ (no solo JRE)
- Maven 3.8+

## Comandos rápidos

Desde `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine`:

```bash
mvn clean compile
```

Ejecutar demo básica (`test.jrxml`):

```bash
mvn -q clean compile exec:java
```

Ejecutar demo robusta (`example-robust.jrxml`):

```bash
mvn -q clean compile exec:java -Dexec.args=robust
```

Ejecutar comparación Jasper vs clase generada (`payment-report.jrxml`):

```bash
mvn -q clean compile exec:java -Dexec.args=payment-compare
```

Ejecutar con archivos externos (`jrxml` + `json`):

```bash
mvn -q clean compile exec:java -Dexec.args="run local-input/mi-reporte.jrxml local-input/mi-data.json"
```

Ejecutar con comparación Jasper incluida:

```bash
mvn -q clean compile exec:java -Dexec.args="run local-input/mi-reporte.jrxml local-input/mi-data.json --compare"
```

## Salidas generadas

- Fuente Java generada: `target/generated-sources/jaspbox/com/jaspbox/generated/*.java`
- Clases compiladas: `target/generated-classes/jaspbox`
- PDFs: `target/output/*.pdf`

## Cómo convertir un nuevo template (`.jrxml`)

Sugerencia para no versionar insumos de pruebas: usa la carpeta `local-input/` (está en `.gitignore`).

### 1) Agrega el JRXML y JSON

Copia tus archivos fuera de `src/main`, por ejemplo:

- `local-input/my-report.jrxml`
- `local-input/my-report-data.json`

### 2) Extrae entradas requeridas (parámetros y campos)

Puedes listar claves mínimas con:

```bash
rg -n "<parameter name=|<field name=" src/main/resources/my-report.jrxml
```

Con eso armas tu JSON con un objeto raíz (`Map<String, Object>`) respetando tipos (`String`, `Integer`, `Double`, `Boolean`, listas, etc.).

### 3) Transpila y genera PDF

Sin tocar código Java:

```bash
mvn -q clean compile exec:java -Dexec.args="run local-input/my-report.jrxml local-input/my-report-data.json"
```

Opciones útiles:

```bash
# Define nombre de clase generado
mvn -q clean compile exec:java -Dexec.args="run local-input/my-report.jrxml local-input/my-report-data.json --class MyCustomTemplate"

# Define nombre del PDF de salida
mvn -q clean compile exec:java -Dexec.args="run local-input/my-report.jrxml local-input/my-report-data.json --out my-report-output.pdf"

# Compara contra Jasper
mvn -q clean compile exec:java -Dexec.args="run local-input/my-report.jrxml local-input/my-report-data.json --compare"
```

### 4) Transpila desde código (opcional)

Forma más directa (desde código):

```java
Path jrxml = Paths.get("src/main/resources/my-report.jrxml");
Path outSrc = Paths.get("target/generated-sources/jaspbox");

JaspBoxCompiler compiler = new JaspBoxCompiler();
Path javaFile = compiler.transpile(jrxml, outSrc, "com.jaspbox.generated", "MyReportTemplate");
```

### 5) Compila la clase generada

Opción A: usar el flujo ya implementado en `Main` (recomendado).

Opción B: compilar tú mismo la clase generada e incluir `pdfbox` en classpath.

### 6) Usa la clase generada para renderizar PDF

Contrato de la clase generada:

```java
public void build(Map<String, Object> data, String outputPath) throws IOException
```

Uso:

```java
Map<String, Object> data = new LinkedHashMap<>();
// data.put("PARAM", valor); ...

new com.jaspbox.generated.MyReportTemplate()
    .build(data, "target/output/my-report.pdf");
```

## Validación contra Jasper (recomendado)

Para validar fidelidad visual, usa la estrategia de `payment-compare` en `Main`:

1. Generar PDF con Jasper original.
2. Generar PDF con la clase transpileada.
3. Comparar:
- páginas
- texto normalizado
- diferencia visual promedio

Esto ya está implementado en:

- `/Users/erinellbarba/Dev/java/jaspbox-transpiler-engine/src/main/java/com/jaspbox/demo/Main.java`

## Uso en otro proyecto (runtime sin Jasper)

Para consumir una clase ya generada fuera de este repo:

1. Copia la clase generada (`MyReportTemplate.java`) a tu proyecto.
2. Asegura dependencia runtime de PDFBox 2.0.x.
3. Construye el `Map<String,Object>` con las claves esperadas del template.
4. Llama `build(data, outputPath)`.

Nota: el template generado no requiere JasperReports en runtime para dibujar el PDF final.

## Consideraciones importantes

- Mantén tipos correctos en `data` para evitar `ClassCastException`/errores de evaluación.
- No envuelvas ni reemplaces el `Map` si usas una implementación custom/mock para tests.
- Si el template usa expresiones complejas, valida siempre con comparación contra Jasper.

## Próximo flujo sugerido para nuevos reportes

1. Crear `src/main/resources/<nombre>.jrxml`
2. Crear método `build<Nombre>Data()` en `Main`
3. Invocar `runTranspiledReport(...)` con ese JRXML
4. (Opcional) crear modo `-Dexec.args=<nombre>-compare` para comparar con Jasper

Con eso tendrás un pipeline repetible para onboardear nuevos templates.
