# Spec Delta

## MODIFIED Requirements

### Requirement: Pérdida de ida y vuelta visible
Como la pérdida se aplica de forma independiente en cada dirección, la interfaz SHALL mostrar junto a
los controles de pérdida la pérdida efectiva de un intercambio de ida y vuelta,
`1 − (1 − p↑)(1 − p↓)`.

#### Scenario: Perfil Wi-Fi inestable
- **WHEN** el usuario selecciona un 8 % de pérdida en ambas direcciones
- **THEN** la interfaz indica que un ping se pierde aproximadamente el 15,4 % de las veces

#### Scenario: Pérdida solo en la subida
- **WHEN** el usuario configura un 10 % de pérdida de subida y un 0 % de bajada
- **THEN** la interfaz indica una pérdida de ida y vuelta del 10 %

## ADDED Requirements

### Requirement: Pérdida configurable por dirección
La pérdida de la subida y la de la bajada SHALL configurarse por separado y cada dirección SHALL
aplicar solo la suya.

#### Scenario: Pérdida asimétrica
- **GIVEN** un 3 % de pérdida de subida y un 12 % de bajada
- **WHEN** pasa tráfico en ambas direcciones
- **THEN** la subida descarta un 3 % ± 1 % de sus paquetes y la bajada un 12 % ± 1 %

### Requirement: Pérdida en ráfagas
El túnel SHALL permitir configurar la longitud media de las ráfagas de pérdida; con ráfagas, los
paquetes perdidos SHALL agruparse manteniendo la tasa media configurada, y con longitud 1 SHALL
perderse de forma independiente.

#### Scenario: Ráfagas de 4 paquetes
- **GIVEN** un 10 % de pérdida con ráfaga media de 4 paquetes
- **WHEN** pasan 400.000 paquetes
- **THEN** se pierde un 10 % ± 1 % y las pérdidas consecutivas forman ráfagas de 4 ± 0,3 paquetes de media
