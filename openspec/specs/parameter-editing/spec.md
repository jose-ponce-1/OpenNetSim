# parameter-editing Specification

## Purpose
Define cómo edita el usuario cada parámetro de la red simulada con un valor exacto: campos, unidades,
rangos admitidos, validación y vuelta a los valores del perfil base.

## Requirements

### Requirement: Campos de valor exacto
La sección Parámetros SHALL ofrecer un campo de texto por parámetro con su unidad y estos rangos:
bajada 50 kbps – 50 Mbps, subida 20 kbps – 20 Mbps, latencia 0 – 1000 ms, jitter 0 – 200 ms,
reordenación 0 – 25 %, pérdida de subida y de bajada 0 – 50 % y ráfaga media 1 – 10 paquetes.

#### Scenario: Valor escrito
- **WHEN** el usuario escribe 250 en la latencia
- **THEN** la configuración pasa a 250 ms de latencia y el perfil elegido pasa a «Personalizado» salvo que los valores coincidan con un perfil

#### Scenario: Coma decimal
- **WHEN** el usuario escribe «2,5» en la pérdida de bajada
- **THEN** la pérdida de bajada pasa a 2.5 %

### Requirement: Unidad del ancho de banda
El ancho de banda SHALL poder escribirse en kbps o en Mbps. Cambiar de unidad SHALL convertir el texto
de los campos sin cambiar el valor, y la unidad elegida SHALL conservarse entre ejecuciones.

#### Scenario: Cambio a Mbps
- **GIVEN** la bajada es 1500 kbps
- **WHEN** el usuario elige Mbps
- **THEN** el campo muestra 1.5 y la bajada sigue siendo 1500 kbps

#### Scenario: Valor en Mbps
- **GIVEN** la unidad es Mbps
- **WHEN** el usuario escribe 12 en la bajada
- **THEN** la bajada pasa a 12000 kbps

### Requirement: Valores no válidos
Un campo con caracteres no numéricos o fuera de rango SHALL mostrar el error y el rango admitido, y la
configuración SHALL conservar el último valor válido de ese parámetro. Un valor no válido nunca SHALL
enviarse al túnel.

#### Scenario: Fuera de rango
- **WHEN** el texto de la latencia pasa a ser 5000
- **THEN** el campo indica «Rango: 0 – 1000 ms», la latencia aplicada sigue siendo el último valor válido escrito y se avisa de que se mantiene el último valor correcto

### Requirement: Campo vacío
Un campo vacío SHALL equivaler a 0: mientras se edita SHALL mostrar 0 como marcador y, al salir del
campo, SHALL mostrar 0. Donde 0 queda fuera del rango (ancho de banda, ráfaga media) SHALL tratarse como
cualquier valor fuera de rango.

#### Scenario: Vaciar la latencia
- **WHEN** el usuario borra el contenido de la latencia
- **THEN** la latencia aplicada pasa a 0 ms y el campo muestra 0

#### Scenario: Vaciar la subida
- **WHEN** el usuario borra el contenido de la subida y pasa a otro campo
- **THEN** el campo muestra 0 e indica «Rango: 20 kbps – 20 Mbps», y la subida aplicada no cambia

#### Scenario: Valor reformateado al salir
- **WHEN** el usuario escribe «0200» en la latencia y pasa a otro campo
- **THEN** el campo muestra 200

### Requirement: Reordenación sin latencia
El campo de reordenación SHALL desactivarse con una latencia de 0 ms, indicando que la reordenación
requiere una latencia mayor que 0.

#### Scenario: Latencia a cero
- **WHEN** el usuario escribe 0 en la latencia
- **THEN** el campo de reordenación queda desactivado con el aviso «Requiere latencia mayor que 0»

### Requirement: Perfil base y restablecimiento
La sección Parámetros SHALL indicar el perfil del que parten los valores actuales y cuántos difieren de
él, marcando cada campo cambiado. «Restablecer» SHALL volver a los valores de ese perfil y ofrecer
«Deshacer».

#### Scenario: Valores cambiados
- **GIVEN** el usuario eligió «3G Lento»
- **WHEN** cambia la latencia y el jitter
- **THEN** se indica «Basado en 3G Lento · 2 cambios» y ambos campos aparecen marcados

#### Scenario: Restablecer y deshacer
- **GIVEN** hay 2 cambios sobre «3G Lento»
- **WHEN** el usuario pulsa «Restablecer» y después «Deshacer» en el aviso
- **THEN** primero se aplican los valores de «3G Lento» y después vuelven los 2 cambios

### Requirement: Aviso de pérdida elevada
Con una pérdida de subida o de bajada superior al 10 %, la sección Parámetros SHALL avisar de que muchas
conexiones TCP sufrirán retransmisiones o caídas.

#### Scenario: Pérdida del 12 %
- **WHEN** el usuario escribe 12 en la pérdida de bajada
- **THEN** se muestra el aviso de pérdida elevada
