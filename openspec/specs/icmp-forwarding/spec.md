# icmp-forwarding Specification

## Purpose
Define cómo el túnel reenvía el ping (ICMP/ICMPv6 Echo) de las apps a la red real, de modo que el RTT
observado sea el real más la degradación configurada y un host que no responde tampoco responda.

## Requirements

### Requirement: Ping a través de la red real
El túnel SHALL reenviar los ICMP/ICMPv6 Echo Request de las apps a su destino real y SHALL entregar
las respuestas reales con la degradación configurada aplicada en ambas direcciones; nunca SHALL
generar respuestas propias.

#### Scenario: Host que responde
- **GIVEN** el túnel está activo con 200 ms de latencia
- **WHEN** una app hace ping a un host alcanzable
- **THEN** recibe la respuesta del host con un RTT igual al real más unos 200 ms

#### Scenario: Host que no responde
- **GIVEN** el túnel está activo
- **WHEN** una app hace ping a una dirección sin host
- **THEN** la app no recibe ninguna respuesta

### Requirement: Identificador ICMP de la app
La respuesta entregada a la app SHALL llevar el identificador y el número de secuencia de su Echo
Request original, aunque el sistema use otro identificador hacia la red.

#### Scenario: Identificador reescrito por el sistema
- **GIVEN** el sistema envía el ping con su propio identificador
- **WHEN** llega la respuesta
- **THEN** la app la recibe con el identificador y la secuencia de su ping

### Requirement: Sin sockets de ping disponibles
Si el sistema no permite abrir sockets de ping, el túnel SHALL descartar los Echo Request y SHALL
registrar el motivo en el diagnóstico, sin afectar al resto del tráfico.

#### Scenario: Sistema sin sockets de ping
- **WHEN** abrir el socket de ping falla
- **THEN** el ping no recibe respuesta y TCP y UDP siguen funcionando
