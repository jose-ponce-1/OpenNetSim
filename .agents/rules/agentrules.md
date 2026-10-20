---
trigger: always_on
---

# Directrices de Desarrollo: Android Network Throttler (VpnService)

## 1. Stack Tecnológico y Arquitectura
- Lenguaje: Kotlin (modo estricto, corrutinas y Flow).
- UI: Jetpack Compose con Material 3.
- Arquitectura desacoplada (Clean Architecture):
  - `core/engine/`: Lógica pura de throttling (algoritmo Token Bucket para límites de subida/bajada, colas de retardo para latencia/jitter, descarte probabilístico para packet loss). No debe contener dependencias de clases del framework de Android.
  - `service/`: Implementación de `VpnService`, ciclo de vida del `ForegroundService`, gestión de notificaciones persistentes y manejo de I/O en la interfaz TUN (`ParcelFileDescriptor`).
  - `ui/`: Pantallas Compose, ViewModels y estado reactivo.

## 2. Restricciones del Sistema Operativo Android
- Manejo de VPN: Recuerda que `VpnService.prepare(context)` devuelve un `Intent` si el usuario aún no ha otorgado permisos. La UI debe interceptar el resultado con `rememberLauncherForActivityResult`.
- Servicios en Primer Plano (Foreground Service): A partir de Android 14 (API 34), declara explícitamente `foregroundServiceType="specialUse"` o el tipo aplicable en `AndroidManifest.xml` y gestiona el canal de notificaciones antes de llamar a `startForeground()`.
- Gestión de Memoria y Paquetes: Para evitar bloqueos del hilo principal o saturación del GC, reutiliza buffers de bytes (`ByteBuffer`) durante la lectura/escritura de la interfaz TUN.

## 3. Reglas de Terminal y Build
- Verificación incremental: No ejecutes tareas pesadas completas innecesariamente. Valida primero con `./gradlew compileDebugKotlin` y `./gradlew testDebugUnitTest`.
- Prohibido modificar dependencias mayores en `build.gradle.kts` sin verificar compatibilidad de versiones con el Android Gradle Plugin (AGP).
- Tras crear o refactorizar lógica matemática del motor (Token Bucket / Colas), ejecuta siempre la suite de tests unitarios antes de dar el paso por terminado.