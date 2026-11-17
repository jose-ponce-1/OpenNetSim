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
- Tope de los datos pendientes de cada socket TCP exterior (cola sin leer más ventana anunciada):
  `clamp(tasaBajada × (latencia + jitter + 500 ms), 64 KB, 4 MB)`.
- Se aplica con `SO_RCVBUF` (la mitad del tope; Linux reserva el doble), que es lo que acota la cola sin
  leer, y `TCP_WINDOW_CLAMP` al mismo valor, para que la ventana pueda crecer hasta el tope.
- Ambos se fijan al terminar de conectar, no antes (la escala de ventana se negocia en el SYN sin tope),
  y se reajustan en las conexiones abiertas al cambiar la configuración.

## Fuera de alcance
UDP (QUIC): no hay ventana de recepción que acotar.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `tcp-forwarding`: el requisito de recepción del socket exterior añade un tope proporcional a la red
  simulada, ajustable en conexiones abiertas.

## Impact
`TcpWindowPolicy` (`:core`), `ReceiveWindowClamp` y `AndroidReceiveWindowClamp` (nuevos),
`ForwarderEnvironment`, `TcpForwarder`, `ThrottleVpnService` y tests.
