# Spec Delta

## MODIFIED Requirements

### Requirement: Aplicación de cambios de configuración
Los cambios de parámetros hechos con los controles deslizantes SHALL aplicarse al túnel activo con
debounce, como máximo unas pocas veces por segundo, y siempre con el valor final. El servicio SHALL ser
la fuente de verdad de la configuración activa: la interfaz SHALL adoptarla al observar el túnel en
marcha y nunca SHALL enviar al servicio una configuración que el usuario no haya cambiado.

#### Scenario: Arrastre continuo de un slider
- **WHEN** el usuario arrastra el slider de latencia durante 2 segundos
- **THEN** el servicio recibe como máximo 10 actualizaciones y la última corresponde al valor en el que se soltó el slider

#### Scenario: Reapertura de la app con el túnel activo
- **GIVEN** el túnel está activo con 2000 kbps de bajada y 300 ms de latencia (perfil Personalizado)
- **WHEN** la actividad se destruye y el usuario vuelve a abrir la app
- **THEN** los controles muestran 2000 kbps y 300 ms y el servicio no recibe ninguna actualización de configuración

## ADDED Requirements

### Requirement: Persistencia y restauración de la configuración
El servicio SHALL persistir cada configuración aplicada y, cuando el sistema lo reinicie (proceso
terminado o VPN siempre activa), SHALL restablecer el túnel con esa configuración, nunca con otra.

#### Scenario: Proceso terminado con el túnel activo
- **GIVEN** el túnel está activo con un perfil personalizado
- **WHEN** el sistema mata el proceso y reinicia el servicio
- **THEN** el túnel se restablece con el mismo perfil personalizado

#### Scenario: Reinicio sin permiso VPN
- **GIVEN** el usuario revocó el permiso VPN desde los ajustes
- **WHEN** el sistema reinicia el servicio
- **THEN** no se llama a `establish()` y el estado publicado es `Stopped`

#### Scenario: VPN siempre activa
- **WHEN** el usuario activa OpenNetSim como VPN siempre activa en los ajustes del sistema
- **THEN** el túnel arranca con la última configuración aplicada o, si no hay ninguna, con el perfil por defecto

### Requirement: Revocación del permiso VPN
Cuando el sistema revoque la VPN, el servicio SHALL detener el túnel de forma ordenada y SHALL
publicar un fallo cuyo motivo indique la revocación, no un error de E/S.

#### Scenario: Otra VPN toma el control
- **GIVEN** el túnel está activo
- **WHEN** otra app VPN se activa y el sistema invoca `onRevoke()`
- **THEN** el estado publicado es `Failed` con un motivo de revocación y la UI no muestra "Error leyendo el descriptor del TUN"
