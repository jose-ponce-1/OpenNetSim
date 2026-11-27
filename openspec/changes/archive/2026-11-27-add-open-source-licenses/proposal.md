# Proposal

## Why
La licencia Apache 2.0 de OpenNetSim y de sus dependencias de tiempo de ejecución (AndroidX, Jetpack Compose, Kotlin y corrutinas) exige entregar el texto de la licencia y los avisos de terceros junto con el binario distribuido. Además, al descartar Google Play (por el requisito de cuenta de Organización con D-U-N-S para apps con `VpnService`) y centrar la distribución en GitHub Releases y F-Droid, conviene retirar la lógica y configuración específica de Play Store del código, las specs y el empaquetado.

## What Changes
- **Tarea Gradle propia para licencias en `:app`:** genera de forma determinista y sin acceso a red (compatible con F-Droid y configuration cache) un JSON con las dependencias de `releaseRuntimeClasspath` y copia el texto de `LICENSE.txt` a los assets generados de la compilación, validando que todas las librerías incluidas tengan licencia compatible declarada.
- **Lector JVM y visor Compose Material 3:** nuevo lector testable en JVM sobre `org.json` (sin librerías externas) y un diálogo/hoja desplazable accesible desde «Acerca de» con el listado de dependencias incluidas y lectura offline del texto completo de la licencia Apache 2.0, en inglés y español.
- **Limpieza de Google Play y preparación de empaquetado FOSS:**
  - Eliminar la comprobación del instalador de Google Play (`SupportLinks.donationLinkAllowed` e `installerPackage` en `AboutDialog`) para mostrar siempre el apoyo en Ko-fi junto al código fuente.
  - Desactivar en `app/build.gradle.kts` el bloque binario de metadatos de dependencias de Google (`dependenciesInfo { includeInApk = false; includeInBundle = false }`), necesario para builds reproducibles en F-Droid, y retirar el bloque `bundle { language { enableSplit = false } }` específico de AAB para Play.
  - Actualizar la especificación `app-navigation` y `project-state.md` para reflejar los canales de distribución actuales (GitHub Releases y F-Droid).

## Fuera de alcance
- Creación de metadatos Fastlane (`fastlane/metadata/android/`) ni apertura de la solicitud en `fdroiddata`.
- Subida de `versionCode`/`versionName` o publicación de una nueva release (se hará en su preparación correspondiente).
- Cambios en la animación o aviso de chips de perfiles (`ProfileChips.kt`).
- Subida a `targetSdk` 37 (aplazada a mediados de 2027).
- Modificación del motor de red (`:core`), el servicio VPN (`service/`) o el módulo `:benchmark`.

## Capabilities

### New Capabilities
- (ninguna)

### Modified Capabilities
- `app-navigation`: añadir el acceso y consulta sin conexión de las «Licencias de código abierto» desde el diálogo de información de la aplicación, y eliminar la condición y escenario específicos de instalación desde Google Play.

## Impact
- **Build y configuración:** `app/build.gradle.kts` (tarea de generación de licencias en assets, `dependenciesInfo`, retirada de `bundle`), `config/detekt/detekt.yml` si hiciera falta, sin nuevas dependencias externas en `gradle/libs.versions.toml`.
- **Código de `:app`:** `SupportLinks.kt`, `AboutDialog.kt`, nuevo lector/modelo de licencias en `ui/`, nueva vista Compose de licencias en `ui/screens/`, recursos `values/strings.xml` y `values-es/strings.xml`, y tests unitarios en `app/src/test/java/com/opennetsim/ui/`.
- **Documentación y specs:** delta spec de `app-navigation` y actualización posterior de `project-state.md` (sin impacto en configuración guardada, perfiles del usuario ni `:core`).
