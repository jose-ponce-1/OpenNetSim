---
description: Compila el código Kotlin, ejecuta los tests unitarios del motor de tráfico y genera el APK de depuración.
---

# Procedimiento de Verificación Android

## 1. Verificación Estática y Compilación
Ejecuta en el terminal:
1. `./gradlew compileDebugKotlin`
*Corrige cualquier error de tipos o advertencia crítica de API de Android antes de avanzar.*

## 2. Tests Unitarios del Motor de Red
Ejecuta la suite de pruebas unitarias locales:
1. `./gradlew testDebugUnitTest --continue`
*Comprueba específicamente los casos límite:*
- *Cálculo de tokens por milisegundo en el limitador de ancho de banda.*
- *Orden de entrega en la cola de retardo (simulación de latencia/ping).*
- *Porcentaje exacto de paquetes descartados en la simulación de pérdida.*

## 3. Ensamblado del APK (Debug)
1. Ejecuta `./gradlew assembleDebug`.
2. Comprueba que el archivo `.apk` se genere correctamente en `app/build/outputs/apk/debug/`.

## 4. Auditoría de Manifiesto
Verifica que `AndroidManifest.xml` incluya:
- Permiso `android.permission.INTERNET`.
- Permiso `android.permission.POST_NOTIFICATIONS` (Android 13+).
- Servicio con `android.permission.BIND_VPN_SERVICE` e intent-filter `android.net.VpnService`.