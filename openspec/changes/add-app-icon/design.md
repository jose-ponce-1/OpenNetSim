# Design

## Context

OpenNetSim's Android project currently lacks an `app/src/main/res/` resource tree, relying on `@android:drawable/sym_def_app_icon` in `AndroidManifest.xml`. Android 8.0 (API 26) introduced adaptive icons, which require two layers (foreground and background) rendered inside a 108dp x 108dp canvas with a central 72dp safe zone.

The attached brand logo consists of:
- A dark blue (`#0066CC` / `#0070BA`) rounded rectangular border with breaks on the left and right horizontal centers.
- Cyan/aqua (`#00C8F8` / `#22D3EE`) terminal circular nodes at the left entry and right exit.
- A dynamic cyan/aqua pulse waveform traversing horizontally through the center.
- A clean white background canvas.

See `proposal.md` for motivation and `specs/app-branding/spec.md` for formal requirements.

## Goals / Non-Goals

**Goals:**
- Provide a high-fidelity Android Vector Drawable representation of the brand logo in `res/drawable/ic_logo_opennetsim.xml`.
- Provide an adaptive icon setup (`res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`) with:
  - `res/drawable/ic_launcher_background.xml` (solid white `#FFFFFF` layer).
  - `res/drawable/ic_launcher_foreground.xml` (centered brand logo scaled to conform strictly to the 72dp safe zone).
- Provide legacy raster mipmap icon sets (`mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`) for backward compatibility with pre-API 26 Android launchers.
- Update `app/src/main/AndroidManifest.xml` to link `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.

**Non-Goals:**
- Dynamic runtime launcher icon swapping (Activity Aliases).
- Themed monochrome icon overrides (Material You dynamic theming) for this initial iteration (can be added as an optional layer later).

## Decisions

### 1. Vector Drawable for Adaptive Icon Foreground vs. Raster PNGs
- **Decision:** Implement `ic_launcher_foreground.xml` as an Android Vector Drawable (`<vector>`) with native path and stroke definitions.
- **Rationale:** Ensures razor-sharp rendering on all screen densities (from hdpi to xxxhdpi) without scaling blur, minimizes APK file size, and avoids compression artifacts.
- **Alternatives Considered:** High-res PNG foreground layer. Rejected due to asset scaling overhead and larger binary footprint.

### 2. Viewport Scaling & Safe Zone Placement (108dp Canvas with 72dp Safe Zone)
- **Decision:** Define `ic_launcher_foreground.xml` with a `viewportWidth="108"` and `viewportHeight="108"`, positioning the logo graphic within the central 72dp x 72dp safe zone (offset margins of 18dp on each side).
- **Rationale:** Android launchers apply arbitrary masks (circle, squircle, rounded rectangle, teardrop). Positioning all elements (including the blue rounded frame and cyan terminal nodes) inside the 72dp inner circle prevents any element from being clipped by aggressive launcher masks.
- **Alternatives Considered:** Full-bleed icon canvas. Rejected because circular launcher masks (Pixel Launcher, Samsung One UI) would clip the rounded corners and terminal dots.

### 3. Distinct Standalone Vector for In-App UI (`ic_logo_opennetsim.xml`)
- **Decision:** Provide a separate vector drawable `res/drawable/ic_logo_opennetsim.xml` without the 108dp adaptive icon padding.
- **Rationale:** Allows Jetpack Compose screens (such as top app bars, about dialogs, or splash elements) to render the logo naturally with `painterResource(R.drawable.ic_logo_opennetsim)` without having to counter-pad adaptive icon margins.

### 4. Legacy Mipmap Density Sets
- **Decision:** Generate standard pre-rendered mipmaps for legacy launchers:
  - `mipmap-mdpi`: 48x48 px
  - `mipmap-hdpi`: 72x72 px
  - `mipmap-xhdpi`: 96x96 px
  - `mipmap-xxhdpi`: 144x144 px
  - `mipmap-xxxhdpi`: 192x192 px
- **Rationale:** Ensures complete backward compatibility for devices running Android 7.1 or lower, or alternative launchers that do not parse API 26 adaptive vector drawables.

## Risks / Trade-offs

- **[Risk] Vector path rendering discrepancies across Android versions** → *Mitigation:* Use standard Android Vector Drawable tags (`<path>`, `<vector>`) with standard stroke caps (`round`), stroke joins (`round`), and RGBA color codes fully supported by Android VectorDrawableCompat back to API 21.
- **[Risk] Launcher mask clipping of terminal dots** → *Mitigation:* Scale the logo within a 66dp-70dp bounding box inside the 108dp foreground canvas, giving an extra 2dp safety margin inside the 72dp safe zone.
- **[Risk] Missing `res/values/colors.xml` or mipmap directory structure** → *Mitigation:* Ensure all required parent resource directories are scaffolded properly and validated by running `./gradlew compileDebugKotlin`.
