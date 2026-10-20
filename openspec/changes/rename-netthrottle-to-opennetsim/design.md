# Design

## Context

El proyecto actual consta de 26 archivos Kotlin en `app/src/main/java/com/netthrottle` y 11 archivos de tests unitarios en `app/src/test/java/com/netthrottle`. Además, la configuración del proyecto en `settings.gradle.kts` asigna `rootProject.name = "NetThrottle"`, y `app/build.gradle.kts` define `namespace = "com.netthrottle"` y `applicationId = "com.netthrottle"`.

Para completar la migración de identidad a "OpenNetSim", se debe realizar un cambio transversal coordinado en la jerarquía del sistema de archivos, las declaraciones de paquetes e importaciones, los identificadores del sistema operativo Android y la interfaz gráfica.

Ver [proposal.md](proposal.md) para detalles sobre la justificación y alcance.

## Goals / Non-Goals

**Goals:**
- Renombrar el proyecto en Gradle (`settings.gradle.kts`) a `"OpenNetSim"`.
- Modificar el `namespace` y `applicationId` en `app/build.gradle.kts` a `"com.opennetsim"`.
- Actualizar `android:label` en `AndroidManifest.xml` a `"OpenNetSim"`.
- Reubicar físicamente los paquetes Kotlin de `com/netthrottle` a `com/opennetsim` tanto en `src/main` como en `src/test`.
- Actualizar todas las sentencias `package` e `import` en los 37 archivos Kotlin del repositorio.
- Renombrar `NetThrottleTheme` a `OpenNetSimTheme` y actualizar su invocación en `MainActivity.kt`.
- Renombrar títulos, textos informativos y constantes de servicio (`ACTION_START`, `ACTION_STOP`, `ACTION_UPDATE_CONFIG`, `CHANNEL_ID`, nombre de sesión TUN y título de notificación).
- Validar mediante build incremental y tests unitarios que la compilación sea limpia y el 100% de los tests pasen exitosamente.

**Non-Goals:**
- No se altera la lógica interna de los algoritmos de throttling (`TokenBucketLimiter`, `LatencyQueue`, `PacketLossSimulator`, `BidirectionalThrottlingEngine`).
- No se modifica el protocolo de forwarders NIO (`TcpForwarder`, `UdpForwarder`, `IcmpResponder`).
- No se preservan datos locales o configuraciones previas de la app instalada bajo el antiguo package name (se asume instalación limpia para entornos de prueba/desarrollo).

## Decisions

### 1. Reubicación física de directorios de paquetes
Se moverán los submódulos (`core`, `model`, `service`, `ui`) desde `app/src/main/java/com/netthrottle/` hacia `app/src/main/java/com/opennetsim/`, y de manera idéntica en `app/src/test/java/`.
- *Razón*: El compilador de Kotlin y el Android Gradle Plugin siguen la convención Java estándar donde la ruta del archivo refleja el paquete.
- *Alternativa descartada*: Mantener carpetas con nombres discordantes con el paquete, lo cual genera confusión en IDEs y herramientas estáticas.

### 2. Sustitución consistente de sentencias de paquete e importaciones
Se sustituirá toda ocurrencia de `com.netthrottle` por `com.opennetsim` de manera exhaustiva en:
- `package com.netthrottle.*` → `package com.opennetsim.*`
- `import com.netthrottle.*` → `import com.opennetsim.*`
- *Razón*: Garantiza que no queden referencias huérfanas o dependencias circulares entre el paquete antiguo y el nuevo.

### 3. Componentes de UI y Tema Compose
Se renombrará la función composable `NetThrottleTheme` a `OpenNetSimTheme` en `ui/theme/Theme.kt` y se actualizará su uso en `ui/MainActivity.kt`. Se actualizará el texto del TopAppBar y el banner de información en `ui/ThrottleDashboardScreen.kt` a "OpenNetSim".
- *Razón*: Mantiene consistencia visual y de nomenclatura dentro del sistema de diseño Material 3.

### 4. Parámetros de VpnService y Notificaciones Android
En `service/ThrottleVpnService.kt`:
- `ACTION_START = "com.opennetsim.action.START"`
- `ACTION_STOP = "com.opennetsim.action.STOP"`
- `ACTION_UPDATE_CONFIG = "com.opennetsim.action.UPDATE_CONFIG"`
- `CHANNEL_ID = "open_net_sim_vpn_channel"`
- Nombre del canal de notificación: `"OpenNetSim VPN Service"`
- Título de notificación: `"OpenNetSim Activo"`
- Sesión TUN: `.setSession("OpenNetSim")`
- *Razón*: El canal y las acciones deben reflejar la nueva identidad para evitar colisiones con posibles servicios o intents legacy.

## Risks / Trade-offs

- **[Riesgo: Conflictos en la caché de compilación de Gradle tras mover directorios]**  
  → *Mitigación*: Ejecutar un `./gradlew clean` previo o inmediato a la verificación de compilación.
- **[Riesgo: Referencias no resueltas en el AndroidManifest]**  
  → *Mitigación*: `AndroidManifest.xml` utiliza referencias relativas (`.ui.MainActivity`, `.service.ThrottleVpnService`), las cuales automáticamente adoptan el nuevo `namespace = "com.opennetsim"`. Se verificará explícitamente con `compileDebugKotlin`.
- **[Riesgo: Omitir alguna referencia en tests unitarios o imports implícitos]**  
  → *Mitigación*: Ejecutar la suite completa `./gradlew testDebugUnitTest` antes de dar por finalizada la fase de aplicación.

## Migration Plan

1. Actualizar configuración de Gradle (`settings.gradle.kts`, `app/build.gradle.kts`) y `AndroidManifest.xml`.
2. Crear directorios de destino `com/opennetsim` y migrar carpetas en `src/main` y `src/test`, eliminando los directorios antiguos `com/netthrottle`.
3. Actualizar directivas de paquete, importaciones y referencias a nombres de clases/temas/strings en el código Kotlin.
4. Actualizar `project-state.md`.
5. Ejecutar `./gradlew compileDebugKotlin` y `./gradlew testDebugUnitTest` para certificar la compilación limpia y la salud de los tests.
