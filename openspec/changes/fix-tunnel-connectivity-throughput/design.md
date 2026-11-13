# Design

## Context

Ver `proposal.md` (Why) para la motivación y los identificadores de fallo (C1–C5, H1–H9, M1–M9).
Estado actual relevante para el enfoque:

- `ThrottleVpnService` tiene tres corrutinas que tocan los forwarders: `ioJob` (lee el TUN y, si
  el motor libera al instante, despacha), `uplinkDrainJob` y `downlinkDrainJob`. El estado de una
  sesión TCP lo mutan a la vez el hilo del TUN, el de drenaje y el hilo del selector de
  `TcpForwarder`.
- `TcpForwarder` mezcla I/O NIO con la lógica TCP (secuencias, reensamblado, retransmisión,
  ventana); no tiene tests. `core/` sí tiene tests JVM.
- `ThrottlingEngine` usa una sola `PriorityQueue` ordenada por instante de liberación; los paquetes
  "prioritarios" (ACK/FIN/RST vacíos, ICMP) entran con su propio instante y pueden adelantar a datos
  del mismo flujo. El drenaje se detiene si la cabeza no tiene tokens.
- La copia de trabajo de `develop` tiene cambios sin commitear en motor, servicio y forwarder TCP;
  este diseño parte de ese estado.
- Restricciones del proyecto: `core/` sin dependencias de Android; buffers reutilizados en el
  hot-path; sin cambios de dependencias; `testDebugUnitTest` obligatorio tras tocar el motor.

## Goals / Non-Goals

**Goals:**
- Una única hebra de ejecución por forwarder para todo el estado de sesión y toda operación sobre
  `SelectionKey`.
- Lógica TCP pura y testeable en JVM, separada del I/O.
- Planificador del motor que preserve el orden por flujo, priorice tráfico interactivo y nunca se
  bloquee.
- Fases independientes y entregables: estabilidad → corrección TCP → rendimiento → diagnóstico/UI.

**Non-Goals:**
- SACK, timestamps TCP o control de congestión propio hacia el cliente (el cliente ya lo tiene).
- Reenvío ICMP real hacia Internet (se mantiene el respondedor local de ping).
- Persistir sesiones TCP a través de una reconfiguración del TUN.
- Cambiar los valores de los presets.

## Decisions

### D1. Modelo actor: el hilo del selector es dueño de las sesiones
`processOutbound*` de TCP y UDP ya no mutan sesiones: encolan el paquete parseado en una cola MPSC
del forwarder y hacen `selector.wakeup()` solo si la cola estaba vacía (flag atómico). El bucle del
selector drena esa cola, las altas de canal (`register`) y los cambios de `interestOps` antes de cada
`select`. Resuelve M1, M6 y la `CancelledKeyException` cruzada de C1.
- *Alternativa:* `synchronized` por sesión. Descartada: no arregla el bloqueo de `register()` ni las
  carreras con `SelectionKey`, y añade contención en el hot-path.

### D2. Estado TCP puro en `core/tcp/`
Nueva clase `TcpConnection` (Kotlin puro): recibe eventos (`onClientSegment`, `onServerData`,
`onServerEof`, `onTimer`, `onWritableSpace`) y devuelve acciones (segmentos a emitir hacia el
cliente, bytes a escribir al socket, cerrar/RST, próximo deadline). `TcpForwarder` solo traduce
acciones a I/O. Contiene: máquina de estados (SYN_RCVD, ESTABLISHED, CLOSE_WAIT/FIN_WAIT, CLOSING,
CLOSED), reensamblado, FIN pendiente por secuencia, mapa de segmentos sin confirmar, estimador RTO y
cálculo de ventana.
- *Alternativa:* corregir `TcpForwarder` in situ. Descartada: sin tests no es verificable y es la
  zona con más fallos.

### D3. Handshake y opciones (H7, retransmisión de SYN-ACK)
- Parsear MSS y window scale del SYN del cliente. Anunciar MSS = MTU − 40 (IPv4) / MTU − 60 (IPv6);
  anunciar window scale 7 solo si el cliente lo ofreció. El tamaño de segmento hacia el cliente es
  `min(MSS del cliente, MSS propio)`.
