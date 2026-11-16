# Proposal

## Why

El proyecto requiere migrar formalmente su identidad de "NetThrottle" a "OpenNetSim" para alinearse con el nombre del repositorio y reflejar de manera precisa su alcance como simulador completo de condiciones de red deficientes en Android. Esta refactorización unifica la identidad del proyecto en la configuración de Gradle, el `applicationId` y `namespace` de Android, la estructura de paquetes Kotlin, la interfaz de usuario y las notificaciones del sistema.

## What Changes

- **settings.gradle.kts**: Renombrar `rootProject.name` de `"NetThrottle"` a `"OpenNetSim"`.
- **app/build.gradle.kts**: Modificar `namespace` y `applicationId` de `"com.netthrottle"` a `"com.opennetsim"`.
- **AndroidManifest.xml**: Actualizar `android:label` a `"OpenNetSim"`.
- **Reestructuración de paquetes Kotlin (src/main y src/test)**:
  - Mover `app/src/main/java/com/netthrottle/` a `app/src/main/java/com/opennetsim/`.
  - Mover `app/src/test/java/com/netthrottle/` a `app/src/test/java/com/opennetsim/`.
  - Actualizar todas las directivas `package com.netthrottle.*` a `package com.opennetsim.*`.
  - Actualizar todas las importaciones `import com.netthrottle.*` a `import com.opennetsim.*`.
- **Tema y UI en Jetpack Compose**:
  - Renombrar el tema `@Composable fun NetThrottleTheme` a `OpenNetSimTheme` en `Theme.kt` y actualizar su invocación en `MainActivity.kt`.
  - Actualizar el título de la TopAppBar de `"NetThrottle"` a `"OpenNetSim"` en `ThrottleDashboardScreen.kt`.
  - Actualizar el texto descriptivo informativo de la interfaz en `ThrottleDashboardScreen.kt`.
  - Actualizar documentación interna y KDoc en `MainViewModel.kt` y pantallas relacionadas.
- **Servicio VPN y Notificaciones**:
  - Renombrar la sesión TUN `.setSession("OpenNetSim")` en `ThrottleVpnService.kt`.
  - Actualizar el título de notificación en primer plano a `"OpenNetSim Activo"`.
  - Actualizar el nombre del canal de notificación a `"OpenNetSim VPN Service"`.
  - Actualizar el identificador del canal a `"open_net_sim_vpn_channel"`.
  - Actualizar las constantes de acción de intent (`com.opennetsim.action.START`, `STOP`, `UPDATE_CONFIG`).
- **Documentación del proyecto**:
  - Actualizar `project-state.md` reflejando el nuevo nombre de la aplicación y paquetes.

## Capabilities

### New Capabilities
<!-- None: Este cambio es una refactorización estructural y de branding sin nuevas capacidades funcionales. -->

### Modified Capabilities
<!-- None: Las especificaciones de comportamiento del motor de red, forwarding y degradación se mantienen intactas. Se ha establecido skip_specs: true en .openspec.yaml. -->

## Impact

- **Código y paquetes**: Todos los archivos fuente Kotlin y tests unitarios dentro del proyecto migran al paquete raíz `com.opennetsim`.
- **Identidad de la aplicación**: Al cambiar el `applicationId` a `com.opennetsim`, Android lo tratará como una aplicación nueva al desplegarse en dispositivos/emuladores.
- **Acciones y Servicios**: Las acciones explícitas de Intent para comunicarse con el servicio en segundo plano pasan a tener el prefijo `com.opennetsim.action.*`.
- **Estabilidad funcional**: No se modifican los algoritmos de throttling (Token Bucket, colas de latencia, simulador de pérdida), la lógica de forwarders NIO TCP/UDP ni los parsers IPv4/IPv6.
