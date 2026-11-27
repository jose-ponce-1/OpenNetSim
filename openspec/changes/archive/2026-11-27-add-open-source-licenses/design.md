# Design

## Context
Ver `proposal.md` para la motivación y el alcance. Estado actual del repositorio en `develop`:
- Las dependencias incluidas en el binario (`:app` `releaseRuntimeClasspath`, ~102 artefactos resueltos entre `androidx.*`, `org.jetbrains.kotlin:*`, `org.jetbrains.kotlinx:*`, `org.jetbrains:annotations`, `com.google.guava:listenablefuture` y `org.jspecify:jspecify`) están todas bajo Apache 2.0, sin ninguna librería propietaria ni de Google Play Services.
- El proyecto usa AGP `9.3.3`, Gradle `9.7.1`, Kotlin `2.4.21` (Kotlin integrado de AGP 9 en `:app`), `isMinifyEnabled = true`, `isShrinkResources = true`, `detekt` sin baselines, `lintDebug` y cobertura Kover con mínimo del 85 % de líneas.
- Los tests unitarios de `:app` (`app:testDebugUnitTest`) corren en JVM pura sin Robolectric, donde las clases de `android.jar` (incluida `org.json`) están sustituidas por stubs.
- La distribución oficial pasa a ser GitHub Releases y F-Droid (builds offline y reproducibles).

## Goals / Non-Goals

**Goals:**
- Generar en compilación, de forma determinista, offline y sin plugins ni librerías externas de terceros, el inventario real de dependencias de `releaseRuntimeClasspath` y el texto completo de la licencia Apache 2.0.
- Mostrar ese inventario y el texto íntegro de la licencia en Compose Material 3 desde «Acerca de», con lector probado en JVM y textos traducidos a inglés y español.
- Retirar el código, configuración de AAB y bloque binario de Google Play (`dependenciesInfo`), dejando el APK limpio para GitHub Releases y F-Droid.

**Non-Goals:**
- Introducir plugins externos de licencias (`AboutLibraries`, `Licensee`, `play-services-oss-licenses`) ni librerías nuevas de serialización/UI.
- Configurar `fastlane/metadata/android/` ni preparar aún el envío a `fdroiddata`.
- Alterar la lógica del motor (`:core`) ni el servicio VPN (`service/`).

## Decisions

### D1. Tarea Gradle propia sobre `releaseRuntimeClasspath` con salida determinista en `assets/`
En `app/build.gradle.kts` se registra una tarea `DefaultTask` conectada al variante mediante `androidComponents` (`onVariants`) para generar dos archivos dentro del directorio de assets generado del build (`build/generated/...`):
- `licenses/libraries.tsv`: listado ordenado alfabéticamente con columnas fijas (`group`, `artifact`, `name`, `version`, `licenseId`, `licenseName`, `url`), extrayendo los metadatos del `.pom` de cada artefacto resuelto en `releaseRuntimeClasspath` (con la caché local de Gradle, sin red).
- `licenses/apache-2.0.txt`: copia directa de `LICENSE.txt` de la raíz del repositorio.

**Alternativas consideradas:**
- *Plugins externos (`AboutLibraries`, `Licensee`):* descartados por añadir peso/dependencias en runtime o riesgo de fricción con AGP 9.3.3 y Gradle 9.7.1.
- *Salida en `res/raw/`:* descartada porque `isShrinkResources = true` podría eliminar recursos no referenciados estáticamente si no se añade un archivo `keep.xml`. Con `assets/` y `AssetManager`, R8/shrinkResources no recorta los ficheros.

**Reproducibilidad y validación:** la tarea ordena las entradas de forma determinista por coordenadas Maven, no escribe rutas locales ni marcas de tiempo, es compatible con *configuration cache* y falla la compilación si alguna dependencia resuelta en runtime carece de licencia o declara una fuera de la lista permitida (p. ej. Apache 2.0 / MIT / BSD).

### D2. Formato TSV ligero frente a `org.json` para poder probar el lector en JVM pura
El lector/modelo (`OpenSourceLicenses` o `OpenSourceLicenseParser` en `ui/`) se escribe en Kotlin puro usando únicamente la librería estándar (`String.split`, `BufferedReader`), recibiendo un `InputStream` o `Reader` (la lectura real de `context.assets.open(...)` queda en un adaptador fino de Android/Compose).

**Alternativa considerada:** `org.json.JSONArray`/`JSONObject` del SDK de Android. Se descarta porque en `:app:testDebugUnitTest` (JVM pura sin Robolectric) `org.json` lanza `RuntimeException("Stub!")` y obligaría a cambiar `testOptions` o duplicar mocks. El formato tabular TSV permite probar el parser y la agrupación al 100 % en JVM sin dependencias nuevas.

