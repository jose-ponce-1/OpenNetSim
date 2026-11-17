package com.opennetsim.core.engine

import com.opennetsim.model.ThrottleConfig

/**
 * Degradación de una dirección del túnel.
 *
 * @param rateKbps Ancho de banda en kbps.
 * @param latencyMs Latencia base añadida en esta dirección.
 * @param jitterMs Amplitud (±ms) de la variación de latencia; nunca reordena.
 * @param lossPercent Porcentaje de paquetes perdidos.
 * @param lossBurstLength Longitud media de las ráfagas de pérdida (1 = pérdidas independientes).
 * @param reorderPercent Porcentaje de paquetes que salen sin la latencia y adelantan a los encolados.
 */
data class ImpairmentParams(
    val rateKbps: Int,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val lossPercent: Float = 0f,
    val lossBurstLength: Int = 1,
    val reorderPercent: Float = 0f
)

/** Parámetros de la subida. Con [halfLatency], la latencia y el jitter se reparten entre ambas direcciones. */
fun ThrottleConfig.uplinkParams(halfLatency: Boolean = false): ImpairmentParams = ImpairmentParams(
    rateKbps = upKbps,
    latencyMs = if (halfLatency) latencyMs / 2 else latencyMs,
    jitterMs = if (halfLatency) jitterMs / 2 else jitterMs,
    lossPercent = upLossPercent,
    lossBurstLength = lossBurstLength,
    reorderPercent = reorderPercent
)

/** Parámetros de la bajada. Con [halfLatency], la latencia y el jitter se reparten entre ambas direcciones. */
fun ThrottleConfig.downlinkParams(halfLatency: Boolean = false): ImpairmentParams = ImpairmentParams(
    rateKbps = downKbps,
    latencyMs = if (halfLatency) latencyMs / 2 else latencyMs,
    jitterMs = if (halfLatency) jitterMs / 2 else jitterMs,
    lossPercent = downLossPercent,
    lossBurstLength = lossBurstLength,
    reorderPercent = reorderPercent
)
