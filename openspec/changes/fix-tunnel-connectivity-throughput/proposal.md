# Proposal

## Why

Con el túnel activo, las apps pierden la conexión de forma intermitente o total, y el caudal real
queda muy por debajo de lo configurado (p. ej. ≤ 0,44 Mbps en el perfil "Alta Latencia" configurado
a 10 Mbps). Una auditoría de extremo a extremo (`MainActivity` → `ThrottleVpnService` → forwarders →
`BidirectionalThrottlingEngine`) encontró fallos de estabilidad, de corrección TCP y de rendimiento.
Varios los introdujeron los cambios sin commitear de `develop` (vía prioritaria de control, umbral de
backpressure 8 → 64, ráfaga de 50 ms, `delay(1)` eliminado del selector TCP), así que hay que
corregirlos antes de consolidar ese trabajo.

## What Changes

**Estabilidad (pérdida total de conectividad)**
- Los bucles de I/O y drenaje del servicio sobreviven a excepciones por paquete y nunca terminan en
  silencio (C1).
- El motor nunca bloquea la cola de forma permanente por un paquete mayor que la ráfaga del token
  bucket (C2).
- El forwarder TCP notifica con RST al cliente cada vez que descarta una sesión (timeout, error de
  I/O) y responde con RST a segmentos de sesiones desconocidas (C4).

**Corrección TCP**
- Un FIN con datos entrega esos datos; un FIN nunca adelanta a datos del mismo flujo que aún están
  en cola (C3).
- Tras el EOF del servidor, la sesión se mantiene hasta que el cliente confirma los datos pendientes
  y el FIN (C5).
- El SYN-ACK y el FIN emitidos hacia el cliente se retransmiten si se pierden (H6).
- Se envía una actualización de ventana cuando el búfer de escritura deja de estar saturado (H9).
- El RTO se adapta al RTT medido y el temporizador no dispara retransmisiones espurias tras
  inactividad (H5).

**Rendimiento (caudal por debajo de lo configurado)**
- La ventana en vuelo hacia el cliente se dimensiona según su ventana anunciada y el BDP configurado,
  no con un tope fijo de 32 KB (H1).
- El SYN-ACK anuncia MSS y window scale (H7); el búfer de recepción del socket exterior deja de
  limitar el caudal (H8).
- El selector TCP no gira en vacío cuando las lecturas están pausadas (H2).
- El lector del TUN no deja de leer ACKs, DNS ni SYN porque la cola de subida esté llena (H3).
- La profundidad de cola se acota por tiempo de cola a la tasa configurada, no por número fijo de
  paquetes, y el tráfico DNS no queda detrás de transferencias masivas (H4).

**Robustez y diagnóstico**
- El registro de canales UDP en el selector no bloquea el hilo que despacha el uplink (M1).
- IPv6 solo se enruta por el túnel si la red física tiene conectividad IPv6; si no, se bloquea para
  que las apps caigan a IPv4 al instante (M2).
- Logging estructurado de fallos de conexión, sockets y bucles, con limitación de frecuencia (M3).
- La UI refleja el estado real del servicio aunque `establish()` falle (M4); los cambios de sliders
  se aplican con debounce (M5).
- Todo el estado de una sesión TCP se modifica desde un único hilo (M6).
- Menos asignaciones y un solo parseo por paquete en el hot-path (M7).
- El VPN se declara no medido (`setMetered(false)`) (M8).
- La telemetría distingue goodput de retransmisiones y separa subida y bajada (M9).

## Capabilities

### New Capabilities
- `tcp-forwarding`: terminación TCP en espacio de usuario entre el cliente del TUN y el socket
  exterior: handshake y opciones, cierre ordenado y RST, retransmisión, control de ventana y
  serialización del estado por sesión.
- `udp-forwarding`: reenvío de datagramas UDP: ciclo de vida de sesiones, registro no bloqueante en
  el selector, manejo de destinos inalcanzables y de datagramas grandes.
- `traffic-shaping`: pipeline bidireccional de degradación (token bucket, cola de latencia/jitter,
  pérdida): orden de paquetes de control, paquetes mayores que la ráfaga, acotación de cola por
  tiempo, ausencia de bloqueo HOL y telemetría de caudal.
- `vpn-tunnel-lifecycle`: arranque, configuración y parada del `VpnService`: familias de direcciones
  según la red física, resiliencia de los bucles, sincronización de estado con la UI, aplicación de
  cambios de configuración y diagnóstico.

### Modified Capabilities
<!-- No hay specs principales en openspec/specs/. -->

> Nota: el cambio `fix-uplink-bandwidth` (completo, sin archivar) define la capacidad
> `uplink-throttling` con requisitos de ráfaga mínima MTU, FIFO estricto, cola acotada y reensamblado
> TCP. `traffic-shaping` y `tcp-forwarding` los complementan sin contradecirlos; la cola acotada pasa
> a medirse en tiempo de cola. Conviene archivar `fix-uplink-bandwidth` antes de archivar este cambio.

## Impact

- **`core/engine/`**: `ThrottlingEngine`, `TokenBucketLimiter`, `LatencyQueue`,
  `BidirectionalThrottlingEngine` (paquetes sobredimensionados, orden de control, cola por tiempo,
  métricas separadas). Sigue siendo Kotlin puro.
- **`core/tcp/` (nuevo)**: estado de conexión TCP puro y testeable (secuencias, ventana, RTO,
  retransmisión) extraído de `TcpForwarder`.
- **`service/`**: `ThrottleVpnService` (TUN condicional IPv6, `setMetered`, bucles resilientes, lector
  sin HOL, despacho de un solo hilo por sesión), `TcpForwarder`, `UdpForwarder`, nuevo logger con
  limitación de frecuencia.
- **`core/packet/`**: `TcpPacket` con opciones MSS/WS en el SYN-ACK y parseo de opciones del SYN.
- **`ui/`**: `MainViewModel` / `MainActivity` (estado derivado del servicio, debounce de config).
- **`model/`**: `NetworkTelemetry` con goodput y caudal por dirección.
- **Tests (`app/src/test`)**: nuevos tests del estado TCP, del motor (paquete sobredimensionado, orden
  de FIN, cola por tiempo) y de integración del forwarder sobre loopback.
- Sin cambios de dependencias ni de versiones en `build.gradle.kts`.
