# Tasks

> Fases (ver design.md, Migration Plan): grupos 1–3 = estabilidad; 4–6 = núcleo y corrección TCP;
> 7–8 = rendimiento y UDP; 9–11 = ciclo de vida, UI y telemetría; 12 = integración.
> Al cerrar cada grupo: `.\gradlew.bat compileDebugKotlin` y `.\gradlew.bat testDebugUnitTest` en verde.

## 1. Preparación y diagnóstico

- [x] 1.1 Comprobar con `git status` que el trabajo previo de `develop` en `app/` está commiteado; si no lo está, pedir al usuario que decida (commitear o descartar) antes de seguir, y verificar que `git status` queda limpio en `app/`
- [x] 1.2 Crear la interfaz `NetDiagnostics` en `service/diag/` con implementación Android (etiqueta `OpenNetSim`, límite de N entradas/s por clave y contador de suprimidas) y una no-op para tests; verificar con un test JVM que la limitación de frecuencia suprime y cuenta las entradas repetidas (spec vpn-tunnel-lifecycle: "Ráfaga de errores repetidos")

## 2. Token bucket con déficit (C2)

- [x] 2.1 Cambiar `TokenBucketLimiter.tryConsume` para aceptar un paquete con tokens ≥ `min(tamaño, ráfaga)` y descontar el tamaño completo con saldo negativo; ajustar `calculateWaitTimeNanos` para incluir la deuda; verificar con tests en `TokenBucketLimiterTest` que un paquete de 3000 B a 128 kbps sale tras ~190 ms y que el ritmo medio de 100 paquetes coincide con la tasa ±2 %
- [x] 2.2 Añadir a `ThrottlingEngineTest` el escenario "Paquete de 3000 bytes a 128 kbps" (paquete grande seguido de pequeños: todos salen, en orden) y verificar que pasa

## 3. Resiliencia del servicio (C1)

- [x] 3.1 En `ThrottleVpnService`, envolver el procesamiento de cada paquete de `ioJob`, `uplinkDrainJob` y `downlinkDrainJob` en `try/catch` que relanza `CancellationException`, registra con `NetDiagnostics` y continúa; verificar leyendo el código que ningún `try` envuelve ya el `while` completo
- [x] 3.2 Tratar `IOException` de lectura/escritura del descriptor del TUN como fatal (detener el túnel y publicar fallo) y añadir un `CoroutineExceptionHandler` a `serviceScope` que registra y detiene el túnel; verificar con `compileDebugKotlin` y revisando que ninguna corrutina del servicio queda sin manejador
- [x] 3.3 Registrar con `NetDiagnostics` los fallos de `establish()` y de `addDisallowedApplication`; verificar que los `catch` vacíos de `ThrottleVpnService` han desaparecido (`grep` de bloques `catch` sin cuerpo)

## 4. Modelo actor en los forwarders (D1, M1, M6)

- [x] 4.1 En `UdpForwarder`, mover `createSession`/`register` al hilo del selector mediante una cola de tareas pendientes con `wakeup()` en la transición de vacía a no vacía; verificar con un test JVM sobre loopback (protector falso) que 20 altas seguidas completan en < 5 ms cada una desde el hilo llamante (spec udp-forwarding: "Ráfaga de consultas DNS")
- [x] 4.2 En `TcpForwarder`, hacer que `processOutbound*` solo encole el segmento parseado y que el bucle del selector procese esa cola, las altas y todos los cambios de `interestOps`; verificar con un test que ninguna operación sobre `SelectionKey` se ejecuta fuera del hilo del selector (aserción de hilo en modo test)
- [x] 4.3 Inyectar `NetDiagnostics` en ambos forwarders y registrar los fallos de `connect`/`finishConnect`/`protect`/lectura/escritura con protocolo, destino y causa; verificar que no quedan `catch` vacíos en `service/forwarder/`

## 5. Núcleo TCP puro en `core/tcp/` (D2–D8)