### D3. Agrupación legible en la interfaz preservando todas las versiones
`releaseRuntimeClasspath` contiene ~102 artefactos transitivos (muchos son desdobles internos `-android` / `-jvm` / `-ktx` o submódulos como `androidx.compose.*` o `androidx.lifecycle.*`).
- El asset generado conserva el detalle exacto de artefactos y licencias resueltas.
- El modelo de UI agrupa artefactos por familia lógica o biblioteca principal (mostrando nombre legible, versión/es, licencia y los artefactos concretos incluidos al desplegar o en el detalle) para evitar una lista repetitiva sin perder transparencia legal.
- Al pulsar una entrada o el botón de ver texto completo de la licencia se abre un diálogo desplazable (con tipografía monoespaciada o legible de cuerpo pequeño) mostrando el texto íntegro offline de `apache-2.0.txt`.

### D4. Diseño visual coherente con `AboutDialog` y localización (`en` / `es`)
- En `AboutDialog.kt`, el bloque «Código abierto» pasa a ofrecer dos acciones claras: `AppOutlinedButton` («Ver el código en GitHub») y una acción secundaria/botón de texto o contorno («Licencias de código abierto») que abre la vista modal de licencias sin cerrar la app ni añadir pestañas a la barra inferior.
- Se añade la vista Compose (`LicensesDialog.kt` o dentro de `ui/screens/`) usando `ModalBottomSheet` o `Dialog` a pantalla/tarjeta amplia con `LazyColumn` y barra superior con botón de cierre, adaptada a pantallas de 360×640 dp y modos claro/oscuro.
- Todos los textos visibles (título, subtítulo/resumen, etiquetas, botón de cierre, aviso de error si faltara un recurso, acceso al texto de licencia) van en `values/strings.xml` y `values-es/strings.xml`.

### D5. Limpieza de Google Play en código, build y specs
- **`SupportLinks.kt` y `AboutDialog.kt`:** se eliminan `GOOGLE_PLAY_INSTALLERS`, `donationLinkAllowed(...)` e `installerPackage(context)` (junto con los tests específicos de instalador de Play en `SupportLinksTest.kt`). El apartado «Apoya el proyecto» con Ko-fi se muestra siempre en «Acerca de», manteniendo intactos `SOURCE_CODE_URL` y `KO_FI_URL` y sus tests HTTPS.
- **`app/build.gradle.kts`:**
  - Se añade `dependenciesInfo { includeInApk = false; includeInBundle = false }` para impedir que AGP inserte el bloque binario cifrado de dependencias de Google Play (requisito para F-Droid).
  - Se elimina el bloque `bundle { language { enableSplit = false } }` específico de AAB para Google Play.
- **Kover:** se comprueba que las nuevas clases Compose en `com.opennetsim.ui.screens.*` ya quedan cubiertas por la exclusión existente (`"com.opennetsim.ui.screens.*"`), mientras el parser/modelo nuevo en `com.opennetsim.ui.*` se cubre con tests unitarios JVM para mantener el mínimo del 85 %.

## Risks / Trade-offs
- [Compatibilidad de la tarea Gradle con *configuration cache* y AGP 9.3.3 al resolver POMs de `releaseRuntimeClasspath`] → Verificar ejecutando Gradle dos veces seguidas (`--configuration-cache` si aplica y tareas de CI) y comprobando que la resolución de `.pom` funciona en build offline sin acceder a red.
- [Artefactos transitivos cuyo POM padre hereda `<licenses>` o tiene formato inesperado] → Inspeccionar la salida real generada sobre las ~102 dependencias actuales de OpenNetSim y soportar la lectura del POM padre en caché si algún POM hijo no repitiese el bloque `<licenses>` (p. ej. `listenablefuture` o BOM/padres de AndroidX/JetBrains).
- [R8 / `isShrinkResources = true` en `:app:assembleRelease`] → Como los ficheros van en `assets/licenses/`, no se recortan; se verifica inspeccionando el APK de `assembleRelease` y probando la release firmada en emulador/teléfono.
- [Cambio observable en `AboutDialog` y retirada de tests de Play] → Reflejado explícitamente en el delta spec de `app-navigation` y verificado con `TestStrings` + captura en emulador en 360×640 dp (claro/oscuro, `en`/`es`).

## Migration Plan
Sin migración de datos de usuario: ni `ConfigStore`, ni `SavedProfile`, ni `UiPreferences` cambian. Al archivar el cambio, sincronizar `openspec/specs/app-navigation/spec.md` y actualizar `project-state.md`. Rollback: revertir la rama o el merge commit antes de etiquetar una release.
