# Tasks

## 1. Version catalog
- [x] 1.1 Crear `gradle/libs.versions.toml` con las versiones actuales y usar alias de plugins y librerías en los scripts; verificar con `git diff` que ninguna versión cambia y con `:app:compileDebugKotlin`

## 2. Módulo `:core`
- [x] 2.1 Crear `core/build.gradle.kts` (Kotlin/JVM 17, `java-test-fixtures`), incluirlo en `settings.gradle.kts` y añadir `implementation(project(":core"))` en `:app`; verificar con `:core:compileKotlin`
- [x] 2.2 Mover con `git mv` `core/` y `model/` (código y tests) a `:core` y `FakeMonotonicClock` a sus *test fixtures*; verificar que `:core:test` (115) y `:app:testDebugUnitTest` (60, 1 omitido en Windows) suman los 175 de antes
- [x] 2.3 Verificar que un `import android.os.Build` en `:core` no compila ("Unresolved reference 'android'") y retirar la prueba

## 3. Calidad
- [x] 3.1 Configurar detekt en el script raíz con una baseline por módulo; verificar que `detekt` termina con código 0 y que ninguna entrada de las baselines es nueva
- [x] 3.2 Actualizar CI, workflow de verificación, reglas del agente, README y `openspec/config.yaml`; verificar con `grep -rn "gradlew testDebugUnitTest\|gradlew compileDebugKotlin" .github .agents README.md` (sin resultados)

## 4. Verificación
- [x] 4.1 Ejecutar `detekt`, `lintDebug`, `:core:test`, `:app:testDebugUnitTest` y `assembleDebug`; verificar que terminan bien
- [ ] 4.2 Verificar que la CI de GitHub Actions termina en verde con los dos módulos
