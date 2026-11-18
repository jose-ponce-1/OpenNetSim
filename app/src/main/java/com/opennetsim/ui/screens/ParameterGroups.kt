package com.opennetsim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opennetsim.ui.BandwidthUnit
import com.opennetsim.ui.ParameterField
import com.opennetsim.ui.ParameterFieldState
import com.opennetsim.ui.components.IconBadge
import com.opennetsim.ui.components.TabularNumbers

// Grupos de la sección Parámetros, en el orden y con los textos del prototipo.

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BandwidthGroup(
    fields: Map<ParameterField, ParameterFieldState>,
    unit: BandwidthUnit,
    actions: ParameterActions,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    ParameterGroup(
        modifier = modifier,
        header = {
            // En pantallas estrechas el selector de unidad baja a la línea siguiente en lugar de partir el título.
            FlowRow(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GroupHeader(
                    "Ancho de banda",
                    Icons.Outlined.SwapVert,
                    colors.primaryContainer,
                    colors.onPrimaryContainer,
                    Modifier.align(Alignment.CenterVertically)
                )
                UnitSelector(unit, actions.onSelectUnit, Modifier.align(Alignment.CenterVertically))
            }
        }
    ) {
        FieldPair(fields, ParameterField.DOWN_KBPS, ParameterField.UP_KBPS, unit, actions)
    }
}

@Composable
fun LatencyGroup(
    fields: Map<ParameterField, ParameterFieldState>,
    unit: BandwidthUnit,
    actions: ParameterActions,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    ParameterGroup(
        modifier = modifier,
        header = {
            GroupHeader(
                "Latencia y variabilidad",
                Icons.Outlined.Timer,
                colors.tertiaryContainer,
                colors.onTertiaryContainer
            )
        }
    ) {
        FieldPair(fields, ParameterField.LATENCY_MS, ParameterField.JITTER_MS, unit, actions)
        ParameterTextField(fields.getValue(ParameterField.REORDER_PERCENT), unit, actions, Modifier.fillMaxWidth())
        GroupNote(
            "El jitter varía el retardo sin cambiar el orden. La reordenación deja salir paquetes sin " +
                "latencia para que adelanten a los que esperan."
        )
    }
}

@Composable
fun LossGroup(
    fields: Map<ParameterField, ParameterFieldState>,
    unit: BandwidthUnit,
    actions: ParameterActions,
    roundTripLoss: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    ParameterGroup(
        modifier = modifier,
        header = {
            GroupHeader(
                "Pérdida de paquetes",
                Icons.Outlined.DisabledByDefault,
                colors.errorContainer,
                colors.onErrorContainer
            )
        }
    ) {
        FieldPair(fields, ParameterField.UP_LOSS_PERCENT, ParameterField.DOWN_LOSS_PERCENT, unit, actions)
        ParameterTextField(fields.getValue(ParameterField.LOSS_BURST), unit, actions, Modifier.fillMaxWidth())
        GroupNote("Con ráfagas (modelo de Gilbert) las pérdidas llegan seguidas manteniendo la tasa media.")
        RoundTripLossRow(roundTripLoss)
    }
}

/** Tarjeta de un grupo de parámetros con su cabecera. */
@Composable
private fun ParameterGroup(
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            header()
            content()
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBadge(icon = icon, containerColor = container, contentColor = content)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
private fun FieldPair(
    fields: Map<ParameterField, ParameterFieldState>,
    first: ParameterField,
    second: ParameterField,
    unit: BandwidthUnit,
    actions: ParameterActions
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ParameterTextField(fields.getValue(first), unit, actions, Modifier.weight(1f))
        ParameterTextField(fields.getValue(second), unit, actions, Modifier.weight(1f))
    }
}

@Composable
private fun UnitSelector(unit: BandwidthUnit, onSelect: (BandwidthUnit) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        BandwidthUnit.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == unit,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = BandwidthUnit.entries.size)
            ) {
                Text(option.label)
            }
        }
    }
}

@Composable
private fun GroupNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RoundTripLossRow(value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Pérdida de ida y vuelta (ping)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(text = value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers))
        }
    }
}
