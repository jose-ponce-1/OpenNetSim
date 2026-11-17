# Spec Delta

## ADDED Requirements

### Requirement: Jitter sin reordenación
El jitter SHALL variar el retardo dentro de `latencia ± jitter` sin cambiar el orden de los paquetes de
una dirección y sin desplazar la latencia media bajo carga.

#### Scenario: Flujo continuo con jitter
- **GIVEN** una dirección con 200 ms de latencia y ±50 ms de jitter
- **WHEN** entra un paquete por milisegundo durante 5 s
- **THEN** los paquetes salen en el orden de llegada, cada uno con un retardo entre 150 y 250 ms y una media de 200 ± 10 ms

### Requirement: Reordenación configurable
El túnel SHALL permitir configurar un porcentaje de paquetes que salen sin la latencia configurada y
adelantan a los paquetes encolados; con 0 % nunca SHALL reordenar.

#### Scenario: Paquete reordenado
- **GIVEN** 100 ms de latencia y un paquete A encolado
- **WHEN** entra un paquete B que resulta reordenado
- **THEN** B sale antes que A, y A sale tras sus 100 ms
