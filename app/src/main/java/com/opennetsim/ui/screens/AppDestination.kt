package com.opennetsim.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/** Secciones de primer nivel de la barra de navegación. */
enum class AppDestination(val label: String, val icon: ImageVector) {
    SIMULATE("Simular", Icons.Outlined.Speed),
    PARAMETERS("Parámetros", Icons.Outlined.Tune),
    TELEMETRY("Telemetría", Icons.AutoMirrored.Outlined.ShowChart)
}
