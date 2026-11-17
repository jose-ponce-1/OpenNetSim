# Tasks

## 1. `:core`
- [x] 1.1 Regenerar las baselines para partir de la deuda real tras la optimización; verificar el recuento (92 en `:core`, 25 en `:app`)
- [x] 1.2 Constantes con nombre y `PacketBytes` en `IpPacket`, `Ipv6Packet`, `TcpPacket`, `UdpPacket`, `IcmpPacket` y `ChecksumUtils`, con la validación en helpers que devuelven `PacketBytes.INVALID`; verificar con `:core:test` (incluido `PacketCodecEquivalenceTest`) y `:core:detekt`
- [x] 1.3 `LatencyQueue.drainReady` con un solo salto, `@Suppress` justificado en `ThrottlePreset`, y tests de `:core` sin avisos; verificar con `:core:detekt` y la baseline de `:core` vacía
- [x] 1.4 Borrar `baseline-core.xml`; verificar que `:core:detekt` termina bien sin ella

## 2. `:app`
- [x] 2.1 `ClosedSelectorException` como `ignored` en los dos selectores, `handleWrite` y `handleSelectedKeys` con un solo salto, `openSession` dividido en `openChannel` y `connectSession`, y `TcpForwarderTest` con helpers; verificar con `:app:testDebugUnitTest` y `:app:detekt` sin esas entradas en la baseline
- [x] 2.2 Regenerar `baseline-app.xml`; verificar que solo quedan las 18 entradas de `Color.kt` y `ThrottleDashboardScreen.kt`

## 3. Verificación
- [x] 3.1 Benchmark antes y después con los jars de los dos commits; verificar que la memoria por paquete no cambia y anotar el tiempo en el design (resultado: +2 ns en el parseo TCP, +6 ns en la subida de 64 B, sin diferencia con 1460 B)
- [x] 3.2 Ejecutar la puerta de calidad completa; verificar que termina bien
- [x] 3.3 Verificar que la CI de GitHub Actions termina en verde (PR #14)
- [x] 3.4 Verificar en dispositivo con la release (cambian los forwarders): navegar, test de velocidad, QUIC/UDP y ping (usuario, 17/11/2026: funciona correctamente)
