# Proposal

## Why
Las sesiones UDP caducaban a los 30 s (RFC 4787 REQ-5 exige ≥ 2 min) y las TCP establecidas a los
10 min (RFC 5382 REQ-5 exige ≥ 2 h 4 min). QUIC, WebRTC o VoIP perdían su mapeo y las conexiones
push/MQTT/websocket inactivas recibían RST: el fallo lo provocaba el simulador, no la red simulada.
Además no había límite de sesiones: cada consulta DNS abre un socket, y un escaneo o una app P2P
podía agotar descriptores y memoria y dejar sin red a todo el dispositivo.

## What Changes
- UDP: inactividad por defecto de 300 s; DNS (puerto 53): 10 s (`UdpSessionPolicy`).
- TCP establecida: inactividad de 2 h 4 min; los timeouts de handshake y cierre no cambian.
- Límite configurable de sesiones TCP y UDP simultáneas (1024 por defecto): UDP desaloja la sesión
  menos recientemente activa; TCP rechaza el SYN nuevo con RST sin abrir socket.
- Diagnóstico: sesiones activas, desalojos y rechazos en Logcat (frecuencia limitada).

## Fuera de alcance
Presupuesto global de memoria TCP y límites por app.

## Capabilities
### New Capabilities
- (ninguna)

### Modified Capabilities
- `udp-forwarding`: tiempo de vida y límite de sesiones.
- `tcp-forwarding`: inactividad de conexiones establecidas y límite de conexiones.

## Impact
`service/forwarder/UdpForwarder.kt`, `service/forwarder/TcpForwarder.kt`,
`core/tcp/TcpConnection.kt` (`TcpConnectionConfig`) y tests de forwarders. Depende de
`use-monotonic-clock` para los tests de tiempo.
