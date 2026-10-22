# OpenNetSim 🌐📱

> **Estado del Proyecto:** 🚧 **Work in Progress (WIP)**  
> Este proyecto se encuentra en fase activa de desarrollo y pruebas.

**OpenNetSim** es una aplicación moderna para Android diseñada para simular y emular condiciones de red adversas o degradadas (como 3G lento, Edge, Wi-Fi inestable, alta latencia o pérdida de paquetes) de forma granular y sin necesidad de permisos de superusuario (`root`).

---

## 🎯 ¿Qué se quiere conseguir?

El objetivo principal de OpenNetSim es proporcionar a desarrolladores, testers y entusiastas de redes una herramienta ligera y fiable en el propio dispositivo Android para evaluar la resiliencia y el comportamiento de aplicaciones móviles ante escenarios de conectividad realistas y hostiles.

### Objetivos Clave:
- **Sin Root (`VpnService` local):** Interceptar el tráfico de red de forma transparente a través de la API oficial de Android `VpnService` e interfaz TUN en espacio de usuario.
- **Throttling Bidireccional Asimétrico:** Control independiente de velocidad de subida (*uplink*) y bajada (*downlink*) mediante el algoritmo **Token Bucket**.
- **Inyección de Latencia y Jitter:** Simulación de retardo de ida y vuelta (RTT) y variación temporal mediante colas de retardo programadas.
- **Pérdida de Paquetes (Packet Loss):** Simulación probabilística de descarte de paquetes IP.
- **Pila de Reenvío en Espacio de Usuario (L3/L4):** Procesamiento nativo en Kotlin para TCP (con control de flujo), UDP (DNS y streaming) e ICMP (soporte para comandos `ping`), compatible con Dual-Stack **IPv4 e IPv6**.
- **Interfaz Intuitiva y Telemetría en Vivo:** UI construida con **Jetpack Compose** y Material 3, equipada con presets rápidos (3G, Flaky Wi-Fi, satélite, etc.), ajustes manuales y métricas de consumo y descarte en tiempo real.

---

## 🏗️ Arquitectura del Proyecto

OpenNetSim sigue los principios de **Clean Architecture** para garantizar desacoplamiento, alto rendimiento y testabilidad unitaria:

```text
OpenNetSim/
├── core/
│   ├── engine/    # Lógica matemática pura: TokenBucketLimiter, LatencyQueue, PacketLossSimulator
│   ├── packet/    # Parsers, serializadores y cálculo de checksums (IPv4, IPv6, TCP, UDP, ICMP)
│   └── buffer/    # Gestión eficiente de memoria y reciclaje (ByteBufferPool sin contención)
├── service/       # VpnService, ForegroundService, túnel TUN y forwarders de red en userspace
├── model/         # Modelos inmutables de configuración, presets y telemetría
└── ui/            # Pantallas reactivas en Jetpack Compose, ViewModels y diseño Material 3
```

---

## 🛠️ Requisitos y Compilación

- **Android SDK:** Min SDK 26 (Android 8.0) / Target SDK 34 (Android 14)
- **Lenguaje:** Kotlin 2.0+
- **Build System:** Gradle (Kotlin DSL)

### Comandos de Verificación

```bash
# Compilar código Kotlin
./gradlew compileDebugKotlin

# Ejecutar tests unitarios (JVM sin emulador)
./gradlew testDebugUnitTest

# Generar APK de depuración
./gradlew assembleDebug
```

---

## 📄 Licencia

Este proyecto está bajo la Licencia Apache 2.0. Consulta el archivo [LICENSE.txt](file:///c:/Users/josea/Documents/PROYECTOS/OpenNetSim/LICENSE.txt) para más detalles.
