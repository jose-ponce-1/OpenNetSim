# Proposal

## Why
La versión 1.0.0 solo está en español, aunque se distribuye en GitHub y se publicará en Google Play para
cualquier usuario. El usuario pidió añadir el inglés para la versión 1.0.1. Hoy no es posible traducir
nada: todos los textos están escritos directamente en Kotlin, y parte de ellos los generan clases JVM
(ViewModel, validación de campos, catálogo de perfiles) o el módulo `:core`, que no tienen acceso a los
recursos de Android.

## What Changes
- **Inglés y español:** la interfaz se muestra en inglés por defecto (`values/`) y en español cuando el
  idioma del dispositivo es el español (`values-es/`, cualquier región).
- **Idioma por app** (Android 13+): la app declara sus idiomas (`localeConfig`) para que el usuario pueda
  elegir inglés o español solo para OpenNetSim en los ajustes del sistema.
- **Todo el texto visible se traduce:** pantallas, diálogos, avisos con «Deshacer», mensajes de
  validación, descripciones accesibles, nombres y descripciones de los perfiles predefinidos («3G Lento» →
  «Slow 3G»…), notificación del túnel, motivos de fallo del túnel y su canal de notificación.
- **Textos como recursos:** la lógica JVM probada deja de construir frases. Devuelve referencias a
  recursos con sus argumentos, que la interfaz convierte en texto. **BREAKING** (API interna):
  `ThrottlePreset` pierde `displayName` y `description`; los nombres pasan a recursos de `:app`.
- **Nombres de perfil:** un perfil guardado no puede llamarse como un predefinido en ninguno de los dos
  idiomas («3g lento» y «slow 3g» se rechazan).
- **Versión 1.0.1** (`versionCode` 2) y README sin la nota «la interfaz está en español».

## Fuera de alcance
- Selector de idioma dentro de la app: se usa el del sistema y, en Android 13+, el idioma por app de los
  ajustes. Para versiones anteriores haría falta AppCompat.
- Otros idiomas además de inglés y español.
- Traducir los mensajes de diagnóstico de Logcat (`OpenNetSim`), que no ve el usuario, ni los nombres de
  los perfiles guardados, que escribe el usuario.
- Formato de números según la región: se mantiene el punto decimal en lo que muestra la app (los campos
  ya aceptan coma o punto al escribir).
- Traducir el README, que ya está en inglés, ni las notas de las releases.
- La release 1.0.1 en GitHub: se publica tras la verificación del usuario, como la 1.0.0.

## Capabilities

### New Capabilities
- `app-localization`: idiomas de la interfaz, idioma por defecto, elección por el sistema y por app, y
  qué textos se traducen.

### Modified Capabilities
- `connection-profiles`: el nombre de un perfil guardado no puede coincidir con el de un predefinido en
  ninguno de los idiomas de la app.

## Impact
- **Recursos:** `res/values/strings.xml` (inglés) y `res/values-es/strings.xml` (español), con plurales
  donde hacen falta. `res/xml/locales_config.xml` y `android:localeConfig` en el manifiesto.
  `androidResources.localeFilters` en `app/build.gradle.kts`, para que R8 y la reducción de recursos no
  incluyan idiomas de las librerías que la app no ofrece.
- **Interfaz:** todas las pantallas y componentes de `ui/screens` y `ui/components`; `MainViewModel`,
  `MainUiState`, `ParameterInput`, `ProfileCatalog`, `UiFormatters` y `ThroughputHistory`, y sus tests.
- **Servicio:** `TunnelNotifications` y los motivos de fallo de `ThrottleVpnService`. Por ese cambio en
  `service/` hace falta verificarlo en dispositivo.
- **`:core`:** `ThrottlePreset` sin textos.
- **Versión:** `app/build.gradle.kts` (1.0.1, `versionCode` 2); README.
