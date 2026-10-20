# Proposal: Modernize Build Configuration & Evaluate AGP Upgrades

## Why

Gradle 9.7.1 and Kotlin 2.0+ introduce newer DSL standards and deprecate legacy APIs. The current build configuration uses the deprecated `kotlinOptions` block in `app/build.gradle.kts`, and Gradle's configuration problems report flags 8 deprecations within AGP 8.7.3 that will become breaking errors in Gradle 10. Modernizing the Kotlin DSL and evaluating AGP compatibility ensures a cleaner build, removes compiler warnings, and prepares the codebase for upcoming Gradle major versions without breaking active development.

## What Changes

- **Modernize Kotlin Compiler Options**: Replace the deprecated `kotlinOptions { jvmTarget = "17" }` in `app/build.gradle.kts` with `compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }`.
- **Evaluate AGP Modernization (Point 4)**: Document and analyze the trade-offs of updating the Android Gradle Plugin (AGP) from 8.7.3 to newer versions (e.g., 8.8+ or 8.9+) versus retaining 8.7.3 while under Gradle 9.7.1.
- **Environment SDK Handling**: Respect user-configured environment variables for Android SDK location without altering `local.properties`.
- **Validation**: Verify that `compileDebugKotlin` and `testDebugUnitTest` run cleanly without Kotlin DSL deprecation warnings.

## Capabilities

### New Capabilities
None (pure tooling and build configuration update; `skip_specs: true`).

### Modified Capabilities
None.

## Impact

- **Affected files**: `app/build.gradle.kts`.
- **Build system**: Removes compiler warnings from Kotlin Gradle Plugin 2.0+.
- **Zero runtime impact**: No production or test code changes.
