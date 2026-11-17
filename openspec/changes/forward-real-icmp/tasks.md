# Tasks

## 1. Reenvío
- [x] 1.1 Crear `IcmpEchoTransport`/`IcmpEchoSocket` y `IcmpForwarder` (sesiones, identificador original, inactividad 60 s, límite 256); verificar con `IcmpForwarderTest` (respuesta con el identificador de la app, host sin respuesta, un socket por identificador, IPv6, caducidad, sin sockets de ping, cierre)
- [x] 1.2 Implementar `AndroidIcmpEchoTransport` con `Os.socket`/`Os.poll`/`Os.pipe` y `protect()` vía `ParcelFileDescriptor.dup()`; verificar con `compileDebugKotlin` y `lintDebug` (sin `NewApi`)

## 2. Integración
- [x] 2.1 Sustituir `IcmpResponder` por `IcmpForwarder` en `TunnelPipeline` y crear el transporte en el servicio; verificar con `pingGoesToTheRealNetworkWithConfiguredLatencyAdded` y `pingToUnreachableHostGetsNoReply`
- [x] 2.2 Actualizar el README; verificar que ya no dice que el ping se responde en local

## 3. Verificación
- [x] 3.1 Ejecutar `detekt`, `lintDebug`, `testDebugUnitTest` y `assembleDebug`; verificar que terminan bien
- [ ] 3.2 En dispositivo: `ping 8.8.8.8` con el perfil "Alta Latencia" (RTT ≈ real + 600 ms) y `ping 10.255.255.1` (sin respuesta); verificar en Logcat que no hay errores `icmp.open`
