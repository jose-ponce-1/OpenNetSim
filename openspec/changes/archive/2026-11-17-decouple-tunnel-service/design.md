# Design

## Context
Ver `proposal.md`.

## Goals / Non-Goals
**Goals:** que la UI no dependa del servicio Android; que el camino de los paquetes se pruebe en la JVM;
una responsabilidad por clase.

**Non-Goals:** cambiar comportamiento (salvo lo que introduce `fix-tunnel-config-sync`).

## Decisions
### D1. `TunnelController` como único contrato de la UI
Es la convención de Android (repositorio/fuente de datos detrás de una interfaz inyectada en el
ViewModel). El permiso VPN sigue en la UI porque necesita una Activity; el ViewModel decide cuándo
pedirlo (`requestVpnPermission`) y arranca el túnel al recibir el resultado.
- *Alternativa:* bind al servicio con un `Binder`. Descartada: un `VpnService` ya lo vincula el
  sistema y el estado debe sobrevivir a la Activity.

### D2. `TunnelPipeline` con `ForwarderEnvironment` y `Listener`
El pipeline recibe un `TunDevice` ya establecido, el entorno de los forwarders (protector, ámbito,
reloj, diagnóstico) y avisa de fallos fatales y telemetría por `Listener`. Crea su propio
`SupervisorJob` hijo, así `close()` cancela todo lo suyo. El fallo fatal se notifica una sola vez y
nunca después de `close()`. Sustituye al contador de generación del servicio: el servicio ignora los
avisos de un pipeline que ya no es el actual.

### D3. Estado compartido `internal`
`TunnelStatusStore` es un objeto `internal` del paquete `service`: el servicio escribe y
`ServiceTunnelController` lee. Ninguna otra capa puede escribir el estado.

### D4. Módulo `:core` (pendiente)
Hoy "`core/` sin Android" es una convención que el compilador no impone porque todo está en `:app`.
Un módulo Kotlin/JVM puro `:core` haría fallar la compilación ante un `import android`. Se deja para
un cambio propio: mueve paquetes, la baseline de detekt y la configuración de tests.

## Risks / Trade-offs
- Más archivos; cada uno con una responsabilidad clara.

## Migration Plan
Refactorización interna; rollback revirtiendo el commit.
