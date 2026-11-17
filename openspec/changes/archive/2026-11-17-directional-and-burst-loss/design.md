# Design

## Context
`PacketLossSimulator` hacía un sorteo de Bernoulli por paquete; cada dirección tiene el suyo.

## Goals / Non-Goals
**Goals:** pérdida independiente por dirección; ráfagas sin cambiar la tasa media.

**Non-Goals:** ajustar perfiles existentes (las condiciones de QA no cambian).

## Decisions
### D1. Modelo de Gilbert de dos estados
En el estado "malo" se pierde todo y en el "bueno" nada. Con tasa `p` y ráfaga media `L`:
`P(malo → bueno) = 1/L` y `P(bueno → malo) = p / (L(1 − p))`, cuya probabilidad estacionaria del
estado malo es `p`. `L = 1` usa el sorteo independiente de siempre (con Gilbert y `L = 1` las
pérdidas nunca serían consecutivas).

### D2. Una sola ráfaga para ambas direcciones
La ráfaga describe el tipo de enlace (p. ej. interferencias); el porcentaje sí es por dirección.
Mantiene la UI en tres controles.

### D3. Agrupar parámetros en `ImpairmentParams`
La degradación de una dirección ya tenía seis parámetros: un `data class` evita listas largas y
centraliza la traducción desde `ThrottleConfig` (`uplinkParams`/`downlinkParams`). La sobrecarga
antigua de `createPipeline` se mantiene para los tests.

### D4. `DashboardActions`
La pantalla pasaba un callback por slider; con los nuevos controles se agrupan en un
`onConfigChange((ThrottleConfig) -> ThrottleConfig)`, de modo que cada control solo cambia su campo.

## Risks / Trade-offs
- Con ráfagas largas y pérdida alta, TCP puede encadenar timeouts: es el comportamiento que se quiere
  simular.

## Migration Plan
`ThrottleConfigCodec` lee `loss_percent` si faltan las claves nuevas; `lossBurstLength` y
`reorderPercent` toman 1 y 0 por defecto.
