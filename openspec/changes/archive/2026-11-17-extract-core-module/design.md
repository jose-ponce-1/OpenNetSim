# Design

## Context
`core/` y `model/` no importaban nada fuera de ellos mismos, de la biblioteca estándar de Kotlin y de
`java.*`: se pueden mover sin tocar código.

## Goals / Non-Goals
**Goals:** que el compilador impida usar Android en el motor; versiones en un único sitio.

**Non-Goals:** cambiar paquetes, APIs o versiones.

## Decisions
### D1. `model/` va con `core/`
El motor usa `ThrottleConfig` (`fromConfig`, `uplinkParams`). Dejar `model/` en `:app` obligaría a
`:core` a depender de `:app`; como `model/` también es Kotlin puro, va a `:core` y conserva su paquete.

### D2. `java-test-fixtures` para el reloj simulado
`FakeMonotonicClock` lo usan tests de ambos módulos. Como *test fixtures* no entra en el código de
producción y `:app` lo consume con `testImplementation(testFixtures(project(":core")))`.

### D3. detekt común en el script raíz
`subprojects { pluginManager.withPlugin("io.gitlab.arturbosch.detekt") { … } }` aplica la misma
configuración a cada módulo que use detekt, con `baseline-<módulo>.xml`. Las reglas de Compose se
cargan en todos los módulos porque `detekt.yml` las configura y, sin el plugin, la configuración no
valida (en `:core` no encuentran nada que revisar).

## Risks / Trade-offs
- Las baselines se regeneraron al separarlas: pasan de 142 a 127 entradas (se eliminan las de código
  que ya no existe) y ninguna entrada es nueva respecto a la baseline anterior.

## Migration Plan
Sin efecto en la app instalada. Rollback: revertir el commit.
