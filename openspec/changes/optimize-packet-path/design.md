# Design

## Context
Medición de referencia en `add-packet-microbenchmark` (JVM 17). Por segmento lleno de 1460 B:
- **Subida:** 451 ns y 3216 B.
- **Bajada:** 1122 ns y 3160 B.
- **Checksum:** 379 ns.

## Decisions
### D1. Vista del payload IP
`IpPacket` e `Ipv6Packet` dejan de ser `data class`: el constructor acepta `payload` (como antes) más
`payloadOffset` y `payloadSize`, con valores por defecto que ocupan el array entero.
- **Parseo:** `parse` pasa el paquete original y el desplazamiento, sin copiar.
- **Copia perezosa:** `payload` devuelve un array propio, que se copia la primera vez que se pide y
  se guarda; si la vista ocupa todo el array, no se copia.
- **Compatibilidad:** los constructores y los tests no cambian, porque nadie usaba `copy()` ni la
  desestructuración.
- **Forwarders:** parsean con `parse(payloadBuffer, payloadOffset, payloadSize)`. Es seguro porque el
  parseo de transporte es síncrono y copia su payload antes de que el array del TUN pueda usarse para
  otra cosa.

### D2. Serialización en un único array
- **Escritura directa:** `toIpBytes` reserva el paquete IP completo (`IpFraming`), escribe la cabecera
  (`writeHeader`) y después el segmento con su checksum, sin `ByteBuffer`.
- **Misma cabecera:** `writeHeader` es la que genera `toByteArray` con los valores por defecto
  (identificación 0, flags 0, TTL 64; IPv6 con clase de tráfico y etiqueta de flujo 0, hop limit 64).
  Lo comprueba `PacketCodecEquivalenceTest`.
- **API anterior:** `toIpPacket()` y `toIpv6Packet()` se mantienen para los tests y el código que
  necesita el objeto.

### D3. Checksum con `ByteBuffer.getLong`
- **Descartado, palabras de 32 bits uniendo bytes:** medido sin mejora (426 ± 63 ns frente a 379).
  Carga y enmascara los mismos bytes y solo ahorra vueltas, que HotSpot ya ahorraba desenrollando.
- **Elegido, `getLong`:** HotSpot lo compila como una carga de 8 bytes. Sumar las dos mitades de
  32 bits equivale a sumar las cuatro palabras de 16 bits, porque `2^16 ≡ 1 (mod 2^16 - 1)` y el
  plegado final de acarreos es el mismo.
- **Sin desbordamiento:** cada vuelta suma menos de 2^33 y un paquete IP tiene como mucho 2^13 vueltas.
- **En ART:** `HeapByteBuffer.getLong` compone los bytes en Java, así que se espera un coste parecido
  al bucle de 16 bits, no peor. No se puede medir sin un benchmark en dispositivo.

### D4. Verificación
- **Equivalencia:** `PacketCodecEquivalenceTest` compara los bytes de `toByteArray`/`toIpBytes`
  (TCP con opciones y UDP; IPv4 e IPv6; payloads de 0 a 1460 B, pares e impares) con una copia de la
  serialización anterior con `ByteBuffer`. También compara el checksum con la implementación de
  16 bits para longitudes de 0 a 200 B y desplazamientos de 0 a 3, y comprueba que el parseo no
  copia. Detecta mutaciones: con un TTL de 63 o el puntero urgente desplazado, el test falla.
- **Rendimiento:** antes y después medidos seguidos, con los jars de los dos commits y 2 forks, para
  que el ruido del equipo afecte a ambos por igual.

## Medición
JVM 17, JMH (5 calentamientos, 5 iteraciones de 1 s, 2 forks), antes y después seguidos:

| Benchmark | Antes | Después | Tiempo | Memoria |
|---|---|---|---|---|
| `parseUplink` 1460 B | 434 ns, 3216 B | 224 ns, 1720 B | −48 % | −47 % |
| `parseUplink` 64 B | 64 ns, 424 B | 59 ns, 320 B | −7 % | −25 % |
| `buildDownlink` 1460 B | 861 ns (±309), 3160 B | 224 ns, 1584 B | −74 % | −50 % |
| `buildDownlink` 64 B | 133 ns, 368 B | 41 ns, 184 B | −69 % | −50 % |
| `checksum` 1460 B | 362 ns | 40 ns | −89 % | — |
| `checksum` 64 B | 31 ns | 9,5 ns | −69 % | — |
| `classify` y `enqueueAndDrain` (sin cambios) | 13 ns; 176/216 ns | 13 ns; 179/217 ns | ±2 % | 0 % |

Con un segmento lleno, la subida (parseo y motor) pasa de ~610 a ~400 ns y la bajada (construcción,
clasificación y motor) de ~1050 a ~415 ns. La memoria reservada por paquete baja a la mitad; a
100 Mbps de bajada, de ~28 a ~15 MB/s.

## Risks / Trade-offs
- `IpPacket` retiene el paquete entero (~1500 B) mientras exista, en lugar de solo su payload. Vive lo
  que dura el despacho de un paquete, así que no cambia la memoria en uso.
- `payload` en un `IpPacket` parseado copia la primera vez que se pide; el código caliente usa la vista.
- Las cifras son de HotSpot; en ART la ganancia del checksum será menor (D3). La de memoria es la misma.

## Migration Plan
Sin migración. Rollback: revertir el commit.
