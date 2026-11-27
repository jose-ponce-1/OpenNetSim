# Spec Delta

## ADDED Requirements

### Requirement: Diálogo de información de la aplicación
El botón de información de la barra superior SHALL abrir un diálogo con la versión instalada, una
descripción breve de la app, un apartado de código abierto (licencia Apache 2.0, enlace al código en
GitHub y acceso a las licencias de código abierto), un apartado para apoyar el proyecto con un enlace a
Ko-fi y la firma «Hecho con ♥ por Jose Ponce».

#### Scenario: Abrir la información
- **GIVEN** el usuario está en la sección Simular
- **WHEN** el usuario pulsa el botón de información de la barra superior
- **THEN** se muestra el diálogo con la versión, el apartado de código abierto con «Ver el código en GitHub» y «Licencias de código abierto», el apartado «Apoya el proyecto» con «Apoyar en Ko-fi» y «Hecho con ♥ por Jose Ponce»

#### Scenario: Ver el código
- **WHEN** el usuario pulsa «Ver el código en GitHub»
- **THEN** se abre el repositorio del proyecto en el navegador

### Requirement: Licencias de código abierto
La acción «Licencias de código abierto» SHALL mostrar sin necesidad de conexión a Internet las
dependencias de terceros incluidas en la aplicación junto con su versión y licencia, y SHALL permitir
consultar el texto completo de la licencia aplicable dentro de la aplicación en el idioma de la interfaz.

#### Scenario: Consultar dependencias incluidas
- **GIVEN** el usuario abrió el diálogo de información de la aplicación
- **WHEN** pulsa «Licencias de código abierto»
- **THEN** se muestra la lista de librerías de terceros incluidas en la aplicación con su nombre, versión y licencia

#### Scenario: Leer el texto completo de la licencia sin conexión
- **GIVEN** está abierta la vista de licencias de código abierto y el dispositivo no tiene conexión a Internet
- **WHEN** el usuario selecciona una librería o la opción para ver el texto de la licencia
- **THEN** se muestra el texto completo de la licencia correspondiente dentro de un diálogo desplazable

## REMOVED Requirements

### Requirement: Información de la aplicación
**Reason**: Se descarta la distribución en Google Play (requisito de cuenta de Organización para `VpnService`), por lo que desaparecen la restricción y el escenario «Instalada desde Google Play» que ocultaban el enlace a Ko-fi.
**Migration**: Sustituido por «Diálogo de información de la aplicación», que mantiene el contenido general y añade el acceso a «Licencias de código abierto».
