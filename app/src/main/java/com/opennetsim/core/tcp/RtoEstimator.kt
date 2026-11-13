package com.opennetsim.core.tcp

import kotlin.math.abs
import kotlin.math.max

/**
 * Estimador del tiempo de retransmisión (RTO) según RFC 6298, con la regla de Karn:
 * no se toman muestras de RTT de segmentos retransmitidos.
 */
class RtoEstimator(
    initialRtoMs: Long = DEFAULT_INITIAL_RTO_MS,
    val minRtoMs: Long = DEFAULT_MIN_RTO_MS,
    val maxRtoMs: Long = DEFAULT_MAX_RTO_MS
) {
    private var smoothedRtt: Double = -1.0
    private var rttVariance: Double = 0.0
    private var baseRtoMs: Long = initialRtoMs.coerceIn(minRtoMs, maxRtoMs)
    private var backoffFactor: Long = 1L

    /** RTO vigente, incluido el backoff exponencial por timeouts consecutivos. */
    val rtoMs: Long
        get() = (baseRtoMs * backoffFactor).coerceAtMost(maxRtoMs)

    /** SRTT suavizado en milisegundos, o null si aún no hay muestras. */
    val smoothedRttMs: Double?
        get() = if (smoothedRtt < 0.0) null else smoothedRtt

    /**
     * Registra la confirmación de un segmento enviado en [sentAtMs]. Si fue retransmitido, la muestra
     * es ambigua y se descarta (Karn).
     */
    fun onAckOfSegment(sentAtMs: Long, nowMs: Long, wasRetransmitted: Boolean) {
        if (wasRetransmitted) return
        onRttSample(max(0L, nowMs - sentAtMs))
    }

    /** Aplica una muestra de RTT válida (RFC 6298, sección 2). */
    fun onRttSample(rttMs: Long) {
        val sample = rttMs.toDouble()
        if (smoothedRtt < 0.0) {
            smoothedRtt = sample
            rttVariance = sample / 2.0
        } else {
            rttVariance = (1.0 - BETA) * rttVariance + BETA * abs(smoothedRtt - sample)
            smoothedRtt = (1.0 - ALPHA) * smoothedRtt + ALPHA * sample
        }
        val rto = smoothedRtt + max(CLOCK_GRANULARITY_MS, K * rttVariance)
        baseRtoMs = rto.toLong().coerceIn(minRtoMs, maxRtoMs)
    }

    /** Duplica el RTO tras un timeout (RFC 6298, sección 5.5). */
    fun onTimeout() {
        if (rtoMs < maxRtoMs) backoffFactor *= 2
    }

    /** Elimina el backoff cuando llega una confirmación nueva. */
    fun resetBackoff() {
        backoffFactor = 1L
    }

    companion object {
        const val DEFAULT_INITIAL_RTO_MS = 1_000L
        const val DEFAULT_MIN_RTO_MS = 200L
        const val DEFAULT_MAX_RTO_MS = 60_000L

        private const val ALPHA = 1.0 / 8.0
        private const val BETA = 1.0 / 4.0
        private const val K = 4.0
        private const val CLOCK_GRANULARITY_MS = 1.0
    }
}
