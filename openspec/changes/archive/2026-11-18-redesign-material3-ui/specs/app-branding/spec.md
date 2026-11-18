# Spec Delta

## ADDED Requirements

### Requirement: Paleta oficial con tema claro y oscuro
La interfaz SHALL usar la paleta Material 3 oficial de OpenNetSim (primario `#3F5F90` en claro y
`#A8C8FF` en oscuro), en tema claro u oscuro según el modo del sistema, y nunca SHALL sustituirla por
el color dinámico del fondo de pantalla.

#### Scenario: Modo oscuro del sistema
- **WHEN** el sistema está en modo oscuro
- **THEN** la app usa el esquema oscuro de la paleta y los iconos de la barra de estado son claros

#### Scenario: Modo claro del sistema
- **WHEN** el sistema está en modo claro
- **THEN** la app usa el esquema claro de la paleta y los iconos de la barra de estado son oscuros

### Requirement: Pantalla de carga con el logo
Al arrancar en frío, la app SHALL mostrar el logo de OpenNetSim centrado sobre el color de fondo del
tema, animado en Android 12 o superior y estático en versiones anteriores, sin alargar el arranque más
allá de lo que dura la animación del icono.

#### Scenario: Arranque en Android 12 o superior
- **WHEN** el usuario abre la app en un dispositivo con Android 12 o superior
- **THEN** el marco y la onda del logo se dibujan, aparecen los nodos y el icono se desvanece al mostrarse la app

#### Scenario: Arranque en Android 8 a 11
- **WHEN** el usuario abre la app en un dispositivo con Android 8 a 11
- **THEN** se muestra el logo estático hasta que la app está lista

#### Scenario: Animaciones desactivadas
- **GIVEN** el usuario desactivó las animaciones en los ajustes del sistema
- **WHEN** abre la app
- **THEN** la pantalla de carga termina sin la animación de salida
