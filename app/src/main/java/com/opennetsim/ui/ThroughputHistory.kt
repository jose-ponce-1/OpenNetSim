package com.opennetsim.ui

import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import kotlin.math.max

/** Una muestra por segundo del caudal útil del túnel. */
data class ThroughputSample(val downBytesPerSecond: Long, val upBytesPerSecond: Long)

/** Dirección del tráfico que muestra la gráfica. */
enum class TrafficDirection(val label: String) {
    DOWN("Bajada"),
    UP("Subida"),
    BOTH("Ambos")
}

/**
 * Gráfica lista para pintar. Las series van de la muestra más antigua a la más reciente, como fracción
 * de la altura (0 abajo, 1 arriba); una serie oculta es null. [capFraction] es la altura del límite.
 */
data class ThroughputChart(
    val down: List<Float>?,
    val up: List<Float>?,
    val capFraction: Float,
    val capacity: Int
)

/** Titular de la gráfica: qué se mide, su valor actual y el límite configurado. */
data class ThroughputHeadline(val label: String, val value: String, val capLabel: String)

/** Historial de los últimos [CAPACITY] segundos de caudal y su representación en la gráfica. */
object ThroughputHistory {
    const val CAPACITY = 60
    private const val BYTES_PER_SECOND_PER_KBPS = 125.0
    private const val CAP_HEADROOM = 1.15
    private const val DATA_HEADROOM = 1.05

    fun append(history: List<ThroughputSample>, telemetry: NetworkTelemetry): List<ThroughputSample> =
        (history + ThroughputSample(telemetry.downBytesPerSecond, telemetry.upBytesPerSecond)).takeLast(CAPACITY)

    fun chart(history: List<ThroughputSample>, config: ThrottleConfig, direction: TrafficDirection): ThroughputChart {
        val downCap = config.downKbps * BYTES_PER_SECOND_PER_KBPS
        val upCap = config.upKbps * BYTES_PER_SECOND_PER_KBPS
        val showDown = direction != TrafficDirection.UP
        val showUp = direction != TrafficDirection.DOWN
        val capsMax = when (direction) {
            TrafficDirection.DOWN -> downCap
            TrafficDirection.UP -> upCap
            TrafficDirection.BOTH -> max(downCap, upCap)
        }
        val dataMax = history.maxOfOrNull { sample ->
            max(
                if (showDown) sample.downBytesPerSecond else 0L,
                if (showUp) sample.upBytesPerSecond else 0L
            )
        } ?: 0L
        val scale = max(max(capsMax * CAP_HEADROOM, dataMax * DATA_HEADROOM), 1.0)
        fun fractions(select: (ThroughputSample) -> Long) = history.map { (select(it) / scale).toFloat() }
        return ThroughputChart(
            down = if (showDown) fractions { it.downBytesPerSecond } else null,
            up = if (showUp) fractions { it.upBytesPerSecond } else null,
            capFraction = ((if (direction == TrafficDirection.UP) upCap else downCap) / scale).toFloat(),
            capacity = CAPACITY
        )
    }

    fun headline(
        telemetry: NetworkTelemetry,
        config: ThrottleConfig,
        direction: TrafficDirection,
        active: Boolean
    ): ThroughputHeadline {
        val value = when {
            !active -> "0 B/s"
            direction == TrafficDirection.DOWN -> telemetry.formattedDownThroughput()
            direction == TrafficDirection.UP -> telemetry.formattedUpThroughput()
            else -> telemetry.formattedThroughput()
        }
        val down = UiFormatters.bandwidth(config.downKbps)
        val up = UiFormatters.bandwidth(config.upKbps)
        return when (direction) {
            TrafficDirection.DOWN -> ThroughputHeadline("Bajada actual", value, "Límite $down")
            TrafficDirection.UP -> ThroughputHeadline("Subida actual", value, "Límite $up")
            TrafficDirection.BOTH -> ThroughputHeadline("Caudal útil total", value, "Límite ↓ $down")
        }
    }
}
