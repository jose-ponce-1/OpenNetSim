package com.opennetsim.ui.screens

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.opennetsim.R

/** Secciones de primer nivel de la barra de navegación. */
enum class AppDestination(@param:StringRes val labelRes: Int, val icon: ImageVector) {
    SIMULATE(R.string.nav_simulate, Icons.Outlined.Speed),
    PARAMETERS(R.string.nav_parameters, Icons.Outlined.Tune),
    TELEMETRY(R.string.nav_telemetry, Icons.AutoMirrored.Outlined.ShowChart)
}
