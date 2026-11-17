# Tasks

## 1. Versiones
- [x] 1.1 Consultar las matrices oficiales de AGP 9.3/9.5, Kotlin y Gradle y elegir la combinación probada; verificar que AGP 9.3.3, KGP 2.4.21 y Gradle 9.7.1 caen dentro de ellas
- [x] 1.2 Subir AGP a 9.3.3, Kotlin a 2.4.21 y detekt a 1.23.8 en `gradle/libs.versions.toml`; verificar con `buildEnvironment` que el KGP efectivo es 2.4.21

## 2. Kotlin integrado
- [x] 2.1 Retirar el plugin `org.jetbrains.kotlin.android` de `:app`, del script raíz y del catálogo, y el bloque `kotlin { jvmTarget }` de `:app`; verificar con `:core:compileKotlin :app:compileDebugKotlin`
- [x] 2.2 Sustituir `Object()` con `wait`/`notifyAll` en `IcmpEchoTransport.UNAVAILABLE` por un `Semaphore`; verificar que la compilación no muestra avisos de Kotlin

## 3. Calidad
- [x] 3.1 Verificar que detekt 1.23.8 analiza con AGP 9 (marca un archivo de prueba con problemas) y que `detekt` termina con código 0
- [x] 3.2 Ejecutar `lintDebug`, `:core:test`, `:app:testDebugUnitTest` (176, 1 omitido en Windows) y `assembleDebug`; verificar que terminan bien
- [ ] 3.3 Verificar que la CI de GitHub Actions termina en verde
- [ ] 3.4 Verificar en dispositivo: activar y detener el túnel, navegar, ping y cambios de configuración con tráfico
