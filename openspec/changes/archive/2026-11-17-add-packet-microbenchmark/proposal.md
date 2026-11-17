# Proposal

## Why
El paso 5 de la hoja de ruta pide medir el camino de cada paquete antes de optimizarlo. Sin una
medición no se sabe si hace falta optimizar, ni dónde, ni si un refactor posterior (por ejemplo, el de
las baselines de detekt en los parsers de paquetes) empeora el rendimiento.

## What Changes
- Módulo JVM `:benchmark` con kotlinx-benchmark 0.4.17 (JMH) que depende de `:core`. No forma parte de
  la app.
- `PacketCodecBenchmark`, con segmentos TCP/IPv4 de 64 y 1460 bytes de datos:
  - `classify`: `PacketMeta.parse`.
  - `parseUplink`: `PacketMeta` más `IpPacket` y `TcpPacket`.
  - `buildDownlink`: construcción y serialización con checksums.
  - `checksum`.
- `ThrottlingEngineBenchmark`: encolar y drenar en el motor como `TunnelPipeline`, con 0 y 50 ms de
  latencia y reloj simulado.
- Tareas `:benchmark:benchmark` (tiempo), `:benchmark:allocationBenchmark` (tiempo y bytes reservados,
  `-prof gc` de JMH) y `:benchmark:smokeBenchmark` (comprobación rápida).
- La CI ejecuta `smokeBenchmark` para que los benchmarks no se rompan con los cambios de `:core`.
- Medición de referencia en el design.

## Fuera de alcance
- Optimizar el camino del paquete: se decide con estos datos en un cambio aparte.
- Benchmarks en dispositivo (Jetpack Microbenchmark sobre ART).
- Benchmarks de los forwarders de `:app`, que dependen de sockets y del selector.
- Comparar resultados automáticamente en la CI (los runners compartidos son demasiado ruidosos).

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- (ninguna; `skip_specs: true`: no cambia ningún comportamiento de la app)

## Impact
`settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `benchmark/` (nuevo),
`.github/workflows/ci.yml`, `openspec/config.yaml`, README y `project-state.md`.
