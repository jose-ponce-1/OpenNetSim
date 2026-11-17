package com.opennetsim.ui

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset

/**
 * Acciones de la pantalla principal. [onConfigChange] recibe una transformación de la configuración
 * actual, de modo que cada control solo cambia su propio parámetro.
 */
class DashboardActions(
    val onPresetSelect: (ThrottlePreset) -> Unit,
    val onConfigChange: ((ThrottleConfig) -> ThrottleConfig) -> Unit,
    val onToggleClick: () -> Unit,
    val onSnackbarDismiss: () -> Unit
)
