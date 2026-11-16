# Design

## Context
Ver `proposal.md`. Depende de `decouple-tunnel-service`, que introduce `TunnelController` y
`TunnelIntents`.

## Goals / Non-Goals
**Goals:** que ninguna recreación de la UI ni reinicio del servicio cambie la configuración aplicada;
que la revocación se distinga de un error de E/S.

**Non-Goals:** sincronizar sliders en tiempo real con cada confirmación del servicio.

## Decisions
### D1. Flujo de cambios del usuario separado del estado de la UI
`userConfig: MutableStateFlow<ThrottleConfig?>` solo se escribe desde las acciones del usuario;
`configToApply = userConfig.filterNotNull().distinctUntilChanged().debounce(150 ms)` con el filtro de
"túnel en marcha y distinto". El estado inicial y la configuración adoptada nunca pasan por él.

### D2. Adoptar la configuración solo al entrar en `Running`
Las confirmaciones posteriores del servicio llegan con retraso respecto al slider que se arrastra;
adoptarlas haría saltar el control. Al entrar en `Running` se descarta cualquier cambio pendiente.

### D3. `SharedPreferences` + códec puro
`ThrottleConfigCodec` (Kotlin puro, probado en JVM) codifica a pares clave/valor de texto; un perfil
desconocido pasa a `CUSTOM` y una entrada incompleta se descarta. `SharedPreferencesConfigStore` solo
lo almacena. Se guarda en cada configuración aplicada (arranque y actualización).
- *Alternativa:* DataStore. Descartada: dependencia nueva para seis valores.

### D4. Revocación
`onRevoke()` detiene el túnel con `Failed(REVOKED_REASON)`. Si la lectura del TUN falla antes de que
llegue `onRevoke()`, el aviso de fallo comprueba `VpnService.prepare()`: si ya no tenemos el permiso,
se informa también como revocación. Cerrar el pipeline antes que el TUN suprime el error de lectura.

## Risks / Trade-offs
- La VPN siempre activa sin configuración guardada usa el perfil por defecto: es una acción explícita
  del usuario en los ajustes del sistema, y es la única forma de que no falle en una instalación nueva.

## Migration Plan
Sin datos previos: la primera configuración aplicada se guarda al activar el túnel.
