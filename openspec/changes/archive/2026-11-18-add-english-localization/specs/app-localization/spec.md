# Spec Delta

## Purpose

Define en qué idiomas se muestra OpenNetSim, cómo se elige el idioma y qué textos se traducen, para que
la app pueda usarse en inglés o en español.

## ADDED Requirements

### Requirement: Idiomas de la interfaz
La app SHALL ofrecer su interfaz en inglés y en español. SHALL mostrarse en español cuando el idioma
elegido para la app o para el sistema sea el español, en cualquier región, y en inglés en cualquier otro
caso.

#### Scenario: Dispositivo en español
- **GIVEN** el idioma del sistema es español (España)
- **WHEN** el usuario abre la app
- **THEN** la interfaz está en español, por ejemplo el botón «Permitir conexión VPN» de la bienvenida

#### Scenario: Dispositivo en inglés
- **GIVEN** el idioma del sistema es inglés (Estados Unidos)
- **WHEN** el usuario abre la app
- **THEN** la interfaz está en inglés, por ejemplo el botón «Allow VPN connection» de la bienvenida

#### Scenario: Otro idioma
- **GIVEN** el idioma del sistema es francés
- **WHEN** el usuario abre la app
- **THEN** la interfaz está en inglés

#### Scenario: Español de otra región
- **GIVEN** el idioma del sistema es español (México)
- **WHEN** el usuario abre la app
- **THEN** la interfaz está en español

### Requirement: Idioma por app
En Android 13 o superior, la app SHALL declarar sus idiomas (inglés y español) para que el usuario pueda
elegir el de OpenNetSim en los ajustes del sistema, sin cambiar el del resto del dispositivo.

#### Scenario: Elegir el idioma de la app
- **GIVEN** el sistema está en español y el dispositivo tiene Android 13 o superior
- **WHEN** el usuario elige «English» para OpenNetSim en los ajustes de idioma de la app
- **THEN** OpenNetSim se muestra en inglés y el resto del sistema sigue en español

#### Scenario: Cambio de idioma con la simulación activa
- **GIVEN** la simulación está activa
- **WHEN** el usuario cambia el idioma de la app o del sistema
- **THEN** la interfaz pasa al idioma nuevo, la simulación sigue activa y las condiciones de red no cambian

### Requirement: Textos traducidos
Todo texto que la app muestra al usuario SHALL estar en el idioma de la interfaz. Eso incluye las
pantallas, los diálogos, los avisos y sus acciones, los mensajes de validación, las descripciones para
lectores de pantalla, los nombres y descripciones de los perfiles predefinidos, la notificación del túnel
y los motivos por los que el túnel no pudo activarse. Los nombres de los perfiles guardados SHALL
mostrarse tal como los escribió el usuario.

#### Scenario: Perfiles predefinidos en inglés
- **GIVEN** la interfaz está en inglés
- **WHEN** el usuario ve los perfiles de conexión
- **THEN** aparecen «Custom», «Slow 3G», «2G / EDGE», «Flaky Wi-Fi» y «High Latency» con sus descripciones en inglés

#### Scenario: Error de validación en inglés
- **GIVEN** la interfaz está en inglés
- **WHEN** el texto de la latencia pasa a ser 5000
- **THEN** el campo indica «Range: 0 – 1000 ms»

#### Scenario: Notificación del túnel en inglés
- **GIVEN** la interfaz está en inglés y el perfil elegido es «Slow 3G»
- **WHEN** la simulación está activa
- **THEN** la notificación persistente muestra el perfil «Slow 3G», sus condiciones y la acción «Stop»

#### Scenario: Perfil guardado
- **GIVEN** el usuario guardó un perfil llamado «Metro en hora punta»
- **WHEN** la interfaz está en inglés
- **THEN** el perfil se sigue llamando «Metro en hora punta»

### Requirement: Textos citados en las specs
Los textos de la interfaz que las specs citan entre comillas («…») SHALL ser los de la interfaz en
español; en inglés SHALL mostrarse su traducción con el mismo significado.

#### Scenario: Texto citado en otra spec
- **GIVEN** una spec indica que el campo muestra «Rango: 0 – 1000 ms»
- **WHEN** la interfaz está en inglés
- **THEN** el campo muestra «Range: 0 – 1000 ms»
