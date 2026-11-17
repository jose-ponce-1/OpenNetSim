# Design

## Context
`TcpForwarder` pausa `OP_READ` cuando el motor de bajada está saturado. El socket exterior no fija
`SO_RCVBUF` desde que un tope fijo de 16 KB limitaba el caudal (16 KB / 80 ms ≈ 1,6 Mbps); el requisito
"Recepción del socket exterior sin tope artificial" lo recoge. Sin tope, la ventana anunciada al
servidor la decide el kernel (en el dispositivo medido, del orden de 1 MB por conexión, hasta 4,3 MB en
total con varias conexiones).

## Decisions
### D1. `SO_RCVBUF` dinámico como tope, con `TCP_WINDOW_CLAMP` al mismo valor
`TCP_WINDOW_CLAMP` solo acota la ventana que se anuncia por delante de lo ya recibido (`rcv_nxt`): los
datos que el kernel ya aceptó y el túnel no ha leído no la reducen mientras quede sitio en el búfer.
Lo único que acota esa cola es el tamaño del búfer de recepción. Medido en el dispositivo (Mi 9T,
kernel 4.14, `tcp_moderate_rcvbuf=1`, `tcp_rmem` máx. 5,5 MB) con el test de Movistar, comprobando
por inodo que la opción se aplicaba al socket correcto:

| Variante | 10 Mbps (tope 625 KB) | 50 Mbps (tope 3,1 MB) |
|---|---|---|
| Solo `TCP_WINDOW_CLAMP` (búfer autoajustado) | 1,68 MB por socket | 4,37 MB por socket |
| `TCP_WINDOW_CLAMP` + `SO_RCVBUF` fijo de 4 MB | 6,9 MB por socket | 6,6 MB por socket |
| `SO_RCVBUF` dinámico + `TCP_WINDOW_CLAMP` (elegida) | 516 KB por socket | 2,56 MB por socket |

En todas, el test alcanzó lo configurado (≈9,7 y ≈48,5 Mbps).

`SO_RCVBUF` se fija a la mitad del tope porque Linux guarda el doble (`sock_setsockopt`). Se fija con
el socket ya conectado, así que se puede subir y bajar en conexiones abiertas: lo que no se puede
cambiar después del SYN es la escala de ventana, y esa ya se negoció con el búfer por defecto. El
antiguo fallo de 16 KB era un búfer fijo antes de conectar e independiente de la configuración.

Fijar `SO_RCVBUF` desactiva el autoajuste (`SOCK_RCVBUF_LOCK`), y con él el crecimiento de
`window_clamp`, que quedaría en el valor calculado al establecer la conexión (~768 KB con el búfer por
defecto de 1 MB). Por eso `TCP_WINDOW_CLAMP` se fija al mismo valor que el tope: deja crecer la ventana
hasta él.

Android no expone la opción en `OsConstants`: se usa su valor de `linux/tcp.h` (10) con
`Os.setsockoptInt` sobre un duplicado del descriptor (`ParcelFileDescriptor.fromSocket`), que comparte
el socket del kernel. Es el mismo mecanismo con el que `VpnService.protect(Socket)` ya obtiene el
descriptor de estos sockets.

### D2. Tamaño del tope
`clamp(tasaBajada × (latencia + jitter + 500 ms), 64 KB, 4 MB)`. Los 500 ms son la ventana de cola del
motor (`ThrottlingEngine.DEFAULT_QUEUE_WINDOW_MS`): el servidor puede tener en vuelo lo que la red
simulada tendría en su cola. También cubre un RTT real de hasta 500 ms, así que el socket exterior no
limita el caudal (5 Mbps a 80 ms necesitan 50 KB; el tope es de 312 KB). Mínimo de 64 KB para tasas
bajas y máximo de 4 MB, el mismo que el tope en vuelo hacia el cliente.

### D3. Aplicar tras conectar
El tope se aplica cuando `finishConnect` termina (o `connect` conecta al instante): la escala de ventana
se negocia en el SYN sin tope y el tope puede subir después. Medido: al cambiar de 10 a 50 Mbps con el
túnel activo, las conexiones abiertas recibieron el nuevo tope. `updateDegradation` lo reajusta en todas las
sesiones abiertas desde el hilo del selector. Si el sistema rechaza la opción, se registra un aviso una
sola vez y la sesión sigue sin tope (comportamiento anterior).

### D4. Abstracción `ReceiveWindowClamp`
Interfaz en `ForwarderEnvironment` (`NONE` por defecto) con `apply(socket, bytes)` sobre el socket
conectado. En Android, `AndroidReceiveWindowClamp` (`SO_RCVBUF` y `TCP_WINDOW_CLAMP`); en los tests
JVM, un doble que registra las llamadas, así que los sockets de los tests de integración conservan el
autoajuste. Los tests JVM no pueden comprobar el efecto en el
kernel de Android: eso se verifica en dispositivo midiendo `rx_queue` en `/proc/net/tcp`.

## Risks / Trade-offs
- Al bajar el tope con la conexión abierta, la ventana ya anunciada no se retira: si el servidor la
  usa, el kernel descarta lo que no cabe y el servidor lo retransmite. Es transitorio y solo ocurre al
  bajar la velocidad.
- Lo ya encolado antes de bajar el tope se entrega al nuevo ritmo, como máximo el tope anterior.
- El búfer incluye la sobrecarga de cada segmento (`truesize`), así que la cola útil queda algo por
  debajo del tope (medido: ~82 %).
- Un RTT real mayor que latencia + jitter + 500 ms limitaría el caudal de una conexión individual.

## Migration Plan
Sin migración. Rollback: revertir el commit.
