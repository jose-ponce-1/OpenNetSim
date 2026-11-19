# Design

## Context
Versiones y requisitos consultados el 19/11/2026. Las versiones salen del `maven-metadata.xml` de Google
Maven y Maven Central, y los mínimos, del `aar-metadata.properties` de cada AAR:

| Artefacto | Versión | `minCompileSdk` | AGP mínimo |
|---|---|---|---|
| `androidx.core:core` | 1.19.1 | 37 | 9.1.0 |
| `compose.ui:ui-android` (BOM 2026.11.01) | 1.13.0 | 37 | 9.1.0 |
| `compose.material3:material3-android` (BOM 2026.11.01) | 1.5.0 | 37 | 9.1.0 |
| `activity:activity` | 1.13.0 | 36 | 8.9.1 |
| `core-splashscreen` | 1.2.0 | 35 | 8.6.0 |
| `lifecycle-runtime-android` | 2.11.1 | 34 | 8.1.1 |
| `lifecycle-runtime-compose-android`, `lifecycle-viewmodel-compose-android` | 2.11.1 | 37 | — |
| `kotlinx-coroutines-core-jvm` | 1.11.0 | — | — (compilada con Kotlin 2.2.20) |

- AGP 9.3.3 admite hasta la API 37 (matriz consultada en `upgrade-toolchain`) y cumple el mínimo de 9.1.0.
- Kotlin 2.4.21 lee los metadatos de librerías compiladas con Kotlin 2.2.
- Google Play (Play Console Help, «Target API level requirements»): desde el 31/08/2026, apps nuevas y
  actualizaciones con `targetSdk` 36 o más.
- En este PC solo está instalada la plataforma `android-35`. La CI (`ubuntu-latest`) no instala
  plataformas a mano: depende de la descarga automática de AGP.
- Código afectado por Android 16: `MainActivity` vuelve a aplicar `enableEdgeToEdge()` al salir de la
  pantalla de carga (corrige un fallo de `core-splashscreen` en Android 12+) y `MainScaffold` usa
  `BackHandler` para volver a Simular. No hay `onBackPressed`, `screenOrientation` ni
  `windowOptOutEdgeToEdge`.

## Goals / Non-Goals
**Goals:** todas las librerías de la app en su última versión estable, sin cambiar lo que ve ni lo que
hace el usuario, y un `targetSdk` que Google Play acepte.

**Non-Goals:** adoptar API nuevas de Compose o Material 3, ni rediseñar nada aprovechando la subida.

## Decisions
### D1. Todo a la última estable, con `compileSdk` 37.1
Alternativa: `compileSdk` 36 con `core-ktx` 1.18.0 y un BOM anterior. Se descarta porque dejaría Compose
atrás sin ganar nada: AGP 9.3.3 ya admite la 37, y `compileSdk` no cambia el comportamiento en
ejecución.

Al aplicar el paso 3, `checkDebugAarMetadata` mostró que `foundation`, `foundation-layout`, `animation`
y `ui-tooling` 1.13.0 piden la API **37.1** (`minCompileMinorSdk`), cosa que la tabla de arriba no
recogía. Se declara con la DSL de versiones menores de AGP
(`compileSdk { version = release(37) { minorApiLevel = 1 } }`). AGP 9.3.3 la admite e instaló la
plataforma `android-37.1` por su cuenta. Alternativa: una BOM anterior que no pida la 37.1. Se descarta
por la misma razón que antes: no cambia nada en ejecución.

### D1b. `LocalResources` para los textos de la interfaz
El lint de Compose 1.13 avisa (`LocalContextResourcesRead`) de que `LocalContext.current.resources` no
está pendiente de la configuración. `UiText.asString()` leía antes `LocalConfiguration.current` para
forzar la recomposición al cambiar de idioma. `LocalResources.current` hace las dos cosas a la vez, así
que se usa en su lugar. Se comprueba cambiando el idioma en el emulador (5.2).

### D2. `targetSdk` 36 y no 37
36 es lo mínimo que acepta Play hoy. Pasar a 37 sumaría otra ronda de cambios de comportamiento
(Android 17) sin que nadie lo pida todavía. Se hará en su propio cambio cuando Play lo exija.

