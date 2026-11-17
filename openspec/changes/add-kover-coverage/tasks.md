# Tasks

## 1. Kover
- [x] 1.1 Añadir Kover 0.9.11 al catálogo y aplicarlo en `:core` y `:app`; verificar con `tasks --group verification --warning-mode all` que aparecen las tareas de Kover sin avisos nuevos de Gradle
- [x] 1.2 Variante `coverage` (`jvm` en `:core`, `debug` en `:app`) con `kover(project(":core"))`; verificar que `:app:koverLogCoverage` cuenta clases de `:core`
- [x] 1.3 Excluir del informe de `:app` el código que solo corre con Android o Compose; verificar en `reportCoverage.xml` que no aparecen y que `MainViewModel` sigue (resultado: 87,6 % de líneas)
- [x] 1.4 Mínimo del 85 % de líneas en `:core` y en el informe conjunto; verificar que `koverVerifyCoverage` pasa y que falla con un mínimo del 95 %

## 2. CI y documentación
- [x] 2.1 Paso "Cobertura (Kover)" en la CI con `pipefail` y el porcentaje en el resumen del job; verificar el YAML
- [x] 2.2 Añadir la verificación de cobertura a la puerta de calidad de `openspec/config.yaml`, al README y a `project-state.md`; verificar con `openspec validate add-kover-coverage --strict`
- [ ] 2.3 Verificar que la CI de GitHub Actions termina en verde y que el resumen del job muestra la cobertura
