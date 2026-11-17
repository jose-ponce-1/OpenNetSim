# Design

## Context
La app no usa reflexión. AndroidX, Compose y las corrutinas traen sus reglas de R8, y los componentes del
manifiesto (`MainActivity`, `ThrottleVpnService`) se conservan solos. `ConfigStore` y `TunnelIntents`
guardan el preset como `preset.name` y lo buscan en `ThrottlePreset.entries`.

## Decisions
### D1. Regla `-keep` para `ThrottlePreset`
El nombre del preset se persiste; si R8 optimizara el enum, la configuración guardada podría dejar de
leerse tras una actualización. El resto de enums no se persisten.

### D2. Firma por propiedades de Gradle
`openNetSimReleaseStoreFile`, `openNetSimReleaseStorePassword`, `openNetSimReleaseKeyAlias` y
`openNetSimReleaseKeyPassword`, en `~/.gradle/gradle.properties` o como `ORG_GRADLE_PROJECT_<nombre>` en la
CI. Nombres sin puntos para que valgan como variables de entorno. Si falta `openNetSimReleaseStoreFile`
no se crea la configuración y la release sale sin firmar, así que la CI y cualquier clon compilan sin
secretos. El keystore lo crea el usuario con `keytool`, fuera del repositorio, y la contraseña no pasa por
el agente.

### D3. Icono de notificación vectorial
Android solo usa el canal alfa del icono pequeño: se dibuja en blanco con los trazos del logo (marco y
pulso), más gruesos para que se lean a 24 dp; sin los puntos de los extremos, que a ese tamaño se funden.

## Risks / Trade-offs
- R8 puede romper en ejecución lo que compila: se verifica la release firmada en dispositivo.
- La release y la debug tienen el mismo `applicationId` con firmas distintas: para instalar una sobre la
  otra hay que desinstalar antes (se pierde la configuración guardada).

## Migration Plan
Sin efecto en la debug instalada. Rollback: revertir el commit.
