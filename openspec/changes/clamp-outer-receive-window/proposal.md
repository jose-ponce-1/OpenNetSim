# Proposal

## Why
Medido en dispositivo (Mi 9T, Android 10, perfil de 10 Mbps): durante una descarga, los sockets TCP
exteriores acumulan hasta 4,3 MB sin leer en el kernel. El túnel deja de leer cuando el motor de bajada
está lleno (contrapresión), pero el kernel sigue anunciando ventana al servidor, que da por entregados
más de 3 s de datos que la red simulada no admitiría. Si la app mantiene la conexión abierta tras
cancelar (cierre ordenado de un websocket, HTTP/2), esos datos se siguen entregando por el límite y
compiten con lo siguiente: es la hipótesis del test de velocidad de Google que, cancelado y repetido
enseguida, mide menos de lo configurado. Los emuladores en el kernel (netem, Network Link Conditioner)
no tienen esta cola oculta porque no terminan la conexión.

## What Changes
- Tope de la ventana de recepción de cada socket TCP exterior con `TCP_WINDOW_CLAMP`:
  `clamp(tasaBajada × (latencia + jitter + 500 ms), 64 KB, 4 MB)`.
- Se aplica al terminar de conectar y se reajusta en las conexiones abiertas al cambiar la
  configuración.
- No se usa `SO_RCVBUF`: fijaría el búfer al abrir el socket, desactivaría el autoajuste y no se
  podría cambiar en conexiones abiertas.

## Fuera de alcance
UDP (QUIC): no hay ventana de recepción que acotar. Re-aplicar el tope si el autoajuste del kernel lo
sube (ver design, riesgos): solo si la verificación en dispositivo lo muestra.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `tcp-forwarding`: el requisito de recepción del socket exterior añade un tope proporcional a la red
  simulada, ajustable en conexiones abiertas.

## Impact
`TcpWindowPolicy` (`:core`), `ReceiveWindowClamp` y `AndroidReceiveWindowClamp` (nuevos),
`ForwarderEnvironment`, `TcpForwarder`, `ThrottleVpnService` y tests.
