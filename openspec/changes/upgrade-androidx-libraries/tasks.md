# Tasks

## 1. Preparación
- [x] 1.1 Crear la rama `chore/upgrade-libraries` desde `develop`; verificar con `git status -sb` que se está en ella y sin cambios pendientes del proyecto
- [x] 1.2 Instalar la plataforma `platforms;android-37` (este SDK no tiene `cmdline-tools`: la instaló AGP al configurar con `compileSdk` 37, revisión 2); verificar que existe `%LOCALAPPDATA%\Android\Sdk\platforms\android-37.0`
- [x] 1.3 Guardar capturas de referencia de la versión actual en el emulador con API 37 (pantalla de carga, bienvenida, Simular, Parámetros, Telemetría, hoja de perfiles; claro y oscuro); verificar que están en el scratchpad para compararlas en 5.2

## 2. Corrutinas (D3, paso 1)
- [x] 2.1 Subir `coroutines` a 1.11.0 en `gradle/libs.versions.toml` (`lifecycle` pasa a 3.1: sus artefactos de Compose 2.11.1 exigen `compileSdk` 37); verificar con `:core:compileKotlin :app:compileDebugKotlin` que compila sin avisos nuevos
- [x] 2.2 Ejecutar `:core:test :app:testDebugUnitTest --continue`; verificar que los 250 tests pasan (1 omitido en Windows) y corregir según D4 lo que rompa la versión nueva de `kotlinx-coroutines-test`

## 3. `compileSdk` 37 y AndroidX (D3, paso 2)
- [x] 3.1 Subir `compileSdk` a 37 en `app/build.gradle.kts` y `coreKtx` a 1.19.1, `lifecycle` a 2.11.1, `activityCompose` a 1.13.0 y `coreSplashscreen` a 1.2.0 en el catálogo; verificar con `:app:compileDebugKotlin` que compila sin errores
- [x] 3.2 Migrar las API obsoletas que señale la compilación (D4); verificar que `:app:compileDebugKotlin` no muestra avisos de obsolescencia y que `detekt` termina con código 0

## 4. Compose (D3, paso 3)
- [x] 4.1 Subir `composeBom` a 2026.11.01; verificar con `dependencyInsight` que `ui` resuelve a 1.13.0, `material3` a 1.5.0 y `material-icons-extended` a 1.7.8
- [x] 4.2 Migrar las API de Compose y Material 3 obsoletas o con cambios incompatibles (D4), incluidas las experimentales de `MainScaffold`, `ProfileDialogs` y `ParameterGroups`; verificar que `:app:compileDebugKotlin`, `detekt`, `lintDebug` y `:app:testDebugUnitTest` terminan bien sin avisos nuevos

## 5. `targetSdk` 36 (D3, paso 4)
- [x] 5.1 Subir `targetSdk` a 36 y revisar los avisos nuevos de `lintDebug`; verificar que `lintDebug` termina sin errores ni avisos nuevos
- [x] 5.2 Revisar en el emulador con API 37 lo mismo que en 1.3 y compararlo con las capturas de referencia; verificar que no hay cambios visibles salvo los que se anoten en `design.md` como aceptados, y que al cambiar el idioma de la app (inglés ↔ español) los textos cambian sin reiniciar (D1b)
- [x] 5.3 Comprobar en API 37 el edge-to-edge de Android 16 (pantalla de carga, barras del sistema en claro y oscuro) y decidir según D5 si el arreglo de `MainActivity` sigue siendo necesario; verificar que los iconos de la barra de estado se leen en los dos temas
- [x] 5.4 Comprobar el «atrás» predictivo; verificar que «Atrás» desde Parámetros o Telemetría vuelve a Simular y que un segundo «Atrás» sale de la app (escenario de `app-navigation`)
- [x] 5.5 Comprobar el servicio con `targetSdk` 36 en el emulador; verificar que el túnel arranca y se detiene, que los cambios en caliente se aplican con tráfico, que el ping funciona, que la notificación aparece y que la configuración se restaura tras forzar el cierre

## 6. Puerta de calidad e integración
- [x] 6.1 Ejecutar la puerta de calidad completa (`:core:compileKotlin :app:compileDebugKotlin`, `detekt`, `lintDebug`, `:core:test :app:testDebugUnitTest`, Kover de `:core` y `:app`, `:benchmark:smokeBenchmark`, `:app:assembleRelease`); verificar que todo termina bien, que la cobertura sigue en el 85 % o más y anotar el tamaño de la release (antes 1,28 MB)
- [x] 6.2 Actualizar `project-state.md` (fase nueva, paso 3 de la hoja de ruta y checkpoint) y el README si cita versiones; verificar que no queda ninguna versión antigua citada con `grep -rn "2024.10.01\|compileSdk = 35\|targetSdk = 35"`
- [ ] 6.3 Abrir el PR hacia `develop`; verificar que la CI de GitHub Actions termina en verde (si falla por falta de la plataforma 37, añadir el paso de `sdkmanager` de D6)
- [x] 6.4 Verificación del usuario en su teléfono con la release firmada; verificar que confirma que el túnel, la interfaz, el «atrás» y la pantalla de carga funcionan como en la 1.0.1

## Workflow follow-up
- Archivar el cambio con `/opsx:archive` cuando la CI esté en verde y el usuario lo haya verificado en su teléfono.
- Subir `versionCode`/`versionName` en el cambio que prepare la siguiente release (1.0.2 o 1.1.0).
