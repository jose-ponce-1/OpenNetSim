# Proposal

## Why
El túnel respondía los ping en local: un host inexistente también respondía y el RTT no incluía la
red real. Las herramientas de referencia a nivel de paquete (`tc netem`, Network Link Conditioner,
Clumsy, el emulador de Android) dejan pasar el ICMP real y le suman la degradación. Decisión del
usuario (17/11/2026): ping real.

## What Changes
- **BREAKING (comportamiento):** se elimina `IcmpResponder`; los ICMP/ICMPv6 Echo Request se reenvían
  a la red real con sockets de ping sin privilegios (`SOCK_DGRAM` + `IPPROTO_ICMP`/`IPPROTO_ICMPV6`).
- `IcmpForwarder` (Kotlin puro, modelo actor) sobre la abstracción `IcmpEchoTransport`; la
  implementación Android (`AndroidIcmpEchoTransport`) usa `android.system.Os` y `poll()`.
- La respuesta vuelve con el identificador ICMP original de la app (el kernel asigna el suyo).
- Sesiones por (cliente, destino, identificador) con inactividad de 60 s y límite de 256.
- Si el sistema no permite sockets de ping, el ping se queda sin respuesta (se registra en Logcat).

## Fuera de alcance
Mensajes ICMP de error (destino inalcanzable, TTL excedido) para el ping, `traceroute` y un modo de
respuesta local opcional.

## Capabilities
### New Capabilities
- `icmp-forwarding`: reenvío del ping a la red real.

### Modified Capabilities
- (ninguna)

## Impact
`service/forwarder/` (`IcmpForwarder`, `IcmpEchoTransport`, `AndroidIcmpEchoTransport`; se borra
`IcmpResponder`), `service/pipeline/TunnelPipeline.kt`, `service/ThrottleVpnService.kt`, README y tests.
