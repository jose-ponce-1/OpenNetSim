# Design

## Context
Ver `proposal.md`.

## Goals / Non-Goals
**Goals:** una puerta de calidad en verde que rompa ante infracciones nuevas.

**Non-Goals:** saldar ahora toda la deuda previa (sobre todo números mágicos de los parsers de
paquetes, que son offsets de cabecera).

## Decisions
### D1. Un solo formateador
detekt-formatting envuelve ktlint; mantener los dos duplicaba informes y configuraciones.

### D2. Baseline en lugar de suprimir reglas
La baseline congela la deuda conocida por archivo y firma: lo nuevo se analiza con todas las reglas.
Se regenera con `./gradlew detektBaseline` cuando se salde deuda.

### D3. CI mínima
Un único job en Ubuntu con JDK 17 y `gradle/actions/setup-gradle` (caché); sube los informes como
artefacto. `gradlew` pasa a ser ejecutable en git.

## Risks / Trade-offs
- La baseline puede esconder deuda: conviene ir reduciéndola en cambios pequeños.

## Migration Plan
Sin impacto en la app.
