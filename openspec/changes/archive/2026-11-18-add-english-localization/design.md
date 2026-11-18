# Design

## Context
Todos los textos de la 1.0.0 están escritos en español directamente en Kotlin:
- **Composables** de `ui/screens` y `ui/components`: títulos, botones, avisos, descripciones accesibles.
- **Lógica JVM probada**, que construye frases que la interfaz solo pinta:
  - `MainViewModel`: avisos con «Deshacer», motivos de fallo.
  - `ParameterInput`: etiquetas, «Solo números», «Rango: …», ayudas, nota de valores no válidos.
  - `ProfileCatalog`: validación de nombres, descripción de los perfiles guardados.
  - `MainUiState`, `UiFormatters` y `ThroughputHistory`: resúmenes y textos de la gráfica.
- **`:core`**: `ThrottlePreset.displayName` y `description`, Kotlin/JVM puro sin recursos de Android.
- **Servicio**: la notificación persistente, su canal y los motivos de fallo de `ThrottleVpnService`,
  que llegan a la interfaz como texto en `TunnelState.Failed(reason)`.

Los mensajes de diagnóstico de Logcat (`diag/`, forwarders, pipeline) son para desarrolladores y no
cambian.

## Goals / Non-Goals
- **Objetivos:**
  - Inglés y español completos.
  - Inglés como idioma por defecto, español para `es-*` e idioma por app en Android 13+.
  - Que cambiar de idioma con la simulación activa cambie la interfaz sin tocar el túnel.
  - Mantener la lógica de la interfaz probada en JVM.
- **No objetivos:** selector de idioma propio en la app, otros idiomas y formato regional de los números
  (ver la propuesta).

## Decisions

### Recursos e idioma por defecto
- `res/values/strings.xml` en inglés (idioma sin calificador) y `res/values-es/strings.xml` en español.
  Un dispositivo en francés o alemán cae en inglés; `es-MX` o `es-AR` usan `values-es`.
- Para los recuentos se usan `<plurals>`: perfiles ocultos, valores no válidos y cambios respecto al
  perfil base.
- Los formatos llevan argumentos posicionales (`%1$s`) para que cada idioma ordene la frase a su manera.
- `lint` (`MissingTranslation`, error por defecto) falla si falta una cadena en español. Es la red de
  seguridad para no dejar textos sin traducir.
- Los números se siguen formateando con `Locale.US` (punto decimal), como en la 1.0.0. Los campos ya
  aceptan coma o punto al escribir.

### Lógica JVM: `UiText` en lugar de frases
- Nuevo tipo en `ui/`: `sealed interface UiText` con tres variantes:
  - `Res(@StringRes id, args)`.
  - `Plural(@PluralsRes id, count, args)`.
  - `Raw(text)`, para nombres que escribe el usuario.
- Los argumentos pueden ser a su vez `UiText` (p. ej. el nombre traducido de un perfil dentro de
  «Valores de «%1$s» restablecidos»).
- `ViewModel`, `ParameterInput`, `ProfileCatalog`, `UiFormatters` y `MainUiState` devuelven `UiText`.
  Los composables lo resuelven con `uiText.asString()` (`stringResource`/`pluralStringResource`) y el
  código Android con `asString(context)`.
- Los tests JVM comparan `UiText` (identificador de recurso y argumentos), no frases. La clase `R` de
  `:app` está disponible en los tests locales. Así se prueba qué se dice sin depender del idioma.
- **Motivo:** el `ViewModel` sobrevive al cambio de idioma (recrear la actividad), pero un texto ya
  resuelto se quedaría en el idioma anterior. Con `UiText` el texto se resuelve al pintar y el aviso
  visible pasa al idioma nuevo.
- **Alternativa descartada:** inyectar en el `ViewModel` un proveedor de textos basado en `Context`.
  Obligaría a falsear cada frase en los tests y no cambiaría los textos ya generados al cambiar de
  idioma.

### Perfiles predefinidos
- `ThrottlePreset` (`:core`) pierde `displayName` y `description`. `:core` queda sin textos de interfaz,
  coherente con no depender de Android.
- `PresetTexts` (`:app`) asigna a cada preset sus recursos de nombre y descripción. Los nombres en
  inglés son «Custom», «Slow 3G», «2G / EDGE», «Flaky Wi-Fi» y «High Latency».
- La persistencia no cambia: los presets se guardan por el nombre del enum (`SLOW_3G`), que R8 ya
  conserva.
