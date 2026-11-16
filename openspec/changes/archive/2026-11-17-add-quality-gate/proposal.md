# Proposal

## Why
El lint estaba en rojo (detekt con 466 avisos y los tres `ktlint*Check` fallando), así que el paso 1
del workflow de verificación no podía pasar nunca, y no había CI. El formato se comprobaba dos veces
(ktlint-gradle y detekt-formatting) y 30 archivos tenían BOM UTF-8.

## What Changes
- Se elimina el plugin `ktlint-gradle`: detekt-formatting ya aplica las reglas de ktlint.
- `FunctionNaming` y `MagicNumber` ignoran los `@Composable` (y `MagicNumber`, los enums); el formato
  se autocorrige; las infracciones restantes, todas previas, pasan a `config/detekt/baseline.xml`.
- Se quitan los BOM y se añade `.editorconfig` (UTF-8).
- GitHub Actions (`.github/workflows/ci.yml`): `compileDebugKotlin`, `detekt`, `lintDebug` y
  `testDebugUnitTest` en cada push a `main`/`develop` y en cada PR.
- `kotlinx-coroutines-test` (1.9.0, misma versión que coroutines) para tests con reloj virtual.

## Fuera de alcance
Kover/cobertura, version catalog, tests instrumentados y configuración de release (R8, firma).

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`)

## Impact
`build.gradle.kts`, `app/build.gradle.kts`, `config/detekt/`, `.github/`, `.editorconfig`, el workflow
`.agents/workflows/android-verification` y el README.