- El SYN-ACK entra en el mapa de no confirmados como un "segmento" de longitud 1 (consume ISN), así
  que la retransmisión es la misma lógica que para datos.
- SYN duplicado con el mismo ISN → reenviar el SYN-ACK; con ISN distinto → RST de la sesión vieja y
  sesión nueva.

### D4. Cierre ordenado (C3, C5)
- FIN del cliente: se registra `clientFinSeq`; se aplica (`shutdownOutput` + ACK) cuando
  `clientExpectedSeq` alcanza ese valor tras el reensamblado. Un FIN con payload pasa primero el
  payload por la vía normal de datos.
- EOF del servidor: estado `serverFinPending`; el FIN se emite tras el último segmento leído, ocupa
  una secuencia y se retransmite como cualquier segmento. La sesión se elimina cuando ambos FIN están
  confirmados o tras 30 s en estado de cierre (enviando RST).

### D5. RST (C4)
- Sesiones descartadas (inactividad, error de I/O, fallo de apertura/conexión) → RST al cliente con
  `seq = serverSeq` y `ack = clientExpectedSeq`.
- Segmento sin SYN/RST de una sesión desconocida → RST según RFC 793: si trae ACK, `seq = SEG.ACK` sin
  flag ACK; si no, `seq = 0`, `ack = SEG.SEQ + SEG.LEN` con RST|ACK. Nunca se responde a un RST.
- Tiempos de inactividad por estado: 10 min establecida, 60 s en cierre, 30 s en SYN_RCVD. Se sube
  el valor actual (60 s) porque los pools HTTP y las conexiones push viven minutos.

### D6. RTO (H5)
RFC 6298: SRTT/RTTVAR con algoritmo de Karn (no se muestrea RTT de segmentos retransmitidos), RTO
mínimo 200 ms, máximo 60 s, backoff exponencial. El temporizador se arma al enviar el primer
segmento sin confirmar y se rearma en cada avance de ACK. El selector usa `select(timeout)` con el
próximo deadline de cualquier sesión (acotado a [5 ms, 250 ms]) en vez de 2 ms fijos.

### D7. Ventana en vuelo y lectura del socket exterior (H1, H2, H8)
- Bytes en vuelo hacia el cliente ≤ `min(ventana del cliente << wscale, topeBDP)`, con
  `topeBDP = clamp(2 × tasaBajada × (latencia + jitter) + 64 KB, 64 KB, 4 MB)`, recalculado al
  cambiar la configuración.
- Cuando una sesión no puede leer (ventana llena o el motor de bajada no admite más), se **quita
  `OP_READ`** de su clave y se guarda en un conjunto de pausadas; se reanuda al avanzar un ACK o
  cuando el motor señala capacidad (el bucle de drenaje de bajada avisa al forwarder vía su cola).
  Se elimina así el giro en vacío.
- No se fija `SO_RCVBUF` (autoajuste del kernel); la contrapresión viene de no leer.

### D8. Actualización de ventana (H9)
Al vaciar escrituras pendientes, si la última ventana anunciada era < ½ del máximo, se emite un ACK
con la ventana nueva. Las escrituras pendientes se acotan a 256 KB por sesión.

### D9. Token bucket con déficit (C2)
`tryConsume` acepta un paquete si los tokens disponibles ≥ `min(tamaño, ráfaga)`, y descuenta el
tamaño completo, permitiendo saldo negativo. El tiempo de espera del siguiente paquete incluye esa
deuda, así que el ritmo medio es exacto y un paquete grande no detiene la cola.
- *Alternativa:* subir la ráfaga a 64 KB. Descartada: permite ráfagas irreales a tasas bajas.

### D10. Planificador de dos carriles con orden por flujo (C3, H3, H4)
- El motor recibe por paquete un `flowId: Long` opaco (hash de la 5-tupla calculado en `service/`) y
  una clase: `INTERACTIVE` (ACK/FIN/RST sin datos, ICMP, DNS) o `BULK`.