### D3. Subida por pasos dentro de la misma rama
Orden: (1) corrutinas, que no piden SDK nuevo; (2) `compileSdk` 37 con `core-ktx`, `lifecycle`,
`activity-compose` y `core-splashscreen` (los artefactos de Compose de `lifecycle` 2.11.1 exigen la 37:
lo detectó `checkDebugAarMetadata` al aplicar el paso 1); (3) Compose BOM; (4) `targetSdk` 36. Después de cada paso se
compila y se pasan los tests. Si algo se rompe, se sabe qué paso lo causó, y el `targetSdk` (lo único
que cambia el comportamiento) se prueba aparte. Alternativa: subirlo todo de golpe. Es más rápido, pero
cuesta más averiguar qué paso rompió algo.

### D4. Avisos de API obsoleta: se migran, no se silencian
No hay baselines de detekt ni de lint, y el proyecto no usa `@Suppress` para tapar avisos. Cada API
obsoleta se sustituye por su reemplazo. Si un reemplazo cambiara algo visible, se anota y se revisa en
el emulador.

### D5. Se mantiene el arreglo de edge-to-edge de `MainActivity`
`core-splashscreen` 1.2.0 podría haber corregido el fallo de las barras del sistema, pero volver a
llamar a `enableEdgeToEdge()` no hace daño. Se quita solo si se comprueba en API 31+ que ya no hace
falta, y nunca a ciegas.

### D5b. Se conserva el aspecto del prototipo frente a los nuevos valores de Material 3
Al comparar en el emulador con API 37 (5.2) las capturas de la 1.0.1 con las de Material 3 1.5, salieron
tres cambios visibles, todos de los valores por defecto de la librería:
1. `OutlinedButton` («Ajustar», «Ver el código en GitHub»): el contenido pasa de `primary` a
   `onSurfaceVariant` y el borde de `outline` a `outlineVariant`.
2. `NavigationBarItem`: la etiqueta elegida pasa de `onSurface` a `secondary`, y el indicador es más
   estrecho.
3. La hoja de perfiles se abre unos 35 px más arriba.

Decisión del usuario (19/11/2026): mantener el aspecto de la 1.0.1. `AppOutlinedButton` (en
`ui/components`) fija el contenido `primary` y el borde `outline`, y la barra inferior fija sus cinco
colores de antes. Se aceptan como diferencias menores el ancho del indicador, que sale de un token
interno (`NavigationBarVerticalItemTokens.ActiveIndicatorWidth`) que la API pública no deja cambiar, y
la posición de la hoja. Después del ajuste, las capturas solo difieren en esas dos cosas y en el
fotograma de las animaciones.

### D6. Plataforma 37 explícita en la CI solo si hace falta
Primero se confía en la descarga automática de AGP (las licencias ya están aceptadas en `ubuntu-latest`).
Si la CI falla por falta de la plataforma, se añade un paso con `sdkmanager "platforms;android-37.1"`.

## Risks / Trade-offs
- [Material 3 1.3 → 1.5 cambia medidas, colores o animaciones de componentes] → comparar en el emulador
  con API 37 (claro y oscuro, pantalla estrecha) las cinco pantallas del prototipo antes y después.
- [Android 16: «atrás» predictivo por defecto] → comprobar que «Atrás» desde Parámetros o Telemetría
  vuelve a Simular y que un segundo «Atrás» sale con la animación del sistema. El escenario de
  `app-navigation` debe seguir igual.
- [Android 16: edge-to-edge obligatorio] → revisar en API 37 la pantalla de carga, la barra de estado y
  la de navegación, en claro y en oscuro.
- [Cambios de comportamiento de Android 16 en el servicio en primer plano o en `VpnService`] → la regla
  del proyecto ya exige verificar en dispositivo los cambios en `service/`: activar y detener el túnel,
  cambios en caliente, ping y restauración tras reinicio.
- [R8: reglas nuevas o distintas en las librerías] → `assembleRelease` en la puerta de calidad y prueba de
  la release firmada en dispositivo. Vigilar el tamaño (hoy 1,28 MB).
- [Corrutinas 1.10/1.11 cambian el comportamiento de `runTest` o de los dispatchers de prueba] → la suite
  de `:app` y `:core` (250 tests) lo detecta. Se corrige el test solo si el cambio es intencionado en la
  librería.
- [Memoria: Gradle y el emulador juntos agotaron la RAM del portátil del usuario] → en este PC de
  sobremesa no ha sido un problema; si alguna tarea muere por memoria, `gradlew --stop` antes de arrancar
  el emulador.

## Migration Plan
Sin migración de datos: la configuración guardada (`ConfigStore`) y los perfiles no cambian de formato.
Instalar encima de la 1.0.1 conserva todo. Rollback: revertir los commits de la rama.
