# Tasks

## 1. Motor
- [x] 1.1 Añadir el modelo de Gilbert a `PacketLossSimulator` (`burstLength`, `updateBurstLength`); verificar con `testGilbertModelKeepsMeanLossAndProducesBursts` y `testBurstLengthOneMeansIndependentLosses`
- [x] 1.2 Crear `ImpairmentParams` con `uplinkParams`/`downlinkParams` y usarlo en `ThrottlingEngine` y `BidirectionalThrottlingEngine`; verificar con `testDirectionalLossAndBurstComeFromConfig`

## 2. Modelo y persistencia
- [x] 2.1 Sustituir `packetLossPercent` por `upLossPercent`/`downLossPercent` y añadir `lossBurstLength` en `ThrottleConfig`; `matchesPreset` exige pérdida simétrica, sin ráfagas ni reordenación; verificar con `burstsReorderingOrAsymmetricLossMakeTheConfigCustom` y `roundTripLossCombinesBothDirections`
- [x] 2.2 Codificar los campos nuevos y migrar `loss_percent`; verificar con `directionalLossBurstsAndReorderingRoundTrip` y `legacyEntryWithSingleLossAppliesItToBothDirections`

## 3. UI
- [x] 3.1 Sliders de pérdida de subida y bajada, "Ráfaga media" y pérdida de ida y vuelta con dos valores; `DashboardActions` con `onConfigChange`; verificar con `compileDebugKotlin` y `MainViewModelTest`

## 4. Verificación
- [x] 4.1 Ejecutar `detekt`, `lintDebug`, `testDebugUnitTest` y `assembleDebug`; verificar que terminan bien
- [x] 4.2 En dispositivo: reabrir la app con una configuración guardada antes de este cambio (se conserva la pérdida en ambas direcciones); 10 % solo de bajada → la subida no pierde; ráfaga 5 con "Wi-Fi Inestable" → una descarga sigue completándose — verificado por el usuario en dispositivo con `app-debug.apk` (17/11/2026: "todo funciona")
