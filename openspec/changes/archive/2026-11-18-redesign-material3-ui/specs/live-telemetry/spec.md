# Spec Delta

## Purpose

Define la sección de telemetría: cómo se muestra en vivo el caudal del túnel frente al límite
configurado, los contadores acumulados y el estado del túnel, y cómo se detiene o inicia desde ella.

## ADDED Requirements

### Requirement: Gráfica de caudal
La sección Telemetría SHALL mostrar el caudal útil de los últimos 60 segundos, con una muestra por
segundo, y la línea del límite configurado. El usuario SHALL poder ver la bajada, la subida o ambas.

#### Scenario: Túnel activo durante más de un minuto
- **GIVEN** el túnel lleva 90 s activo
- **WHEN** el usuario abre Telemetría
- **THEN** la gráfica muestra las últimas 60 muestras, la más reciente a la derecha, con el caudal actual sobre ella

#### Scenario: Solo subida
- **WHEN** el usuario elige «Subida»
- **THEN** la gráfica muestra solo la subida, el límite de subida configurado y el caudal de subida actual

#### Scenario: Nuevo arranque
- **GIVEN** el túnel se detuvo tras estar activo
- **WHEN** el usuario lo vuelve a activar
- **THEN** la gráfica empieza vacía

### Requirement: Contadores del túnel
La sección Telemetría SHALL mostrar los bytes útiles transferidos, los paquetes descartados por la
simulación, los bytes retransmitidos por el túnel y el tiempo que lleva activo.

#### Scenario: Túnel detenido
- **WHEN** el túnel está detenido
- **THEN** los contadores y el tiempo activo aparecen a cero y el estado indica que el túnel está detenido

### Requirement: Estado del túnel en la telemetría
La sección Telemetría SHALL indicar si el túnel está activo, si enruta IPv6 o solo IPv4, y el perfil
con sus valores, y SHALL ofrecer un botón para detener el túnel o iniciarlo.

#### Scenario: Red sin IPv6
- **GIVEN** el túnel está activo en una red física sin IPv6
- **WHEN** el usuario abre Telemetría
- **THEN** el estado indica que solo se enruta IPv4 porque la red no tiene IPv6

#### Scenario: Detener desde la telemetría
- **WHEN** el usuario pulsa «Detener»
- **THEN** el túnel se detiene y el botón pasa a «Iniciar»
