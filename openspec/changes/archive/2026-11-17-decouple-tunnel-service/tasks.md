# Tasks

## 1. Contrato UI ↔ túnel
- [x] 1.1 Crear `tunnel/TunnelController` e implementarlo en `ServiceTunnelController`; verificar con `grep -rn "ThrottleVpnService" app/src/main/java/com/opennetsim/ui` (sin resultados)
- [x] 1.2 Inyectar `TunnelController` en `MainViewModel` (factoría en `MainActivity`) y mover a él el arranque, la parada y el envío de configuración; verificar con `MainViewModelTest` y un controlador falso

## 2. Pipeline sin Android
- [x] 2.1 Extraer `TunnelPipeline` y `TunDevice` a `service/pipeline/`; verificar con `grep -n "import android" app/src/main/java/com/opennetsim/service/pipeline/TunnelPipeline.kt` (sin resultados)
- [x] 2.2 Probar el pipeline con un TUN en memoria: ida y vuelta UDP con la latencia configurada, ping local, fallo de lectura notificado una sola vez y `close()` sin fallos; verificar con `TunnelPipelineTest`

## 3. Servicio como orquestador
- [x] 3.1 Extraer `TunnelIntents`/`TunnelCommand`, `TunnelNotifications`, `TunnelStatusStore` y `ParcelFileDescriptorTunDevice`; verificar con `compileDebugKotlin`
- [x] 3.2 Verificar en dispositivo o emulador con cada preset: navegación, descarga, subida, `ping` y cambio de red Wi-Fi ↔ datos (reconstrucción por IPv6) — verificado por el usuario en dispositivo con `app-debug.apk` (17/11/2026: "todo funciona")
