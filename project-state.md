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
- [x] Verificación en dispositivo con `app-debug.apk` (usuario, 17/11/2026: todo funciona) y CI de GitHub Actions en verde; los cinco cambios archivados en `openspec/changes/archive/2026-11-17-*`.

### Fase 9: Fidelidad de la simulación (rama `feat/network-fidelity`)
Decisiones del usuario (17/11/2026), tomadas comparando con `tc netem`, Network Link Conditioner y Clumsy.
- [x] `forward-real-icmp`: el ping va a la red real con sockets de ping sin privilegios (RTT real + degradación; un host que no responde no responde). Se elimina `IcmpResponder`.
- [x] `smooth-jitter-and-reordering`: jitter continuo que nunca reordena ni sesga la latencia bajo carga (hallazgo 8) y control aparte de reordenación (0–25 %).
- [x] `directional-and-burst-loss`: pérdida de subida y de bajada por separado y en ráfagas (modelo de Gilbert, ráfaga media 1–10); migración de configuraciones guardadas.
- [x] 175 tests JVM en verde (1 omitido en Windows), `detekt`, `lintDebug` y `assembleDebug` sin errores.
- [x] Verificación en dispositivo con `app-debug.apk` (usuario, 17/11/2026: todo funciona) y CI en verde; los tres cambios archivados en `openspec/changes/archive/2026-11-17-*`.

### Fase 10: Módulo `:core` y version catalog (cambio OpenSpec `extract-core-module`, rama `refactor/core-module`)
- [x] `core/` y `model/` en el módulo Gradle `:core`, Kotlin/JVM puro: un `import android.*` ya no compila.
- [x] Version catalog `gradle/libs.versions.toml` (mismas versiones); detekt configurado en el script raíz con una baseline por módulo (127 entradas, antes 142, ninguna nueva).
- [x] 175 tests (115 en `:core`, 60 en `:app`, 1 omitido en Windows), `detekt`, `lintDebug` y `assembleDebug` en verde.
- [x] CI de GitHub Actions en verde con los dos módulos (PR #6); cambio archivado.
- [x] La CI destapó una carrera en `UdpForwarder.close()` y `TcpForwarder.close()` (cerraban el selector desde otro hilo: NullPointerException del JDK en `AbstractSelector.deregister`). Ahora, como en `IcmpForwarder`, se libera todo en el hilo del bucle; test de estrés añadido. Verificado en dispositivo.

### Fase 11: Actualización de AGP, Kotlin y detekt (cambio OpenSpec `upgrade-toolchain`, PR #7)
- [x] AGP 8.7.3 → 9.3.3, Kotlin 2.0.21 → 2.4.21 (Kotlin integrado de AGP 9, sin `kotlin-android`), detekt 1.23.7 → 1.23.8; Gradle 9.7.1 se mantiene. Combinación dentro de las matrices oficiales.
- [x] 176 tests (1 omitido en Windows), `detekt`, `lintDebug` y `assembleDebug` en verde.
- [x] CI de GitHub Actions en verde y verificación en dispositivo; cambio archivado.

### Fase 12: Preparar la release (cambio OpenSpec `release-prep`, PR #10)
- [x] R8 y reducción de recursos en release (APK de 16,6 MB a ~1 MB); `ThrottlePreset` conservado porque se guarda por su nombre. La CI compila la release.
- [x] Firma desde propiedades de Gradle `openNetSimRelease*` fuera del repositorio; sin ellas, release sin firmar.
- [x] Icono propio en la notificación y texto de la tarjeta informativa corregido.
- [x] Keystore de release creado por el usuario fuera del repositorio; `app-release.apk` firmado (RSA 4096, esquema v2).
- [x] Release firmada verificada en dispositivo (túnel, cambios en caliente, configuración conservada, icono).
- [x] CI en verde (incluye la compilación de release con R8, PR #10); cambio archivado.

---

## Hoja de Ruta
1. ~~Fidelidad de la simulación~~ (Fase 9).
2. ~~Módulo `:core` y version catalog~~ (Fase 10).
3. ~~Actualizar AGP, Kotlin y Gradle~~ (Fase 11). Queda un aviso de Gradle 10 que viene del plugin de detekt 1.23.8: se resuelve con detekt 2.0 cuando sea estable. Pendiente aparte: Compose BOM, AndroidX y corrutinas.
4. ~~Preparar la release~~ (Fase 12).
5. Rendimiento y deuda: microbenchmark del camino de cada paquete antes de optimizar, reducir las baselines de detekt y medir cobertura con Kover.
   - Investigar (observado por el usuario el 17/11/2026 con la release): si se cancela el test de velocidad de Google a medias y se repite enseguida, mide menos de lo configurado; si se deja terminar, el siguiente es normal. Hipótesis por comprobar: datos de las conexiones canceladas que siguen en la cola del motor o en los búferes del forwarder TCP y consumen el ancho de banda hasta vaciarse.
6. Nueva interfaz (pedido por el usuario el 17/11/2026):
   - Sustituir los sliders por campos para escribir el valor exacto (ancho de banda en kbps/Mbps, como otras apps; también latencia, jitter y porcentajes).
   - Paleta de colores oficial de OpenNetSim. Colores del logo encontrados en `res/drawable` (pendientes de que el usuario confirme los oficiales o envíe prototipos): `#0067C6`, `#00C4FF`, `#67E8F9` y `#FFFFFF`.
   - Pantalla de inicio con el logo (API SplashScreen de Android 12 mediante `androidx.core:core-splashscreen` para API 26+).

---

## Punto de Retorno (Checkpoint)
- **Últimos archivos modificados:** `app/build.gradle.kts`, `app/proguard-rules.pro`, `ic_stat_opennetsim.xml`, `TunnelNotifications.kt`, `ThrottleDashboardScreen.kt`, `.github/workflows/ci.yml` y documentación (Fase 12, rama `feat/release-prep`).
- **Estado General:** Fases 1–11 en `develop` y `main`; Fase 12 verificada (local, CI y dispositivo), pendiente de fusionar (PR #10).
- **Artefacto de Distribución:** APK de depuración en `app/build/outputs/apk/debug/app-debug.apk`; release firmada en `app/build/outputs/apk/release/app-release.apk` (keystore del usuario, fuera del repositorio).
- **Siguiente acción inmediata:** fusionar el PR #10; después, el paso 5 de la hoja de ruta (empezando por el caso del test de velocidad cancelado).
