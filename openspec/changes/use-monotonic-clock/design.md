# Design

## Context
Ver `proposal.md`. Había 19 usos de `System.currentTimeMillis()` en motor y forwarders; el token bucket
ya era monotónico, así que cola y bucket podían divergir.

## Goals / Non-Goals
**Goals:** un único reloj monotónico inyectable para todo lo que mide intervalos; tests con reloj
simulado sin `Thread.sleep`.

**Non-Goals:** cambiar la semántica de latencia, jitter o ancho de banda.

## Decisions
### D1. `fun interface MonotonicClock { fun nowNanos(): Long }`
El método abstracto es en nanosegundos (lo que necesita el token bucket) y `nowMs()` se deriva. Un
`fun interface` permite pasar una lambda en los tests (`MonotonicClock { simulatedNanos }`).
- *Alternativa:* dos lambdas (`timeSourceMs`, `timeSourceNanos`), como antes. Descartada: es justo lo
  que permitía relojes distintos en cola y bucket.

### D2. Valor por defecto `MonotonicClock.SYSTEM`
Los constructores mantienen un valor por defecto, pero monotónico. `createPipeline` y `fromConfig`
pasan el mismo reloj a todos los componentes de un pipeline.

### D3. El reloj viaja en `ForwarderEnvironment`
Los forwarders ya reciben sus dependencias comunes en `ForwarderEnvironment`; añadir `clock` allí evita
parámetros nuevos en cada constructor y permite al pipeline compartirlo con el motor.

## Risks / Trade-offs
- `System.nanoTime()` tiene un origen arbitrario (puede ser negativo en la JVM). Ningún código usa 0
  como "sin valor" para instantes reales; los centinelas existentes solo se leen con la cola no vacía.

## Migration Plan
Cambio interno sin datos persistidos. Rollback: revertir el commit.
