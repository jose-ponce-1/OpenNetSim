# Tasks

## 1. Fuente de verdad de la configuración
- [x] 1.1 En `MainViewModel`, al entrar en `Running` copiar `state.config` a `config` y `selectedPreset`; verificar con `reopeningAppWithCustomTunnelAdoptsServiceConfigAndSendsNothing`
- [x] 1.2 Hacer que `configToApply` solo emita desde acciones del usuario (`userConfig`); verificar con un test que una reapertura con `Running(custom)` produce 0 actualizaciones y que una confirmación tardía no pisa el slider (`lateServiceUpdatesDoNotOverwriteSliderBeingDragged`)
- [x] 1.3 Mantener verde `continuousSliderDragIsDebouncedAndEndsWithFinalValue` con reloj virtual (`kotlinx-coroutines-test`); verificar con `testDebugUnitTest`

## 2. Persistencia
- [x] 2.1 Crear `ConfigStore` (load/save), `InMemoryConfigStore` y `ThrottleConfigCodec`; verificar con `ThrottleConfigCodecTest` (ida y vuelta, perfil desconocido → CUSTOM, entrada corrupta descartada)
- [x] 2.2 Implementar `SharedPreferencesConfigStore`; verificar con `compileDebugKotlin`
- [x] 2.3 Guardar en `startPipeline` y `applyConfig` a través de `rememberConfig`; verificar leyendo el código que todo cambio aplicado pasa por `rememberConfig`
- [x] 2.4 Con el túnel parado, la UI muestra la última configuración aplicada; verificar con `stoppedTunnelShowsLastPersistedConfig`

## 3. Ciclo de vida
- [x] 3.1 En `onStartCommand`, tratar intent nulo y `android.net.VpnService` como `TunnelCommand.Restore`; sin configuración (salvo siempre activa) o con `VpnService.prepare(this) != null`, publicar `Stopped` y `stopSelf()`; verificar con `compileDebugKotlin`
- [x] 3.2 Sobrescribir `onRevoke()` con `Failed(REVOKED_REASON)` y tratar un fallo de lectura sin permiso como revocación; verificar con `compileDebugKotlin`
- [x] 3.3 Verificar en dispositivo o emulador: (a) perfil custom, atrás, reabrir → sin cambios; (b) `adb shell am kill com.opennetsim` con el túnel activo → se restablece con la misma configuración; (c) activar otra VPN → `Failed` con motivo de revocación; (d) VPN siempre activa en los ajustes → `Running` — verificado por el usuario en dispositivo con `app-debug.apk` (17/11/2026: "todo funciona")

## 4. Pérdida de ida y vuelta
- [x] 4.1 Añadir `ThrottleConfig.roundTripLossPercent` y mostrarlo bajo el slider de pérdida; verificar con `roundTripLossCompoundsPerDirectionLoss`

## 5. Cierre
- [x] 5.1 Ejecutar `testDebugUnitTest`, `detekt` y `lintDebug`; verificar que terminan bien y actualizar `project-state.md`
