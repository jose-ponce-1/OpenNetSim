# Spec Delta

## ADDED Requirements

### Requirement: Aplicación de cambios de configuración al túnel activo
Los cambios de parámetros hechos con los campos de texto o al elegir un perfil SHALL aplicarse al túnel
activo con debounce, como máximo unas pocas veces por segundo, y siempre con el valor final. El servicio
SHALL ser la fuente de verdad de la configuración activa: la interfaz SHALL adoptarla al observar el
túnel en marcha y nunca SHALL enviar al servicio una configuración que el usuario no haya cambiado.

#### Scenario: Escritura continua en un campo
- **WHEN** el usuario escribe un valor de latencia carácter a carácter durante 2 segundos
- **THEN** el servicio recibe como máximo 10 actualizaciones y la última corresponde al valor final escrito

#### Scenario: Reapertura de la app con el túnel activo
- **GIVEN** el túnel está activo con 2000 kbps de bajada y 300 ms de latencia (perfil Personalizado)
- **WHEN** la actividad se destruye y el usuario vuelve a abrir la app
- **THEN** los controles muestran 2000 kbps y 300 ms y el servicio no recibe ninguna actualización de configuración

### Requirement: Tiempo activo del túnel
El servicio SHALL publicar, junto con el estado activo, el instante de arranque del túnel medido con el
reloj monotónico, y la interfaz SHALL calcular el tiempo activo a partir de él. Reconstruir el túnel por
un cambio de red o aplicar una configuración nueva no SHALL reiniciarlo.

#### Scenario: Reapertura de la app
- **GIVEN** el túnel lleva 5 minutos activo
- **WHEN** la actividad se destruye y el usuario vuelve a abrir la app
- **THEN** el tiempo activo indica 05:00 y sigue contando

#### Scenario: Cambio de perfil
- **GIVEN** el túnel lleva 2 minutos activo
- **WHEN** el usuario elige otro perfil
- **THEN** el tiempo activo sigue contando desde 02:00

## REMOVED Requirements

### Requirement: Aplicación de cambios de configuración
**Reason**: Los sliders desaparecen de la interfaz; el requisito y su escenario de arrastre se refieren a ellos.
**Migration**: Sustituido por «Aplicación de cambios de configuración al túnel activo», con el mismo comportamiento para campos de texto y perfiles.
