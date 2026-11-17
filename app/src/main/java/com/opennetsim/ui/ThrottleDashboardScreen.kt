package com.opennetsim.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.ui.theme.AmberWarning
import com.opennetsim.ui.theme.CyanPrimary
import com.opennetsim.ui.theme.EmeraldActive
import com.opennetsim.ui.theme.RoseDanger
import com.opennetsim.ui.theme.Slate400
import com.opennetsim.ui.theme.Slate600
import com.opennetsim.ui.theme.Slate800
import com.opennetsim.ui.theme.Slate900
import com.opennetsim.ui.theme.Slate950
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThrottleDashboardScreen(
    uiState: MainUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val onSnackbarDismiss by rememberUpdatedState(actions.onSnackbarDismiss)

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onSnackbarDismiss()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Slate950,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "OpenNetSim",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    StatusBadge(isActive = uiState.isActive)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Slate950
                )
            )
        }
    ) { innerPadding ->
        DashboardContent(
            uiState = uiState,
            actions = actions,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun DashboardContent(
    uiState: MainUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero: Tarjeta Principal de Control ON / OFF
        HeroActivationCard(
            isActive = uiState.isActive,
            selectedPreset = uiState.selectedPreset,
            onToggleClick = actions.onToggleClick
        )

        // Telemetría en Tiempo Real
        LiveTelemetrySection(
            telemetry = uiState.telemetry,
            isActive = uiState.isActive,
            ipv6Routed = uiState.ipv6Routed
        )

        // Selector Horizontal de Perfiles
        PresetsSection(
            selectedPreset = uiState.selectedPreset,
            onPresetSelected = actions.onPresetSelect
        )

        // Sección 1: Ancho de Banda (Upload / Download)
        BandwidthSection(config = uiState.config, onConfigChange = actions.onConfigChange)

        // Sección 2: Latencia, jitter y reordenación
        LatencySection(config = uiState.config, onConfigChange = actions.onConfigChange)

        // Sección 3: Pérdida de paquetes por dirección y en ráfagas
        PacketLossSection(config = uiState.config, onConfigChange = actions.onConfigChange)

        // Nota informativa
        InfoCard()

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatusBadge(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor by animateColorAsState(
        targetValue = if (isActive) EmeraldActive else Slate600,
        label = "statusDotColor"
    )
    val label = if (isActive) "ACTIVO" else "EN REPOSO"

    Row(
        modifier = modifier
            .padding(end = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Slate900)
            .border(1.dp, if (isActive) EmeraldActive.copy(alpha = 0.5f) else Slate800, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (isActive) EmeraldActive else Slate400
        )
    }
}

@Composable
private fun HeroActivationCard(
    isActive: Boolean,
    selectedPreset: ThrottlePreset,
    onToggleClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) EmeraldActive.copy(alpha = 0.7f) else Slate800,
        label = "heroBorder"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isActive) "Simulación en Curso" else "Throttler Desactivado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) EmeraldActive else MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isActive) {
                        "Aplicando perfil '${selectedPreset.displayName}' al tráfico del dispositivo."
                    } else {
                        "Pulsa el interruptor para iniciar la captura e inyección de retardo."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Switch(
                checked = isActive,
                onCheckedChange = { onToggleClick() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = EmeraldActive,
                    uncheckedThumbColor = Slate400,
                    uncheckedTrackColor = Slate800
                )
            )
        }
    }
}

@Composable
private fun PresetsSection(
    selectedPreset: ThrottlePreset,
    onPresetSelected: (ThrottlePreset) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Perfiles de Conexión",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThrottlePreset.entries.forEach { preset ->
                val isSelected = selectedPreset == preset
                FilterChip(
                    selected = isSelected,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(preset.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Slate900,
                        labelColor = Slate400,
                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = CyanPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = Slate800,
                        selectedBorderColor = CyanPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = selectedPreset.description,
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400
        )
    }
}

@Composable
private fun BandwidthSection(
    config: ThrottleConfig,
    onConfigChange: ((ThrottleConfig) -> ThrottleConfig) -> Unit
) {
    ControlCard(
        title = "Límites de Ancho de Banda",
        icon = Icons.Default.NetworkCheck
    ) {
        SliderControlRow(
            label = "Bajada (Download)",
            icon = Icons.Default.CloudDownload,
            valueDisplay = config.formattedDownBandwidth(),
            value = config.downKbps.toFloat(),
            range = 50f..50000f,
            onValueChange = { value -> onConfigChange { it.copy(downKbps = value.toInt()) } }
        )

        Spacer(modifier = Modifier.height(16.dp))

        SliderControlRow(
            label = "Subida (Upload)",
            icon = Icons.Default.CloudUpload,
            valueDisplay = config.formattedUpBandwidth(),
            value = config.upKbps.toFloat(),
            range = 20f..20000f,
            onValueChange = { value -> onConfigChange { it.copy(upKbps = value.toInt()) } }
        )
    }
}

