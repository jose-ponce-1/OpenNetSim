# Design

## Context
La UI es `ThrottleDashboardScreen.kt` (una pantalla, paleta oscura fija) sobre `MainViewModel`, que
solo conoce `TunnelController`. El prototipo de referencia es el lienzo de Claude Design
«OpenNetSim · Prototipo Android M3» (Splash, Bienvenida, Simular, Parámetros y Telemetría, 390×844).
Restricciones: Compose BOM 2024.10.01 (Material 3 1.3), sin cambiar versiones mayores; `:core` sin
Android; Kover exige el 85 % de líneas sin contar el código que solo corre con Compose o Android;
detekt con las reglas de Compose (≤ 60 líneas por función, ≤ 6 parámetros, ≤ 11 funciones por
archivo).

## Goals / Non-Goals

**Goals:**
- Toda la lógica de la interfaz (selección de perfil, perfil base, textos de los campos, validación,
  historial de la gráfica, tiempo activo, avisos con «Deshacer») en clases JVM probadas; los
  composables solo pintan el estado y llaman a acciones.
- Fidelidad visual con el prototipo usando componentes Material 3 estándar.

**Non-Goals:**
- Navegación con back stack general: tres secciones de primer nivel bastan.
- Mantener la API de `ThrottleDashboardScreen` y `DashboardActions`: se eliminan.

## Decisions

### Estado y lógica
- `MainViewModel` sigue siendo el único ViewModel (la configuración es común a las tres secciones).
  `MainUiState` añade: perfiles guardados, perfil elegido y perfil base (`ProfileRef`: predefinido o
  guardado), textos de los campos (`drafts`), unidad de ancho de banda, historial de caudal, segundos
  activos, `showOnboarding` y el aviso actual (`UserMessage` con texto, acción opcional e id para que
  dos avisos iguales se muestren dos veces).
- El perfil elegido se **deriva de los valores** (`ProfileCatalog.match`): predefinido exacto, luego
  guardado con los mismos valores, luego «Personalizado». Así una reapertura reconoce el perfil sin
  guardar nada más. El perfil base solo cambia al elegir un perfil, restablecer o adoptar la
  configuración del servicio al arrancar; editar un campo no lo cambia.
- «Deshacer» guarda la acción inversa en el ViewModel, ligada al id del aviso: pulsar la acción de un
  aviso ya sustituido no hace nada.
- Eliminar el perfil guardado elegido mantiene los valores y marca «Personalizado» (el prototipo pasaba
  a «3G Lento»: cambiaría la red bajo prueba sin que el usuario lo pida).
- Alternativa descartada: un ViewModel por sección. Duplicaría la sincronización con el servicio, que
  es la parte delicada y ya probada.

### Campos de texto
- `ParameterField` (enum) describe cada parámetro: rango, decimales, prefijo, sufijo y cómo leerlo y
  escribirlo en `ThrottleConfig`. `ParameterInput.parse`/`format` son funciones puras (coma o punto
  decimal, conversión kbps/Mbps, mensajes «Solo números», «Rango: …»).
- Vacío equivale a 0 (petición del usuario tras probarlo en su teléfono): `parse` lo interpreta como
  «0», así que donde 0 está fuera de rango da el mismo error que escribirlo. Mientras se edita, el 0 es
  el marcador (`placeholder`) del campo, no texto: si se escribiera «0» en el campo, el cursor quedaría
  delante o detrás y teclear 5 daría «50» o «05». Al salir del campo el ViewModel escribe «0».
- Los textos viven en el ViewModel (`drafts`), no en el composable: un valor válido se aplica con el
  debounce de siempre (150 ms) y uno no válido solo cambia el texto. Al salir del campo se reformatea
  si es válido. Cualquier cambio de configuración que no venga de teclear (perfil, restablecer,
  servicio, unidad) reescribe los textos desde la configuración.
- El debounce y el filtro «no reenviar lo que ya aplica el servicio» no cambian.

### Perfiles guardados y preferencias
- `SavedProfile(id, name, config)` y `ProfileStore` (interfaz, en memoria para tests y sobre
  SharedPreferences en la app). `SavedProfilesCodec` reutiliza `ThrottleConfigCodec` con un prefijo por
  perfil y una clave con el orden de los ids; una entrada corrupta se descarta sola, sin perder las
  demás. Sin dependencias nuevas (sin JSON).
- `UiPreferences` (bienvenida completada y unidad de ancho de banda), mismo patrón.

### Tiempo activo
- `TunnelState.Running` gana `startedAtMs` (reloj monotónico del proceso). El servicio lo fija en
  `startTunnel` cuando no hay pipeline y lo conserva al reconstruir el pipeline por un cambio de red y
  al aplicar una configuración. El ViewModel usa el mismo `MonotonicClock` (inyectable) y un ticker de
  1 s mientras el túnel está activo para los segundos activos y para muestrear la telemetría.
- Alternativa descartada: contar en el ViewModel desde que ve `Running`. Se reiniciaría al recrear la
  actividad.

