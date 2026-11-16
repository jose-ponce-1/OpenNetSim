# Tasks

## 1. Lint
- [x] 1.1 Eliminar `org.jlleitschuh.gradle.ktlint` de ambos `build.gradle.kts` y actualizar el workflow de verificación; verificar con `grep -rn ktlint --include=*.kts .` (sin resultados)
- [x] 1.2 Configurar `ignoreAnnotated: ['Composable']` en `FunctionNaming` y `MagicNumber` e `ignoreEnums` en `MagicNumber`; autocorregir el formato con `detekt --auto-correct`; verificar que bajan los avisos
- [x] 1.3 Corregir los avisos de los archivos tocados en esta tanda y crear `config/detekt/baseline.xml` con el resto; verificar que `detekt` termina con código 0
- [x] 1.4 Quitar los BOM y añadir `.editorconfig`; verificar con `grep -rl $'^\xEF\xBB\xBF' --include=*.kt .` (sin resultados)
- [x] 1.5 Verificar que `lintDebug` termina con código 0

## 2. Tests
- [x] 2.1 Añadir `kotlinx-coroutines-test` 1.9.0 y pasar `MainViewModelTest` a `runTest` con reloj virtual; verificar con `testDebugUnitTest`

## 3. CI
- [x] 3.1 Crear `.github/workflows/ci.yml` y marcar `gradlew` como ejecutable; verificar con `git ls-files -s gradlew` (modo 100755)
- [x] 3.2 Tras el primer push, verificar que el workflow "CI" termina en verde en GitHub Actions — ejecución 43981166816 en verde (17/11/2026); la primera falló por una carrera del test `exteriorSocketErrorResetsClient` en Linux, corregida en `7c208be`
