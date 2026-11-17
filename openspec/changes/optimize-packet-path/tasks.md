# Tasks

## 1. Subida sin copia
- [x] 1.1 Vista del payload en `IpPacket` e `Ipv6Packet` (`payloadBuffer`, `payloadOffset`, `payloadSize`) con `payload` copiado bajo demanda; verificar con `PacketCodecEquivalenceTest` que `parse` no copia (también con desplazamiento) y que un paquete construido devuelve su propio array
- [x] 1.2 `TcpForwarder`, `UdpForwarder` e `IcmpForwarder` parsean desde la vista; verificar con `:app:testDebugUnitTest`

## 2. Bajada en un único array
- [x] 2.1 `IpPacket.writeHeader`, `Ipv6Packet.writeHeader`, `IpFraming` y `toIpBytes` en `TcpPacket` y `UdpPacket`, sin `ByteBuffer`; verificar con `PacketCodecEquivalenceTest` que los bytes coinciden con la serialización anterior (TCP y UDP, IPv4 e IPv6, payloads pares e impares) y que el test falla con una mutación (TTL 63, puntero urgente desplazado)
- [x] 2.2 Los forwarders usan `toIpBytes`; verificar con `:app:testDebugUnitTest`

## 3. Checksum
- [x] 3.1 Medir palabras de 32 bits frente a 16 (resultado: sin mejora) y `ByteBuffer.getLong` (resultado: 10 veces más rápido); verificar con un benchmark temporal de las tres variantes
- [x] 3.2 Checksum con `getLong`; verificar con `PacketCodecEquivalenceTest` frente a la implementación de 16 bits (longitudes 0–200, desplazamientos 0–3, datos a 0xFF)

## 4. Verificación
- [x] 4.1 Benchmark antes y después con los jars de los dos commits, seguidos y con 2 forks; verificar que los benchmarks sin cambios (`classify`, `enqueueAndDrain`) quedan a ±2 % y anotar la medición en el design
- [x] 4.2 Ejecutar la puerta de calidad completa (compilación, `detekt`, `lintDebug`, tests, Kover, `smokeBenchmark` y `assembleRelease`); verificar que termina bien
- [ ] 4.3 Verificar que la CI de GitHub Actions termina en verde
- [ ] 4.4 Verificar en dispositivo con la release (cambia `service/`): navegar, test de velocidad a 10 y 50 Mbps (TCP), vídeo o test por QUIC (UDP), ping (ICMP) e IPv6 si la red lo ofrece
