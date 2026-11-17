# Design

## Context
`TcpForwarder` pausa `OP_READ` cuando el motor de bajada está saturado. El socket exterior no fija
`SO_RCVBUF` desde que un tope fijo de 16 KB limitaba el caudal (16 KB / 80 ms ≈ 1,6 Mbps); el requisito
"Recepción del socket exterior sin tope artificial" lo recoge. Sin tope, la ventana anunciada al
servidor la decide el kernel (en el dispositivo medido, del orden de 1 MB por conexión, hasta 4,3 MB en
total con varias conexiones).

## Decisions
### D1. `TCP_WINDOW_CLAMP` y no `SO_RCVBUF`
`TCP_WINDOW_CLAMP` (Linux) acota la ventana anunciada sin desactivar el autoajuste del búfer y admite
cambios con la conexión establecida. `SO_RCVBUF` habría que fijarlo antes de conectar (la escala de
ventana se negocia en el SYN), desactiva el autoajuste y no se podría ajustar en conexiones abiertas.
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
se negocia en el SYN sin tope y el tope puede subir después. `updateDegradation` lo reajusta en todas las
sesiones abiertas desde el hilo del selector. Si el sistema rechaza la opción, se registra un aviso una
sola vez y la sesión sigue sin tope (comportamiento anterior).

### D4. Abstracción `ReceiveWindowClamp`
Interfaz en `ForwarderEnvironment` (`NONE` por defecto): en Android, `AndroidReceiveWindowClamp`; en
los tests JVM, un doble que registra las llamadas. Los tests JVM no pueden comprobar el efecto en el
kernel de Android: eso se verifica en dispositivo midiendo `rx_queue` en `/proc/net/tcp`.

## Risks / Trade-offs
- Según el código de Linux (`tcp_rcv_space_adjust`), si el autoajuste agranda el búfer también sube
  `window_clamp`. Solo ocurre cuando la app lee deprisa; con la bajada limitada el túnel lee al ritmo
  configurado, así que no debería superar el tope. Se comprueba en dispositivo; si no se cumple, se
  estudia re-aplicar el tope al pausar las lecturas.
- Un RTT real mayor que latencia + jitter + 500 ms limitaría el caudal de una conexión individual.

## Migration Plan
Sin migración. Rollback: revertir el commit.
