# Proposal

## Why
Con un valor de jitter aleatorio e independiente por paquete, la cola FIFO obliga a cada paquete a no
salir antes que el anterior; bajo carga la latencia tendía a `base + jitter/2` en vez de oscilar en
`base ± jitter` (hallazgo 8 de la auditoría). En LTE/5G la capa de enlace entrega en orden casi
siempre, y reordenar provoca ACK duplicados y retransmisiones que no reflejan una red móvil típica.
Decisión del usuario (17/11/2026): jitter sin reordenar y un control de reordenación aparte, como
`netem reorder` o Clumsy.

## What Changes
- `JitterProcess`: el desplazamiento del retardo recorre segmentos lineales entre objetivos
  aleatorios en `±jitter`, de duración mínima `2 × jitter`; nunca baja más deprisa que el tiempo, así
  que nunca reordena ni recorta la distribución.
- Nuevo parámetro `reorderPercent` (0–25 %): ese porcentaje de paquetes sale sin latencia (solo espera
  al ancho de banda) y adelanta a los encolados.
- Control "Reordenación" en la UI, con su explicación.

## Fuera de alcance
Distribuciones de jitter (normal, pareto), correlación configurable y duplicación o corrupción.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `traffic-shaping`: jitter sin reordenación y reordenación configurable.

## Impact
`core/engine/` (`JitterProcess`, `LatencyQueue`, `ThrottlingEngine`, `ImpairmentParams`),
`model/ThrottleConfig.kt`, codificación (`ConfigStore`, `TunnelIntents`), UI y tests.
