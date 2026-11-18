# Proposal

## Why
Paso 6 de la hoja de ruta. La interfaz actual es una sola pantalla oscura con sliders que no permiten
escribir un valor exacto, colores ajenos a la marca y sin pantalla de inicio. El usuario diseñó el
prototipo «OpenNetSim · Prototipo Android M3» (18/11/2026, cinco pantallas Material 3) y pidió
implementarlo.

## What Changes
- **Paleta oficial y tema claro/oscuro** del prototipo (Material 3, semilla `#3F5F90`), según el modo
  del sistema. Sustituye la paleta oscura actual.
- **Pantalla de carga** con la API SplashScreen (`androidx.core:core-splashscreen`, API 26+): logo
  animado en Android 12+ y estático en versiones anteriores.
- **Bienvenida** en el primer arranque: qué hace la app, botón para conceder el permiso VPN y «Ahora
  no».
- **Navegación inferior** con tres secciones: Simular, Parámetros y Telemetría.
- **Simular:** tarjeta principal que activa o detiene el túnel, con el tiempo activo y la familia de
  direcciones; perfiles en chips desplazables y en una hoja con todos; resumen de las condiciones
  aplicadas; acceso a la telemetría.
- **Perfiles guardados por el usuario:** guardar las condiciones actuales con un nombre, elegirlos como
  un perfil más y eliminarlos, con «Deshacer». Se conservan entre ejecuciones.
- **Parámetros:** **BREAKING (UI)** los sliders se sustituyen por campos de texto con valor exacto,
  validación con mensaje y unidad kbps/Mbps para el ancho de banda. Un valor no válido nunca llega al
  túnel. Se indica el perfil base y cuántos valores cambian, con «Restablecer» y «Deshacer».
- **Telemetría:** gráfica del caudal de los últimos 60 s (bajada, subida o ambos) con la línea del
  límite, contadores (transferido, descartados, retransmitido, tiempo activo), estado del túnel y botón
  para detenerlo o iniciarlo.
- El servicio publica el instante de arranque del túnel para que el tiempo activo sea correcto aunque se
  recree la actividad.

## Fuera de alcance
- Fuente propia (Noto Sans del prototipo): se usa la fuente del sistema.
- Imagen de marca bajo el icono de la pantalla de carga (`windowSplashScreenBrandingImage`, solo API
  31+ y desaconsejada por Google) y espera artificial de carga: la pantalla dura lo que tarda la app en
  estar lista más, como máximo, la animación del icono.
- El diálogo propio «¿Activar el túnel VPN?» del prototipo: su papel lo cumple el diálogo del sistema
  que Android muestra al pedir el permiso.
- Color dinámico (Material You): la app usa siempre su paleta de marca.
- Exportar o importar perfiles, editarlos tras guardarlos y ordenarlos a mano.
- Tabletas y horizontal con un diseño propio: la misma interfaz se adapta al ancho.
- Traducciones: la interfaz sigue solo en español.

## Capabilities

### New Capabilities
- `app-navigation`: bienvenida del primer arranque con el permiso VPN y navegación entre Simular,
  Parámetros y Telemetría.
- `connection-profiles`: perfiles predefinidos y guardados por el usuario, su selección, el perfil base
  y el resumen de las condiciones aplicadas.
- `parameter-editing`: edición de cada parámetro con valor exacto, unidades, validación y
  restablecimiento.
- `live-telemetry`: pantalla de telemetría con la gráfica de caudal, los contadores y el estado del
  túnel.

### Modified Capabilities
- `app-branding`: paleta oficial con tema claro y oscuro, y pantalla de carga con el logo.
- `vpn-tunnel-lifecycle`: los cambios de configuración llegan desde campos de texto en lugar de
  sliders, y el tiempo activo del túnel lo fija el servicio.

## Impact
`ui/` reescrita (pantallas nuevas en `ui/screens/`, tema en `ui/theme/`), `MainViewModel`,
`MainActivity`, perfiles guardados (`profiles/`), preferencias de interfaz, recursos (`themes.xml`,
colores, icono de la pantalla de carga), `AndroidManifest.xml`, `TunnelState.Running` y
`ThrottleVpnService` (instante de arranque), `NetworkTelemetry` (formato de bytes retransmitidos),
dependencia nueva `androidx.core:core-splashscreen` 1.0.1, filtros de Kover, baseline de detekt de
`:app` (desaparece) y tests.
