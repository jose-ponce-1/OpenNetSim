# Proposal

## Why
Gradle 9.7.1 queda fuera de la matriz probada de AGP 8.7.3 y Kotlin 2.0.21, y la línea 8.x de AGP ya no
recibe novedades. Kotlin 2.0 tampoco está soportado por las versiones actuales de AGP.

## What Changes
- AGP 8.7.3 → 9.3.3 y Kotlin 2.0.21 → 2.4.21; Gradle se mantiene en 9.7.1.
- `:app` usa el Kotlin integrado de AGP 9: se retira el plugin `org.jetbrains.kotlin.android` (y su
  alias del catálogo); `jvmTarget` se toma de `compileOptions.targetCompatibility` (17).
- detekt 1.23.7 → 1.23.8 (último estable).
- `IcmpEchoTransport.UNAVAILABLE` usa un `Semaphore` en lugar de `Object()` con `wait`/`notifyAll`,
  que Kotlin 2.4 desaconseja.

## Fuera de alcance
Actualizar Compose BOM, AndroidX y corrutinas; subir `compileSdk`/`targetSdk`; migrar a detekt 2.0
(sigue en alpha).

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: no cambia ningún comportamiento)

## Impact
`gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `IcmpEchoTransport.kt`, README y
`project-state.md`.
