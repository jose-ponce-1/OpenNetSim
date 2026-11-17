# Spec Delta

## REMOVED Requirements

### Requirement: Strict FIFO Uplink Scheduling
**Reason**: Sustituido por el planificador de dos carriles de `traffic-shaping` ("Los paquetes de control respetan el orden de su flujo" y "Tráfico interactivo frente a transferencias masivas"): el FIFO estricto entre todos los flujos hacía esperar a los ACK y al DNS detrás de las subidas masivas. El orden se garantiza ahora por flujo, no global.
**Migration**: Ninguna en el código; los requisitos vigentes están en `traffic-shaping`.

### Requirement: Bounded Uplink Queue Backpressure
**Reason**: Sustituido por "Profundidad de cola acotada por tiempo" y "Sin bloqueo de la lectura de subida" de `traffic-shaping`: la cota pasa de un número fijo de paquetes a una ventana de tiempo de serialización a la tasa configurada.
**Migration**: Ninguna en el código; los requisitos vigentes están en `traffic-shaping`.
