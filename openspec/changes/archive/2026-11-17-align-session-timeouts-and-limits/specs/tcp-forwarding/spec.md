# Spec Delta

## ADDED Requirements

### Requirement: Inactividad de conexiones establecidas
Una conexión TCP establecida SHALL mantenerse abierta al menos 2 h 4 min sin tráfico antes de
abortarse por inactividad.

#### Scenario: Conexión persistente inactiva
- **GIVEN** una conexión TCP establecida sin tráfico
- **WHEN** pasan 30 minutos
- **THEN** la conexión sigue abierta, no se envía RST y el siguiente segmento del cliente llega al servidor

### Requirement: Límite de conexiones TCP simultáneas
El forwarder TCP SHALL limitar las conexiones simultáneas a un máximo configurable y SHALL responder
con RST a los SYN que lo superen, sin afectar a las conexiones existentes.

#### Scenario: Límite alcanzado
- **GIVEN** hay tantas conexiones TCP abiertas como el límite
- **WHEN** el cliente envía un SYN nuevo
- **THEN** el cliente recibe un RST para ese SYN, no se abre ningún socket exterior y las conexiones existentes siguen transfiriendo datos
