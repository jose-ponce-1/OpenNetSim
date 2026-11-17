# Design

## Context
La puerta de calidad compara contra una baseline por módulo (`config/detekt/baseline-<módulo>.xml`):
toda infracción nueva rompe el build, pero las de la baseline no se ven. Al empezar quedaban 92 entradas
en `:core` y 25 en `:app`. Los parsers de paquetes están en el camino de cada paquete, así que el refactor
se mide con el microbenchmark (`add-packet-microbenchmark`).

## Decisions
### D1. Refactorizar el código, no relajar las reglas
- **Números mágicos:** son posiciones y máscaras de las cabeceras de los RFC. Con nombre
  (`TOTAL_LENGTH_OFFSET`, `FLAGS_SHIFT`…) el código se lee contra el RFC sin contar bytes.
- **`ReturnCount`:** la validación de la cabecera pasa a un helper y `parse` se queda en dos `return`:
  la comprobación del helper y la construcción.
- **`detekt.yml`:** no cambia.

### D2. `@Suppress` solo con un motivo escrito
`LongParameterList` se suprime en tres sitios, con el motivo en el propio `@Suppress`:
- `TcpPacket.buildPshAck` y `ChecksumUtils.calculateTransportChecksum` se llaman por cada paquete, y
  agrupar sus parámetros en un objeto reservaría memoria por paquete.
- `ThrottlePreset`: cada entrada nombra sus siete valores, y agruparlos no aclara nada.

### D3. Validadores que devuelven `Int`, no `Int?`
La primera versión de los helpers devolvía `Int?`:
- **Coste en tiempo:** obliga a empaquetar el entero (`Integer.valueOf`) en cada paquete.
- **Coste en memoria:** con la longitud de un payload IPv6 o UDP, que supera 127, se sale de la caché
  de enteros y reserva un `Integer` por paquete.

Los helpers devuelven `PacketBytes.INVALID` (−1).

### D4. Interfaz fuera de alcance
Las 18 entradas que quedan son de `Color.kt` (15 colores sin nombre) y `ThrottleDashboardScreen.kt`.
El paso 6 sustituye los sliders y la paleta, así que arreglarlas ahora sería trabajo que se tira.

## Medición
Jars de los dos commits (`7e95fe0` antes, este cambio después), ejecutados seguidos o alternados para
que el ruido del equipo afecte a ambos; JVM 17, JMH.

| Benchmark | Antes | Después | Memoria |
|---|---|---|---|
| `parseUplink` 1460 B | 216–219 ns | 213–224 ns | 1720 B, igual |
| `parseUplink` 64 B | 54–55 ns | 61–63 ns | 320 B, igual |
| Parseo IP solo (64 B) | 23,4–24,2 ns | 24,0 ns | igual |
| Parseo TCP solo (64 B) | 18,9–20,0 ns | 21,0–21,9 ns | igual |
| `buildDownlink`, `checksum`, `classify`, motor | — | ±4 %, dentro del error | igual |

- **Memoria:** idéntica en todos los benchmarks.
- **Tiempo:** el parseo TCP es ~2 ns más lento por separar la validación en un helper, y la subida de
  un segmento de 64 B, ~6 ns (~10 %). Con un segmento lleno no hay diferencia medible.
- **Descartado:** se probaron funciones `inline`, sin empaquetado y con `readU32` directo; solo la de
  sin empaquetado se mantiene, porque evita la reserva en IPv6 y UDP.
- **Impacto real:** 6 ns es menos del 0,1 % del presupuesto por paquete a 100 Mbps incluso con paquetes
  de 64 B (~8 µs), y la optimización anterior dejó la subida en la mitad de lo que costaba.
- **Mediciones descartadas:** una tanda en la que el equipo se cargó a mitad de la ejecución, en la que
  el motor, sin cambios de código, duplicaba su tiempo en ambas versiones.

## Risks / Trade-offs
- Un refactor de los parsers puede cambiar bytes. Lo cubren `PacketCodecEquivalenceTest`, que compara
  con la serialización anterior, y los tests de los parsers y forwarders.
- `UdpForwarder.openSession` y `TcpForwarder.handleWrite` están en `service/`: exigen verificación en
  dispositivo.

## Migration Plan
Sin migración. Rollback: revertir el commit.
