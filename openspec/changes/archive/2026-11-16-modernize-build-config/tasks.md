# Tasks

## 1. Modernizar Kotlin DSL en el Módulo App

- [x] 1.1 Reemplazar el bloque deprecado `kotlinOptions` por `compilerOptions` en `app/build.gradle.kts` fijando `jvmTarget` a JVM 17 y verificar que no genere advertencias de sintaxis.
- [x] 1.2 Ejecutar `.\gradlew.bat compileDebugKotlin` para verificar que el código compile sin advertencias de DSL obsoletas.

## 2. Verificación de Tests y Suite Completa

- [x] 2.1 Ejecutar `.\gradlew.bat testDebugUnitTest` y verificar que la suite completa de pruebas unitarias continúe pasando al 100%.
- [x] 2.2 Ejecutar `.\gradlew.bat assembleDebug` y verificar la generación exitosa del APK de depuración.
