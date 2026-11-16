# Spec Delta

## ADDED Requirements

### Requirement: Caducidad de sesiones UDP independiente de la hora del sistema
La inactividad de las sesiones UDP SHALL medirse con el reloj monotónico del túnel.

#### Scenario: Hora del sistema adelantada
- **GIVEN** una sesión UDP con tráfico hace 5 s
- **WHEN** la hora del sistema se adelanta 1 hora
- **THEN** la sesión sigue activa y su siguiente datagrama usa el mismo socket exterior
