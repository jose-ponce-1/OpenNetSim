# Tasks

## 1. Preparación y limpieza de Google Play

- [x] 1.1 Comprobar que se trabaja en la rama `feat/open-source-licenses` creada desde `develop`; verificar con `git status -sb` que la rama es `feat/open-source-licenses` y el árbol parte limpio respecto al cambio OpenSpec.
- [x] 1.2 Añadir `dependenciesInfo { includeInApk = false; includeInBundle = false }` y retirar el bloque `bundle` en `app/build.gradle.kts`; verificar ejecutando `./gradlew :app:compileDebugKotlin` que Gradle configura y compila sin errores.
- [x] 1.3 Eliminar `GOOGLE_PLAY_INSTALLERS` y `donationLinkAllowed(...)` de `SupportLinks.kt`, retirar `installerPackage(...)` de `AboutDialog.kt` para mostrar siempre «Apoya el proyecto» con Ko-fi, y actualizar `SupportLinksTest.kt`; verificar con `./gradlew :app:testDebugUnitTest --tests "com.opennetsim.ui.SupportLinksTest" detekt` que los tests y `detekt` terminan con código 0.

## 2. Generación de licencias en Gradle

- [x] 2.1 Implementar en `app/build.gradle.kts` la tarea Gradle determinista conectada a `androidComponents` que lee los `.pom` resueltos de `releaseRuntimeClasspath`, valida que todas las dependencias tengan licencia compatible y genera `licenses/libraries.tsv` y `licenses/apache-2.0.txt` (copiado de `LICENSE.txt`) en los assets generados; verificar ejecutando la tarea de generación y comprobando que la salida lista todas las dependencias resueltas en orden alfabético, sin rutas locales ni marcas de tiempo.
- [x] 2.2 Verificar compatibilidad sin conexión y con *configuration cache*; verificar ejecutando dos compilaciones consecutivas con `--offline` y `--configuration-cache` (`./gradlew :app:compileDebugKotlin --offline --configuration-cache`) que ambas terminan con código 0.

## 3. Lector JVM y tests unitarios

- [x] 3.1 Implementar el modelo y lector en Kotlin puro dentro de `app/src/main/java/com/opennetsim/ui/` (lectura del TSV sin dependencias externas y agrupación legible de artefactos por biblioteca/familia con sus versiones y licencia); verificar con `./gradlew :app:compileDebugKotlin detekt` que compila sin errores ni infracciones.
- [x] 3.2 Escribir tests unitarios en `app/src/test/java/com/opennetsim/ui/` que cubran parseo normal, agrupación, ordenación y entradas vacías o malformadas; verificar con `./gradlew :app:testDebugUnitTest :app:koverVerifyCoverage` que todos los tests pasan y la cobertura de líneas se mantiene en el 85 % o más.

## 4. Interfaz Compose Material 3 y localización

- [x] 4.1 Añadir las cadenas de interfaz en `app/src/main/res/values/strings.xml` y `app/src/main/res/values-es/strings.xml` para «Licencias de código abierto», resumen, etiquetas y lectura del texto completo de la licencia; verificar con `./gradlew lintDebug` que no hay traducciones faltantes ni avisos nuevos.
- [x] 4.2 Añadir la acción «Licencias de código abierto» en `AboutDialog.kt` y crear la vista modal Compose Material 3 en `app/src/main/java/com/opennetsim/ui/screens/` que muestre la lista agrupada de dependencias y un diálogo desplazable con el texto completo de `apache-2.0.txt` sin conexión; verificar con `./gradlew :app:compileDebugKotlin detekt lintDebug` que compila y supera el análisis estático sin avisos.

## 5. Puerta de calidad, verificación en dispositivo y documentación

- [x] 5.1 Ejecutar la puerta de calidad completa de la CI (`./gradlew :core:compileKotlin :app:compileDebugKotlin detekt lintDebug :core:test :app:testDebugUnitTest :core:koverVerifyCoverage :app:koverVerifyCoverage :benchmark:smokeBenchmark :app:assembleRelease`); verificar que todas las tareas terminan en verde y que el APK de release incluye `assets/licenses/libraries.tsv` y `assets/licenses/apache-2.0.txt` sin bloque `DependencyInfo`.
- [x] 5.2 Probar el APK compilado en emulador o teléfono (`360×640 dp`, tema claro y oscuro, inglés y español, y sin conexión); verificar que «Acerca de» muestra «Apoyar en Ko-fi» y «Licencias de código abierto», que el listado y el texto de Apache 2.0 se desplazan bien y que todos los textos siguen el idioma activo.
- [x] 5.3 Actualizar `project-state.md` con la fase nueva, el cambio de estrategia de distribución (GitHub Releases y F-Droid) y el checkpoint; verificar con `git grep -n -i "google play\|bundleRelease"` que no quedan referencias activas a Google Play fuera del historial archivado.

## Workflow follow-up

- Abrir el PR de `feat/open-source-licenses` hacia `develop` y comprobar que la CI de GitHub Actions pasa en verde.
- Archivar el cambio con `openspec archive` (sincronizando `openspec/specs/app-navigation/spec.md`) tras la verificación en dispositivo y la CI en verde.
- En un paso posterior de lanzamiento: añadir metadatos `fastlane/metadata/android/`, subir `versionCode`/`versionName`, publicar la release en GitHub y enviar el Merge Request a `fdroiddata`.