- **Nombres reservados:** `ProfileCatalog.checkName` recibe el conjunto de nombres predefinidos en todos
  los idiomas.
  - `MainActivity`, la raíz de composición, lo calcula una vez con
    `createConfigurationContext` para `en` y `es`, y se lo pasa al `ViewModel`.
  - Los tests usan un conjunto fijo.
  - Así «3g lento» y «slow 3g» se rechazan en cualquier idioma, y cambiar de idioma no hace que un perfil
    guardado choque con uno predefinido.

### Servicio y notificación
- `ThrottleVpnService` construye los motivos de fallo con `getString`, de modo que salen en el idioma
  activo en ese momento. `TunnelState.Failed(reason: String)` no cambia de forma.
- `TunnelNotifications` usa recursos para el título, el texto (nombre traducido del perfil y
  condiciones), la acción «Detener» y el nombre y la descripción del canal.
- El servicio vuelve a publicar la notificación y a registrar el canal en `onConfigurationChanged`.
  Volver a crear un canal con el mismo id solo actualiza su nombre y descripción. Así la notificación
  cambia de idioma sin reiniciar el túnel.
- El nombre de la sesión VPN (`setSession("OpenNetSim")`) es la marca y no se traduce.

### Idioma por app (Android 13+)
- `res/xml/locales_config.xml` declara `en` y `es`, y el manifiesto lo enlaza con
  `android:localeConfig`. Sin dependencias nuevas: no hace falta AppCompat porque no hay selector propio.
- `androidResources.localeFilters` en `app/build.gradle.kts` limita los recursos empaquetados a `en` y
  `es`. Así las traducciones de las librerías a otros idiomas no aparecen en la app y el APK no crece.
  Hay que comprobar el nombre exacto de la propiedad en AGP 9.

### Versión
`versionCode` 2 y `versionName` «1.0.1». La release de GitHub se publica tras la verificación del
usuario, con el mismo keystore.

### Ajustes surgidos al implementar
- **AAB sin división por idioma** (`bundle.language.enableSplit = false`). Google Play reparte los AAB
  por idioma (aviso `AppBundleLocaleChanges` de lint): un móvil en inglés no recibiría el español y no
  podría elegirlo como idioma de la app, y los nombres reservados de `MainActivity` saldrían incompletos.
  Con dos idiomas el tamaño apenas cambia.
- **Errores internos del pipeline:** `TunnelPipeline.Listener.onFatalError(message)` lleva un texto de
  diagnóstico en español. El servicio muestra en su lugar un motivo genérico traducido
  («El túnel dejó de reenviar tráfico…») y el detalle queda en Logcat.
- **`ProfileNaming`** agrupa los nombres reservados y el generador de ids del `ViewModel`, que si no
  pasaba del límite de parámetros de detekt.
- **Tests con los textos reales:** `TestStrings` lee los `strings.xml` del módulo y resuelve `UiText`
  como Android. Así los tests siguen comprobando frases en español (las de las specs) y además en inglés.
  No hace falta Robolectric.
- **Plural para la longitud máxima del nombre** (aviso `PluralsCandidate` de lint).

## Risks / Trade-offs
- **Cadenas olvidadas en Kotlin:** `lint` no detecta literales en código.
  - Mitigación: un barrido de literales con letras en `ui/`, `service/` (salvo diagnóstico) y
    `profiles/` como tarea de verificación.
  - Revisión en el emulador de cada pantalla en los dos idiomas.
- **Diff grande en la interfaz** (casi todos los composables cambian): no hay cambios de diseño, solo el
  origen de los textos. La revisión visual en el emulador en ambos idiomas lo cubre.
- **Textos en inglés más largos o más cortos:** pueden partir líneas de otra forma en 360 dp. Se revisa
  en una pantalla de 360 × 640 dp.
- **Tests que comparaban frases:** pasan a comparar `UiText`. Se pierde la comprobación del texto
  literal, pero el texto queda en los recursos, que revisan `lint` y la revisión visual.
- **Cambio en `service/`:** verificación en dispositivo obligatoria.

## Migration Plan
- No hay datos que migrar: la configuración guarda el preset por su nombre de enum y los perfiles
  guardados conservan el nombre que escribió el usuario.
- Tras actualizar, un dispositivo en español sigue viendo la app en español. Uno en otro idioma la verá
  en inglés: es el cambio buscado, y en Android 13+ se puede volver al español solo para la app.
- Volver atrás es revertir el cambio.

## Open Questions
- Ninguna.
