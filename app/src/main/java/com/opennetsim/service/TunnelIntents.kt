package com.opennetsim.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset

/** Orden recibida por [ThrottleVpnService]. */
sealed interface TunnelCommand {
    data class Start(val config: ThrottleConfig) : TunnelCommand

    data class UpdateConfig(val config: ThrottleConfig) : TunnelCommand

    data object Stop : TunnelCommand

    /**
     * Arranque no pedido por la UI: reinicio de un servicio `START_STICKY` (intent nulo) o arranque
     * como VPN siempre activa ([alwaysOn]). Usa la configuración persistida.
     */
    data class Restore(val alwaysOn: Boolean) : TunnelCommand

    /** Acción desconocida: se ignora. */
    data object Unknown : TunnelCommand
}

/**
 * Codificación de las órdenes del servicio en [Intent]s. Es el único lugar que conoce las acciones y
 * los extras.
 */
object TunnelIntents {
    const val ACTION_START = "com.opennetsim.action.START"
    const val ACTION_STOP = "com.opennetsim.action.STOP"
    const val ACTION_UPDATE_CONFIG = "com.opennetsim.action.UPDATE_CONFIG"

    private const val EXTRA_DOWN_KBPS = "extra_down_kbps"
    private const val EXTRA_UP_KBPS = "extra_up_kbps"
    private const val EXTRA_LATENCY_MS = "extra_latency_ms"
    private const val EXTRA_JITTER_MS = "extra_jitter_ms"
    private const val EXTRA_UP_LOSS_PERCENT = "extra_up_loss_percent"
    private const val EXTRA_DOWN_LOSS_PERCENT = "extra_down_loss_percent"
    private const val EXTRA_LOSS_BURST_LENGTH = "extra_loss_burst_length"
    private const val EXTRA_REORDER_PERCENT = "extra_reorder_percent"
    private const val EXTRA_PRESET_NAME = "extra_preset_name"

    fun start(context: Context, config: ThrottleConfig): Intent =
        serviceIntent(context, ACTION_START).also { putConfig(it, config) }

    fun stop(context: Context): Intent = serviceIntent(context, ACTION_STOP)

    fun updateConfig(context: Context, config: ThrottleConfig): Intent =
        serviceIntent(context, ACTION_UPDATE_CONFIG).also { putConfig(it, config) }

    fun parse(intent: Intent?): TunnelCommand = when (intent?.action) {
        null -> TunnelCommand.Restore(alwaysOn = false)
        VpnService.SERVICE_INTERFACE -> TunnelCommand.Restore(alwaysOn = true)
        ACTION_STOP -> TunnelCommand.Stop
        ACTION_START -> readConfig(intent)?.let { TunnelCommand.Start(it) } ?: TunnelCommand.Unknown
        ACTION_UPDATE_CONFIG -> readConfig(intent)?.let { TunnelCommand.UpdateConfig(it) } ?: TunnelCommand.Unknown
        else -> TunnelCommand.Unknown
    }

    private fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, ThrottleVpnService::class.java).setAction(action)

    private fun putConfig(intent: Intent, config: ThrottleConfig) {
        intent.putExtra(EXTRA_DOWN_KBPS, config.downKbps)
        intent.putExtra(EXTRA_UP_KBPS, config.upKbps)
        intent.putExtra(EXTRA_LATENCY_MS, config.latencyMs)
        intent.putExtra(EXTRA_JITTER_MS, config.jitterMs)
        intent.putExtra(EXTRA_UP_LOSS_PERCENT, config.upLossPercent)
        intent.putExtra(EXTRA_DOWN_LOSS_PERCENT, config.downLossPercent)
        intent.putExtra(EXTRA_LOSS_BURST_LENGTH, config.lossBurstLength)
        intent.putExtra(EXTRA_REORDER_PERCENT, config.reorderPercent)
        intent.putExtra(EXTRA_PRESET_NAME, config.preset.name)
    }

    /** Lee la configuración completa del intent, o null si falta algún extra. */
    private fun readConfig(intent: Intent): ThrottleConfig? {
        val required = listOf(
            EXTRA_DOWN_KBPS,
            EXTRA_UP_KBPS,
            EXTRA_LATENCY_MS,
            EXTRA_JITTER_MS,
            EXTRA_UP_LOSS_PERCENT,
            EXTRA_DOWN_LOSS_PERCENT,
            EXTRA_LOSS_BURST_LENGTH,
            EXTRA_REORDER_PERCENT
        )
        if (required.any { !intent.hasExtra(it) }) return null
        val preset = intent.getStringExtra(EXTRA_PRESET_NAME)
            ?.let { name -> ThrottlePreset.entries.firstOrNull { it.name == name } }
            ?: ThrottlePreset.CUSTOM
        return ThrottleConfig(
            downKbps = intent.getIntExtra(EXTRA_DOWN_KBPS, 0),
            upKbps = intent.getIntExtra(EXTRA_UP_KBPS, 0),
            latencyMs = intent.getIntExtra(EXTRA_LATENCY_MS, 0),
            jitterMs = intent.getIntExtra(EXTRA_JITTER_MS, 0),
            upLossPercent = intent.getFloatExtra(EXTRA_UP_LOSS_PERCENT, 0f),
            downLossPercent = intent.getFloatExtra(EXTRA_DOWN_LOSS_PERCENT, 0f),
            lossBurstLength = intent.getIntExtra(EXTRA_LOSS_BURST_LENGTH, 1),
            reorderPercent = intent.getFloatExtra(EXTRA_REORDER_PERCENT, 0f),
            preset = preset
        )
    }
}
