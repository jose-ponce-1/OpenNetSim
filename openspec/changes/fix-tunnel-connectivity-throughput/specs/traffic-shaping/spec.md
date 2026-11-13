# Spec Delta

## Purpose

Define cómo el pipeline bidireccional aplica ancho de banda, latencia, jitter y pérdida al tráfico
del túnel, garantizando que la degradación sea la configurada y que el pipeline nunca deje de
entregar paquetes.

## ADDED Requirements

### Requirement: Paquetes mayores que la ráfaga
El limitador de ancho de banda SHALL dejar pasar un paquete de cualquier tamaño de forma
proporcional a su tamaño, aunque supere la capacidad de ráfaga, y nunca SHALL detener la cola de
forma permanente por ese paquete.

#### Scenario: Paquete de 3000 bytes a 128 kbps
- **WHEN** la bajada está limitada a 128 kbps y entra un paquete de 3000 bytes seguido de otros paquetes
- **THEN** el paquete grande sale tras unos 190 ms de serialización y los siguientes salen después, en orden

### Requirement: Los paquetes de control respetan el orden de su flujo
Un paquete de control (ACK puro, FIN, RST) SHALL poder adelantar a datos de otros flujos, pero
nunca SHALL liberarse antes que un paquete de datos anterior del mismo flujo de transporte.

#### Scenario: FIN detrás de datos limitados por ancho de banda
- **WHEN** un flujo TCP tiene segmentos de datos esperando tokens y su FIN entra en el pipeline
- **THEN** el FIN sale después del último de esos segmentos

#### Scenario: ACK de otro flujo
- **WHEN** hay datos de un flujo A en cola y entra un ACK puro de un flujo B
- **THEN** el ACK de B puede salir antes que los datos de A, con la latencia configurada aplicada

### Requirement: Profundidad de cola acotada por tiempo
Cada dirección SHALL limitar los datos encolados a una ventana de tiempo de serialización a la tasa
configurada (con un mínimo de paquetes), de modo que el retardo de cola añadido no crezca sin
control a tasas bajas.

#### Scenario: Descargas paralelas a 128 kbps
- **WHEN** con 128 kbps de bajada varias descargas llenan la cola
- **THEN** el retardo de cola añadido a un paquete nuevo no supera la ventana configurada (500 ms por defecto) más la latencia configurada

### Requirement: Tráfico interactivo frente a transferencias masivas
Los datagramas DNS SHALL tratarse como tráfico interactivo: se les aplica latencia, jitter, pérdida
y ancho de banda, pero no esperan detrás de los datos masivos encolados en la misma dirección.

#### Scenario: Consulta DNS durante una descarga
- **WHEN** hay una descarga saturando la bajada y llega una respuesta DNS
- **THEN** la respuesta llega al cliente con un retardo de la latencia configurada más su propio tiempo de serialización, no detrás de toda la cola

### Requirement: Sin bloqueo de la lectura de subida
Una cola de subida llena nunca SHALL impedir que se lean y procesen del túnel los paquetes de control
ni el tráfico interactivo; los paquetes de datos que no quepan SHALL descartarse por la cola (tail-drop).

#### Scenario: Subida masiva y descarga simultánea
- **WHEN** una subida llena la cola de subida y a la vez otra conexión descarga
- **THEN** los ACKs de la descarga siguen saliendo con la latencia configurada y la descarga no se detiene

### Requirement: Pérdida aplicada a paquetes de control
La pérdida configurada SHALL aplicarse por igual a datos y paquetes de control en cada dirección,
y el túnel SHALL recuperarse de cualquier paquete de control perdido que él mismo haya originado.

#### Scenario: Pérdida del 5 % en ambas direcciones
- **WHEN** el perfil configura un 5 % de pérdida
- **THEN** la tasa de descarte medida en cada dirección es del 5 % ± 1 % sobre todos los paquetes y ninguna conexión queda colgada por un SYN-ACK o FIN perdido

### Requirement: Telemetría de caudal útil
La telemetría SHALL informar por separado del caudal de subida y de bajada, y SHALL excluir del
caudal útil los bytes retransmitidos por el túnel.

#### Scenario: Descarga con retransmisiones
- **WHEN** durante una descarga el túnel retransmite segmentos
- **THEN** el caudal de bajada mostrado excluye esos bytes y el contador de retransmisiones los refleja aparte
