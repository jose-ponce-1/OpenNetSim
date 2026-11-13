package com.opennetsim.core.tcp

/**
 * Dimensionado de ventanas y temporizadores TCP a partir de la degradación configurada.
 */
object TcpWindowPolicy {
    const val MIN_IN_FLIGHT_BYTES = 64 * 1024
    const val MAX_IN_FLIGHT_BYTES = 4 * 1024 * 1024

    private const val HEADROOM_BYTES = 64 * 1024L
    private const val BDP_MULTIPLIER = 2L
    private const val BITS_PER_BYTE = 8L
    private const val BITS_PER_KILOBIT = 1000L
    private const val MS_PER_SECOND = 1000L

    private const val MIN_INITIAL_RTO_MS = 200L
    private const val MAX_INITIAL_RTO_MS = 1_000L

    /**
     * Tope de bytes en vuelo hacia el cliente:
     * `clamp(2 × tasaBajada × (latencia + jitter) + 64 KB, 64 KB, 4 MB)`.
     */
    fun bdpCapBytes(downKbps: Int, latencyMs: Int, jitterMs: Int): Int {
        val bytesPerSecond = downKbps.coerceAtLeast(0) * BITS_PER_KILOBIT / BITS_PER_BYTE
        val delayMs = (latencyMs + jitterMs).coerceAtLeast(0).toLong()
        val cap = BDP_MULTIPLIER * bytesPerSecond * delayMs / MS_PER_SECOND + HEADROOM_BYTES
        return cap.coerceIn(MIN_IN_FLIGHT_BYTES.toLong(), MAX_IN_FLIGHT_BYTES.toLong()).toInt()
    }

    /**
     * RTO inicial (antes de tener muestras de RTT): `clamp(2 × (latencia + jitter) + 200 ms, 200 ms, 1 s)`.
     * Permite retransmitir un SYN-ACK perdido antes de que el cliente reintente su SYN (1 s).
     */
    fun initialRtoMs(latencyMs: Int, jitterMs: Int): Long {
        val delayMs = (latencyMs + jitterMs).coerceAtLeast(0).toLong()
        return (BDP_MULTIPLIER * delayMs + MIN_INITIAL_RTO_MS).coerceIn(MIN_INITIAL_RTO_MS, MAX_INITIAL_RTO_MS)
    }
}
