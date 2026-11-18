# Tasks

## 1. Base de recursos

- [x] 1.1 `UiText` (`Res`, `Plural`, `Raw`, con argumentos anidados) y su resolución en Compose (`asString()`) y con `Context` (`asString(context)`); verificar con `UiTextTest` (igualdad de valores y argumentos anidados) y `:app:compileDebugKotlin`
- [x] 1.2 `res/values/strings.xml` (inglés) y `res/values-es/strings.xml` (español) con todos los textos visibles, plurales para perfiles ocultos, valores no válidos y cambios; verificar con `lintDebug` sin `MissingTranslation`
- [x] 1.3 `res/xml/locales_config.xml` (`en`, `es`), `android:localeConfig` en el manifiesto y `localeFilters` (`en`, `es`) en `app/build.gradle.kts`; verificar con `:app:assembleRelease` y `aapt2 dump badging` (solo `en` y `es` en `locales`)

## 2. Perfiles predefinidos

- [x] 2.1 Quitar `displayName` y `description` de `ThrottlePreset` (`:core`) y añadir `PresetTexts` en `:app` (nombre y descripción de cada preset); verificar con `:core:test` y `:app:compileDebugKotlin`
- [x] 2.2 `ProfileCatalog` con `UiText` y `checkName` con los nombres predefinidos de todos los idiomas, calculados en `MainActivity`; verificar con `ProfileCatalogTest` («3g lento» y «slow 3g» rechazados)

## 3. Lógica de la interfaz

- [x] 3.1 `ParameterInput` y `ParameterField` con `UiText` (etiquetas, errores, ayudas, nota de valores no válidos); verificar con `ParameterInputTest`
- [x] 3.2 `UiFormatters`, `ThroughputHistory` y `MainUiState` con `UiText` (resumen de condiciones, cabecera del perfil base, descripción de la gráfica); verificar con `UiFormattersTest`, `ThroughputHistoryTest` y `MainViewModelTest`
- [x] 3.3 `MainViewModel` y `UserMessage` con `UiText` (avisos, «Deshacer», fallo del túnel); verificar con `MainViewModelTest`

## 4. Pantallas

- [x] 4.1 Bienvenida, barra superior, navegación, botón flotante y «Acerca de» con recursos; verificar con `:app:compileDebugKotlin` y en el emulador en ambos idiomas — revisado en el emulador con API 37 (360 × 640 dp) en inglés y en español: bienvenida, barra superior, navegación, «Stop»/«Detener» y «Acerca de»
- [x] 4.2 Simular (tarjeta, chips, hoja de perfiles, diálogo de guardar, resumen) con recursos; verificar en el emulador en ambos idiomas — revisado en ambos idiomas: tarjeta, chips, «Swipe to see 2 more profiles», hoja, diálogo de guardar («Slow 3g» y «slow 3g» rechazados en inglés y en español)
- [x] 4.3 Parámetros y Telemetría (campos, grupos, gráfica, contadores, estado del túnel) con recursos; verificar en el emulador en ambos idiomas — revisado en ambos idiomas, con tráfico real en Telemetría

## 5. Servicio

- [x] 5.1 `TunnelNotifications` (título, texto con el perfil traducido, «Detener», canal) y motivos de fallo de `ThrottleVpnService` con `getString`; notificación y canal se actualizan en `onConfigurationChanged`; verificar con `:app:testDebugUnitTest` y en el emulador cambiando el idioma con la simulación activa — en el emulador, cambiando el idioma de la app a español con la simulación activa: notificación («OpenNetSim activo», «3G Lento…», «Detener») y canal traducidos, túnel activo sin reiniciar el contador y con las mismas condiciones

## 6. Versión y documentación

- [x] 6.1 `versionCode` 2 y `versionName` «1.0.1»; README sin la nota de interfaz solo en español; `project-state.md` con la fase nueva; verificar con `aapt2 dump badging` del APK de release y leyendo ambos documentos — `aapt2 dump badging`: `versionCode=2`, `versionName=1.0.1`, `locales: --_-- es`

## 7. Verificación

- [x] 7.1 Barrido de literales con letras en `ui/`, `profiles/` y `service/` (excluidos los mensajes de diagnóstico) sin textos visibles en Kotlin; verificar con `grep` y revisando cada coincidencia
- [x] 7.2 Puerta de calidad completa (`:core:compileKotlin`, `:app:compileDebugKotlin`, `detekt`, `lintDebug`, `:core:test`, `:app:testDebugUnitTest`, Kover, `:benchmark:smokeBenchmark`, `:app:assembleRelease`); verificar que todas terminan bien
- [x] 7.3 Emulador con API 37: todas las pantallas en inglés (sistema) y en español (idioma por app), en 360 × 640 dp, y cambio de idioma con la simulación activa sin que cambien las condiciones de red; verificar con capturas — capturas en ambos idiomas; además `es-MX` en español, `fr-FR` en inglés y el selector de idioma de la app en los ajustes del sistema con «English» y «Español»
- [x] 7.4 Verificación del usuario en su teléfono con `app-debug.apk` en ambos idiomas; verificar con el usuario — usuario, 18/11/2026: APK probado en su teléfono, correcto

## Workflow follow-up

- PR hacia `develop`, CI en verde y archivar el cambio.
- Llevar `develop` a `main`, etiqueta `v1.0.1` y release de GitHub con el APK firmado.
