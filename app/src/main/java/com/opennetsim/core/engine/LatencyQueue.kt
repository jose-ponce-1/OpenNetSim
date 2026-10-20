package com.opennetsim.core.engine

import java.util.PriorityQueue
import kotlin.math.max

/**
 * Representa un paquete retenido en la cola con su timestamp programado de entrega.
 */
data class DelayedPacket(
    val data: ByteArray,
    val scheduledReleaseTimeMs: Long,
    val originalTimestampMs: Long,
    val sequenceNumber: Long
) : Comparable<DelayedPacket> {
    override fun compareTo(other: DelayedPacket): Int {
        val timeCmp = scheduledReleaseTimeMs.compareTo(other.scheduledReleaseTimeMs)
        return if (timeCmp != 0) timeCmp else sequenceNumber.compareTo(other.sequenceNumber)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DelayedPacket
        return sequenceNumber == other.sequenceNumber &&
                scheduledReleaseTimeMs == other.scheduledReleaseTimeMs &&
                originalTimestampMs == other.originalTimestampMs &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + scheduledReleaseTimeMs.hashCode()
        result = 31 * result + originalTimestampMs.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}

/**
 * Cola priorizada que simula latencia y jitter de red.
 * Los paquetes son ordenados por su tiempo de liberación programado (scheduledReleaseTimeMs).
 *
 * @param baseLatencyMs Retardo base en milisegundos.
 * @param jitterMs Desviación máxima simétrica (+/- ms).
 * @param timeSourceMs Proveedor inyectable de tiempo actual en milisegundos.
 * @param jitterRandom Generador de número aleatorio en un rango cerrado [min, max].
 */
class LatencyQueue(
    var baseLatencyMs: Long = 0L,
    var jitterMs: Long = 0L,
    val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
    private val jitterRandom: (minInclusive: Long, maxInclusive: Long) -> Long = { min, max ->
        if (min == max) min else kotlin.random.Random.nextLong(min, max + 1)
    }
) {
    private val queue = PriorityQueue<DelayedPacket>()
    private var sequenceCounter = 0L

    val size: Int
        @Synchronized get() = queue.size

    val isEmpty: Boolean
        @Synchronized get() = queue.isEmpty()

    val isFull: Boolean
        @Synchronized get() = queue.size >= maxCapacity

    /**
     * Calcula el retardo de latencia sumando la base y el jitter aleatorio.
     */
    fun calculateDelayMs(): Long {
        val jitterOffset = if (jitterMs > 0L) {
            jitterRandom(-jitterMs, jitterMs)
        } else {
            0L
        }
        return max(0L, baseLatencyMs + jitterOffset)
    }

    /**
     * Encola un paquete con una marca de tiempo programada de liberación explícita.
     */
    @Synchronized
    fun enqueueWithScheduledTime(
        data: ByteArray,
        scheduledReleaseTimeMs: Long,
        originalTimestampMs: Long = timeSourceMs()
    ): DelayedPacket {
        val delayedPacket = DelayedPacket(
            data = data,
            scheduledReleaseTimeMs = scheduledReleaseTimeMs,
            originalTimestampMs = originalTimestampMs,
            sequenceNumber = sequenceCounter++
        )

        queue.add(delayedPacket)
        return delayedPacket
    }

    /**
     * Encola un paquete calculando su tiempo objetivo:
     * scheduledTime = currentTimeMs + max(0, baseLatencyMs ± jitter)
     */
    @Synchronized
    fun enqueue(data: ByteArray, currentTimeMs: Long = timeSourceMs()): DelayedPacket {
        val scheduledTime = currentTimeMs + calculateDelayMs()
        return enqueueWithScheduledTime(
            data = data,
            scheduledReleaseTimeMs = scheduledTime,
            originalTimestampMs = currentTimeMs
        )
    }

    /**
     * Inspecciona sin extraer el siguiente paquete listo si su scheduledReleaseTimeMs <= currentTimeMs.
     */
    @Synchronized
    fun peekReady(currentTimeMs: Long = timeSourceMs()): DelayedPacket? {
        val next = queue.peek() ?: return null
        return if (next.scheduledReleaseTimeMs <= currentTimeMs) {
            next
        } else {
            null
        }
    }

    /**
     * Extrae el siguiente paquete listo para ser transmitido si su scheduledReleaseTimeMs <= currentTimeMs.
     */
    @Synchronized
    fun pollReady(currentTimeMs: Long = timeSourceMs()): DelayedPacket? {
        val next = queue.peek() ?: return null
        return if (next.scheduledReleaseTimeMs <= currentTimeMs) {
            queue.poll()
        } else {
            null
        }
    }

    /**
     * Drena y extrae todos los paquetes cuyo tiempo de liberación programado ya haya expirado.
     */
    @Synchronized
    fun drainReady(currentTimeMs: Long = timeSourceMs()): List<ByteArray> {
        val ready = mutableListOf<ByteArray>()
        while (true) {
            val next = queue.peek() ?: break
            if (next.scheduledReleaseTimeMs <= currentTimeMs) {
                val polled = queue.poll() ?: break
                ready.add(polled.data)
            } else {
                break
            }
        }
        return ready
    }

    /**
     * Devuelve el tiempo restante en milisegundos hasta que el próximo paquete en la cola esté listo,
     * o null si la cola está vacía.
     */
    @Synchronized
    fun timeUntilNextReadyMs(currentTimeMs: Long = timeSourceMs()): Long? {
        val next = queue.peek() ?: return null
        return max(0L, next.scheduledReleaseTimeMs - currentTimeMs)
    }

    /**
     * Limpia la cola de paquetes retenidos.
     */
    @Synchronized
    fun clear() {
        queue.clear()
    }

    /**
     * Actualiza la latencia base y jitter dinámicamente.
     */
    @Synchronized
    fun updateLatencyAndJitter(newBaseLatencyMs: Long, newJitterMs: Long) {
        this.baseLatencyMs = max(0L, newBaseLatencyMs)
        this.jitterMs = max(0L, newJitterMs)
    }

    companion object {
        const val DEFAULT_MAX_CAPACITY = 100
    }
}

