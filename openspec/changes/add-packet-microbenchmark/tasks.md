# Tasks

## 1. Módulo
- [x] 1.1 Módulo `:benchmark` con kotlinx-benchmark 0.4.17, `allopen` y detekt; verificar con `--warning-mode all` que no aparecen avisos nuevos de Gradle
- [x] 1.2 `PacketCodecBenchmark` (`classify`, `parseUplink`, `buildDownlink`, `checksum`; 64 y 1460 B) y `ThrottlingEngineBenchmark` (`enqueueAndDrain`; 0 y 50 ms); verificar con `:benchmark:smokeBenchmark`
- [x] 1.3 Tarea `allocationBenchmark` con `-prof gc` de JMH; verificar que el informe incluye `gc.alloc.rate.norm`
- [x] 1.4 Medición de referencia con `allocationBenchmark` anotada en el design; verificar que `allocations.json` tiene las 10 combinaciones y anotar el error (resultado: ≤ 11 % en las medidas de más de 50 ns; hasta ±28 % en las de ~15–30 ns)

## 2. CI y documentación
- [x] 2.1 Paso de humo (`:benchmark:smokeBenchmark`) en la CI y en la puerta de calidad de `openspec/config.yaml`; verificar el YAML
- [x] 2.2 README y `project-state.md` con los comandos y la conclusión; verificar con `openspec validate add-packet-microbenchmark --strict`
- [ ] 2.3 Verificar que la CI de GitHub Actions termina en verde
