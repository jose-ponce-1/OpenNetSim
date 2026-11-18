@file:Suppress("MagicNumber") // Cada valor hexadecimal es un rol de la paleta: el nombre ya está en el argumento.

package com.opennetsim.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Paleta oficial de OpenNetSim (prototipo «OpenNetSim · Prototipo Android M3», Material 3 con semilla
// #3F5F90). Los colores del logo (#0067C6, #00C4FF, #67E8F9) solo se usan en el propio logo.

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF3F5F90),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF254777),
    inversePrimary = Color(0xFFA8C8FF),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF7D570E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEAD),
    onTertiaryContainer = Color(0xFF604100),
    error = Color(0xFF904A43),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF73342D),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF3F5F90),
    inverseSurface = Color(0xFF2E3035),
    inverseOnSurface = Color(0xFFF0F0F7),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9)
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF06305F),
    primaryContainer = Color(0xFF254777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF3F5F90),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFF0BE6D),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF604100),
    onTertiaryContainer = Color(0xFFFFDEAD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF561E19),
    errorContainer = Color(0xFF73342D),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceTint = Color(0xFFA8C8FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3035),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A)
)
