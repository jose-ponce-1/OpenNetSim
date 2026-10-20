package com.opennetsim.core.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Limitador de tasa de transferencia basado en el algoritmo Token Bucket.
 * Opera en Kotlin puro sin dependencias de la plataforma Android.
 *
 * @param bytesPerSecond Tasa sostenida en bytes por segundo.
 * @param burstCapacityBytes Capacidad máxima acumulable de tokens (ráfaga).
 * @param timeSourceNanos Proveedor inyectable de tiempo en nanosegundos (por defecto System.nanoTime).
 */
class TokenBucketLimiter(
    bytesPerSecond: Long,
    burstCapacityBytes: Long = bytesPerSecond,
    private val timeSourceNanos: () -> Long = System::nanoTime,
    minBurstCapacityBytes: Long = MIN_BURST_CAPACITY_BYTES
) {
    val minBurstCapacityBytes: Long = max(0L, minBurstCapacityBytes)

    var bytesPerSecond: Long = max(1L, bytesPerSecond)
        private set

    var burstCapacityBytes: Long = max(this.minBurstCapacityBytes, max(1L, burstCapacityBytes))
        private set

    private var availableTokens: Double = this.burstCapacityBytes.toDouble()
    private var lastRefillTimestampNanos: Long = timeSourceNanos()

    val currentTokens: Double
        @Synchronized get() {
            refill(timeSourceNanos())
            return availableTokens
        }

    /**
     * Intenta consumir [bytes] tokens de forma atómica.
     * @return true si había suficientes tokens y fueron consumidos; false en caso contrario.
     */
    @Synchronized
    fun tryConsume(bytes: Int): Boolean {
        require(bytes >= 0) { "El número de bytes a consumir no puede ser negativo: $bytes" }
        if (bytes == 0) return true

        val now = timeSourceNanos()
        refill(now)

        return if (availableTokens >= bytes) {
            availableTokens -= bytes
            true
        } else {
            false
        }
    }

    /**
     * Calcula los nanosegundos de espera necesarios para que se acumulen suficientes
     * tokens para transmitir un paquete de [bytes] bytes.
     */
    @Synchronized
    fun calculateWaitTimeNanos(bytes: Int, nowNanos: Long = timeSourceNanos()): Long {
        require(bytes >= 0) { "El número de bytes no puede ser negativo: $bytes" }
        if (bytes == 0) return 0L

        refill(nowNanos)

        return if (availableTokens >= bytes) {
            0L
        } else {
            val deficit = bytes - availableTokens
            val nanosRequired = ((deficit / bytesPerSecond.toDouble()) * 1_000_000_000.0).toLong()
            max(1L, nanosRequired)
        }
    }

    /**
     * Calcula el tiempo de espera en milisegundos (redondeo hacia arriba).
     */
    @Synchronized
    fun calculateWaitTimeMs(bytes: Int, nowNanos: Long = timeSourceNanos()): Long {
        val nanos = calculateWaitTimeNanos(bytes, nowNanos)
        return if (nanos == 0L) 0L else (nanos + 999_999L) / 1_000_000L
    }

    /**
     * Actualiza la tasa y la capacidad de ráfaga sin reiniciar el bucket acumulado.
     */
    @Synchronized
    fun updateRate(newBytesPerSecond: Long, newBurstCapacityBytes: Long = newBytesPerSecond) {
        val now = timeSourceNanos()
        refill(now)

        this.bytesPerSecond = max(1L, newBytesPerSecond)
        this.burstCapacityBytes = max(this.minBurstCapacityBytes, max(1L, newBurstCapacityBytes))
        this.availableTokens = min(this.burstCapacityBytes.toDouble(), this.availableTokens)
    }

    /**
     * Restablece el bucket a su capacidad máxima de ráfaga.
     */
    @Synchronized
    fun reset(nowNanos: Long = timeSourceNanos()) {
        lastRefillTimestampNanos = nowNanos
        availableTokens = burstCapacityBytes.toDouble()
    }

    private fun refill(nowNanos: Long) {
        val elapsedNanos = nowNanos - lastRefillTimestampNanos
        if (elapsedNanos > 0) {
            val tokensToAdd = (elapsedNanos.toDouble() / 1_000_000_000.0) * bytesPerSecond
            availableTokens = min(burstCapacityBytes.toDouble(), availableTokens + tokensToAdd)
            lastRefillTimestampNanos = nowNanos
        }
    }

    companion object {
        /**
         * Capacidad mínima de ráfaga garantizada para evitar inanición ante tramas MTU estándar (1500 bytes).
         */
        const val MIN_BURST_CAPACITY_BYTES: Long = 1500L
    }
}

