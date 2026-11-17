# Design

## Context
Cada dirección tiene dos carriles FIFO; un paquete sale en `max(llegada + retardo, salida anterior)`.

## Goals / Non-Goals
**Goals:** latencia en `base ± jitter` también bajo carga; orden preservado salvo la reordenación
pedida.

**Non-Goals:** reproducir exactamente `netem`.

## Decisions
### D1. Jitter continuo
Para que el FIFO nunca recorte, el retardo `d(t)` debe cumplir `d(t₂) ≥ d(t₁) − (t₂ − t₁)`: pendiente
≥ −1. Un camino lineal entre objetivos uniformes en `[−A, A]` con segmentos de al menos `2A` ms tiene
pendiente en `[−1, 1]`. Paquetes muy seguidos ven retardos parecidos (como una red real) y los
separados más de un segmento (p. ej. `ping` cada segundo) ven valores independientes. Tras cuatro
segmentos sin tráfico el proceso se reinicia en lugar de recorrerlos.
- *Alternativa:* AR(1) correlacionado. Descartada: con alta tasa de paquetes la caída entre muestras
  puede superar el tiempo transcurrido y el FIFO vuelve a sesgar.

### D2. Reordenación como en `netem`
Un paquete reordenado se programa en su instante de llegada, sin la latencia y sin el recorte FIFO, y
no actualiza la "salida anterior" del carril. Requiere latencia > 0 para tener efecto.

## Risks / Trade-offs
- La distribución del desplazamiento no es exactamente uniforme (interpolación entre uniformes); su
  rango sí es `±A` y su media 0.

## Migration Plan
Configuraciones guardadas sin `reorder_percent` se leen con 0 %.