@Composable
private fun LatencySection(
    config: ThrottleConfig,
    onConfigChange: ((ThrottleConfig) -> ThrottleConfig) -> Unit
) {
    ControlCard(
        title = "Latencia y Variabilidad",
        icon = Icons.Default.HourglassTop
    ) {
        SliderControlRow(
            label = "Latencia adicional (Ping)",
            icon = null,
            valueDisplay = "+${config.latencyMs} ms",
            value = config.latencyMs.toFloat(),
            range = 0f..1000f,
            onValueChange = { value -> onConfigChange { it.copy(latencyMs = value.toInt()) } }
        )

        Spacer(modifier = Modifier.height(16.dp))

        SliderControlRow(
            label = "Jitter (Fluctuación)",
            icon = null,
            valueDisplay = "±${config.jitterMs} ms",
            value = config.jitterMs.toFloat(),
            range = 0f..200f,
            onValueChange = { value -> onConfigChange { it.copy(jitterMs = value.toInt()) } }
        )
        HintText("El jitter varía el retardo sin cambiar el orden de los paquetes.")

        Spacer(modifier = Modifier.height(16.dp))

        SliderControlRow(
            label = "Reordenación",
            icon = null,
            valueDisplay = String.format(Locale.US, "%.1f%%", config.reorderPercent),
            value = config.reorderPercent,
            range = 0f..ThrottleConfig.MAX_REORDER_PERCENT,
            onValueChange = { value -> onConfigChange { it.copy(reorderPercent = value) } }
        )
        HintText("Paquetes que salen sin latencia y adelantan a los que esperan. Requiere latencia > 0.")
    }
}

@Composable
private fun PacketLossSection(
    config: ThrottleConfig,
    onConfigChange: ((ThrottleConfig) -> ThrottleConfig) -> Unit
) {
    ControlCard(
        title = "Pérdida de Paquetes",
        icon = Icons.Default.WifiOff
    ) {
        val isHighLoss = maxOf(config.upLossPercent, config.downLossPercent) >= HIGH_LOSS_PERCENT

        LossSlider(
            label = "Pérdida de subida",
            icon = Icons.Default.CloudUpload,
            lossPercent = config.upLossPercent,
            onLossChange = { value -> onConfigChange { it.copy(upLossPercent = value) } }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LossSlider(
            label = "Pérdida de bajada",
            icon = Icons.Default.CloudDownload,
            lossPercent = config.downLossPercent,
            onLossChange = { value -> onConfigChange { it.copy(downLossPercent = value) } }
        )

        Spacer(modifier = Modifier.height(16.dp))

        SliderControlRow(
            label = "Ráfaga media",
            icon = null,
            valueDisplay = if (config.lossBurstLength <= 1) "Independiente" else "${config.lossBurstLength} paquetes",
            value = config.lossBurstLength.toFloat(),
            range = 1f..ThrottleConfig.MAX_LOSS_BURST_LENGTH.toFloat(),
            onValueChange = { value -> onConfigChange { it.copy(lossBurstLength = value.roundToInt()) } }
        )
        HintText("Con ráfagas, los paquetes se pierden seguidos (modelo de Gilbert) manteniendo la tasa media.")

        HintText(
            String.format(
                Locale.US,
                "Pérdida de ida y vuelta (p. ej. ping): %.1f%%",
                config.roundTripLossPercent
            )
        )

        HighLossWarning(visible = isHighLoss)
    }
}

@Composable
private fun HighLossWarning(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AmberWarning.copy(alpha = 0.15f))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = AmberWarning,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Pérdida elevada (>10%). Muchas conexiones TCP sufrirán retransmisiones o caídas.",
                style = MaterialTheme.typography.bodyMedium,
                color = AmberWarning
            )
        }
    }
}

@Composable
private fun LossSlider(
    label: String,
    icon: ImageVector,
    lossPercent: Float,
    onLossChange: (Float) -> Unit
) {
    SliderControlRow(
        label = label,
        icon = icon,
        valueDisplay = String.format(Locale.US, "%.1f%%", lossPercent),
        value = lossPercent,
        range = 0f..50f,
        onValueChange = onLossChange,
        accentColor = if (lossPercent >= HIGH_LOSS_PERCENT) RoseDanger else CyanPrimary
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Slate400,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private const val HIGH_LOSS_PERCENT = 10f

@Composable
private fun SliderControlRow(
    label: String,
    icon: ImageVector?,
    valueDisplay: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    accentColor: Color = CyanPrimary
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Slate400,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Slate800
            )
        )
    }
}

@Composable
private fun ControlCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Slate800, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun InfoCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Slate900)
            .border(1.dp, Slate800, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = Slate400,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "OpenNetSim intercepta el tráfico en un bucle local mediante Android VpnService. No se envían paquetes fuera de tu dispositivo ni se requiere acceso root.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun LiveTelemetrySection(
    telemetry: NetworkTelemetry,
    isActive: Boolean,
    ipv6Routed: Boolean
) {
    ControlCard(
        title = "Telemetría en Vivo",
        icon = Icons.Default.Analytics
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TelemetryItem(
                label = "Bajada",
                value = if (isActive) telemetry.formattedDownThroughput() else "0 B/s",
                valueColor = CyanPrimary
            )
            TelemetryItem(
                label = "Subida",
                value = if (isActive) telemetry.formattedUpThroughput() else "0 B/s",
                valueColor = CyanPrimary
            )
            TelemetryItem(
                label = "Descartados",
                value = if (isActive) "${telemetry.totalDroppedPackets} pkts" else "0 pkts",
                valueColor = if (telemetry.totalDroppedPackets > 0) RoseDanger else Slate400
            )
            TelemetryItem(
                label = "Transferido",
                value = if (isActive) telemetry.formattedTotalData() else "0 B",
                valueColor = EmeraldActive
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (telemetry.isConnected) EmeraldActive.copy(alpha = 0.12f) else Slate800)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (telemetry.isConnected) EmeraldActive else Slate600)
            )
            Text(
                text = when {
                    !telemetry.isConnected -> "Túnel VPN desconectado"
                    ipv6Routed -> "Túnel VPN activo (IPv4 + IPv6 • MTU 1500)"
                    else -> "Túnel VPN activo (solo IPv4: la red no tiene IPv6 • MTU 1500)"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (telemetry.isConnected) EmeraldActive else Slate400
            )
        }
    }
}

@Composable
private fun TelemetryItem(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
