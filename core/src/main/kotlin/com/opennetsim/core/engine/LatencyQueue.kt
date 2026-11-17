package com.opennetsim.core.engine

import com.opennetsim.core.time.MonotonicClock
import java.util.PriorityQueue
import kotlin.math.max

/**
 * Representa un paquete retenido en la cola con su timestamp programado de entrega.
 */
data class DelayedPacket(
    val data: ByteArray,
    val scheduledReleaseTimeMs: Long,
    val originalTimestampMs: Long,
    val sequenceNumber: Long,
    val flowId: Long = NO_FLOW
) : Comparable<DelayedPacket> {
    companion object {
        /** Paquete sin flujo de transporte identificado. */
        const val NO_FLOW: Long = Long.MIN_VALUE
    }

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
 * @param jitterMs Desviación máxima simétrica (+/- ms), aplicada con un [JitterProcess] continuo.
 * @param clock Reloj monotónico compartido con el resto del motor.
 * @param jitterRandom Generador de número aleatorio en un rango cerrado [min, max].
 */
class LatencyQueue(
    baseLatencyMs: Long = 0L,
    jitterMs: Long = 0L,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    jitterRandom: (minInclusive: Long, maxInclusive: Long) -> Long = { min, max ->
        if (min == max) min else kotlin.random.Random.nextLong(min, max + 1)
    }
) {
    private val queue = PriorityQueue<DelayedPacket>()
    private var sequenceCounter = 0L
    private val jitter = JitterProcess(jitterRandom).also { it.reset(max(0L, jitterMs)) }

    var baseLatencyMs: Long = max(0L, baseLatencyMs)

    var jitterMs: Long = max(0L, jitterMs)
        private set

    val size: Int
        @Synchronized get() = queue.size

    val isEmpty: Boolean
        @Synchronized get() = queue.isEmpty()

    /**
     * Retardo de un paquete que entra en [nowMs]: la base más el desplazamiento del jitter en ese
     * instante.
     */
    @Synchronized
    fun calculateDelayMs(nowMs: Long = clock.nowMs()): Long = max(0L, baseLatencyMs + jitter.offsetAt(nowMs))

    /**
     * Encola un paquete con una marca de tiempo programada de liberación explícita.
     */
    @Synchronized
    fun enqueueWithScheduledTime(
        data: ByteArray,
        scheduledReleaseTimeMs: Long,
        originalTimestampMs: Long = clock.nowMs(),
        flowId: Long = DelayedPacket.NO_FLOW
    ): DelayedPacket {
        val delayedPacket = DelayedPacket(
            data = data,
            scheduledReleaseTimeMs = scheduledReleaseTimeMs,
            originalTimestampMs = originalTimestampMs,
            sequenceNumber = sequenceCounter++,
            flowId = flowId
        )

        queue.add(delayedPacket)
        return delayedPacket
    }

    /**
     * Encola un paquete calculando su tiempo objetivo:
     * scheduledTime = currentTimeMs + max(0, baseLatencyMs ± jitter)
     */
    @Synchronized
    fun enqueue(data: ByteArray, currentTimeMs: Long = clock.nowMs()): DelayedPacket {
        val scheduledTime = currentTimeMs + calculateDelayMs(currentTimeMs)
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
    fun peekReady(currentTimeMs: Long = clock.nowMs()): DelayedPacket? {
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
    fun pollReady(currentTimeMs: Long = clock.nowMs()): DelayedPacket? {
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
    fun drainReady(currentTimeMs: Long = clock.nowMs()): List<ByteArray> {
        val ready = mutableListOf<ByteArray>()
        var next = queue.peek()
        while (next != null && next.scheduledReleaseTimeMs <= currentTimeMs) {
            queue.poll()
            ready.add(next.data)
            next = queue.peek()
        }
        return ready
    }

    /** Tamaño en bytes del paquete en cabeza de la cola, o null si está vacía. */
    @Synchronized
    fun peekHeadSize(): Int? = queue.peek()?.data?.size

    /**
     * Devuelve el tiempo restante en milisegundos hasta que el próximo paquete en la cola esté listo,
     * o null si la cola está vacía.
     */
    @Synchronized
    fun timeUntilNextReadyMs(currentTimeMs: Long = clock.nowMs()): Long? {
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
        val amplitude = max(0L, newJitterMs)
        if (amplitude != jitterMs) jitter.reset(amplitude)
        this.jitterMs = amplitude
    }
}
