# app-navigation Specification

## Purpose
Define cómo entra el usuario en OpenNetSim la primera vez y cómo se mueve entre las secciones de la
aplicación: bienvenida con el permiso VPN y navegación entre Simular, Parámetros y Telemetría.

## Requirements

### Requirement: Bienvenida en el primer arranque
La aplicación SHALL mostrar una pantalla de bienvenida solo hasta que el usuario la complete con
«Empezar» o «Ahora no». La bienvenida SHALL explicar qué simula la app y que el tráfico no sale hacia
ningún servidor externo.

#### Scenario: Primer arranque
- **GIVEN** la app se abre por primera vez
- **WHEN** termina la pantalla de carga
- **THEN** se muestra la bienvenida con el botón «Permitir conexión VPN» y la opción «Ahora no»

#### Scenario: Arranques posteriores
- **GIVEN** el usuario completó la bienvenida
- **WHEN** vuelve a abrir la app, incluso tras cerrar el proceso
- **THEN** se abre directamente la sección Simular

### Requirement: Permiso VPN desde la bienvenida
El botón «Permitir conexión VPN» SHALL abrir el diálogo del sistema para el permiso VPN sin arrancar el
túnel. Con el permiso concedido, o si ya lo estaba, la bienvenida SHALL ofrecer «Empezar».

#### Scenario: Permiso concedido
- **WHEN** el usuario pulsa «Permitir conexión VPN» y acepta el diálogo del sistema
- **THEN** el botón pasa a «Empezar», el túnel sigue detenido y no se ha alterado ninguna condición de red

#### Scenario: Permiso rechazado
- **WHEN** el usuario rechaza el diálogo del sistema
- **THEN** la bienvenida indica que el permiso se puede conceder más tarde, al activar la simulación

#### Scenario: Continuar sin permiso
- **WHEN** el usuario pulsa «Ahora no»
- **THEN** se abre la sección Simular y el permiso se pedirá al activar la simulación

### Requirement: Navegación entre secciones
La aplicación SHALL ofrecer una barra de navegación inferior con las secciones Simular, Parámetros y
Telemetría, que conserve la sección elegida al recrearse la actividad.

#### Scenario: Cambio de sección
- **WHEN** el usuario pulsa «Telemetría» en la barra inferior
- **THEN** se muestra la telemetría y su destino aparece marcado como actual

#### Scenario: Atrás desde una sección secundaria
- **GIVEN** el usuario está en Parámetros o en Telemetría
- **WHEN** pulsa Atrás
- **THEN** vuelve a Simular, y un segundo Atrás sale de la app

#### Scenario: Rotación
- **GIVEN** el usuario está en Parámetros
- **WHEN** gira el dispositivo
- **THEN** sigue en Parámetros

### Requirement: Información de la aplicación
El botón de información de la barra superior SHALL abrir un diálogo con la versión instalada, una
descripción breve de la app, un apartado de código abierto (licencia Apache 2.0 y enlace al código en
GitHub), un apartado para apoyar el proyecto con un enlace a Ko-fi y la firma «Hecho con ♥ por
Jose Ponce». Si la app se instaló desde Google Play, el diálogo SHALL omitir el apartado de apoyo
económico: la política de pagos de Google Play no permite enlazar desde la app a otro medio de pago.

#### Scenario: Abrir la información
- **GIVEN** la app se instaló con el APK publicado en GitHub
- **WHEN** el usuario pulsa el botón de información de la barra superior
- **THEN** se muestra el diálogo con la versión, el apartado de código abierto con «Ver el código en GitHub», el apartado «Apoya el proyecto» con «Apoyar en Ko-fi» y «Hecho con ♥ por Jose Ponce»

#### Scenario: Instalada desde Google Play
- **GIVEN** la app se instaló desde Google Play
- **WHEN** el usuario abre la información de la app
- **THEN** se muestra el apartado de código abierto y no el de apoyo económico

#### Scenario: Ver el código
- **WHEN** el usuario pulsa «Ver el código en GitHub»
- **THEN** se abre el repositorio del proyecto en el navegador
