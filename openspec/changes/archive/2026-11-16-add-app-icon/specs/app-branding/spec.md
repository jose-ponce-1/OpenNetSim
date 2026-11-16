# Spec Delta

## Purpose

Defines visual branding assets, launcher icon resources, and manifest identity attributes for OpenNetSim across modern and legacy Android launcher environments.

## ADDED Requirements

### Requirement: Application Launcher Icon Configuration
The application manifest SHALL configure the OpenNetSim launcher icon and round launcher icon resources, ensuring the application is visually branded and distinguishable on Android home screens, app drawers, and system task switchers.

#### Scenario: Manifest references launcher icon resources
- **WHEN** the Android application manifest is processed during packaging or inspected by the operating system
- **THEN** both `android:icon` and `android:roundIcon` attributes reference `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` rather than generic Android system placeholders

### Requirement: Adaptive Launcher Icon Compliance
The application SHALL provide adaptive icon XML definitions for Android 8.0 (API 26) and higher, containing layered foreground and background drawables that conform to Android adaptive icon safe-zone specifications.

#### Scenario: Adaptive icon rendering on modern Android launchers
- **WHEN** an Android 8.0+ (API 26+) launcher applies circular, squircle, or rounded square masks to the application icon
- **THEN** the icon displays the OpenNetSim waveform motif, frame, and terminal nodes within the safe zone without visual distortion or unintended edge clipping

### Requirement: Backward-Compatible Legacy Icon Assets
The application SHALL provide multi-density raster icon assets across standard Android density buckets (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi) to ensure crisp rendering on legacy launchers and system dialogs.

#### Scenario: Icon rendering on legacy launchers
- **WHEN** an Android environment or legacy launcher resolves the application icon from density mipmap buckets
- **THEN** the system resolves a crisp density-specific raster icon matching the official OpenNetSim visual brand identity

### Requirement: Brand Vector Asset Availability
The application SHALL provide a standalone scalable vector drawable resource representing the official OpenNetSim logo for in-app UI usage and visual identity consistency.

#### Scenario: Logo rendering in application UI
- **WHEN** an in-app Compose screen, dialog, or about view renders the OpenNetSim brand logo
- **THEN** the vector drawable renders the sharp geometric rounded frame with terminal nodes and the central network pulse waveform
