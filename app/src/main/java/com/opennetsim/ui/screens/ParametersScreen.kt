package com.opennetsim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.ParameterInput
import com.opennetsim.ui.UiFormatters

private const val HIGH_LOSS_PERCENT = 10f

/** Sección Parámetros: un campo de valor exacto por parámetro, agrupados como en el prototipo. */
@Composable
fun ParametersScreen(
    state: MainUiState,
    actions: ParameterActions,
    onChangeProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fields = remember(state.drafts, state.config, state.base, state.bandwidthUnit) {
        ParameterInput.fieldStates(state.drafts, state.config, state.base.config, state.bandwidthUnit)
            .associateBy { it.field }
    }
    val invalidCount = fields.values.count { it.error != null }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BaseProfileCard(state, invalidCount, onReset = actions.onReset, onChangeProfile = onChangeProfile)
        BandwidthGroup(fields, state.bandwidthUnit, actions)
        LatencyGroup(fields, state.bandwidthUnit, actions)
        LossGroup(fields, state.bandwidthUnit, actions, UiFormatters.percent(state.config.roundTripLossPercent))
        if (maxOf(state.config.upLossPercent, state.config.downLossPercent) > HIGH_LOSS_PERCENT) HighLossWarning()
    }
}

@Composable
private fun BaseProfileCard(
    state: MainUiState,
    invalidCount: Int,
    onReset: () -> Unit,
    onChangeProfile: () -> Unit
) {
    val header = state.baseHeader
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.Speed, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(header.title, style = MaterialTheme.typography.titleMedium)
                    Text(header.subtitle, style = MaterialTheme.typography.bodySmall)
                }
                if (header.canReset) {
                    TextButton(onClick = onReset) {
                        Icon(
                            Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Text("Restablecer", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                } else {
                    TextButton(onClick = onChangeProfile) { Text("Cambiar") }
                }
            }
        }
        Text(
            text = ParameterInput.applyNote(invalidCount),
            style = MaterialTheme.typography.bodySmall,
            color = if (invalidCount > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun HighLossWarning() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Text(
                text = "Pérdida elevada (>10 %). Muchas conexiones TCP sufrirán retransmisiones o caídas.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
