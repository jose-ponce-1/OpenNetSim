# vpn-tunnel-lifecycle Specification

## Purpose
Define cómo se arranca, configura, mantiene y detiene el túnel VPN local, y cómo su estado real
llega a la interfaz, de modo que el túnel nunca quede activo sin reenviar tráfico.

## Requirements

### Requirement: Familias de direcciones según la red física
Al establecer el túnel, el servicio SHALL enrutar IPv6 y anunciar servidores DNS IPv6 solo si la red
física activa tiene una ruta IPv6 por defecto; si no, SHALL dejar IPv6 fuera del túnel para que el
sistema lo bloquee y las apps usen IPv4 al instante.

#### Scenario: Red solo IPv4
- **WHEN** el túnel se activa en una red Wi-Fi o un emulador sin conectividad IPv6
- **THEN** el túnel no tiene dirección, ruta ni DNS IPv6 y las conexiones IPv6 fallan de inmediato en el propio dispositivo

#### Scenario: Red de doble pila
- **WHEN** el túnel se activa en una red con ruta IPv6 por defecto
- **THEN** el túnel enruta IPv4 e IPv6 y anuncia DNS de ambas familias

#### Scenario: Cambio de red con el túnel activo
- **WHEN** el dispositivo pasa de una red de doble pila a una solo IPv4
- **THEN** el servicio reconfigura el túnel para dejar de enrutar IPv6

### Requirement: Red del túnel no medida
El túnel SHALL declararse como red no medida para que las apps no reduzcan calidad ni difieran
descargas por considerarla de datos limitados.

#### Scenario: App que respeta redes medidas
- **WHEN** el túnel está activo sobre una Wi-Fi no medida
- **THEN** el sistema informa a las apps de que la red activa no es medida

### Requirement: Bucles de reenvío resilientes
Un error al procesar un paquete concreto nunca SHALL detener los bucles de lectura, de reenvío
ni de entrega del túnel; ese paquete se descarta, el error se registra y el bucle continúa.

#### Scenario: Excepción inesperada en un paquete
- **WHEN** procesar un paquete lanza una excepción no prevista
- **THEN** el paquete se descarta, el error queda registrado y el resto del tráfico sigue fluyendo en ambas direcciones

#### Scenario: Fallo no recuperable de un bucle
- **WHEN** un bucle no puede continuar (p. ej. el descriptor del túnel se invalida)
- **THEN** el servicio detiene el túnel por completo y publica que ya no está activo

### Requirement: El estado mostrado refleja el servicio
La interfaz SHALL mostrar el túnel como activo solo cuando el servicio haya establecido el túnel, y
SHALL mostrar un error si el servicio no logra establecerlo.

#### Scenario: Fallo al establecer el túnel
- **WHEN** el usuario activa el interruptor y el sistema rechaza establecer el túnel
- **THEN** el interruptor vuelve a apagado y se muestra un mensaje con el motivo

#### Scenario: Recreación de la actividad
- **WHEN** la actividad se recrea (rotación) con el túnel activo
- **THEN** el interruptor se muestra activo sin reenviar la orden de arranque

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

### Requirement: Diagnóstico de fallos de red
El túnel SHALL registrar en el log del sistema, con una etiqueta común y frecuencia limitada, los
fallos de conexión, de creación o protección de sockets, de los bucles y de establecimiento del túnel.

#### Scenario: Conexión exterior rechazada
- **WHEN** falla la conexión a un servidor exterior
- **THEN** el log contiene una entrada con protocolo, destino y causa, sin datos del payload

#### Scenario: Ráfaga de errores repetidos
- **WHEN** el mismo tipo de error se repite cientos de veces por segundo
- **THEN** el log contiene como máximo unas pocas entradas por segundo de ese tipo y un contador de las suprimidas

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
