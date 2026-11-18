package com.opennetsim.ui

import com.opennetsim.R
import com.opennetsim.model.ThrottleConfig
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Textos de valores que se repiten en varias secciones. Las cifras y unidades son iguales en todos los
 * idiomas (punto decimal); las frases con palabras devuelven [UiText].
 */
object UiFormatters {
    private const val KBPS_PER_MBPS = 1000.0
    private const val MBPS_DECIMALS = 3
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val LOSS_TOLERANCE = 0.01f

    /** 384 → "384 kbps"; 1500 → "1.5 Mbps"; 50000 → "50 Mbps". */
    fun bandwidth(kbps: Double): String = if (kbps >= KBPS_PER_MBPS) {
        "${ParameterInput.trimmed(kbps / KBPS_PER_MBPS, MBPS_DECIMALS)} Mbps"
    } else {
        "${kbps.roundToLong()} kbps"
    }

    fun bandwidth(kbps: Int): String = bandwidth(kbps.toDouble())

    fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value)

    /** Pérdida aplicada: un valor si es igual en ambas direcciones, si no los dos. */
    fun loss(config: ThrottleConfig): String {
        val symmetric = abs(config.upLossPercent - config.downLossPercent) < LOSS_TOLERANCE
        return if (symmetric) {
            percent(config.downLossPercent)
        } else {
            "↑ ${percent(config.upLossPercent)} · ↓ ${percent(config.downLossPercent)}"
        }
    }

    /** "384 kbps ↓ · 128 kbps ↑ · +200 ms · 2.0%". */
    fun summary(config: ThrottleConfig): String =
        "${bandwidth(config.downKbps)} ↓ · ${bandwidth(config.upKbps)} ↑ · +${config.latencyMs} ms · ${loss(config)}"

    fun jitter(config: ThrottleConfig): UiText = if (config.reorderPercent > 0f) {
        UiText.res(R.string.summary_jitter_reorder, config.jitterMs, percent(config.reorderPercent))
    } else {
        UiText.res(R.string.summary_jitter, config.jitterMs)
    }

    fun roundTripLoss(config: ThrottleConfig): UiText {
        val loss = percent(config.roundTripLossPercent)
        return if (config.lossBurstLength > 1) {
            UiText.res(R.string.summary_round_trip_bursts, loss, config.lossBurstLength)
        } else {
            UiText.res(R.string.summary_round_trip, loss)
        }
    }

    /** 754 → "12:34"; 3754 → "1:02:34". */
    fun elapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / SECONDS_PER_HOUR
        val minutes = safe % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val secs = safe % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }
}
