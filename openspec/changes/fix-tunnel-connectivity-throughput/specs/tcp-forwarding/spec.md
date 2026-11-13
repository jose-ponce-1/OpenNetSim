# Spec Delta

## Purpose

Define cómo el túnel termina las conexiones TCP de las apps en espacio de usuario y las reenvía a
la red física, de forma que el flujo de bytes se entregue completo, en orden y a un caudal limitado
solo por la configuración de degradación.

## ADDED Requirements

### Requirement: Opciones TCP anunciadas en el handshake
El túnel SHALL anunciar en su SYN-ACK un MSS compatible con el MTU del túnel y, si el cliente lo
ofreció en su SYN, un factor de window scale, de modo que el cliente pueda usar segmentos de tamaño
completo y ventanas mayores de 64 KB.

#### Scenario: Cliente IPv4 con window scale
- **WHEN** un cliente envía un SYN IPv4 con la opción window scale
- **THEN** el SYN-ACK del túnel incluye MSS ≤ MTU − 40 y la opción window scale, y los segmentos de datos posteriores del cliente usan ese MSS

#### Scenario: Cliente sin window scale
- **WHEN** un cliente envía un SYN sin la opción window scale
- **THEN** el SYN-ACK del túnel incluye la opción MSS pero no window scale, y la ventana anunciada nunca supera 65535 bytes

### Requirement: Retransmisión del SYN-ACK
El túnel SHALL retransmitir el SYN-ACK si no recibe el ACK del cliente dentro del RTO vigente, y
SHALL responder a un SYN duplicado de una conexión ya en curso reenviando el mismo SYN-ACK, sin
abrir una nueva conexión exterior.

#### Scenario: SYN-ACK perdido
- **WHEN** el SYN-ACK del túnel se descarta y el cliente no envía ACK
- **THEN** el túnel reenvía el SYN-ACK con el mismo número de secuencia inicial y la conexión se establece sin esperar al reintento de SYN del cliente

#### Scenario: SYN duplicado tras conectar
- **WHEN** llega un SYN repetido con el mismo número de secuencia para una conexión ya establecida hacia el exterior
- **THEN** el túnel reenvía el SYN-ACK original y conserva la conexión exterior existente

### Requirement: Entrega de datos que acompañan a un FIN
El túnel SHALL entregar al destino exterior el payload de un segmento que lleve FIN antes de cerrar
la mitad de envío, y SHALL confirmarlo con un ACK que cubra el payload más el FIN.

#### Scenario: FIN con payload
- **WHEN** el cliente envía un segmento con FIN y 200 bytes de datos contiguos
- **THEN** el destino exterior recibe los 200 bytes y el cliente recibe un ACK igual a secuencia + 200 + 1

### Requirement: El FIN respeta el orden del flujo
El túnel SHALL procesar el FIN de un cliente solo cuando todos los bytes anteriores a su número de
secuencia se hayan entregado; un FIN que llegue antes que datos previos se retiene hasta que el
hueco se llene.

#### Scenario: FIN adelantado a datos en cola
- **WHEN** el FIN de un flujo llega al forwarder antes que el último segmento de datos de ese flujo
- **THEN** ningún byte de datos se descarta como duplicado, el destino exterior recibe el flujo completo y solo después se cierra la mitad de envío

### Requirement: Cierre ordenado tras el EOF del servidor
Cuando el destino exterior cierra la conexión, el túnel SHALL enviar FIN al cliente después de los
datos pendientes y SHALL conservar la sesión, retransmitiendo datos y FIN si hace falta, hasta que
el cliente los confirme o venza un tiempo máximo de cierre.

#### Scenario: Último segmento perdido antes del cierre
- **WHEN** el servidor cierra la conexión y el último segmento de datos hacia el cliente se pierde
- **THEN** el túnel retransmite ese segmento y el FIN hasta recibir el ACK del cliente, y el cliente recibe la respuesta completa

#### Scenario: FIN hacia el cliente perdido
- **WHEN** el FIN del túnel hacia el cliente se pierde
- **THEN** el túnel lo retransmite y la conexión del cliente se cierra sin quedar colgada

### Requirement: Reinicio explícito de sesiones descartadas
Cada vez que el túnel descarte una sesión TCP por inactividad, por error de I/O en el socket
exterior o por fallo de conexión, SHALL enviar un RST al cliente con números de secuencia que el
cliente acepte.

