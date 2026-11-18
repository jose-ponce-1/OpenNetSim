package com.opennetsim.ui.screens

import com.opennetsim.ui.BandwidthUnit
import com.opennetsim.ui.ParameterField
import com.opennetsim.ui.ProfileRef

/** Acciones sobre los perfiles. [onSave] devuelve false si el nombre no es válido. */
class ProfileActions(
    val onSelect: (ProfileRef) -> Unit,
    val onSave: (String) -> Boolean,
    val onDelete: (String) -> Unit
)

/** Acciones de la sección Parámetros. */
class ParameterActions(
    val onTextChange: (ParameterField, String) -> Unit,
    val onFocusLost: (ParameterField) -> Unit,
    val onSelectUnit: (BandwidthUnit) -> Unit,
    val onReset: () -> Unit
)

/** Acciones de la app principal; la raíz las crea a partir del ViewModel, que nunca baja a las pantallas. */
class MainActions(
    val profiles: ProfileActions,
    val parameters: ParameterActions,
    val onToggle: () -> Unit,
    val onMessageAction: (Long) -> Unit,
    val onMessageDismiss: (Long) -> Unit
)
