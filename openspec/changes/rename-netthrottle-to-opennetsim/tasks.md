# Tasks

## 1. Project Configuration & Manifest Identity

- [x] 1.1 Update `rootProject.name` to `"OpenNetSim"` in `settings.gradle.kts` and verify with `./gradlew projects` or checking file content.
- [x] 1.2 Update `namespace` and `applicationId` to `"com.opennetsim"` in `app/build.gradle.kts` and verify file content.
- [x] 1.3 Update `android:label` to `"OpenNetSim"` in `app/src/main/AndroidManifest.xml` and verify manifest syntax.

## 2. Directory Migration & Package Refactoring

- [x] 2.1 Move directory `app/src/main/java/com/netthrottle` to `app/src/main/java/com/opennetsim` and update package declarations and imports in all main Kotlin source files.
- [x] 2.2 Move directory `app/src/test/java/com/netthrottle` to `app/src/test/java/com/opennetsim` and update package declarations and imports in all unit test files.
- [x] 2.3 Verify no obsolete `com/netthrottle` directories remain and validate initial syntax with `./gradlew compileDebugKotlin`.

## 3. UI, Themes & Service String References

- [x] 3.1 Rename `NetThrottleTheme` to `OpenNetSimTheme` in `ui/theme/Theme.kt` and update invocation in `ui/MainActivity.kt`.
- [x] 3.2 Update TopAppBar title and informative disclaimer in `ui/ThrottleDashboardScreen.kt` to display "OpenNetSim".
- [x] 3.3 Update `ThrottleVpnService.kt` session name (`"OpenNetSim"`), notification channel name, notification title, and intent action constants (`com.opennetsim.action.*`).

## 4. Documentation & Final Verification

- [x] 4.1 Update references to NetThrottle in `project-state.md` with the new OpenNetSim naming and package paths.
- [x] 4.2 Execute `./gradlew testDebugUnitTest` and `./gradlew compileDebugKotlin` to verify 100% test pass rate and clean compilation.
