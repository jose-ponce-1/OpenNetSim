# Design

## Context
Ver `proposal.md`. Las sesiones viven en el hilo del selector de cada forwarder (modelo actor).

## Goals / Non-Goals
**Goals:** que el simulador no rompa mapeos UDP ni conexiones TCP inactivas antes que una red real;
acotar los recursos.

**Non-Goals:** simular NATs agresivos (podría añadirse como degradación configurable).

## Decisions
### D1. Timeout por sesión fijado al abrirla
Cada `Session` UDP guarda su `idleTimeoutMs` (`UdpSessionPolicy.idleTimeoutFor(serverPort)`), de modo
que la limpieza no recalcula nada. La limpieza corre cada 1 s (antes 5 s) para que el DNS caduque
cerca de los 10 s.

### D2. Desalojo LRU en UDP, rechazo en TCP
Desalojar una sesión UDP es inocuo (el siguiente datagrama abre otra); cortar una conexión TCP viva sí
rompe a la app, así que en TCP se rechaza la nueva con RST. La búsqueda de la menos activa es O(n) y
solo ocurre al alcanzar el límite.

### D3. Ganchos de test
`expireIdleSessionsNow()` (interno) fuerza una pasada de caducidad en el hilo del selector, para probar
con reloj simulado sin esperar al temporizador de limpieza.

## Risks / Trade-offs
- Con 2 h de inactividad, las sesiones TCP abandonadas por las apps sin FIN/RST ocupan memoria más
  tiempo; el límite de 1024 lo acota.

## Migration Plan
Solo cambian valores por defecto. Rollback: revertir el commit.
