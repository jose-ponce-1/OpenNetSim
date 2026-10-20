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

---

## Punto de Retorno (Checkpoint)
- **Últimos archivos modificados:** Módulos `core/buffer`, `core/engine`, `core/packet`, `service/forwarder` y `service/ThrottleVpnService.kt` (Fase 6 completada).
- **Estado General:** **100% COMPLETADO**. Deuda técnica totalmente resuelta. Motor bidireccional simétrico, buffer pooling activo, soporte nativo IPv4/IPv6, ICMP ping operativo y control de flujo TCP implementado.
- **Artefacto de Distribución:** APK de depuración actualizado exitosamente en `app/build/outputs/apk/debug/app-debug.apk`.
- **Siguiente acción inmediata:** Pruebas en vivo en dispositivo físico o emulador con comandos `ping` y tráfico web concurrente.