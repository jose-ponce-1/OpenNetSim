# Tasks

## 1. Resource Scaffolding & Vector Drawables

- [x] 1.1 Scaffold the resource directory tree under `app/src/main/res/` (`drawable/`, `mipmap-anydpi-v26/`, `mipmap-mdpi/`, `mipmap-hdpi/`, `mipmap-xhdpi/`, `mipmap-xxhdpi/`, `mipmap-xxxhdpi/`) and verify directory creation.
- [x] 1.2 Implement the brand vector asset `app/src/main/res/drawable/ic_logo_opennetsim.xml` featuring the blue rounded boundary with breaks, terminal nodes, and central cyan pulse waveform, verifying valid vector XML syntax.
- [x] 1.3 Implement the adaptive icon background drawable `app/src/main/res/drawable/ic_launcher_background.xml` (solid white layer) and verify valid XML structure.
- [x] 1.4 Implement the adaptive icon foreground vector `app/src/main/res/drawable/ic_launcher_foreground.xml` positioned within the 72dp safe zone inside the 108dp canvas, verifying safe-zone alignment.

## 2. Adaptive Icon Setup & Legacy Mipmap Generation

- [x] 2.1 Create the adaptive icon definitions `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` linking the foreground and background drawables, and verify XML validity.
- [x] 2.2 Generate legacy raster mipmap icon sets (`ic_launcher.png` and `ic_launcher_round.png`) for `mdpi` (48x48), `hdpi` (72x72), `xhdpi` (96x96), `xxhdpi` (144x144), and `xxxhdpi` (192x192), verifying dimensions and file presence.

## 3. Manifest Integration & Build Validation

- [x] 3.1 Update `app/src/main/AndroidManifest.xml` to set `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`, replacing the `@android:drawable/sym_def_app_icon` placeholder.
- [x] 3.2 Run `./gradlew compileDebugKotlin` and `./gradlew testDebugUnitTest` to verify that Android resource processing succeeds without errors and the build remains green.
