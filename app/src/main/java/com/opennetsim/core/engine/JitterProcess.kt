package com.opennetsim.core.engine

import kotlin.math.max

/**
 * Variación del retardo (jitter) continua en el tiempo, que nunca reordena paquetes.
 *
 * El desplazamiento recorre segmentos lineales entre objetivos aleatorios en `[-A, A]`. Cada segmento
 * dura al menos `2A` ms, así que el desplazamiento nunca cae más deprisa que el tiempo: un paquete que
 * entra después nunca tendría que salir antes que el anterior. Por eso la cola FIFO no recorta la
 * distribución, y bajo carga la latencia sigue oscilando en `base ± A` en lugar de tender a
 * `base + A/2`, como pasaba con un valor aleatorio independiente por paquete.
 *
 * Paquetes muy seguidos ven retardos parecidos, como en una red real. Paquetes separados por más de
 * un segmento, como los de `ping` cada segundo, ven valores independientes.
 *
 * @param random generador uniforme en un rango cerrado `[min, max]`.
 */
class JitterProcess(
    private val random: (minInclusive: Long, maxInclusive: Long) -> Long
) {
    private var amplitudeMs = 0L
    private var initialized = false
    private var segmentStartMs = 0L
    private var segmentDurationMs = 0L
    private var startOffsetMs = 0L
    private var endOffsetMs = 0L

    /** Cambia la amplitud (±ms) y reinicia el proceso. */
    fun reset(amplitudeMs: Long) {
        this.amplitudeMs = max(0L, amplitudeMs)
        initialized = false
    }

    /** Desplazamiento del retardo en el instante [nowMs], en `[-A, A]`. */
    fun offsetAt(nowMs: Long): Long {
        if (amplitudeMs == 0L) return 0L
        advanceTo(nowMs)
        val elapsed = (nowMs - segmentStartMs).coerceAtLeast(0L)
        return startOffsetMs + (endOffsetMs - startOffsetMs) * elapsed / segmentDurationMs
    }

    /** Lleva el proceso al segmento que contiene [nowMs] (o lo reinicia tras una pausa larga). */
    private fun advanceTo(nowMs: Long) {
        if (!initialized || nowMs - segmentStartMs >= IDLE_RESTART_SEGMENTS * segmentDurationMs) {
            startSegment(nowMs, randomOffset())
            initialized = true
        }
        while (nowMs >= segmentStartMs + segmentDurationMs) {
            startSegment(segmentStartMs + segmentDurationMs, endOffsetMs)
        }
    }

    private fun startSegment(startMs: Long, fromOffsetMs: Long) {
        segmentStartMs = startMs
        segmentDurationMs = max(MIN_SEGMENT_MS, 2 * amplitudeMs)
        startOffsetMs = fromOffsetMs
        endOffsetMs = randomOffset()
    }

    private fun randomOffset(): Long = random(-amplitudeMs, amplitudeMs)

    private companion object {
        const val MIN_SEGMENT_MS = 10L

        /** Tras este número de segmentos sin paquetes se empieza de cero en vez de recorrerlos. */
        const val IDLE_RESTART_SEGMENTS = 4L
    }
}
