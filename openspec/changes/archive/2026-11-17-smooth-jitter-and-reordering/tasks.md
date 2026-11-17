# Tasks

## 1. Jitter
- [x] 1.1 Crear `JitterProcess` y usarlo en `LatencyQueue.calculateDelayMs(nowMs)`; verificar con `JitterProcessTest` (rango, salidas nunca hacia atrás, valores independientes a 1 s, amplitud 0)
- [x] 1.2 Verificar el escenario "Flujo continuo con jitter" con `testJitterUnderLoadKeepsOrderAndIsNotBiasedUpwards`

## 2. Reordenación
- [x] 2.1 Añadir `reorderProbability` a `ThrottlingEngine` (paquete programado a su llegada, sin recorte FIFO) y `reorderPercent` a `ThrottleConfig`/`ImpairmentParams`; verificar con `testReorderedPacketOvertakesQueuedPackets` y `testReorderPercentIsAppliedFromParams`
- [x] 2.2 Persistir y enviar `reorderPercent` (`ThrottleConfigCodec`, `TunnelIntents`); verificar con `directionalLossBurstsAndReorderingRoundTrip`
- [x] 2.3 Añadir el control "Reordenación" (0–25 %) con su explicación; verificar con `compileDebugKotlin`

## 3. Verificación
- [x] 3.1 Ejecutar `detekt`, `lintDebug`, `testDebugUnitTest` y `assembleDebug`; verificar que terminan bien
- [x] 3.2 En dispositivo: con "Wi-Fi Inestable", `ping -i 0.2` muestra RTT variables sin saltos de orden (`icmp_seq` consecutivos) y una descarga no pierde caudal frente a jitter 0; con reordenación al 10 % la descarga sigue completándose — verificado por el usuario en dispositivo con `app-debug.apk` (17/11/2026: "todo funciona")
