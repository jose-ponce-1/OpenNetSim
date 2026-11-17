# Design

## Context
Los tests son JVM (`:core:test` y `:app:testDebugUnitTest`); no hay tests instrumentados. Los tests de
`:app` ejercitan también código de `:core` (forwarders sobre el motor y los parsers). AGP 9.3.3 con el
Kotlin integrado, Kotlin 2.4.21 y Gradle 9.7.1.

## Decisions
### D1. Kover 0.9.11
Es el plugin de cobertura de JetBrains para Kotlin. La 0.9.4 añadió soporte de AGP 9, la 0.9.5 corrigió
variantes y dependencias de tests con AGP 9, y la 0.9.9 un aviso de Gradle 9.6. Comprobado: con 0.9.11
el build no muestra avisos nuevos (el único aviso de Gradle 10 sigue siendo el de detekt 1.23.8). JaCoCo
se descarta porque entiende peor el bytecode de Kotlin (funciones `inline`, clases generadas).

### D2. Variante `coverage` e informe conjunto
En un módulo Android, la variante `total` de Kover no incluye las variantes de compilación, así que se
crea `coverage`: `jvm` en `:core` y `debug` en `:app`. `kover(project(":core"))` en `:app` fusiona la
variante del mismo nombre de `:core`, de modo que el informe conjunto cuenta los tests de ambos módulos.

### D3. Exclusiones
El informe excluye las clases que solo se pueden ejecutar con el framework de Android o con Compose:
`MainActivity`, `ThrottleDashboardScreen`, `ComposableSingletons`, `ui.theme`, `ThrottleVpnService`,
`ServiceTunnelController`, `SharedPreferencesConfigStore`, `TunnelIntents`, `TunnelNotifications`,
`AndroidLogSink`, `forwarder.Android*`, `UnderlyingNetworkMonitor` y `ParcelFileDescriptorTunDevice`.
Con ellas, el porcentaje mide la lógica que los tests JVM pueden cubrir. Esas clases se verifican en
dispositivo (obligatorio para cambios en `service/`, `openspec/config.yaml`). `MainViewModel`,
`TunnelStatusStore` e `Ipv6Availability` no se excluyen: son código JVM.

### D4. Mínimo del 85 % de líneas
Medido al introducir Kover:

| Informe | Líneas | Ramas |
|---|---|---|
| `:core` (sus tests) | 86,7 % | — |
| Conjunto `:app` + `:core`, con exclusiones | 87,6 % | 66,3 % |
| Conjunto sin exclusiones | 64,0 % | — |

El mínimo queda por debajo de lo medido, con margen para cambios pequeños sin tests. Las ramas se miden
pero no se exigen: el 66 % refleja sobre todo caminos de error de red difíciles de provocar en la JVM, y
exigirlo ahora obligaría a escribir tests fuera del alcance del cambio.

### D5. En la CI
Un paso propio tras los tests (`koverVerifyCoverage` en los dos módulos, informe HTML y `koverLog`), que
reutiliza los resultados de los tests ya ejecutados. `set -o pipefail` para que `tee` no oculte un
fallo. El HTML queda en `app/build/reports/kover/`, que ya se sube como artefacto.

## Risks / Trade-offs
- La instrumentación de Kover hace los tests algo más lentos. Los tests de tiempo del motor pasan con
  ella (ejecución local completa).
- Un mínimo fijo no impide que la cobertura baje de 87 % a 85 % sin aviso; subirlo es manual.

## Migration Plan
Sin migración. Rollback: revertir el commit.
