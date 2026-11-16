# Spec Delta

## ADDED Requirements

### Requirement: Pérdida de ida y vuelta visible
Como la pérdida se aplica de forma independiente en cada dirección, la interfaz SHALL indicarlo junto
al control de pérdida y SHALL mostrar la pérdida efectiva de un intercambio de ida y vuelta,
`1 − (1 − p)²`.

#### Scenario: Perfil Wi-Fi inestable
- **WHEN** el usuario selecciona un 8 % de pérdida
- **THEN** la interfaz indica que se aplica en cada dirección y que un ping se pierde aproximadamente el 15,4 % de las veces