- [x] 5.1 Ampliar `TcpPacket` para parsear las opciones MSS y window scale del SYN y para construir el SYN-ACK con MSS (MTU − 40 / MTU − 60) y window scale solo si el cliente lo ofreció; verificar con tests en `PacketTest` (escenarios "Cliente IPv4 con window scale" y "Cliente sin window scale")
- [x] 5.2 Añadir un constructor de RST para segmentos de sesión desconocida según RFC 793 (con y sin ACK en el segmento entrante) y verificar con tests que los números de secuencia son los esperados en ambos casos
- [x] 5.3 Implementar el estimador RTO (RFC 6298, Karn, mínimo 200 ms, máximo 60 s, backoff) en `core/tcp/` y verificar con tests que no se muestrean segmentos retransmitidos y que el RTO converge al RTT simulado
- [x] 5.4 Implementar `TcpConnection` (estados, reensamblado, mapa de no confirmados con SYN-ACK y FIN incluidos, `clientFinSeq` pendiente, `serverFinPending`, ventana en vuelo `min(rwnd << wscale, topeBDP)`, actualización de ventana tras saturación) devolviendo acciones; verificar con tests JVM que cubren cada escenario de la spec tcp-forwarding: retransmisión de SYN-ACK, SYN duplicado, FIN con payload, FIN adelantado, último segmento perdido antes del cierre, FIN perdido, sesión inactiva, respuesta tras inactividad, perfil de alta latencia, cliente con ventana pequeña, subida tras ventana cero
- [x] 5.5 Calcular `topeBDP = clamp(2 × tasaBajada × (latencia + jitter) + 64 KB, 64 KB, 4 MB)` a partir de la configuración y recalcularlo al cambiarla; verificar con un test que 10 Mbps y 600 ms dan un tope ≥ 1,5 MB

## 6. Integración de `TcpConnection` en `TcpForwarder` (C3, C4, C5, H1, H2, H5, H6, H8, H9)

- [x] 6.1 Sustituir la lógica TCP de `TcpForwarder` por llamadas a `TcpConnection`, traduciendo sus acciones a I/O; verificar con un test de integración sobre loopback (`ServerSocketChannel` local y protector falso) que una petición y una respuesta de 1 MB llegan completas y en orden
- [x] 6.2 Pausar lecturas quitando `OP_READ` y reanudarlas al avanzar un ACK o cuando el motor señale capacidad; usar `select(timeout)` con el próximo deadline acotado a [5 ms, 250 ms]; verificar en el test de integración que, con la ventana del cliente llena, el bucle del selector no hace más de ~200 iteraciones por segundo
- [x] 6.3 Enviar RST en toda eliminación de sesión (inactividad 10 min establecida / 60 s en cierre / 30 s en SYN_RCVD, error de I/O, fallo de apertura o conexión) y a segmentos de sesiones desconocidas; verificar con tests de integración los escenarios "Sesión inactiva expirada", "Error de escritura en el socket exterior", "Fallo al abrir el socket exterior" y "Reutilización de una conexión del pool ya expirada"
- [x] 6.4 Eliminar `SO_RCVBUF` fijo y acotar las escrituras pendientes a 256 KB por sesión; verificar con el test de integración que una subida de 1 MB contra un servidor lento llega completa sin superar ese límite
- [x] 6.5 Contar los bytes retransmitidos por el forwarder y exponerlos para la telemetría; verificar con un test que una retransmisión forzada incrementa el contador

## 7. Planificador de dos carriles y lector del TUN (C3, H3, H4, M7)

- [x] 7.1 Añadir `PacketMeta` en `core/packet/` (versión, protocolo, 5-tupla, flags TCP, longitud de payload, offsets) parseado sin copiar payload, y un `flowId` derivado de la 5-tupla; verificar con tests de IPv4/IPv6 TCP/UDP/ICMP
- [x] 7.2 Reescribir `ThrottlingEngine.processIncoming`/`drainReady` con carril interactivo y carril masivo, orden por flujo (contador de paquetes masivos por `flowId`), cota masiva por bytes `max(2 × MTU, tasa × (500 ms + latencia + jitter))` con tail-drop (corregida durante la implementación, ver design D10), cota interactiva de 128 paquetes, y eliminar la liberación inmediata prioritaria; verificar con tests en `ThrottlingEngineTest` los escenarios "FIN detrás de datos limitados por ancho de banda", "ACK de otro flujo", "Descargas paralelas a 128 kbps" y "Consulta DNS durante una descarga"
- [x] 7.3 Exponer en el motor una señal de capacidad para la bajada (por debajo del 75 % de la cota masiva) y actualizar `BidirectionalThrottlingEngine` y sus tests; verificar con `BidirectionalThrottlingEngineTest` que la señal cambia al cruzar el umbral
- [x] 7.4 En `ThrottleVpnService`, eliminar el `delay` por contrapresión de `ioJob`, parsear una sola vez con `PacketMeta`, clasificar el paquete (interactivo: ACK/FIN/RST sin datos, ICMP, DNS) y despachar hacia los forwarders solo desde `uplinkDrainJob`; verificar con `compileDebugKotlin` y revisando que `dispatchOutboundPacket` tiene un único llamante
- [x] 7.5 Hacer que `downlinkDrainJob` avise al forwarder TCP cuando el motor recupera capacidad; verificar con el test de integración de 6.2 que las lecturas pausadas se reanudan
- [x] 7.6 Añadir a los tests del motor el escenario de pérdida "Pérdida del 5 % en ambas direcciones" (tasa medida 5 % ± 1 % sobre todos los paquetes, incluidos los de control) y verificar que pasa

