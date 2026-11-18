# Spec Delta

## MODIFIED Requirements

### Requirement: Guardar las condiciones actuales como perfil
El usuario SHALL poder guardar los valores actuales con un nombre de 1 a 24 caracteres, distinto, sin
distinguir mayúsculas, de los perfiles guardados y de los nombres de los perfiles predefinidos en
cualquiera de los idiomas de la app. El perfil nuevo SHALL aparecer el primero, quedar elegido y
conservarse entre ejecuciones de la app.

#### Scenario: Guardar con nombre válido
- **WHEN** el usuario guarda las condiciones actuales como «Metro en hora punta»
- **THEN** el perfil aparece el primero de la lista, queda elegido y sigue ahí tras cerrar y abrir la app

#### Scenario: Nombre repetido
- **GIVEN** existe el perfil predefinido «3G Lento»
- **WHEN** el usuario escribe «3g lento» como nombre
- **THEN** no puede guardarlo y se indica que ya existe un perfil con ese nombre

#### Scenario: Nombre de un perfil predefinido en el otro idioma
- **GIVEN** la interfaz está en español
- **WHEN** el usuario escribe «slow 3g», nombre en inglés de «3G Lento», como nombre
- **THEN** no puede guardarlo y se indica que ya existe un perfil con ese nombre

#### Scenario: Deshacer el guardado
- **WHEN** el usuario pulsa «Deshacer» en el aviso de perfil guardado
- **THEN** el perfil desaparece y vuelve a estar elegido el perfil anterior
