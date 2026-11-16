# Estado del Proyecto: OpenNetSim Android

## Objetivo
Aplicación Android moderna (Kotlin + Jetpack Compose) que simula condiciones de red deficientes (3G, Edge, alta latencia, pérdida de paquetes) redirigiendo el tráfico local mediante un `VpnService` en bucle local sin requerir permisos de superusuario (root).

## Lista de Tareas

### Fase 1: Esqueleto del Proyecto y UI de Control
- [x] Inicializar proyecto Android con Gradle KTS y Jetpack Compose.
- [x] Diseñar pantalla principal con controles:
  - Selector de perfiles (3G lento, Wi-Fi inestable, Personalizado, etc.).
  - Sliders para Ancho de Banda (Upload / Download en kbps/Mbps).
  - Sliders para Latencia adicional (ms) y Jitter (ms).
  - Slider de Pérdida de paquetes (0% a 50%).
  - Interruptor principal (ON/OFF) con estado reactivo.

### Fase 2: Motor de Throttling (Core Engine)
- [x] Implementar algoritmo Token Bucket (`TokenBucketLimiter.kt`) para limitar tasa de bytes por segundo.
- [x] Implementar cola de retardo (`LatencyQueue.kt`) con marca de tiempo para simular retardo en el reenvío de paquetes.
- [x] Implementar generador de pérdida aleatoria (`PacketLossSimulator.kt`).
- [x] Escribir tests unitarios exhaustivos para el motor en JVM (sin emulador).

### Fase 3: Integración de `VpnService`
- [x] Configurar `ThrottleVpnService.kt` extendiendo de `VpnService`.
- [x] Configurar canal de notificación y llamada obligatoria a `startForeground()`.
- [x] Configurar el constructor `Builder` del TUN: asignar IP virtual local (ej. `10.0.0.2`), máscara de subred y rutas DNS.
- [x] Implementar bucle de procesamiento de paquetes conectando el descriptor de archivo TUN con el motor de throttling.

### Fase 4: Métricas en Tiempo Real y Cierre
- [x] Exponer telemetría en tiempo real hacia la UI mediante Kotlin Flows (Bytes leídos, Bytes throttled, paquetes descartados).
- [x] Validar compilación final del APK con el workflow de verificación.

### Fase 5: Resolución de Blackhole & Reenvío Userspace Real (L3/L4 Pure Kotlin)
- [x] Diseñar e implementar submódulo de paquetes IP/TCP/UDP (`com.opennetsim.core.packet`):
  - `ChecksumUtils.kt` (Internet Checksum RFC 1071 y pseudo-cabeceras IPv4).
  - `IpPacket.kt` (Parser y serializador IPv4).
  - `UdpPacket.kt` (Parser y serializador UDP con cálculo de checksum).
  - `TcpPacket.kt` (Parser y serializador TCP con flags SYN/ACK/FIN/RST/PSH y checksum).
  - Suite de tests unitarios completa (`PacketTest.kt`).
- [x] Implementar `VpnSocketProtector.kt` para abstraer la protección de sockets contra bucles de enrutamiento.
- [x] Implementar `UdpForwarder.kt` con Java NIO Selector y sockets protegidos para resolución DNS y tráfico UDP.
- [x] Implementar `TcpForwarder.kt` con Java NIO Selector, TCB (Transmission Control Block) no bloqueante y conexión a sockets físicos protegidos.
- [x] Refactorizar `ThrottleVpnService.kt`:
  - Desacoplar salida: los paquetes del TUN se envían a los forwarders físicos correspondientes.
  - Sincronizar inyección al TUN con bloqueo thread-safe.
  - Conectar el flujo de descarga externa hacia `ThrottlingEngine` antes de la entrega al TUN.
- [x] Validación completa de compilación y ejecución de tests unitarios (`testDebugUnitTest` y `assembleDebug`).

### Fase 6: Resolución Integral de Deuda Técnica & Optimización Arquitectural
- [x] Gestión de Memoria y Buffer Pooling (`com.opennetsim.core.buffer.ByteBufferPool`):
  - Búferes reciclables sin contención implementados con `ConcurrentLinkedQueue`.
  - Eliminación de GC churn y asignaciones de arrays en el hot-path del bucle TUN.
  - Suite de tests unitarios completa (`ByteBufferPoolTest.kt`).
- [x] Motor de Degradación Bidireccional Asimétrico (`BidirectionalThrottlingEngine`):
  - Desacoplamiento de pipeline de subida `uplinkEngine` (`upKbps`) y bajada `downlinkEngine` (`downKbps`).
  - Distribución simétrica de latencia y jitter (RTT / 2 en subida y RTT / 2 en bajada).
  - Telemetría y métricas acumuladas consolidadas.
  - Suite de tests unitarios completa (`BidirectionalThrottlingEngineTest.kt`).
- [x] Soporte Dual-Stack IPv6 (RFC 2460 / RFC 8200) e ICMP Ping (RFC 792 / RFC 4443):
  - `Ipv6Packet.kt` (parser y serializador de cabeceras fijas de 40 bytes).
  - `ChecksumUtils.kt` con cálculo de pseudo-cabecera IPv6 para TCP, UDP e ICMPv6.
  - `IcmpPacket.kt` e `IcmpResponder.kt` respondiendo a pings con latencia y packet loss sin requerir root.
  - Interfaz TUN configurada con subred `fd00::2/64`, rutas `::/0` y DNS IPv6.
  - Tests unitarios completos (`Ipv6PacketTest.kt` e `IcmpPacketTest.kt`).