#### Scenario: Sesión inactiva expirada
- **WHEN** una sesión supera el tiempo máximo de inactividad
- **THEN** el túnel envía RST al cliente y libera el socket exterior

#### Scenario: Error de escritura en el socket exterior
- **WHEN** escribir en el socket exterior falla con un error de I/O
- **THEN** el cliente recibe un RST y la sesión se elimina

#### Scenario: Fallo al abrir el socket exterior
- **WHEN** no se puede crear o proteger el socket exterior al recibir un SYN
- **THEN** el cliente recibe un RST en lugar de quedar esperando reintentos de SYN

### Requirement: Respuesta a segmentos de sesiones desconocidas
El túnel SHALL responder con RST a cualquier segmento TCP sin SYN ni RST que no pertenezca a una
sesión activa, para que el cliente abandone la conexión de inmediato.

#### Scenario: Reutilización de una conexión del pool ya expirada
- **WHEN** una app envía datos por una conexión cuya sesión ya no existe en el túnel
- **THEN** la app recibe RST en un RTT y puede reconectar, en lugar de esperar a su propio timeout

### Requirement: Retransmisión adaptada al RTT
El túnel SHALL calcular el tiempo de retransmisión hacia el cliente a partir del RTT medido (incluida
la latencia simulada) con un mínimo de 200 ms, y SHALL armar el temporizador al enviar el primer
segmento sin confirmar, no desde la última confirmación.

#### Scenario: Respuesta tras inactividad
- **WHEN** una sesión inactiva durante varios segundos envía un nuevo segmento al cliente
- **THEN** ese segmento no se retransmite antes de que venza el RTO medido desde su envío

#### Scenario: Perfil de alta latencia
- **WHEN** la latencia configurada es de 600 ms y el cliente confirma cada ventana en ~600 ms
- **THEN** el túnel no emite retransmisiones por timeout mientras las confirmaciones lleguen dentro del RTO

### Requirement: Ventana en vuelo hacia el cliente
El túnel SHALL limitar los bytes sin confirmar hacia el cliente por la ventana que este anuncia y
por un tope proporcional al producto ancho de banda × retardo configurado, no por un valor fijo.

#### Scenario: Alta latencia y ancho de banda alto
- **WHEN** el perfil configura 10 Mbps de bajada y 600 ms de latencia, y hay una única descarga TCP
- **THEN** el caudal sostenido de esa descarga alcanza al menos el 80 % de 10 Mbps, siempre que el servidor real lo permita

#### Scenario: Cliente con ventana pequeña
- **WHEN** el cliente anuncia una ventana menor que el tope calculado
- **THEN** el túnel no tiene en vuelo más bytes que esa ventana

### Requirement: Actualización de ventana tras saturación
Si el túnel anunció una ventana reducida o cero porque el socket exterior no aceptaba datos, SHALL
enviar al cliente un ACK con la ventana actualizada en cuanto haya espacio.

#### Scenario: Subida tras ventana cero
- **WHEN** el túnel anuncia ventana cero y después vacía su búfer de escritura pendiente
- **THEN** el cliente recibe un ACK con ventana distinta de cero sin depender de su temporizador de persistencia

### Requirement: Orden de procesamiento por sesión
Todos los segmentos de una misma sesión TCP SHALL procesarse en el orden en que el pipeline de
subida los libera y sin ejecución concurrente sobre el estado de esa sesión.

#### Scenario: ACK y datos liberados a la vez
- **WHEN** un ACK puro y un segmento de datos de la misma sesión se liberan casi simultáneamente
- **THEN** se procesan uno tras otro y el estado de secuencia de la sesión es coherente con ambos

### Requirement: Recepción del socket exterior sin tope artificial
El búfer de recepción del socket exterior SHALL ser suficiente para el caudal de bajada configurado
con la latencia real del camino, de modo que no sea el factor limitante del caudal.

#### Scenario: Perfil de 5 Mbps con servidor a 80 ms
- **WHEN** el perfil configura 5 Mbps de bajada y el servidor real está a 80 ms de RTT
- **THEN** el socket exterior no limita el caudal por debajo de los 5 Mbps configurados
