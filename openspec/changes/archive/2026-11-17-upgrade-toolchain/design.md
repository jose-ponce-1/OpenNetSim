# Design

## Context
Matrices oficiales consultadas el 17/11/2026:
- AGP 9.3 (developer.android.com): Gradle mínimo 9.5.0, JDK 17, API máxima 37; último parche 9.3.3.
- AGP 9.5 (el más reciente, 9.5.1): Gradle mínimo 9.6.0.
- Kotlin (kotlinlang.org, "Configure a Gradle project"): KGP 2.4.20–2.4.21 probado con Gradle
  7.6.3–9.7.0 y AGP 8.5.2–9.3.1. developer.android.com/build/kotlin-support: Kotlin 2.4 requiere AGP
  8.5.2+.
- Gradle estable más reciente: 9.9.1. detekt: estable 1.23.8 (compilado con Kotlin 2.0.21); 2.0.0-alpha.6
  es la última pre-release.

## Goals / Non-Goals
**Goals:** volver a una combinación AGP/Kotlin/Gradle dentro de las matrices probadas.

**Non-Goals:** estrenar las versiones más nuevas a costa de salir de esas matrices.

## Decisions
### D1. AGP 9.3.3 y no 9.5.1
Kotlin 2.4.21, el último, está probado hasta AGP 9.3.1. AGP 9.3.3 es un parche de esa línea; 9.5 queda
fuera de lo que Kotlin ha probado.

### D2. Gradle 9.7.1 sin cambios
Cumple el mínimo de AGP 9.3 (9.5.0) y es un parche del máximo probado por KGP 2.4.21 (9.7.0). Gradle 9.8 o
9.9 quedarían fuera.

### D3. Kotlin integrado de AGP 9
AGP 9 depende en tiempo de ejecución de KGP 2.2.10. Declarar `org.jetbrains.kotlin.jvm` y
`org.jetbrains.kotlin.plugin.compose` 2.4.21 en el script raíz sube esa dependencia a 2.4.21 (comprobado
con `buildEnvironment`), así que `:app` y `:core` compilan con el mismo Kotlin.

### D4. detekt 1.23.8 estable
detekt analiza con su propio compilador (Kotlin 2.0.21 en la configuración `detekt`), independiente del
Kotlin del proyecto, y su plugin funciona con AGP 9. Se comprobó que sigue detectando problemas con un
archivo de prueba. Su plugin usa `ReportingExtension.file(String)`, que Gradle 10 eliminará: es el único
aviso de Gradle 10 que queda, y se resuelve al pasar a detekt 2.0 cuando sea estable.

## Risks / Trade-offs
- El APK lo genera otra versión mayor de AGP (D8/R8 nuevos): se verifica en dispositivo.

## Migration Plan
Sin efecto en la app instalada. Rollback: revertir el commit.
