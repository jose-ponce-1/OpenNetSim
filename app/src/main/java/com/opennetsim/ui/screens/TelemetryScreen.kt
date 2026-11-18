package com.opennetsim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opennetsim.R
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.TrafficDirection
import com.opennetsim.ui.UiFormatters
import com.opennetsim.ui.components.IconBadge
import com.opennetsim.ui.components.StatTile
import com.opennetsim.ui.components.asString

/** Sección Telemetría: gráfica de caudal, contadores y estado del túnel. */
@Composable
fun TelemetryScreen(state: MainUiState, modifier: Modifier = Modifier) {
    var direction by rememberSaveable { mutableStateOf(TrafficDirection.BOTH) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Hueco inferior para que el botón flotante no tape la última tarjeta.
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        DirectionSelector(direction, onSelect = { direction = it })
        ThroughputCard(state, direction)
        Counters(state)
        TunnelStatus(state)
    }
}

@Composable
private fun DirectionSelector(direction: TrafficDirection, onSelect: (TrafficDirection) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        TrafficDirection.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == direction,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TrafficDirection.entries.size)
            ) {
                Text(stringResource(option.labelRes))
            }
        }
    }
}

@Composable
private fun Counters(state: MainUiState) {
    val telemetry = state.telemetry
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CounterRow(
            stringResource(R.string.counter_transferred) to telemetry.formattedTotalData(),
            stringResource(R.string.counter_dropped) to
                stringResource(R.string.value_dropped_packets, telemetry.totalDroppedPackets)
        )
        CounterRow(
            stringResource(R.string.counter_retransmitted) to telemetry.formattedRetransmittedData(),
            stringResource(R.string.counter_active_time) to UiFormatters.elapsed(state.activeSeconds)
        )
    }
}

@Composable
private fun CounterRow(first: Pair<String, String>, second: Pair<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatTile(first.first, first.second, Modifier.weight(1f).fillMaxHeight())
        StatTile(second.first, second.second, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TunnelStatus(state: MainUiState) {
    val colors = MaterialTheme.colorScheme
    val (title, subtitle) = when {
        state.isActive -> stringResource(R.string.tunnel_active) to if (state.ipv6Routed) {
            stringResource(R.string.value_network_families, stringResource(R.string.families_dual_stack))
        } else {
            stringResource(R.string.tunnel_active_ipv4_only)
        }
        state.isStarting -> stringResource(R.string.tunnel_starting) to stringResource(R.string.tunnel_starting_detail)
        else -> stringResource(R.string.tunnel_stopped) to stringResource(R.string.tunnel_stopped_detail)
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            StatusRow(Icons.Outlined.Shield, colors.primaryContainer, colors.onPrimaryContainer, title, subtitle)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            StatusRow(
                Icons.Outlined.Speed,
                colors.secondaryContainer,
                colors.onSecondaryContainer,
                stringResource(R.string.status_profile, state.selectedName.asString()),
                UiFormatters.summary(state.config)
            )
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, container: Color, content: Color, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconBadge(icon = icon, containerColor = container, contentColor = content)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
