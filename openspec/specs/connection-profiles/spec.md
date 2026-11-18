# connection-profiles Specification

## Purpose
Define los perfiles de conexión que el usuario puede aplicar (predefinidos y guardados por él), cómo se
elige uno, cuál es el perfil base de los valores actuales y cómo se resumen las condiciones aplicadas.

## Requirements

### Requirement: Selección de perfil
La sección Simular SHALL ofrecer los perfiles guardados, del más reciente al más antiguo, seguidos de
los predefinidos, en chips desplazables y en una lista completa. Elegir uno SHALL aplicar sus valores
y, con el túnel activo, aplicarlos sin reiniciarlo.

#### Scenario: Perfil elegido con el túnel activo
- **GIVEN** el túnel está activo con «3G Lento»
- **WHEN** el usuario elige «Alta Latencia»
- **THEN** el servicio recibe los valores de «Alta Latencia», el túnel no se reinicia y se avisa de que el perfil se aplicó sin reiniciarlo

#### Scenario: Más perfiles de los que caben
- **GIVEN** hay más chips de perfil de los que caben a lo ancho
- **WHEN** se muestra la sección Simular y el usuario aún no ha desplazado los chips
- **THEN** se indica cuántos perfiles quedan fuera y que se puede deslizar para verlos

#### Scenario: Personalizado
- **WHEN** el usuario elige «Personalizado»
- **THEN** los valores actuales se mantienen y quedan marcados como ajuste manual

### Requirement: Perfil reconocido por sus valores
El perfil marcado como elegido SHALL corresponder a los valores actuales: un predefinido si coinciden
exactamente con él, si no un perfil guardado con esos mismos valores, y si no «Personalizado».

#### Scenario: Valores editados que vuelven a coincidir
- **GIVEN** el usuario parte de «3G Lento» y cambia la latencia a 250 ms
- **WHEN** vuelve a escribir 200 ms
- **THEN** el perfil elegido vuelve a ser «3G Lento»

#### Scenario: Reapertura con valores de un perfil guardado
- **GIVEN** el túnel está activo con los valores del perfil guardado «Metro»
- **WHEN** el usuario vuelve a abrir la app
- **THEN** el perfil elegido es «Metro»

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

### Requirement: Eliminar un perfil guardado
El usuario SHALL poder eliminar un perfil guardado, con «Deshacer». Eliminarlo nunca SHALL cambiar las
condiciones de red aplicadas.

#### Scenario: Eliminar el perfil elegido
- **GIVEN** el túnel está activo con el perfil guardado «Metro»
- **WHEN** el usuario elimina «Metro»
- **THEN** las condiciones de red no cambian y el perfil elegido pasa a «Personalizado»

#### Scenario: Deshacer la eliminación
- **WHEN** el usuario pulsa «Deshacer» en el aviso de perfil eliminado
- **THEN** el perfil vuelve a su posición en la lista y, si estaba elegido, vuelve a estarlo

### Requirement: Resumen de las condiciones aplicadas
La sección Simular SHALL mostrar la bajada, la subida, la latencia con su jitter y la pérdida con su
pérdida de ida y vuelta de los valores actuales, junto con la descripción del perfil elegido.

#### Scenario: Pérdida distinta por dirección
- **GIVEN** la pérdida de subida es 1 % y la de bajada 2 %
- **WHEN** se muestra el resumen
- **THEN** la pérdida indica ambos valores por dirección y una pérdida de ida y vuelta del 3,0 %
