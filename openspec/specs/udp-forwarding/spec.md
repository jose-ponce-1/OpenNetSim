# udp-forwarding Specification

## Purpose
Define cómo el túnel reenvía datagramas UDP (DNS, QUIC y otros) entre las apps y la red física sin
introducir bloqueos en el resto del tráfico y avisando a las apps cuando un destino no es alcanzable.

## Requirements

### Requirement: Alta de sesión UDP sin bloquear el tráfico
Crear una sesión UDP nueva SHALL completarse sin esperar al ciclo de espera del selector de
recepción UDP, de modo que el despacho del resto de paquetes de subida no sufra retrasos por altas
de sesión.

#### Scenario: Ráfaga de consultas DNS
- **WHEN** una app lanza 20 consultas DNS seguidas, cada una con un puerto origen distinto
- **THEN** ninguna consulta ni ningún otro paquete de subida se retrasa más de 5 ms por el alta de sesiones

#### Scenario: Respuesta a una sesión recién creada
- **WHEN** se crea una sesión y el servidor responde de inmediato
- **THEN** la respuesta se recibe y se entrega al cliente sin esperar al vencimiento del intervalo de espera del selector

### Requirement: Destino UDP inalcanzable
Si el túnel no puede abrir o usar un socket hacia un destino UDP porque la red no es alcanzable o el
puerto lo rechaza, SHALL devolver al cliente un mensaje ICMP/ICMPv6 de destino inalcanzable en lugar
de descartar el datagrama en silencio.

#### Scenario: Servidor DNS IPv6 desde una red solo IPv4
- **WHEN** una app envía un datagrama UDP a una dirección IPv6 y la red física no tiene ruta IPv6
- **THEN** la app recibe un ICMPv6 de destino inalcanzable y su resolver pasa al siguiente servidor sin esperar a su timeout

#### Scenario: Puerto cerrado
- **WHEN** el servidor exterior responde con puerto inalcanzable
- **THEN** el cliente recibe un ICMP de puerto inalcanzable y la sesión se libera

### Requirement: Datagramas grandes
El túnel SHALL entregar al cliente datagramas UDP de respuesta de hasta 65507 bytes sin truncarlos,
y un datagrama grande nunca SHALL impedir la entrega de los paquetes posteriores.

#### Scenario: Respuesta DNS de 3000 bytes con perfil EDGE
- **WHEN** con el perfil EDGE activo llega una respuesta UDP de 3000 bytes
- **THEN** el cliente recibe la respuesta completa y el tráfico de bajada posterior sigue fluyendo

### Requirement: Caducidad de sesiones UDP independiente de la hora del sistema
La inactividad de las sesiones UDP SHALL medirse con el reloj monotónico del túnel.

#### Scenario: Hora del sistema adelantada
- **GIVEN** una sesión UDP con tráfico hace 5 s
- **WHEN** la hora del sistema se adelanta 1 hora
- **THEN** la sesión sigue activa y su siguiente datagrama usa el mismo socket exterior

### Requirement: Tiempo de vida de las sesiones UDP
Una sesión UDP SHALL mantenerse al menos 120 s sin tráfico (300 s por defecto), salvo las sesiones
DNS (puerto 53), que SHALL caducar a los 10 s de inactividad.

#### Scenario: Keepalive cada 60 s
- **GIVEN** una app envía un datagrama al mismo destino cada 60 s
- **WHEN** pasan 5 minutos
- **THEN** todos los datagramas salen por el mismo socket exterior (mismo puerto de origen)

#### Scenario: Consulta DNS
- **GIVEN** una consulta DNS recibió su respuesta
- **WHEN** pasan 10 s sin tráfico en esa sesión
- **THEN** su socket se cierra

### Requirement: Límite de sesiones UDP
El forwarder UDP SHALL limitar las sesiones simultáneas a un máximo configurable y, al alcanzarlo,
SHALL desalojar la sesión menos recientemente activa para admitir la nueva.

#### Scenario: Límite alcanzado
- **GIVEN** el límite es de 3 sesiones y hay 3 abiertas
- **WHEN** llega un datagrama de una sesión nueva
- **THEN** se cierra la sesión menos recientemente activa, la nueva se abre y siguen abiertas 3
