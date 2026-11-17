# Proposal

## Why
La pérdida era un único valor aplicado igual en cada dirección. La norma (Network Link Conditioner,
Clumsy, `netem` por interfaz) es configurarla por separado, y las redes Wi-Fi inestables pierden en
ráfagas, no de forma independiente. Decisión del usuario (17/11/2026): sliders separados de subida y
bajada y pérdida en ráfagas.

## What Changes
- **BREAKING (modelo):** `ThrottleConfig.packetLossPercent` se sustituye por `upLossPercent` y
  `downLossPercent`. Los perfiles aplican su pérdida a ambas direcciones (sin cambios de valores).
- Pérdida en ráfagas con el modelo de Gilbert: parámetro `lossBurstLength` (1 = independiente,
  hasta 10 paquetes) que mantiene la tasa media configurada.
- UI: sliders "Pérdida de subida", "Pérdida de bajada" y "Ráfaga media"; la pérdida de ida y vuelta
  pasa a `1 − (1 − p↑)(1 − p↓)`.
- Compatibilidad: una configuración guardada con la clave antigua `loss_percent` se aplica a ambas
  direcciones.

## Fuera de alcance
Modelo de Gilbert-Elliott con pérdida parcial en ambos estados y ráfagas distintas por dirección.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `traffic-shaping`: pérdida por dirección, en ráfagas y pérdida de ida y vuelta con dos valores.

## Impact
`core/engine/PacketLossSimulator.kt`, `ImpairmentParams`, `ThrottlingEngine`,
`BidirectionalThrottlingEngine`, `model/`, `tunnel/ConfigStore.kt`, `service/TunnelIntents.kt`, UI y
tests.
