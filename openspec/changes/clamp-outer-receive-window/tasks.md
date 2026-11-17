# Tasks

## 1. Política
- [x] 1.1 `TcpWindowPolicy.receiveWindowClampBytes` con tope `clamp(tasa × (latencia + jitter + 500 ms), 64 KB, 4 MB)`; verificar con `TcpWindowPolicyTest` (valores, mínimo, máximo y que 5 Mbps a 80 ms de RTT real no queda limitado)

## 2. Forwarder
- [x] 2.1 `ReceiveWindowClamp` en `ForwarderEnvironment` y `AndroidReceiveWindowClamp` con `TCP_WINDOW_CLAMP` sobre `ParcelFileDescriptor.fromSocket`; verificar con `:app:compileDebugKotlin`
- [x] 2.2 Aplicar el tope al conectar y reajustarlo en las sesiones abiertas en `updateDegradation`; verificar con `TcpReceiveWindowClampTest` y que el test falla si se quita el reajuste

## 3. Verificación
- [x] 3.1 Ejecutar `detekt`, `lintDebug`, `:core:test`, `:app:testDebugUnitTest` y `assembleDebug`; verificar que terminan bien
- [ ] 3.2 Verificar en dispositivo a 10 Mbps que, con el motor saturado, `rx_queue` de cada socket exterior no supera aproximadamente el tope (≈625 KB) cuando antes llegaba a ~1 MB por conexión
- [ ] 3.3 Verificar en dispositivo a 50 Mbps que un test de velocidad sigue alcanzando lo configurado
- [ ] 3.4 Verificar que la CI de GitHub Actions termina en verde
