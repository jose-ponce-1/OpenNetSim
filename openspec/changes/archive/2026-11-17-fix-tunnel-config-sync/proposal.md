# Proposal

## Why
Al reabrir la app con el túnel activo, el ViewModel nuevo arrancaba con el perfil por defecto y
`configToApply` lo enviaba al servicio: la simulación cambiaba sin que nadie tocara nada. Además el
servicio era `START_STICKY` sin configuración persistida (un reinicio del proceso volvía a 3G lento sin
avisar), no sobrescribía `onRevoke()` (la UI mostraba "Error leyendo el descriptor del TUN") e
ignoraba el arranque como VPN siempre activa. Una herramienta de QA no puede cambiar en silencio las
condiciones bajo prueba.

## What Changes
- El servicio es la única fuente de verdad: al entrar en `Running`, el ViewModel adopta su
  configuración; `configToApply` solo emite cambios hechos por el usuario.
- Persistencia de la última configuración aplicada (`ConfigStore`, SharedPreferences, sin
  dependencias nuevas). Con el túnel parado, la UI muestra esa configuración.
- Reinicio con intent nulo y acción `android.net.VpnService`: se usa la configuración persistida (la
  VPN siempre activa sin configuración usa el perfil por defecto); sin permiso VPN no se establece
  nada y se publica `Stopped`.
- `onRevoke()`: parada limpia y `TunnelState.Failed` con un motivo de revocación explícito.
- La UI explica que la pérdida se aplica en cada dirección y muestra la pérdida de ida y vuelta.

## Fuera de alcance
Perfiles con nombre, importación/exportación y elegir entre pérdida por dirección o por RTT.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `vpn-tunnel-lifecycle`: se modifica "Aplicación de cambios de configuración" y se añaden la
  persistencia/restauración y la revocación.
- `traffic-shaping`: se añade que la UI informa de la pérdida de ida y vuelta.

## Impact
`ui/MainViewModel.kt`, `ui/MainActivity.kt`, `ui/ThrottleDashboardScreen.kt`,
`service/ThrottleVpnService.kt`, `service/TunnelIntents.kt`, `service/SharedPreferencesConfigStore.kt`,
`tunnel/ConfigStore.kt`, `model/ThrottleConfig.kt` y sus tests.
