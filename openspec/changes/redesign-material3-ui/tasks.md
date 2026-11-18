# Tasks

## 1. Modelo y servicio

- [x] 1.1 Añadir `startedAtMs` a `TunnelState.Running`; `ThrottleVpnService` lo fija al arrancar el túnel y lo conserva al reconstruir el pipeline y al aplicar una configuración; verificar con `:app:compileDebugKotlin` y `MainViewModelTest`
- [x] 1.2 Añadir `NetworkTelemetry.formattedRetransmittedData()` con el mismo formato que el total; verificar con `NetworkTelemetryTest`
- [x] 1.3 Añadir `androidx.core:core-splashscreen` 1.0.1 al version catalog y a `:app`; verificar que `:app:compileDebugKotlin` resuelve la dependencia

## 2. Lógica de la interfaz

- [x] 2.1 `ParameterField` y `ParameterInput` (rangos, coma decimal, kbps/Mbps, mensajes de error, formato); verificar con `ParameterInputTest`
- [x] 2.2 `SavedProfile`, `ProfileStore`, `SavedProfilesCodec` y `SharedPreferencesProfileStore`; verificar con `SavedProfilesCodecTest` (ida y vuelta, nombres con comas, entrada corrupta)
- [x] 2.3 `ProfileRef`, `ProfileCatalog` (orden, reconocimiento por valores, validación de nombres) y textos de resumen; verificar con `ProfileCatalogTest` y `UiFormattersTest`
- [x] 2.4 `UiPreferences` (bienvenida y unidad) con implementación en memoria y sobre SharedPreferences; verificar con `MainViewModelTest`
- [x] 2.5 `ThroughputHistory` (60 muestras); verificar con `ThroughputHistoryTest`
- [x] 2.6 `MainViewModel`: perfiles guardados, perfil base, `drafts`, restablecer, avisos con «Deshacer», bienvenida, ticker de tiempo activo y muestreo; verificar con `MainViewModelTest` (escenarios de las specs `connection-profiles`, `parameter-editing`, `live-telemetry` y `vpn-tunnel-lifecycle`)

## 3. Tema y pantalla de carga

- [x] 3.1 Paleta clara y oscura, tipografía y `OpenNetSimTheme` según el modo del sistema; verificar con `:app:compileDebugKotlin` y en dispositivo en ambos modos
- [ ] 3.2 `themes.xml`, colores de fondo, icono estático y `animated-vector` de la pantalla de carga, tema en el manifiesto e `installSplashScreen` con la salida animada en `MainActivity`; verificar con `lintDebug` y en dispositivo (Android 12+ y, si hay, Android 8–11) — `lintDebug` en verde y logo estático visto en el emulador con Android 10; falta la animación en Android 12+

## 4. Pantallas

- [x] 4.1 Esqueleto de la app: bienvenida, `Scaffold` con barra superior, `NavigationBar`, botón flotante, avisos y diálogo «Acerca de»; `AppActions`; verificar con `:app:compileDebugKotlin` y en dispositivo (primer arranque, Atrás)
- [x] 4.2 Simular: tarjeta principal, chips de perfil con degradados e indicación de deslizar, hoja con todos los perfiles, diálogo para guardar, resumen de condiciones y acceso a Telemetría; verificar en dispositivo — revisado en el emulador (guardar, nombre repetido, deshacer, hoja)
- [x] 4.3 Parámetros: cabecera del perfil base, campos con unidad y errores, reordenación desactivada sin latencia, pérdida de ida y vuelta y aviso de pérdida elevada; verificar en dispositivo — revisado en el emulador (valor fuera de rango)
- [x] 4.4 Telemetría: selector Bajada/Subida/Ambos, gráfica con el límite, contadores, estado del túnel y botón Detener/Iniciar; verificar en dispositivo con tráfico real — revisado en el emulador con el túnel activo, en claro y en oscuro
- [x] 4.5 Eliminar `ThrottleDashboardScreen.kt`, `DashboardActions.kt` y la paleta antigua; actualizar los filtros de Kover y dejar `:app` sin baseline de detekt; verificar con `detekt` y `:app:koverVerifyCoverage`

## 5. Documentación

- [x] 5.1 Actualizar `README.md` (interfaz) y `project-state.md` (fase nueva y hoja de ruta); verificar leyendo ambos

## 6. Verificación

- [ ] 6.1 Puerta de calidad completa: `:core:compileKotlin`, `:app:compileDebugKotlin`, `detekt`, `lintDebug`, `:core:test`, `:app:testDebugUnitTest`, `koverVerifyCoverage`, `:benchmark:smokeBenchmark` y `:app:assembleRelease`; verificar que todas terminan bien — con el código final pasan compilación, detekt, tests, Kover (90 %) y benchmark; `lintDebug` y `assembleRelease` pasaron antes de los últimos retoques visuales y falta repetirlos
- [ ] 6.2 En dispositivo con `app-debug.apk`: primer arranque completo, perfiles (guardar, deshacer, eliminar con el túnel activo), parámetros con valores no válidos, telemetría con tráfico, tiempo activo tras reabrir la app y modo claro/oscuro; verificar con el usuario

## Workflow follow-up

- Abrir el PR hacia `develop` y esperar la CI en verde.
- Archivar el cambio tras la verificación en dispositivo.
