# Proposal

## Why
Las librerías de la app (Compose, AndroidX y corrutinas) siguen en las versiones del arranque del proyecto
(finales de 2024), y la Fase 11 las dejó fuera de alcance. Además, Google Play exige desde 2026
`targetSdk` 36 (Android 16) para publicar apps nuevas y actualizaciones, y la app está en 35: sin este
cambio no se puede subir a Play.

## What Changes
- Compose BOM 2024.10.01 → 2026.11.01 (`ui` 1.7.5 → 1.13.0, `material3` 1.3.1 → 1.5.0,
  `material-icons-extended` 1.7.5 → 1.7.8).
- `core-ktx` 1.15.0 → 1.19.1, `activity-compose` 1.9.3 → 1.13.0, `lifecycle` 2.8.7 → 2.11.1,
  `core-splashscreen` 1.0.1 → 1.2.0.
- `kotlinx-coroutines` 1.9.0 → 1.11.0 (`-android` y `-test`).
- `compileSdk` 35 → 37.1 (la API 37 con su versión menor 1): la 37 la exigen `core` 1.19.1, Compose
  `ui` 1.13.0 y `material3` 1.5.0, y la 37.1, `foundation` y `animation` 1.13.0 de la misma BOM.
- `UiText.asString()` lee los recursos con `LocalResources` en lugar de `LocalContext.current.resources`
  (aviso `LocalContextResourcesRead` del lint de Compose 1.13).
- `targetSdk` 35 → 36: el mínimo de Google Play. Activa los cambios de comportamiento de Android 16
  (edge-to-edge sin opción de desactivarlo, «atrás» predictivo por defecto, sin bloqueos de orientación ni
  de tamaño en pantallas de 600 dp o más).
- Corregir los avisos de API obsoleta que aparezcan al compilar con las versiones nuevas.
- Mantener los colores del prototipo en los botones con contorno y en la barra inferior, que Material 3
  1.5 cambia por defecto (`AppOutlinedButton`; ver `design.md`, D5b).
- `versionCode`/`versionName` no cambian en este cambio: se suben al preparar la siguiente release.

## Fuera de alcance
- `targetSdk` 37: Play lo pedirá previsiblemente en agosto de 2027; irá en su propio cambio.
- AGP, Kotlin, Gradle y detekt (Fase 11); detekt 2.0 sigue sin versión estable.
- Retirar `material-icons-extended` (Google ya no la desarrolla): sigue funcionando y R8 la reduce en
  release.
- Publicar en Google Play: AAB (`bundleRelease`), declaraciones de `VpnService` y del servicio en primer
  plano `specialUse`, y los enlaces a Ko-fi.
- Librerías de herramientas que no se empaquetan en la app (Kover, kotlinx-benchmark, compose-rules) y
  JUnit.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: no cambia ningún requisito. Los comportamientos que toca Android 16, como
  «Atrás desde una sección secundaria» de `app-navigation`, se verifican en dispositivo y deben seguir
  igual).

## Impact
`gradle/libs.versions.toml`, `app/build.gradle.kts`, los archivos de `ui/` y `service/` que usen API
obsoleta, la plataforma Android 37 del SDK (local y en la CI), README (si cita versiones) y
`project-state.md`. Sin efecto en la configuración guardada ni en los perfiles del usuario.
