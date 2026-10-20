package com.opennetsim.model

import java.util.Locale

/**
 * Representa la configuración activa del motor de throttling.
 *
 * @param downKbps Límite de ancho de banda de descarga en kilobits por segundo.
 * @param upKbps Límite de ancho de banda de subida en kilobits por segundo.
 * @param latencyMs Retardo base añadido a cada paquete en milisegundos.
 * @param jitterMs Variación máxima aleatoria en el retardo (+/- ms).
 * @param packetLossPercent Porcentaje de descarte probabilístico de paquetes (0.0% a 50.0%).
 * @param preset Perfil asociado o CUSTOM si se ha modificado manualmente.
 */
data class ThrottleConfig(
    val downKbps: Int = 1000,
    val upKbps: Int = 500,
    val latencyMs: Int = 100,
    val jitterMs: Int = 20,
    val packetLossPercent: Float = 0.0f,
    val preset: ThrottlePreset = ThrottlePreset.CUSTOM
) {
    fun formattedDownBandwidth(): String = formatBandwidth(downKbps)

    fun formattedUpBandwidth(): String = formatBandwidth(upKbps)

    private fun formatBandwidth(kbps: Int): String {
        return if (kbps >= 1000) {
            val mbps = kbps / 1000.0
            String.format(Locale.US, "%.1f Mbps", mbps)
        } else {
            "$kbps kbps"
        }
    }

    /**
     * Comprueba si los valores actuales coinciden exactamente con un perfil preconfigurado.
     */
    fun matchesPreset(target: ThrottlePreset): Boolean {
        if (target == ThrottlePreset.CUSTOM) return false
        return downKbps == target.downKbps &&
                upKbps == target.upKbps &&
                latencyMs == target.latencyMs &&
                jitterMs == target.jitterMs &&
                kotlin.math.abs(packetLossPercent - target.packetLossPercent) < 0.01f
    }
}

