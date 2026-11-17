## MODIFIED Requirements

### Requirement: Recepción del socket exterior sin tope artificial
El búfer de recepción del socket exterior SHALL ser suficiente para el caudal de bajada configurado
con la latencia real del camino, de modo que no sea el factor limitante del caudal. A la vez, la
ventana que el socket exterior anuncia al servidor SHALL estar acotada en proporción a la red simulada
(caudal de bajada × (latencia + jitter + ventana de cola del motor)), para que el servidor no tenga en
vuelo hacia el túnel más datos de los que esa red admitiría; el tope SHALL reajustarse también en las
conexiones abiertas cuando cambia la configuración.

#### Scenario: Perfil de 5 Mbps con servidor a 80 ms
- **WHEN** el perfil configura 5 Mbps de bajada y el servidor real está a 80 ms de RTT
- **THEN** el socket exterior no limita el caudal por debajo de los 5 Mbps configurados

#### Scenario: Descarga con el motor de bajada saturado
- **WHEN** el perfil configura 10 Mbps de bajada sin latencia y una descarga llena la cola del motor
- **THEN** los datos sin leer en cada socket exterior no superan aproximadamente el tope de 625 KB (0,5 s a 10 Mbps)

#### Scenario: Cambio de velocidad con la conexión abierta
- **WHEN** se cambia la velocidad de bajada configurada durante una descarga
- **THEN** el tope de la ventana de esa conexión se ajusta al nuevo valor sin esperar a conexiones nuevas
