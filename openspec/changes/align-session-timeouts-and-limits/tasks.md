# Tasks

## 1. UDP
- [x] 1.1 Sustituir `SESSION_TIMEOUT_MS` por `UdpSessionPolicy` (`idleTimeoutMs = 300 s`, `dnsIdleTimeoutMs = 10 s`); verificar con `keepaliveEvery60SecondsKeepsTheSameExternalSocket` e `idleSessionExpiresAfterPolicyTimeoutMeasuredWithMonotonicClock`
- [x] 1.2 Añadir `maxSessions` (1024) con desalojo de la menos activa; verificar con `reachingSessionLimitEvictsLeastRecentlyActiveSession`

## 2. TCP
- [x] 2.1 Subir `DEFAULT_ESTABLISHED_IDLE_TIMEOUT_MS` a 2 h 4 min; verificar con `persistentConnectionIdleFor30MinutesIsNotReset` y `establishedIdleTimeoutMeetsRfc5382`
- [x] 2.2 Añadir `maxSessions` (1024): un SYN por encima del límite recibe `buildRstForSegment` sin `SocketChannel.open()`; verificar con `synAboveSessionLimitIsResetWithoutOpeningSocketAndExistingConnectionKeepsWorking`
- [x] 2.3 Registrar con `NetDiagnostics` (limitado por frecuencia) los desalojos y rechazos con el número de sesiones activas; verificar leyendo el código que no hay logs por paquete

## 3. Verificación
- [x] 3.1 Ejecutar `testDebugUnitTest`, `detekt` y `lintDebug`; verificar que terminan bien
- [ ] 3.2 En dispositivo o emulador: conexión websocket inactiva 30 min sin cortes; ráfaga de 2000 consultas DNS (`dig`) sin errores de descriptores; verificar en Logcat (etiqueta `OpenNetSim`)
