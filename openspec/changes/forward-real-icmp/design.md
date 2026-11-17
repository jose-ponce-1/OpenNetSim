# Design

## Context
Ver `proposal.md`. La app está excluida del túnel (`addDisallowedApplication`), así que sus sockets
salen por la red física; aun así se protegen con `VpnService.protect()`.

## Goals / Non-Goals
**Goals:** RTT real + degradación; un host que no responde tampoco responde por el túnel; probar la
lógica en la JVM.

**Non-Goals:** ICMP de error, raw sockets (requieren root).

## Decisions
### D1. Sockets de ping del kernel
Linux permite `socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` a los grupos de `ping_group_range`, que
Android abre a las apps. El kernel asigna el identificador (el "puerto" del socket) y calcula el
checksum; `recv` devuelve el mensaje ICMP sin cabecera IP. Un socket por (cliente, destino,
identificador): un `ping` mantiene su identificador, así que reutiliza el socket.

### D2. `IcmpEchoTransport` como frontera con Android
`java.net` no abre sockets ICMP, así que la parte Android (`Os.socket`, `Os.poll`, un `Os.pipe` para
despertar) queda detrás de una interfaz. `IcmpForwarder` es Kotlin puro y se prueba con un
transporte en memoria que imita al kernel.

### D3. `protect()` de un `FileDescriptor`
`VpnService` solo ofrece `protect(int)`: se duplica el descriptor con `ParcelFileDescriptor.dup()`,
se protege el duplicado (marca el mismo socket) y se cierra.

### D4. Compatibilidad con API 26
`ErrnoException.rethrowAsIOException()` es de API 30; se usa una conversión propia (lo detectó
`lintDebug`).

## Risks / Trade-offs
- El emulador con red de usuario (SLIRP) no suele dejar pasar ICMP: allí el ping puede no responder
  aunque el dispositivo real sí.
- Sin ICMP de error, un host inalcanzable se ve como "timeout", no como "Destination Host Unreachable".

## Migration Plan
Sin datos persistidos. Rollback: revertir el commit.
