# Proposal

## Why
La release no aplicaba R8 (APK de 16,6 MB), no tenía firma, la notificación usaba un icono genérico del
sistema y la tarjeta informativa afirmaba algo falso: "No se envían paquetes fuera de tu dispositivo",
cuando la app reenvía el tráfico a su destino real.

## What Changes
- Release con `isMinifyEnabled` y `isShrinkResources` (R8). `proguard-rules.pro` conserva
  `ThrottlePreset`, que se guarda por su nombre.
- Firma de release desde propiedades de Gradle (`openNetSimRelease*`) fuera del repositorio; sin ellas
  `assembleRelease` genera un APK sin firmar.
- Icono propio de la notificación (`ic_stat_opennetsim`, silueta del logo).
- Texto de la tarjeta informativa corregido.
- La CI compila la release (`:app:assembleRelease`) para detectar problemas de R8 en cada PR.

## Fuera de alcance
Publicación en tiendas, App Bundle (`.aab`), firma en la CI y `versionCode` automático.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: no cambia el comportamiento de la simulación)

## Impact
`app/build.gradle.kts`, `app/proguard-rules.pro`, `res/drawable/ic_stat_opennetsim.xml`,
`TunnelNotifications.kt`, `ThrottleDashboardScreen.kt`, `config/detekt/baseline-app.xml`,
`.github/workflows/ci.yml` y documentación.
