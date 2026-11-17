# Proposal

## Why
El paso 5 de la hoja de ruta (rendimiento y deuda) empieza por medir. Antes de reducir las baselines de
detekt u optimizar el camino de cada paquete hace falta saber qué código cubren los tests, para no
refactorizar a ciegas, y una puerta en la CI que impida que la cobertura baje sin que nadie lo note.

## What Changes
- Plugin Kover 0.9.11 en `:core` y `:app` (catálogo de versiones).
- Variante `coverage`: en `:core`, los tests JVM; en `:app`, los tests de `debug` más los de `:core`
  (`kover(project(":core"))`), en un informe conjunto.
- El informe de `:app` excluye el código que solo se ejecuta con el framework de Android o con Compose
  (servicio VPN, actividad, pantallas, tema, notificaciones, intents y adaptadores `Android*`), que se
  verifica en dispositivo.
- Mínimo de 85 % de líneas cubiertas en `:core` y en el informe conjunto: la CI falla si baja.
- La CI ejecuta la verificación, genera el informe HTML (se sube con los demás informes) y escribe el
  porcentaje en el resumen del job.

## Fuera de alcance
- Escribir tests nuevos para subir la cobertura.
- Mínimo de cobertura de ramas (se mide, 66 %, pero no se exige).
- Tests instrumentados o de Compose en dispositivo.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: no cambia ningún comportamiento de la app)

## Impact
`gradle/libs.versions.toml`, `core/build.gradle.kts`, `app/build.gradle.kts`, `.github/workflows/ci.yml`,
`openspec/config.yaml`, README y `project-state.md`.
