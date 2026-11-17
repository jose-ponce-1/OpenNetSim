# Proposal

## Why
Las baselines de detekt guardaban la deuda previa a la puerta de calidad: 106 entradas en `:core` y 32
en `:app`. Cada entrada es un aviso que nadie ve. Tras la optimización del camino de paquetes quedaban
92 y 25, casi todas números mágicos en los parsers de paquetes (posiciones de bytes del RFC sin nombre)
y funciones con demasiados `return`. Es el último punto del paso 5 de la hoja de ruta.

## What Changes
- **Parsers de `:core`:** `IpPacket`, `Ipv6Packet`, `TcpPacket`, `UdpPacket`, `IcmpPacket` y
  `ChecksumUtils` usan constantes con nombre para los desplazamientos y las máscaras de cada cabecera,
  y `PacketBytes.readU16`/`readU32`/`slice` en lugar de componer los bytes a mano.
- **Validación aparte:** la validación de cada cabecera pasa a un helper que devuelve `Int` con
  `PacketBytes.INVALID`, no `Int?`, para no empaquetar el entero en cada paquete. Así `parse` tiene
  dos `return`.
- **Sin `ByteBuffer`:** `IpPacket.toByteArray` e `Ipv6Packet.toByteArray` escriben sobre el array,
  como `writeHeader`.
- **Excepciones:** `Ipv6Packet.formatIpv6` captura `UnknownHostException` en lugar de `Exception`, y
  los bucles de los selectores nombran `ignored` la `ClosedSelectorException`, que es su fin normal.
- **Bucles y funciones de `:app`:**
  - Los bucles de `LatencyQueue.drainReady`, `TcpForwarder.handleWrite` y `UdpForwarder.handleSelectedKeys`
    quedan con un solo salto.
  - `UdpForwarder.openSession` se divide en `openChannel` y `connectSession`.
  - El test largo de `TcpForwarderTest` se divide con helpers.
- **`@Suppress` justificados:** en `buildPshAck` y `calculateTransportChecksum`, porque agrupar sus
  parámetros reservaría un objeto por paquete, y en `ThrottlePreset`, porque cada preset da sus
  valores con nombre.
- **Baselines:** la de `:core` desaparece. La de `:app` queda con 18 entradas, todas de `Color.kt` y
  `ThrottleDashboardScreen.kt`.

## Fuera de alcance
- `Color.kt` y `ThrottleDashboardScreen.kt`: el paso 6 (nueva interfaz y paleta oficial) los reescribe.
- Cambiar las reglas de `detekt.yml`.
- Corregir que `IpPacket` serialice los flags por defecto (0x40) como 0: se documenta en el código y
  no cambia ningún byte.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: refactor sin cambios de comportamiento; los bytes serializados son los
  mismos, como comprueba `PacketCodecEquivalenceTest`)

## Impact
Parsers de `core/packet`, `LatencyQueue`, `ThrottlePreset`, `TcpForwarder`, `UdpForwarder`, tests de
`:core` y `:app` y `config/detekt/baseline-*.xml`.