- [x] Terminación TCP Robusta en Espacio de Usuario (`TcpForwarder.kt`):
  - Handshake sincronizado con el socket exterior (`finishConnect()`), evitando falsos SYN-ACK.
  - Control de flujo por ventana deslizante dinámica (anuncio adaptativo y Zero-Window).
  - Filtrado y deduplicación de retransmisiones del cliente.
  - Soporte transparente para conexiones salientes tanto IPv4 como IPv6.
- [x] Validación completa de compilación y ejecución de tests unitarios (`testDebugUnitTest` y `assembleDebug`).

### Fase 7: Conectividad y Caudal del Túnel (cambio OpenSpec `fix-tunnel-connectivity-throughput`)
- [x] Estabilidad: bucles del servicio resilientes a errores por paquete, token bucket con déficit (un paquete mayor que la ráfaga ya no bloquea la bajada) y RST en toda sesión TCP descartada o desconocida.
- [x] Núcleo TCP puro en `core/tcp/` (`TcpConnection`, RTO RFC 6298 con Karn, reensamblado, FIN ordenado, retransmisión de SYN-ACK/FIN, ventana según BDP, sonda de ventana cero) y `TcpForwarder` reducido a capa de I/O.
- [x] Opciones MSS y window scale en el SYN-ACK; sin `SO_RCVBUF` fijo; selector sin giro en vacío.
- [x] Planificador de dos carriles (interactivo / masivo) con orden por flujo y cota de cola por tiempo `max(2 × MTU, tasa × (500 ms + latencia + jitter))`; el lector del TUN nunca espera.
- [x] UDP: alta de sesiones sin bloqueo (modelo actor), datagramas de hasta 64 KB entregados enteros e ICMP de destino inalcanzable.
- [x] Ciclo de vida: `TunnelState` real hacia la UI, IPv6 solo si la red física lo tiene (`NetworkCallback`), `setMetered(false)`, debounce de sliders, telemetría por dirección sin retransmisiones y diagnóstico en Logcat (`OpenNetSim`).
- [x] 126 tests JVM en verde (1 omitido en Windows: ICMP por loopback) y `assembleDebug` correcto.
- [x] Verificación en dispositivo (tareas 9.2, 11.2 y 12.3): `app-debug.apk` validado por el usuario el 16/11/2026, sin problemas detectados.
- [x] Lint en verde (ver Fase 8). Cambio archivado en `openspec/changes/archive/2026-11-16-fix-tunnel-connectivity-throughput`.

### Fase 8: Correcciones de la auditoría (rama `fix/audit-corrections`)
- [x] Higiene de OpenSpec: conflicto `uplink-throttling` ↔ `traffic-shaping` resuelto (delta REMOVED), 5 cambios archivados y specs principales en `openspec/specs/`; contexto y reglas en `openspec/config.yaml`.
- [x] `use-monotonic-clock`: `MonotonicClock` único para motor, token bucket, forwarders, telemetría y diagnóstico.
- [x] `fix-tunnel-config-sync`: el servicio es la fuente de verdad de la configuración; persistencia (`ConfigStore`), restauración tras reinicio y VPN siempre activa, `onRevoke()` con motivo explícito; la UI muestra la pérdida de ida y vuelta.
- [x] `align-session-timeouts-and-limits`: UDP 300 s (DNS 10 s), TCP establecida 2 h 4 min, límite de 1024 sesiones TCP/UDP.
- [x] `decouple-tunnel-service`: `TunnelController` entre UI y servicio; `TunnelPipeline` sin Android y probado en JVM; servicio de 748 a 361 líneas.
- [x] `add-quality-gate`: un solo formateador (detekt-formatting), baseline de detekt, sin BOM, `.editorconfig`, `kotlinx-coroutines-test` y GitHub Actions.
- [x] 152 tests JVM en verde (1 omitido en Windows), `detekt` y `lintDebug` sin errores.
- [ ] Verificación en dispositivo de los cinco cambios (última tarea de cada `tasks.md`) y primera ejecución de la CI en GitHub; después, archivarlos con `/opsx:archive`.

---

## Punto de Retorno (Checkpoint)
- **Últimos archivos modificados:** `core/time/` (nuevo), `core/engine/`, `tunnel/` (nuevo), `service/` (servicio, `pipeline/` nuevo, forwarders), `ui/`, `config/detekt/`, `.github/workflows/ci.yml` (Fase 8, sin commitear en `fix/audit-corrections`).
- **Estado General:** Fases 1–7 cerradas; Fase 8 implementada y probada en JVM, pendiente de verificación en dispositivo.
- **Artefacto de Distribución:** APK de depuración en `app/build/outputs/apk/debug/app-debug.apk`.
- **Siguiente acción inmediata:** Probar en dispositivo las tareas pendientes de los cinco cambios abiertos (reapertura con perfil custom, `adb shell am kill`, otra VPN activa, VPN siempre activa, cambio de hora del sistema, websocket inactivo 30 min, ráfaga DNS) y archivarlos.
