# Tasks

## 1. Abstracción
- [x] 1.1 Crear `core/time/MonotonicClock` (`fun interface` con `nowNanos()`, `nowMs()`, `SYSTEM` sobre `nanoTime`, `ofMillis`) y `FakeMonotonicClock` en tests; verificar que `core/` sigue sin imports de Android con `grep -rn "import android" app/src/main/java/com/opennetsim/core`

## 2. Motor
- [x] 2.1 Sustituir `timeSourceMs`/`timeSourceNanos` por `MonotonicClock` en `LatencyQueue`, `TokenBucketLimiter`, `ThrottlingEngine` y `BidirectionalThrottlingEngine`, sin valores por defecto de reloj de pared, y eliminar `maxCapacity`/`isFull`; verificar con `testDebugUnitTest`
- [x] 2.2 Añadir a `ThrottlingEngineTest` un test que comparta un reloj simulado entre cola y bucket (escenario "Reloj único para cola y token bucket"); verificar que pasa

## 3. Forwarders y servicio
- [x] 3.1 Añadir `clock` a `ForwarderEnvironment` y usarlo en `TcpForwarder`, `UdpForwarder`, la telemetría del pipeline y `RateLimitedDiagnostics`; verificar con `grep -rn currentTimeMillis app/src/main` (solo la mención en el KDoc de `MonotonicClock`) y los tests de los forwarders
- [x] 3.2 Probar la caducidad UDP con reloj simulado (`UdpSessionLifetimeTest`); verificar que pasa

## 4. Verificación
- [x] 4.1 Ejecutar `testDebugUnitTest`, `detekt` y `lintDebug`; verificar que terminan bien
- [x] 4.2 En dispositivo o emulador con el túnel activo y tráfico (descarga + `ping`): retrasar y adelantar la hora del sistema 1 h desde los ajustes; verificar que no hay congelación ni RST masivos — verificado por el usuario en dispositivo con `app-debug.apk` (17/11/2026: "todo funciona")
