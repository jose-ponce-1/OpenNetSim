# Proposal

## Why
El microbenchmark de `add-packet-microbenchmark` mostró tres costes evitables en el camino de cada
paquete:
- Cada segmento lleno reservaba ~2,2 veces su tamaño: el payload se copiaba dos veces al parsear la
  subida y otras dos al construir la bajada.
- El checksum suponía un tercio del coste de construir un segmento.
- A 100 Mbps eso eran ~28 MB/s de basura en la bajada, que en Android presiona al GC.

El usuario pidió aplicar las tres mejoras identificadas.

## What Changes
- **Subida sin copia del payload IP:** `IpPacket` e `Ipv6Packet` guardan el payload como una vista
  (`payloadBuffer`, `payloadOffset`, `payloadSize`) sobre el paquete original. Los forwarders parsean
  TCP, UDP e ICMP directamente desde la vista. `payload` sigue existiendo y copia solo la primera vez
  que se pide. Queda una única copia, la del payload de transporte, porque el búfer de recepción TCP
  guarda ese array y el datagrama UDP se envía desde otro hilo.
- **Bajada en un único array:**
  - `TcpPacket.toIpBytes` y `UdpPacket.toIpBytes` escriben la cabecera IPv4 o IPv6 y el segmento en el
    mismo array, sin `ByteBuffer`.
  - `IpPacket.writeHeader` e `Ipv6Packet.writeHeader` producen la misma cabecera que `toByteArray`.
  - Los forwarders usan `toIpBytes`.
- **Checksum por palabras de 64 bits:** `ByteBuffer.getLong` y suma de las dos mitades de 32 bits, con
  el resultado idéntico tras el plegado de acarreos.
- Arrays vacíos compartidos para opciones y payloads ausentes.

## Fuera de alcance
- El payload de transporte sigue copiándose una vez: evitarlo exigiría que `TcpReceiveBuffer` y el
  envío UDP trabajaran con vistas.
- Las asignaciones del motor (~260 B por paquete: `Arrival`, `DelayedPacket`, la lista de drenaje y el
  boxing de `HashMap<Long, Int>`).
- Las cadenas de IP que los forwarders formatean por paquete para la clave de sesión.
- Benchmarks en dispositivo (ART).

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: los bytes que salen al TUN y al exterior son idénticos)

## Impact
`IpPacket`, `Ipv6Packet`, `TcpPacket`, `UdpPacket`, `ChecksumUtils`, `PacketBytes` e `IpFraming` (nuevos,
`internal`) en `:core`; `TcpForwarder`, `UdpForwarder` e `IcmpForwarder` en `:app`; el benchmark y un
test de equivalencia nuevo.
