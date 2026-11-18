package com.opennetsim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Tema de OpenNetSim: paleta oficial en claro u oscuro según el sistema, nunca el color dinámico del
 * fondo de pantalla. Los iconos de las barras del sistema los ajusta `enableEdgeToEdge` en la actividad.
 */
@Composable
fun OpenNetSimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = OpenNetSimTypography,
        content = content
    )
}
