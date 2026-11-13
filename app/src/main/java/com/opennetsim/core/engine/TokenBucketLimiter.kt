package com.opennetsim.core.engine

import kotlin.math.ceil
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
    burstCapacityBytes: Long = defaultBurstCapacity(bytesPerSecond),
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
     *
     * Un paquete se admite cuando hay al menos `min(bytes, burstCapacityBytes)` tokens y se descuenta
     * su tamaño completo, aunque el saldo quede negativo (déficit). Así un paquete mayor que la ráfaga
     * nunca bloquea la cola: sale en cuanto el bucket alcanza la ráfaga, y los siguientes esperan a
     * que se pague la deuda, de modo que la tasa media sigue siendo exacta.
     *
     * @return true si el paquete fue admitido y sus tokens descontados; false en caso contrario.
     */
    @Synchronized
    fun tryConsume(bytes: Int): Boolean {
        require(bytes >= 0) { "El número de bytes a consumir no puede ser negativo: $bytes" }
        if (bytes == 0) return true

        val now = timeSourceNanos()
        refill(now)

        return if (availableTokens + TOKEN_EPSILON >= admissionThreshold(bytes)) {
            availableTokens -= bytes
            true
        } else {
            false
        }
    }

    /**
     * Calcula los nanosegundos de espera hasta que un paquete de [bytes] bytes sea admitido por
     * [tryConsume], incluyendo la deuda pendiente de paquetes anteriores mayores que la ráfaga.
     */
    @Synchronized
    fun calculateWaitTimeNanos(bytes: Int, nowNanos: Long = timeSourceNanos()): Long {
        require(bytes >= 0) { "El número de bytes no puede ser negativo: $bytes" }
        if (bytes == 0) return 0L

        refill(nowNanos)

        val threshold = admissionThreshold(bytes)
        return if (availableTokens + TOKEN_EPSILON >= threshold) {
            0L
        } else {
            val deficit = threshold - availableTokens
            val nanosRequired = ceil((deficit / bytesPerSecond.toDouble()) * 1_000_000_000.0).toLong()
            max(1L, nanosRequired)
        }
    }

    /**
     * Estima, en milisegundos (redondeo hacia arriba), cuánto tardará en admitirse un paquete de
     * [bytes] si antes deben salir [bytesAhead] bytes ya encolados.
     */
    @Synchronized
    fun estimateWaitTimeMs(bytesAhead: Long, bytes: Int, nowNanos: Long = timeSourceNanos()): Long {
        refill(nowNanos)
        val deficit = bytesAhead.coerceAtLeast(0L) + admissionThreshold(bytes) - availableTokens
        if (deficit <= TOKEN_EPSILON) return 0L
        return ceil(deficit * MS_PER_SECOND / bytesPerSecond.toDouble()).toLong()
    }

    private fun admissionThreshold(bytes: Int): Double = min(bytes.toLong(), burstCapacityBytes).toDouble()

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
    fun updateRate(
        newBytesPerSecond: Long,
        newBurstCapacityBytes: Long = defaultBurstCapacity(newBytesPerSecond)
    ) {
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

        /** Tolerancia de redondeo en coma flotante al comparar tokens. */
        private const val TOKEN_EPSILON: Double = 1e-6
        private const val MS_PER_SECOND: Double = 1000.0
        private const val BURST_WINDOW_DIVISOR: Long = 20L // Ráfaga máxima de 50 ms

        fun defaultBurstCapacity(bytesPerSecond: Long): Long =
            max(MIN_BURST_CAPACITY_BYTES, bytesPerSecond / BURST_WINDOW_DIVISOR)
    }
}

