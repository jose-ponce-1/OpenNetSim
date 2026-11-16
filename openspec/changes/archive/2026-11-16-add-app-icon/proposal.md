# Proposal

## Why

OpenNetSim currently defaults to Android's generic system placeholder icon (`@android:drawable/sym_def_app_icon`) in `AndroidManifest.xml`, without dedicated visual branding or custom resource assets. Introducing the official OpenNetSim logo as the application's launcher icon establishes professional visual branding, makes the app immediately identifiable on Android home screens and app switchers, and ensures compliance with modern Android adaptive icon standards.

## What Changes

- Create the official OpenNetSim vector drawable asset based on the provided SVG logo (dark blue rounded frame with breaks, light cyan signal line and waveform pulse, and connection node terminals).
- Generate adaptive launcher icon resources (`ic_launcher.xml` and `ic_launcher_round.xml`) in `res/mipmap-anydpi-v26/` with matching foreground vector layer (`ic_launcher_foreground.xml`) and solid/styled background layer (`ic_launcher_background.xml`).
- Generate legacy raster mipmap icon sets (`mipmap-mdpi`, `mipmap-hdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi`) for compatibility with launchers prior to Android 8.0 (API 26).
- Update `app/src/main/AndroidManifest.xml` to reference `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` instead of the system default placeholder icons.

## Capabilities

### New Capabilities
- `app-branding`: Defines application visual branding, launcher icon resources (adaptive and legacy), and manifest identity attributes for OpenNetSim across supported Android platform versions.

### Modified Capabilities
*(None. There are no existing capability specs in the repository).*

## Impact

- **Resources**: Adds `res/drawable/` and `res/mipmap-*/` resource directories and files inside `app/src/main/res/`.
- **Manifest**: Modifies `app/src/main/AndroidManifest.xml` (`android:icon` and `android:roundIcon` attributes).
- **Runtime & UI**: Replaces the generic Android default launcher icon in home screen launchers, notification shade/settings, and recent app cards. No breaking changes or functional engine impact.
