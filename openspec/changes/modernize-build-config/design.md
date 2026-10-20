# Design: Build Configuration Modernization & Tooling Analysis

## Context

The project builds against Android SDK 35 with Kotlin 2.0.21, Jetpack Compose, and AGP 8.7.3 running on Gradle 9.7.1 and JDK 21.
See `proposal.md` for motivation.

## Goals / Non-Goals

**Goals:**
- Replace deprecated Kotlin DSL syntax in `app/build.gradle.kts`.
- Provide a rigorous architectural analysis of AGP modernization (Point 4) evaluating advantages and disadvantages.
- Keep build scripts strictly compatible with project rules in `.agents/rules/agentrules.md`.

**Non-Goals:**
- Modifying `local.properties` (Point 1 is resolved externally via environment variable by the user).
- Aggressively bumping major dependencies without verified stability.

## Decisions

### Decision 1: Kotlin DSL Migration (`kotlinOptions` → `compilerOptions`)
- **Choice**: Use `kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }` inside `android { ... }` or at top-level.
  In modern Android projects with Kotlin 2.0+, the recommended configuration inside `android { ... }` is:
  ```kotlin
  kotlin {
      compilerOptions {
          jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
      }
  }
  ```
- **Rationale**: Eliminates the warning `The kotlinOptions type is deprecated. Please use compilerOptions instead.` while guaranteeing target bytecode remains Java 17.
- **Alternatives Considered**: Keeping `kotlinOptions` as-is. Rejected because it generates unnecessary noise in build logs and will be removed in future Kotlin releases.

### Decision 2: Study of AGP Modernization (Point 4: Upgrading AGP vs Retaining AGP 8.7.3)

Gradle 9.7.1 emitted 8 deprecation warnings in `problems-report.html` due to AGP 8.7.3 internal APIs:
1. Multi-string dependency notation (`lint-gradle`, `aapt2`).
2. Groovy boolean property getter conventions (`crunchPngs`, `useProguard`, `wearAppUnbundled`).
3. Project object dependency notation.

#### Analysis of Options:

#### Option A: Retain AGP 8.7.3 (Recommended for Current Phase)
- **Ventajas**:
  - **Máxima estabilidad y cero regresiones**: El stack actual (AGP 8.7.3 + Kotlin 2.0.21 + Compose BOM 2024.10.01) está completamente validado y compila APKs funcionales sin errores.
  - **Cumplimiento estricto de directrices**: `agentrules.md` estipula *"Prohibido modificar dependencias mayores en build.gradle.kts sin verificar compatibilidad de versiones con el Android Gradle Plugin (AGP)"*.
  - **Avisos inocuos en Gradle 9**: Los 8 problemas en `problems-report.html` son advertencias de deprecación para **Gradle 10**, no fallos en Gradle 9.7.1.
- **Inconvenientes**:
  - Si en el futuro se actualiza a Gradle 10.0, estas 8 advertencias se convertirán en errores fatales hasta que se actualice AGP.

#### Option B: Actualizar a AGP 8.8+ o 8.9+ (Modernización Anticipada)
- **Ventajas**:
  - **Eliminación proactiva de avisos**: AGP 8.8+ y versiones posteriores adaptan progresivamente sus llamadas internas para reemplazar las APIs obsoletas de Gradle.
  - **Mejoras en Configuration Cache**: Mayor compatibilidad con el caché de configuración de Gradle en builds incrementales.
- **Inconvenientes**:
  - **Riesgo de incompatibilidad con Compose / Kotlin**: Exige verificar versiones mínimas de Android Studio / SDK Build Tools y la compatibilidad con el compilador de Compose actual (`org.jetbrains.kotlin.plugin.compose 2.0.21`).
  - **Rotura potencial de flags experimentales**: Nuevas versiones de AGP a menudo deprecian o alteran opciones en el bloque `android { ... }`.
  - **Innecesario a corto plazo**: Gradle 9.7.1 tolera perfectamente estas llamadas y la compilación produce APKs correctos.

**Conclusión del Estudio**: Mantener AGP 8.7.3 en este momento es la decisión prudente y recomendada. No introduce inestabilidad ni riesgos, y las advertencias no impiden el desarrollo. La actualización de AGP debe abordarse como un cambio planificado cuando se decida dar el salto a una nueva versión mayor de Gradle o Android Studio.

## Risks / Trade-offs

- **[Risk]** Si el daemon de Gradle retiene variables de entorno previas, la detección de `ANDROID_HOME` podría requerir detener el daemon.
  - *Mitigación*: Ejecutar `.\gradlew.bat --stop` si el daemon no reconoce de inmediato la nueva variable de entorno.
- **[Risk]** Error de sintaxis en el bloque de `compilerOptions` en `app/build.gradle.kts`.
  - *Mitigación*: Validar inmediatamente con `.\gradlew.bat compileDebugKotlin` tras el cambio.
