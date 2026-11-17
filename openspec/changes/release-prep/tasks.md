# Tasks

## 1. R8
- [x] 1.1 Activar `isMinifyEnabled` e `isShrinkResources` en release y conservar `ThrottlePreset` en `proguard-rules.pro`; verificar con `:app:assembleRelease` que el APK baja de 16,6 MB a ~1 MB y que `mapping.txt` conserva `ThrottlePreset`, `ThrottleVpnService` y `MainActivity`
- [x] 1.2 Compilar la release en la CI; verificar que el paso aparece en `.github/workflows/ci.yml`

## 2. Firma
- [x] 2.1 Leer la firma de las propiedades `openNetSimRelease*`; verificar con un keystore desechable que `apksigner verify` acepta el APK y que, sin propiedades, se genera `app-release-unsigned.apk`
- [x] 2.2 Crear el keystore de release (usuario, fuera del repositorio) y configurar sus propiedades; verificar que `:app:assembleRelease` genera `app-release.apk` firmado con él

## 3. Interfaz
- [x] 3.1 Icono `ic_stat_opennetsim` en la notificación; verificar con `assembleDebug`
- [x] 3.2 Corregir el texto de la tarjeta informativa y retirar su entrada de la baseline de detekt; verificar que `detekt` termina con código 0

## 4. Verificación
- [x] 4.1 Ejecutar `detekt`, `lintDebug`, `:core:test`, `:app:testDebugUnitTest` (176, 1 omitido en Windows) y `assembleDebug`; verificar que terminan bien
- [ ] 4.2 Verificar que la CI de GitHub Actions termina en verde
- [x] 4.3 Verificar en dispositivo la release firmada: túnel, navegación, cambios de configuración con tráfico, configuración conservada tras cerrar la app y borrar su caché, e icono de la notificación