- Dos colas FIFO ordenadas por instante de liberación (latencia + jitter): interactiva y masiva.
  El drenaje sirve primero la interactiva; ambas consumen tokens del mismo bucket.
- Orden por flujo: si un `flowId` tiene paquetes en la cola masiva, sus paquetes "interactivos" van
  a la cola masiva detrás de ellos (contador por flujo). Así un FIN/RST/ACK nunca adelanta datos de
  su propio flujo, pero sí los de otros.
- Cota de la cola masiva por bytes: `max(2 × MTU, tasa × (500 ms + latencia + jitter))` de la
  dirección; al superarla → tail-drop (subida) o pausa de lectura (bajada TCP, umbral 75 %). Cola
  interactiva acotada a 128 paquetes. La ventana de 500 ms es un valor fijo decidido, no un punto
  pendiente de ajuste.
  - *Corrección durante la implementación:* la cota inicial `max(16 × MTU, tasa × 500 ms)` era
    incorrecta en dos sentidos. (1) La cola también retiene los bytes que solo esperan la latencia
    simulada, así que sin sumar la latencia el tope limitaba el caudal con latencias altas (≈ 75 % a
    1 s de RTT, por debajo del 80 % de la spec). (2) El suelo de 16 MTU equivale a 1,5 s de cola a
    128 kbps e incumplía el escenario "Descargas paralelas a 128 kbps"; 2 MTU es el mínimo para tener
    un paquete en serialización y otro esperando.
- La vía prioritaria con `Released` inmediato desaparece. Además, el servicio encola siempre
  (`allowImmediateRelease = false`) y solo los bucles de drenaje despachan o escriben, despertados por
  un canal al encolar: así nunca se reordenan paquetes de un mismo flujo entre el hilo productor y el
  de drenaje. Los tests del motor pueden seguir usando la liberación inmediata.
- *Alternativa:* una sola cola FIFO estricta. Descartada: DNS y ACKs quedarían detrás de descargas
  (H4) y se perdería la mejora de interactividad.

### D11. Lector del TUN sin esperas (H3)
`ioJob` nunca hace `delay` por contrapresión: el motor encola o descarta. Todo despacho hacia los
forwarders sale de `uplinkDrainJob` (un solo productor por forwarder, compatible con D1). El lector
parsea la cabecera una sola vez en un `PacketMeta` sin copiar payload (versión, protocolo, 5-tupla,
flags, offsets) que viaja con el paquete (M7).

### D12. Datagramas grandes e ICMP inalcanzable (udp-forwarding)
- Lectura UDP con búfer de 64 KB del pool (pool dedicado de tamaño mayor).
- Un datagrama de respuesta se escribe en el TUN como un único paquete IP entero, aunque supere el
  MTU, sin fragmentar. Límite: longitud total IPv4 ≤ 65.535 bytes y campo de longitud de payload
  IPv6 ≤ 65.535 bytes; un datagrama que no quepa se descarta y se registra con `NetDiagnostics`.
- Excepción a D14: si la escritura en el TUN de un paquete mayor que el MTU falla con
  `IOException`, solo se descarta ese paquete y se registra; no se detiene el túnel.
- *Alternativa:* fragmentar IPv4 (DF a 0) e IPv6 (cabecera de fragmento). Descartada: añade un
  reensamblado que no hace falta si el kernel acepta el paquete entero, y más superficie de fallos.
- `connect`/`write`/`read` fallidos por red inalcanzable o puerto inalcanzable → construir ICMP tipo
  3 (código 0/1/3) o ICMPv6 tipo 1 (código 0/4) con la cabecera original y entregarlo como tráfico
  interactivo.

### D13. Familias de direcciones y red no medida (M2, M8)
- Antes de `establish()`, leer `LinkProperties` de la red por defecto: IPv6 entra en el túnel solo
  si hay una ruta por defecto IPv6 y una dirección IPv6 global. Si no, no se añade dirección, ruta ni
  DNS IPv6 (Android bloquea esa familia y las apps caen a IPv4).
- `setUnderlyingNetworks(arrayOf(red))` y, en API 29+, `setMetered(false)`.
- `NetworkCallback` sobre la red por defecto: si cambia la disponibilidad de IPv6, se reconstruye el
  túnel completo (forwarders y motor nuevos; las conexiones se reinician).

