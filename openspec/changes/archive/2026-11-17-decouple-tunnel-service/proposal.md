# Proposal

## Why
`ThrottleVpnService` era una god-class (TUN, pipeline, notificación, estado, codificación de intents y
monitor de red) y, por eso, la parte más crítica del sistema no tenía ni un test. Además la UI y el
servicio se comunicaban por `StateFlow` estáticos del companion del servicio: el ViewModel importaba
`ThrottleVpnService` y la Activity construía los intents.

## What Changes
- Interfaz `TunnelController` (paquete `tunnel/`) entre la UI y el túnel: estado, telemetría, última
  configuración, `start`/`stop`/`updateConfig`. La implementa `ServiceTunnelController`; el ViewModel
  solo conoce la interfaz y la Activity solo la construye (raíz de composición).
- `TunnelPipeline` (`service/pipeline/`, sin Android): bucles del TUN, motor, forwarders y telemetría,
  sobre una abstracción `TunDevice`; se prueba en la JVM con un TUN en memoria.
- Extraídos del servicio: `TunnelIntents` (codificación de órdenes como `TunnelCommand`),
  `TunnelNotifications`, `TunnelStatusStore` (estado compartido, `internal`) y
  `ParcelFileDescriptorTunDevice`.
- El servicio queda como orquestador del ciclo de vida (361 líneas, antes 748).

## Fuera de alcance
Módulo Gradle `:core` independiente (ver design, D4) y cambios de comportamiento: esta refactorización
no cambia ningún requisito.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`)

## Impact
`service/`, `ui/MainActivity.kt`, `ui/MainViewModel.kt`, nuevo `tunnel/` y tests.