### Gráfica
- `ThroughputHistory` (máximo 60 muestras) se alimenta del ticker leyendo `telemetry.value`, no de cada
  emisión: `StateFlow` funde dos muestras iguales seguidas (p. ej. sin tráfico) y la gráfica perdería
  tiempo. Se vacía al entrar en `Running` desde otro estado. Se dibuja con `Canvas`; escala
  `max(límite × 1,15, máximo de las muestras × 1,05)`.

### Navegación y pantallas
- Un solo `Scaffold`: barra superior, botón flotante (solo en Telemetría) y `NavigationBar` según la
  sección (`AppDestination`, `rememberSaveable`); `BackHandler` vuelve a Simular. Sin
  `navigation-compose`: tres destinos de primer nivel no lo justifican.
- La bienvenida pide el permiso con su propio launcher y no toca el túnel; el permiso de
  notificaciones se pide al llegar a la app principal, no encima de la bienvenida.
- Composables en `ui/screens/` y `ui/components/`, excluidos de Kover como hoy la pantalla actual; las
  acciones llegan en `AppActions` (lambdas), sin pasar el ViewModel hacia abajo (regla
  `ViewModelForwarding`).
- Iconos de `material-icons-extended` (Outlined) en lugar de los SVG del prototipo; R8 elimina los no
  usados.

### Código abierto y donaciones («Acerca de»)
- Petición del usuario (18/11/2026) tras investigar cómo lo hacen otras apps (NewPipe, AntennaPod,
  Thunderbird, Fossify, AnkiDroid): apartado «Código abierto» (Apache 2.0 y «Ver el código en GitHub»),
  apartado «Apoya el proyecto» con «Apoyar en Ko-fi», sin avisos que interrumpan, y la firma. El diálogo
  se desplaza porque en 360 × 640 dp no cabe.
- La app se distribuirá como APK en GitHub y en Google Play. La política de pagos de Play (sección 4)
  prohíbe botones o enlaces que lleven a otro medio de pago, y en 2026 Play bloqueó actualizaciones de
  AnkiDroid por su enlace de donaciones. Por eso el apartado de Ko-fi solo aparece si la app no la
  instaló Google Play: `SupportLinks.donationLinkAllowed(instalador)` (pura, probada en JVM) con
  `getInstallSourceInfo` (API 30+) o `getInstallerPackageName` (API 26–29).
- Alternativa descartada: dos variantes de compilación (`github`/`play`). Evita incluir la URL en el
  binario de Play, pero cambia los nombres de todas las tareas (`lintGithubDebug`, `assemblePlayRelease`…),
  la CI, Kover y la documentación. Se puede adoptar más adelante si Google lo exige.
- El enlace al repositorio se mantiene también en Play: enlazar al código fuente es habitual y no es un
  medio de pago. Riesgo residual: si el README o la página del repositorio enlazan a Ko-fi, un revisor
  podría considerarlo un camino indirecto al pago.
- `SupportLinks.KO_FI_URL` es la página de Ko-fi del autor (`https://ko-fi.com/joseponce`), la misma que
  `.github/FUNDING.yml` (botón «Sponsor» del repositorio) y la sección «Support» del README.

### Tema y pantalla de carga
- `lightColorScheme`/`darkColorScheme` con todos los roles del prototipo, incluidos los contenedores de
  superficie; los roles que el prototipo no define (`onSecondary`, `onTertiary`, `onError`,
  `surfaceDim`, `surfaceBright`) se completan con los tonos de la misma paleta. Tipografía: escala
  Material 3 por defecto con títulos en semibold, fuente del sistema.
- `core-splashscreen` 1.0.1: tema `Theme.OpenNetSim.Starting` con `postSplashScreenTheme`. El icono es
  un vector de 288 dp con el logo dentro del círculo de 192 dp; en `drawable-v31` es un
  `animated-vector` (trazo del marco y de la onda, nodos que aparecen; ~1 s). La salida espera a que
  termine la animación del icono (como mucho su duración) y lo escala y desvanece en 320 ms.
- En Android 12+, `core-splashscreen` aplica las barras del sistema del tema al empezar la salida y otra
  vez en `remove()` (`windowLightStatusBar`, colores de barra…), lo que deshacía `enableEdgeToEdge()`:
  iconos blancos sobre fondo claro tras la pantalla de carga (visto en el emulador con API 37). La
  salida vuelve a llamar a `enableEdgeToEdge()` tras esos dos puntos, de modo que la política de las
  barras sigue en un único sitio en lugar de duplicarla en los temas.

## Risks / Trade-offs
- [Campos con texto no válido al recrear la actividad] → los textos están en el ViewModel y sobreviven
  a la rotación; tras la muerte del proceso se reconstruyen desde la configuración.
- [El ticker de 1 s mantiene el ViewModel activo con el túnel en marcha] → solo corre mientras el túnel
  está activo y se cancela con el ViewModel.
- [Pantalla de carga más larga en Android 12+] → solo en arranque en frío y como máximo la duración de
  la animación (1 s).
- [`TunnelState.Running` cambia de forma] → solo lo construye el servicio; cambio en `service/`, por lo
  que se verifica en dispositivo.

## Migration Plan
Sin migración de datos: la configuración guardada del túnel no cambia de formato. Los perfiles
guardados y las preferencias de interfaz usan archivos nuevos. Volver atrás es revertir el cambio; los
archivos nuevos se ignoran.