### D14. Resiliencia de bucles (C1)
- Cada iteración procesa un paquete dentro de `try/catch (e: Exception)` que vuelve a lanzar
  `CancellationException`, registra el error (D16) y continúa.
- `IOException` al leer/escribir el descriptor del TUN se considera fatal: detiene el servicio y
  publica `Failed`.
- `serviceScope` incluye un `CoroutineExceptionHandler` que registra y detiene el túnel en vez de
  tumbar el proceso.

### D15. Estado del túnel hacia la UI (M4, M5)
- El servicio publica `StateFlow<TunnelState>` = `Stopped | Starting | Running(config) |
  Failed(reason)`. `MainViewModel` deriva `isActive` de él; no hay asignación optimista.
- El `LaunchedEffect(uiState.config)` se sustituye por un flujo del ViewModel:
  `config.distinctUntilChanged().debounce(150 ms)` que la actividad recoge y envía solo si el túnel
  está `Running` y el valor difiere del `activeConfig` del servicio.

### D16. Diagnóstico (M3)
Interfaz `NetDiagnostics` en `service/diag/` con implementación Android (`android.util.Log`, etiqueta
`OpenNetSim`) limitada a N entradas/s por clave de error con contador de suprimidas, y una
implementación no-op para tests JVM. Se inyecta en forwarders y servicio. `core/` no registra:
devuelve resultados.

### D17. Telemetría (M9)
El motor expone bytes liberados por dirección; el forwarder TCP cuenta bytes retransmitidos.
`NetworkTelemetry` añade `downBytesPerSecond`, `upBytesPerSecond` y `retransmittedBytes`; el caudal
de bajada mostrado resta los retransmitidos del intervalo.

## Risks / Trade-offs

- [Reescritura TCP introduce regresiones] → `TcpConnection` con tests de escenarios de la spec y un
  test de integración sobre loopback (`ServerSocketChannel` local y protector falso) antes de
  sustituir la lógica antigua.
- [Más paquetes despertando el selector] → `wakeup()` solo en transición de cola vacía a no vacía;
  medir CPU con una descarga a 10 Mbps.
- [La ventana grande aumenta la memoria] → tope de 4 MB por sesión y escrituras pendientes ≤ 256 KB;
  el tope BDP solo crece con perfiles de alta latencia y ancho de banda.
- [Bloquear IPv6 en redes solo IPv4 oculta tráfico IPv6 a la simulación] → es el comportamiento que
  tendría el dispositivo sin túnel; se documenta en la notificación/README.
- [Reconstruir el TUN al cambiar de red corta las conexiones] → evento raro; preferible a un agujero
  negro IPv6.
- [El debounce retrasa 150 ms los cambios] → imperceptible frente a latencias simuladas de 100+ ms.
- [Aplicar pérdida a los paquetes de control reduce el caudal efectivo] → es lo configurado; la
  retransmisión de SYN-ACK/FIN evita cuelgues.
- [El kernel o el cliente podrían rechazar paquetes mayores que el MTU escritos en el TUN] → la
  escritura fallida solo descarta ese paquete (D12) y queda registrada; para la app equivale a una
  pérdida y aplica sus propios reintentos.

## Migration Plan

1. Commitear (o descartar conscientemente) el trabajo sin commitear de `develop` antes de aplicar el
   cambio, para que cada fase tenga un diff limpio.
2. Implementar por fases, con un commit por fase y `compileDebugKotlin` + `testDebugUnitTest` en
   verde al final de cada una: (1) estabilidad C1/C2/C4, (2) núcleo TCP y corrección C3/C5/H5/H6/H9,
   (3) rendimiento H1/H2/H3/H4/H7/H8/M1/M6/M7, (4) ciclo de vida, diagnóstico, UI y telemetría.
3. Al terminar: `ktlintCheck detekt lintDebug`, `assembleDebug` y pruebas manuales en dispositivo y
   emulador solo IPv4.
4. Rollback: revertir el commit de la fase afectada; las fases 1 y 4 son independientes del núcleo
   TCP.
