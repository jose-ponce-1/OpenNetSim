# Design

## Context
Por cada paquete, en la subida, `TunnelPipeline`:
1. Lee del TUN y clasifica con `PacketMeta.parse`.
2. Lo encola en el motor.
3. Al drenarlo, lo parsea con `IpPacket.parse` y `TcpPacket.parse` para el forwarder.

En la bajada, el forwarder construye el segmento (`TcpPacket.buildPshAck`), lo serializa con
`toIpPacket().toByteArray()` (checksums TCP e IPv4), y el motor lo encola y drena. Todo eso está en
`:core`, que es Kotlin/JVM puro.

## Decisions
### D1. Módulo `:benchmark` con kotlinx-benchmark
- **Por qué kotlinx-benchmark:** es JMH con configuración de Gradle de JetBrains. Funciona con Kotlin
  2.4.21 y Gradle 9.7.1 sin avisos nuevos.
- **Por qué un módulo aparte:** `:core` queda sin dependencias de benchmark y sin interferir con Kover.
- **`allopen`:** JMH necesita subclasificar las clases `@State`, y Kotlin las haría `final`.
- **Por qué no Jetpack Microbenchmark:** mide sobre ART en dispositivo y necesita un módulo Android
  con tests instrumentados. La JVM basta para ver dónde está el coste y comparar antes y después de
  un cambio. Las cifras absolutas en un teléfono serán mayores.

### D2. Qué se mide
- **Segmentos:** TCP/IPv4 de 64 bytes (interactivo) y 1460 bytes (MSS con MTU de 1500).
- **Motor:** encola con `allowImmediateRelease = false`, como `TunnelPipeline`, y drena en la misma
  operación. El reloj es simulado y avanza 1 ms por operación, a 1 Gbps: nunca faltan tokens, así que
  se mide el coste del motor y no la espera del ancho de banda. Con 50 ms de latencia la cola llega a
  un estado estable de unos 50 paquetes.
- **Memoria:** `allocationBenchmark` ejecuta el jar JMH con `-prof gc`, que kotlinx-benchmark no
  expone, para medir los bytes reservados por operación. En Android, la presión sobre el GC importa
  tanto como el tiempo.

### D3. En la CI, solo humo
`smokeBenchmark` (una iteración de 200 ms por benchmark) comprueba que compilan y corren. No se comparan
cifras: los runners compartidos varían demasiado para detectar regresiones de forma fiable.

## Medición de referencia
JVM 17 en el equipo de desarrollo (Windows 11), JMH con 5 calentamientos y 5 iteraciones de 1 s:

| Benchmark | 64 B | 1460 B |
|---|---|---|
| `classify` | 15 ns, 48 B | 15 ns, 48 B |
| `parseUplink` | 66 ns, 424 B | 451 ns, 3216 B |
| `buildDownlink` | 102 ns, 368 B | 1122 ns, 3160 B |
| `checksum` | 28 ns, 0 B | 379 ns, 0 B |

| Benchmark | 0 ms | 50 ms |
|---|---|---|
| `enqueueAndDrain` (1460 B) | 182 ns, 280 B | 230 ns, 238 B |

Error de JMH (99,9 %): ≤ 11 % en las medidas de más de 50 ns, y hasta ±28 % en las de 15–30 ns
(`classify`, `checksum` de 64 B). Los bytes reservados son exactos.

Lectura:
- **Coste por segmento lleno:** unos 0,65 µs en la subida (parseo y motor) y 1,35 µs en la bajada
  (construcción, clasificación y motor).
- **Presupuesto a 100 Mbps:** son ~8.300 paquetes/s por dirección, o 120 µs por paquete, así que el
  camino gasta el ~1 % de un núcleo. Aunque ART en un teléfono fuera 10 veces más lento, quedaría por
  debajo del 15 %.
- **Memoria:** el parseo y la construcción reservan ~2,2 veces el tamaño del paquete, porque
  `IpPacket.parse` copia el payload y `TcpPacket.parse` lo vuelve a copiar, y la serialización crea el
  array TCP y después el IP. A 100 Mbps de bajada son ~28 MB/s de basura.
- **Checksum:** un tercio del coste de `buildDownlink` con 1460 B.

Conclusión: no hace falta optimizar para las velocidades que simula la app. Si llegara a hacer falta,
estos serían los objetivos, por orden:
1. Parseo sin copias (vistas con desplazamiento sobre el array original).
2. Serialización IP + TCP en un único array.
3. Checksum sumando palabras de 32 bits.

El usuario decidió aplicarlas igualmente: cambio `optimize-packet-path`, con la medición antes y
después. El checksum acabó leyendo palabras de 64 bits, porque las de 32 bits no mejoraban.

## Risks / Trade-offs
- Las cifras de la JVM de escritorio no son las de ART: sirven para comparar, no como presupuesto
  exacto en el teléfono.
- El humo en la CI añade unos 30 s.

## Migration Plan
Sin migración. Rollback: revertir el commit.
