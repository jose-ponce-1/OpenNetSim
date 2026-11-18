package com.opennetsim.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.UiFormatters
import com.opennetsim.ui.components.IconBadge
import com.opennetsim.ui.components.SectionTitle
import com.opennetsim.ui.components.StatTile
import com.opennetsim.ui.components.TabularNumbers

/** Sección Simular: control del túnel, perfiles, condiciones aplicadas y acceso a la telemetría. */
@Composable
fun SimulateScreen(
    state: MainUiState,
    actions: ProfileActions,
    onToggle: () -> Unit,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAllProfiles by rememberSaveable { mutableStateOf(false) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            HeroCard(state = state, onToggle = onToggle, modifier = Modifier.padding(horizontal = 16.dp))
            ProfileChips(state = state, onSelect = actions.onSelect, onShowAll = { showAllProfiles = true })
            AppliedConditions(
                state = state,
                onAdjust = { onNavigate(AppDestination.PARAMETERS) },
                onSave = { showSaveDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            TelemetryShortcut(
                state = state,
                onClick = { onNavigate(AppDestination.TELEMETRY) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = "OpenNetSim captura el tráfico con el VpnService de Android y lo reenvía desde el propio " +
                    "dispositivo. Sin servidores externos ni root.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (showAllProfiles) {
            ProfilesSheet(state = state, actions = actions, onDismiss = { showAllProfiles = false })
        }
        if (showSaveDialog) {
            SaveProfileDialog(state = state, onSave = actions.onSave, onDismiss = { showSaveDialog = false })
        }
    }
}

/** Tarjeta principal: toda ella es el interruptor del túnel. */
@Composable
private fun HeroCard(state: MainUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (state.isActive) colors.primaryContainer else colors.surfaceContainerHigh,
        label = "heroContainer"
    )
    val on = state.isActive || state.isStarting
    val (overline, title, body) = heroTexts(state)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = if (state.isActive) colors.onPrimaryContainer else colors.onSurface,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
                .padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(overline, style = MaterialTheme.typography.labelMedium)
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
                HeroSwitch(checked = on, modifier = Modifier.padding(top = 8.dp))
            }
            AnimatedVisibility(visible = state.isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip(Icons.Outlined.Timer, UiFormatters.elapsed(state.activeSeconds))
                    val families = if (state.ipv6Routed) "IPv4 + IPv6" else "Solo IPv4"
                    InfoChip(Icons.Outlined.Lan, "$families · MTU 1500")
                }
            }
        }
    }
}

private fun heroTexts(state: MainUiState): Triple<String, String, String> = when {
    state.isActive -> Triple(
        "SIMULACIÓN EN CURSO",
        state.selectedName,
        "Aplicando el perfil a todo el tráfico. Toca para detener."
    )
    state.isStarting -> Triple("INICIANDO", state.selectedName, "Estableciendo el túnel VPN…")
    else -> Triple(
        "SIMULACIÓN DETENIDA",
        "Red sin alterar",
        "Toca para capturar el tráfico y aplicar «${state.selectedName}»."
    )
}

/** Interruptor decorativo: la tarjeta entera es el control (rol de interruptor). */
@Composable
private fun HeroSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        thumbContent = if (checked) {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
        modifier = modifier
    )
}

@Composable
private fun InfoChip(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(text, style = MaterialTheme.typography.labelLarge.merge(TabularNumbers))
        }
    }
}

/** Resumen de los valores actuales, con accesos a Parámetros y a guardar el perfil. */
@Composable
private fun AppliedConditions(
    state: MainUiState,
    onAdjust: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Condiciones aplicadas")
        TileRow(
            first = {
                StatTile("Bajada", UiFormatters.bandwidth(config.downKbps), it) {
                    TileIcon(Icons.Outlined.Download, colors.primary)
                }
            },
            second = {
                StatTile("Subida", UiFormatters.bandwidth(config.upKbps), it) {
                    TileIcon(Icons.Outlined.Upload, colors.primary)
                }
            }
        )
        TileRow(
            first = {
                StatTile("Latencia", "+${config.latencyMs} ms", it, UiFormatters.jitter(config)) {
                    TileIcon(Icons.Outlined.Timer, colors.tertiary)
                }
            },
            second = {
                StatTile("Pérdida", UiFormatters.loss(config), it, UiFormatters.roundTripLoss(config)) {
                    TileIcon(Icons.Outlined.DisabledByDefault, colors.error)
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdjust, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Ajustar", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            FilledTonalButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Outlined.BookmarkAdd,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Text("Guardar perfil", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

/** Dos tarjetas de la misma altura; cada una recibe su modificador de ancho. */
@Composable
private fun TileRow(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        first(Modifier.weight(1f).fillMaxHeight())
        second(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TileIcon(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint)
}

/** Acceso a la telemetría con el caudal actual. */
@Composable
private fun TelemetryShortcut(state: MainUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IconBadge(
                icon = Icons.AutoMirrored.Outlined.ShowChart,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Telemetría en vivo", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (state.isActive) {
                        val telemetry = state.telemetry
                        "↓ ${telemetry.formattedDownThroughput()}  ·  ↑ ${telemetry.formattedUpThroughput()}"
                    } else {
                        "Inicia la simulación para ver métricas"
                    },
                    style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