## 8. UDP: datagramas grandes e inalcanzables

- [x] 8.1 Leer datagramas UDP con un búfer de 64 KB de un pool dedicado y emitir cada respuesta como un único paquete IP entero sin fragmentar (longitud total IPv4 / payload IPv6 ≤ 65.535 bytes; si no cabe, descartar y registrar); verificar con un test de loopback que una respuesta de 3000 B sale como un solo paquete IP con el datagrama completo
- [x] 8.2 Hacer que un fallo de escritura en el TUN de un paquete mayor que el MTU solo descarte ese paquete y lo registre, sin detener el túnel (excepción a la tarea 3.2, design D12); verificar revisando el código y con `compileDebugKotlin`
- [x] 8.3 Añadir constructores de ICMP tipo 3 e ICMPv6 tipo 1 en `IcmpPacket` y emitirlos (como tráfico interactivo) cuando `connect`/`write`/`read` fallen por red o puerto inalcanzable; verificar con tests de construcción y un test de loopback contra un puerto cerrado (escenario "Puerto cerrado")

## 9. Ciclo de vida del túnel (M2, M8, C1)

- [x] 9.1 Publicar `StateFlow<TunnelState>` (`Stopped`, `Starting`, `Running(config)`, `Failed(reason)`) desde el servicio y emitir `Failed` cuando `establish()` falle o un bucle sea fatal; verificar con `compileDebugKotlin` y revisando que todos los caminos de `startThrottling`/`stopThrottling` emiten un estado
- [ ] 9.2 Antes de `establish()`, decidir IPv6 según `LinkProperties` de la red por defecto (ruta por defecto IPv6 + dirección global); añadir dirección, ruta y DNS IPv6 solo en ese caso; llamar a `setUnderlyingNetworks` y, en API 29+, `setMetered(false)`; verificar en un emulador solo IPv4 que `ip -6 route` dentro del dispositivo no muestra ruta por el túnel y que `curl -6` falla de inmediato
- [x] 9.3 Registrar un `NetworkCallback` de la red por defecto que reconstruye el túnel cuando cambia la disponibilidad de IPv6, y desregistrarlo al parar; verificar alternando el emulador entre redes (o con un test del decisor de familias extraído como función pura)

## 10. UI y aplicación de configuración (M4, M5)

- [x] 10.1 Derivar `isActive` y los mensajes de error de `MainViewModel` de `TunnelState`, sin asignación optimista; mostrar `Failed(reason)` en el snackbar; verificar con un test del ViewModel (con un `StateFlow` falso) que un `Failed` vuelve el interruptor a apagado
- [x] 10.2 Sustituir `LaunchedEffect(uiState.config)` por un flujo del ViewModel `config.distinctUntilChanged().debounce(150 ms)` recogido por la actividad, que envía la actualización solo si el túnel está `Running` y el valor difiere del activo; verificar con un test del ViewModel que 2 s de cambios continuos producen ≤ 10 emisiones y la última es el valor final

## 11. Telemetría (M9)

- [x] 11.1 Añadir a `NetworkTelemetry` `downBytesPerSecond`, `upBytesPerSecond` y `retransmittedBytes`, calcularlos en el bucle de telemetría (bajada sin retransmisiones) y actualizar `NetworkTelemetryTest`; verificar con el test el escenario "Descarga con retransmisiones"
- [ ] 11.2 Mostrar caudal de bajada y subida por separado en `ThrottleDashboardScreen` y hacer que el texto de estado del túnel refleje si IPv6 está enrutado; verificar con `compileDebugKotlin` y una captura en emulador

## 12. Integración y verificación final

- [ ] 12.1 Ejecutar `.\gradlew.bat ktlintCheck detekt lintDebug` y corregir los avisos introducidos; verificar que los tres terminan sin errores
- [x] 12.2 Ejecutar `.\gradlew.bat testDebugUnitTest --continue` y `.\gradlew.bat assembleDebug`; verificar que todos los tests pasan y que existe `app/build/outputs/apk/debug/app-debug.apk`
- [ ] 12.3 Prueba manual en emulador solo IPv4 y en dispositivo físico con cada preset: navegación web, una descarga larga (medir caudal frente al configurado, objetivo ≥ 80 % en "Alta Latencia"), una subida, `ping` y una app con conexión persistente inactiva más de 60 s; verificar que no hay cortes ni conexiones colgadas y anotar los resultados
- [x] 12.4 Actualizar `project-state.md` con una nueva fase que resuma este cambio y su checkpoint; verificar que el archivo refleja el estado final

## Workflow follow-up

- Archivar antes `fix-uplink-bandwidth` (completo) para evitar solapes de capacidades.
- Archivar este cambio con `/opsx:archive` tras la revisión y verificar las specs resultantes en `openspec/specs/`.
