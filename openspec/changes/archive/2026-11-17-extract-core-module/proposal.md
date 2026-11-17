# Proposal

## Why
"`core/` sin Android" era solo una convención: al estar todo en `:app`, un `import android.*` en el
motor compilaba y el fallo aparecía después, en los tests JVM o en el dispositivo. Además las versiones
estaban escritas a mano en dos `build.gradle.kts`.

## What Changes
- Nuevo módulo Gradle `:core`, Kotlin/JVM puro, con los paquetes `com.opennetsim.core.*` (motor,
  paquetes, TCP, reloj, buffers) y `com.opennetsim.model.*`. `:app` depende de él.
- Sus tests pasan a `:core` (`:core:test`); `FakeMonotonicClock` se publica como *test fixtures* de
  `:core` para los tests de `:app`.
- Version catalog en `gradle/libs.versions.toml`; los scripts de Gradle usan alias de plugins y
  librerías. No cambia ninguna versión.
- detekt se configura una vez en el script raíz para todos los módulos, con una baseline por módulo
  (`baseline-core.xml`, `baseline-app.xml`).
- CI, workflow de verificación, reglas del agente, README y `openspec/config.yaml` usan las tareas
  nuevas (`:core:compileKotlin`, `:core:test`).

## Fuera de alcance
Mover `tunnel/` o el pipeline a módulos propios y actualizar versiones (AGP, Kotlin, Gradle).

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: no cambia ningún comportamiento)

## Impact
`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, nuevo `core/`,
`gradle/libs.versions.toml`, `config/detekt/`, `.github/workflows/ci.yml` y documentación.
