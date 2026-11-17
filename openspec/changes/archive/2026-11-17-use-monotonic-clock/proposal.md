# Proposal

## Why
El motor, los forwarders y los timers medían el tiempo con `System.currentTimeMillis()` mientras el
token bucket usaba `System.nanoTime()`. Si la hora del sistema retrocede, los paquetes encolados quedan
programados en el "futuro" y el FIFO por carril bloquea todo lo que llega después (el túnel se
congela); si avanza, todas las conexiones TCP superan sus timeouts y reciben RST y las sesiones UDP se
cierran. Cambiar la hora a mano es habitual en QA.

## What Changes
- Nueva abstracción pura `MonotonicClock` en `core/time/` (`nowNanos()`, `nowMs()`, `SYSTEM` sobre
  `System.nanoTime()`, `ofMillis` para relojes simulados).
- `LatencyQueue`, `TokenBucketLimiter`, `ThrottlingEngine` y `BidirectionalThrottlingEngine` reciben
  el reloj por inyección; `createPipeline` garantiza que cola, carril interactivo y token bucket
  comparten el mismo reloj. Desaparecen los valores por defecto de reloj de pared.
- `ForwarderEnvironment.clock`: `TcpForwarder`, `UdpForwarder`, la telemetría del pipeline y la
  limitación de frecuencia del diagnóstico usan el reloj monotónico.
- Se elimina código muerto de `LatencyQueue` (`maxCapacity`, `isFull`).

## Fuera de alcance
Marcas de tiempo mostradas al usuario (no hay ninguna hoy).

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `traffic-shaping`: se añade el requisito de temporización monotónica.
- `udp-forwarding`: se añade la caducidad de sesiones independiente de la hora del sistema.

## Impact
`core/time/` (nuevo), `core/engine/*`, `service/forwarder/*`, `service/pipeline/TunnelPipeline.kt`,
`service/diag/NetDiagnostics.kt` y los tests del motor. Sin dependencias nuevas.
