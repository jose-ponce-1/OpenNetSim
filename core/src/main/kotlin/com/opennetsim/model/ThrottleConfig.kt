package com.opennetsim.model

import java.util.Locale

/**
 * Representa la configuración activa del motor de throttling.
 *
 * @param downKbps Límite de ancho de banda de descarga en kilobits por segundo.
 * @param upKbps Límite de ancho de banda de subida en kilobits por segundo.
 * @param latencyMs Retardo base añadido a cada paquete en milisegundos.
 * @param jitterMs Variación máxima del retardo (+/- ms); nunca reordena paquetes.
 * @param upLossPercent Porcentaje de paquetes perdidos en la subida (0.0% a 50.0%).
 * @param downLossPercent Porcentaje de paquetes perdidos en la bajada (0.0% a 50.0%).
 * @param lossBurstLength Longitud media de las ráfagas de pérdida en paquetes (1 = independientes).
 * @param reorderPercent Porcentaje de paquetes que salen sin latencia y adelantan a otros.
 * @param preset Perfil asociado o CUSTOM si se ha modificado manualmente.
 */
data class ThrottleConfig(
    val downKbps: Int = 1000,
    val upKbps: Int = 500,
    val latencyMs: Int = 100,
    val jitterMs: Int = 20,
    val upLossPercent: Float = 0.0f,
    val downLossPercent: Float = 0.0f,
    val lossBurstLength: Int = 1,
    val reorderPercent: Float = 0.0f,
    val preset: ThrottlePreset = ThrottlePreset.CUSTOM
) {
    /** Pérdida de un intercambio de ida y vuelta (p. ej. un ping) con esta configuración. */
    val roundTripLossPercent: Float get() = roundTripLossPercent(upLossPercent, downLossPercent)

    fun formattedDownBandwidth(): String = formatBandwidth(downKbps)

    fun formattedUpBandwidth(): String = formatBandwidth(upKbps)

    private fun formatBandwidth(kbps: Int): String {
        return if (kbps >= KBPS_PER_MBPS) {
            val mbps = kbps / KBPS_PER_MBPS.toDouble()
            String.format(Locale.US, "%.1f Mbps", mbps)
        } else {
            "$kbps kbps"
        }
    }

    /**
     * Comprueba si los valores actuales coinciden exactamente con un perfil preconfigurado. Los
     * perfiles aplican la misma pérdida en ambas direcciones, sin ráfagas ni reordenación.
     */
    fun matchesPreset(target: ThrottlePreset): Boolean {
        if (target == ThrottlePreset.CUSTOM) return false
        return downKbps == target.downKbps &&
            upKbps == target.upKbps &&
            latencyMs == target.latencyMs &&
            jitterMs == target.jitterMs &&
            kotlin.math.abs(upLossPercent - target.packetLossPercent) < LOSS_TOLERANCE &&
            kotlin.math.abs(downLossPercent - target.packetLossPercent) < LOSS_TOLERANCE &&
            lossBurstLength == 1 &&
            reorderPercent < LOSS_TOLERANCE
    }

    companion object {
        private const val LOSS_TOLERANCE = 0.01f
        private const val KBPS_PER_MBPS = 1000
        private const val PERCENT = 100f

        /** Longitud máxima de ráfaga de pérdida que ofrece la interfaz. */
        const val MAX_LOSS_BURST_LENGTH = 10

        /** Reordenación máxima que ofrece la interfaz. */
        const val MAX_REORDER_PERCENT = 25f

        /**
         * Pérdida efectiva de un intercambio de ida y vuelta (p. ej. un ping) cuando la subida descarta
         * [upPercent] y la bajada [downPercent] de forma independiente: `1 − (1 − p↑)(1 − p↓)`.
         */
        fun roundTripLossPercent(upPercent: Float, downPercent: Float = upPercent): Float {
            val up = (upPercent / PERCENT).coerceIn(0f, 1f)
            val down = (downPercent / PERCENT).coerceIn(0f, 1f)
            return (1f - (1f - up) * (1f - down)) * PERCENT
        }
    }
}
