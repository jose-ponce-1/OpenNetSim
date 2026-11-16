# Spec Delta

## ADDED Requirements

### Requirement: Temporización monotónica
La latencia, el jitter, el ancho de banda y los timeouts del túnel SHALL medirse con un único reloj
monotónico, de modo que un cambio de la hora del sistema no altere la degradación aplicada.

#### Scenario: Hora del sistema retrasada con paquetes en cola
- **GIVEN** la bajada tiene 300 ms de latencia y paquetes en cola
- **WHEN** la hora del sistema se retrasa una hora
- **THEN** los paquetes encolados salen tras su latencia programada y los nuevos siguen saliendo con unos 300 ms de retardo

#### Scenario: Reloj único para cola y token bucket
- **GIVEN** un pipeline construido con un reloj monotónico simulado
- **WHEN** el reloj simulado avanza 300 ms sin tráfico
- **THEN** el token bucket acumula exactamente los tokens de esos 300 ms (recortados a la ráfaga) y nada sale mientras el reloj simulado está parado
