package com.opennetsim.model

import java.util.Locale
import kotlin.math.max

/**
 * Métricas de telemetría de red en tiempo real emitidas por el motor de throttling.
 *
 * @param bytesPerSecond Caudal útil total (bajada sin retransmisiones + subida).
 * @param totalTransferredBytes Volumen total útil liberado a través del túnel.
 * @param totalDroppedPackets Número acumulado de paquetes descartados en la simulación.
 * @param isConnected Indica si el túnel TUN está establecido y operativo.
 * @param downBytesPerSecond Caudal útil de bajada (excluye los bytes retransmitidos por el túnel).
 * @param upBytesPerSecond Caudal de subida.
 * @param retransmittedBytes Bytes acumulados retransmitidos por el túnel hacia las apps.
 */
data class NetworkTelemetry(
    val bytesPerSecond: Long = 0L,
    val totalTransferredBytes: Long = 0L,
    val totalDroppedPackets: Long = 0L,
    val isConnected: Boolean = false,
    val downBytesPerSecond: Long = 0L,
    val upBytesPerSecond: Long = 0L,
    val retransmittedBytes: Long = 0L
) {
    /**
     * Formatea la tasa instantánea en KB/s o MB/s.
     */
    fun formattedThroughput(): String = formatRate(bytesPerSecond)

    fun formattedDownThroughput(): String = formatRate(downBytesPerSecond)

    fun formattedUpThroughput(): String = formatRate(upBytesPerSecond)

    /**
     * Formatea el volumen acumulado transferido.
     */
    fun formattedTotalData(): String {
        return when {
            totalTransferredBytes >= 1_000_000L -> {
                String.format(Locale.US, "%.1f MB", totalTransferredBytes / 1_000_000.0)
            }
            totalTransferredBytes >= 1_000L -> {
                String.format(Locale.US, "%.1f KB", totalTransferredBytes / 1_000.0)
            }
            else -> "$totalTransferredBytes B"
        }
    }

    private fun formatRate(rate: Long): String {
        return when {
            rate >= 1_000_000L -> String.format(Locale.US, "%.1f MB/s", rate / 1_000_000.0)
            rate >= 1_000L -> String.format(Locale.US, "%.1f KB/s", rate / 1_000.0)
            else -> "$rate B/s"
        }
    }
}

/**
 * Contadores acumulados del túnel en un instante.
 */
data class TrafficCounters(
    val downReleasedBytes: Long = 0L,
    val upReleasedBytes: Long = 0L,
    val retransmittedBytes: Long = 0L,
    val droppedPackets: Long = 0L
)

/**
 * Convierte contadores acumulados en caudales por intervalo. El caudal de bajada resta los bytes
 * que el propio túnel retransmitió en ese intervalo, para mostrar caudal útil.
 */
class TelemetrySampler(startMs: Long, private var last: TrafficCounters = TrafficCounters()) {
    private var lastMs: Long = startMs

    fun sample(counters: TrafficCounters, nowMs: Long): NetworkTelemetry {
        val elapsedMs = max(1L, nowMs - lastMs)
        val downDelta = max(0L, counters.downReleasedBytes - last.downReleasedBytes)
        val upDelta = max(0L, counters.upReleasedBytes - last.upReleasedBytes)
        val retransmittedDelta = max(0L, counters.retransmittedBytes - last.retransmittedBytes)
        val downGoodput = max(0L, downDelta - retransmittedDelta) * MS_PER_SECOND / elapsedMs
        val upRate = upDelta * MS_PER_SECOND / elapsedMs

        last = counters
        lastMs = nowMs

        val totalUseful = max(
            0L,
            counters.downReleasedBytes + counters.upReleasedBytes - counters.retransmittedBytes
        )
        return NetworkTelemetry(
            bytesPerSecond = downGoodput + upRate,
            totalTransferredBytes = totalUseful,
            totalDroppedPackets = counters.droppedPackets,
            isConnected = true,
            downBytesPerSecond = downGoodput,
            upBytesPerSecond = upRate,
            retransmittedBytes = counters.retransmittedBytes
        )
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
    }
}
