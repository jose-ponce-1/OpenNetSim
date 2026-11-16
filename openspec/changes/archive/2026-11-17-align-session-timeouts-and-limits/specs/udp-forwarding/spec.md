# Spec Delta

## ADDED Requirements

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
