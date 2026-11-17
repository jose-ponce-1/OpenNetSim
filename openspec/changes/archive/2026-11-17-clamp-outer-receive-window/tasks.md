# Tasks

## 1. Política
- [x] 1.1 `TcpWindowPolicy.receiveWindowClampBytes` con tope `clamp(tasa × (latencia + jitter + 500 ms), 64 KB, 4 MB)`; verificar con `TcpWindowPolicyTest` (valores, mínimo, máximo y que 5 Mbps a 80 ms de RTT real no queda limitado)

## 2. Forwarder
- [x] 2.1 `ReceiveWindowClamp` en `ForwarderEnvironment` y `AndroidReceiveWindowClamp` con `TCP_WINDOW_CLAMP` sobre `ParcelFileDescriptor.fromSocket`; verificar con `:app:compileDebugKotlin`
- [x] 2.2 Aplicar el tope al conectar y reajustarlo en las sesiones abiertas en `updateDegradation`; verificar con `TcpReceiveWindowClampTest` y que el test falla si se quita el reajuste
- [x] 2.3 Medir en dispositivo solo con `TCP_WINDOW_CLAMP`; verificar si el kernel respeta el tope (resultado: no, el autoajuste lo sube; a 10 Mbps un socket llegó a 1,68 MB con tope de 625 KB)
- [x] 2.4 Medir con `SO_RCVBUF` fijo de 4 MB antes de conectar (resultado: peor, 6,9 MB por socket; `TCP_WINDOW_CLAMP` no acota la cola sin leer, solo el búfer lo hace)
- [x] 2.5 `AndroidReceiveWindowClamp` fija `SO_RCVBUF` (mitad del tope) y `TCP_WINDOW_CLAMP` con el socket conectado; verificar con `:app:testDebugUnitTest` y `detekt`

## 3. Verificación
- [x] 3.1 Ejecutar `detekt`, `lintDebug`, `:core:test`, `:app:testDebugUnitTest` y `assembleDebug`; verificar que terminan bien
- [x] 3.2 Verificar en dispositivo con el test de Movistar a 10 Mbps que `rx_queue` de cada socket exterior no supera aproximadamente el tope (resultado: máx. 516 KB con tope de 625 KB; 9,69 Mbps)
- [x] 3.3 Verificar en dispositivo a 50 Mbps, tras cambiar la velocidad con el túnel activo, que el test alcanza lo configurado, que cada socket no supera el tope y que las conexiones abiertas reciben el nuevo tope (resultado: máx. 2,56 MB con tope de 3,1 MB; 48,54 Mbps; 4 conexiones abiertas reajustadas)
- [x] 3.4 Verificar que la CI de GitHub Actions termina en verde (PR #11)
- [x] 3.5 Verificar en dispositivo que recargar un test de velocidad a medias y repetirlo no baja el rendimiento (usuario, test oficial de M-Lab recargado 5–6 veces durante la prueba: sin pérdida; el de Google no se pudo repetir por bloqueo temporal de la IP)
