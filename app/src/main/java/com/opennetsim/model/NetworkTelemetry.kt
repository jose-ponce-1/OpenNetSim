package com.opennetsim.model

import java.util.Locale

/**
 * Métricas de telemetría de red en tiempo real emitidas por el motor de throttling.
 *
 * @param bytesPerSecond Velocidad actual de transferencia calculada por segundo.
 * @param totalTransferredBytes Volumen total de datos liberados a través del túnel.
 * @param totalDroppedPackets Número acumulado de paquetes descartados en la simulación.
 * @param isConnected Indica si el túnel TUN está establecido y operativo.
 */
data class NetworkTelemetry(
    val bytesPerSecond: Long = 0L,
    val totalTransferredBytes: Long = 0L,
    val totalDroppedPackets: Long = 0L,
    val isConnected: Boolean = false
) {
    /**
     * Formatea la tasa instantánea en KB/s o MB/s.
     */
    fun formattedThroughput(): String {
        return when {
            bytesPerSecond >= 1_000_000L -> {
                String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
            }
            bytesPerSecond >= 1_000L -> {
                String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1_000.0)
            }
            else -> "$bytesPerSecond B/s"
        }
    }

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
}

