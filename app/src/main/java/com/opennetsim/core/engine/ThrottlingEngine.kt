package com.opennetsim.core.engine

import com.opennetsim.model.ThrottleConfig
import kotlin.math.max

/**
 * Resultado del procesamiento de un paquete entrante en el pipeline.
 */
sealed interface PacketResult {
    /**
     * El paquete fue liberado de inmediato sin retardo.
     */
    data class Released(val packet: ByteArray) : PacketResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Released
            return packet.contentEquals(other.packet)
        }

        override fun hashCode(): Int = packet.contentHashCode()
    }

    /**
     * El paquete fue descartado probabilísticamente por el simulador de pérdida o por tail-drop.
     */
    data object Dropped : PacketResult

    /**
     * El paquete fue retenido en cola para su posterior liberación.
     * @param scheduledReleaseTimeMs Instante estimado de salida (latencia y espera de tokens).
     * @param waitMs Espera estimada desde el momento de encolado.
     */
    data class Enqueued(val scheduledReleaseTimeMs: Long, val waitMs: Long) : PacketResult
}

/**
 * Clase de tráfico de un paquete:
 * - [INTERACTIVE]: ACK/FIN/RST/SYN sin datos, ICMP y DNS. Se sirve antes que el masivo de otros flujos.
 * - [BULK]: datos de transferencias.
 */
enum class TrafficClass { INTERACTIVE, BULK }

/**
 * Métricas acumuladas del motor de throttling.
 */
data class EngineMetrics(
    val totalReceivedPackets: Long = 0L,
    val totalReleasedPackets: Long = 0L,
    val totalDroppedPackets: Long = 0L,
    val totalReceivedBytes: Long = 0L,
    val totalReleasedBytes: Long = 0L,
    val totalDroppedBytes: Long = 0L
)

/**
 * Coordinador central del pipeline de degradación de tráfico de una dirección.
 *
 * Integra descarte estocástico, latencia/jitter y control de tasa por Token Bucket con dos carriles
 * FIFO: interactivo y masivo. El drenaje sirve primero el carril interactivo; ambos consumen tokens
 * del mismo bucket. Un paquete interactivo de un flujo con datos masivos en cola va al carril masivo,
 * detrás de ellos, de modo que nunca adelanta a datos anteriores de su propio flujo.
 *
 * @param latencyQueue Carril masivo; también define la latencia base y el jitter.
 * @param queueWindowMs Retardo de cola máximo (además de la latencia) que admite el carril masivo.
 */
@Suppress("TooManyFunctions")
class ThrottlingEngine(
    val bandwidthLimiter: TokenBucketLimiter,
    val latencyQueue: LatencyQueue,
    val packetLossSimulator: PacketLossSimulator,
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
    private val queueWindowMs: Long = DEFAULT_QUEUE_WINDOW_MS
) {
    private val interactiveQueue = LatencyQueue(timeSourceMs = timeSourceMs)

    private var totalReceivedPackets: Long = 0L
    private var totalReleasedPackets: Long = 0L
    private var totalDroppedPackets: Long = 0L
    private var totalReceivedBytes: Long = 0L
    private var totalReleasedBytes: Long = 0L
    private var totalDroppedBytes: Long = 0L

    private var bulkBytes: Long = 0L
    private var interactiveBytes: Long = 0L
    private val bulkPacketsByFlow = HashMap<Long, Int>()
    private var lastBulkScheduledMs: Long = 0L
    private var lastInteractiveScheduledMs: Long = 0L

    /**
     * Bytes que admite el carril masivo: los que esperan la latencia configurada más
     * [queueWindowMs] de cola a la tasa configurada, con un mínimo de [MIN_BULK_PACKETS] MTUs.
     */
    val bulkCapacityBytes: Long
        @Synchronized get() {
            val delayMs = queueWindowMs + latencyQueue.baseLatencyMs + latencyQueue.jitterMs
            return max(MIN_BULK_PACKETS * MTU_BYTES, bandwidthLimiter.bytesPerSecond * delayMs / MS_PER_SECOND)
        }

    /** Bytes encolados en el carril masivo. */
    val queuedBulkBytes: Long
        @Synchronized get() = bulkBytes

    /**
     * True cuando el carril masivo supera el 75 % de su capacidad: los lectores de sockets exteriores
     * deben pausarse antes de llegar al tail-drop.
     */
    val isBackpressured: Boolean
        @Synchronized get() = bulkBytes * BACKPRESSURE_DENOMINATOR >= bulkCapacityBytes * BACKPRESSURE_NUMERATOR

    /** Señal de capacidad: el carril masivo admite más datos sin acercarse al tail-drop. */
    val hasCapacity: Boolean get() = !isBackpressured

    /** Paquetes encolados en ambos carriles. */
    val queuedPackets: Int
        @Synchronized get() = latencyQueue.size + interactiveQueue.size

    /**
     * Procesa un paquete entrante a través del pipeline.
     *
     * @param flowId Identificador del flujo de transporte, o [DelayedPacket.NO_FLOW].
     * @param allowImmediateRelease Si es false, el paquete siempre se encola (el llamante drena en
     * orden desde un único hilo) aunque pudiera salir al instante.
     */
    @Synchronized
    fun processIncoming(
        packet: ByteArray,
        currentTimeMs: Long = timeSourceMs(),
        trafficClass: TrafficClass = TrafficClass.BULK,
        flowId: Long = DelayedPacket.NO_FLOW,
        allowImmediateRelease: Boolean = true
    ): PacketResult {
        totalReceivedPackets++
        totalReceivedBytes += packet.size

        // Paso 1: pérdida estocástica, igual para datos y paquetes de control.
        if (packetLossSimulator.shouldDrop()) return drop(packet)

        val useInteractiveLane = trafficClass == TrafficClass.INTERACTIVE &&
            (flowId == DelayedPacket.NO_FLOW || (bulkPacketsByFlow[flowId] ?: 0) == 0)

        val demoted = trafficClass == TrafficClass.INTERACTIVE && !useInteractiveLane
        return if (useInteractiveLane) {
            enqueueInteractive(packet, currentTimeMs, flowId, allowImmediateRelease)
        } else {
            enqueueBulk(packet, currentTimeMs, flowId, allowImmediateRelease, demoted)
        }
    }

    private fun enqueueInteractive(packet: ByteArray, now: Long, flowId: Long, allowImmediate: Boolean): PacketResult {
        val latencyReady = laneSchedule(now, interactiveQueue.isEmpty, lastInteractiveScheduledMs)
        return when {
            interactiveQueue.size >= MAX_INTERACTIVE_PACKETS -> drop(packet)
            allowImmediate && interactiveQueue.isEmpty && tryReleaseNow(latencyReady, now, packet) -> release(packet)
            else -> {
                lastInteractiveScheduledMs = latencyReady
                interactiveQueue.enqueueWithScheduledTime(packet, latencyReady, now, flowId)
                interactiveBytes += packet.size
                estimateDeparture(latencyReady, interactiveBytes - packet.size, packet.size, now)
            }
        }
    }

    private fun enqueueBulk(
        packet: ByteArray,
        now: Long,
        flowId: Long,
        allowImmediate: Boolean,
        demoted: Boolean
    ): PacketResult {
        val latencyReady = laneSchedule(now, latencyQueue.isEmpty, lastBulkScheduledMs)
        val lanesEmpty = latencyQueue.isEmpty && interactiveQueue.isEmpty
        return when {
            allowImmediate && lanesEmpty && tryReleaseNow(latencyReady, now, packet) -> release(packet)
            // Tail-drop por bytes de lo que se encola. Un paquete de control relegado detrás de su
            // flujo no se descarta.
            !demoted && bulkBytes + packet.size > bulkCapacityBytes -> drop(packet)
            else -> {
                lastBulkScheduledMs = latencyReady
                latencyQueue.enqueueWithScheduledTime(packet, latencyReady, now, flowId)
                bulkBytes += packet.size
                if (flowId != DelayedPacket.NO_FLOW) bulkPacketsByFlow.merge(flowId, 1, Int::plus)
                estimateDeparture(latencyReady, interactiveBytes + bulkBytes - packet.size, packet.size, now)
            }
        }
    }

    /** True (y consume los tokens) si el paquete ya cumplió su latencia y hay tokens para él. */
    private fun tryReleaseNow(latencyReady: Long, now: Long, packet: ByteArray): Boolean =
        latencyReady <= now && bandwidthLimiter.tryConsume(packet.size)

    /** Instante en que el paquete cumple su latencia, sin adelantar al anterior del carril (FIFO). */
    private fun laneSchedule(now: Long, laneEmpty: Boolean, lastScheduled: Long): Long {
        val latencyScheduled = now + latencyQueue.calculateDelayMs()
        return if (laneEmpty) latencyScheduled else max(latencyScheduled, lastScheduled)
    }

    private fun estimateDeparture(latencyReady: Long, bytesAhead: Long, size: Int, now: Long): PacketResult.Enqueued {
        val tokenWaitMs = bandwidthLimiter.estimateWaitTimeMs(bytesAhead, size)
        val departure = max(latencyReady, now + tokenWaitMs)
        return PacketResult.Enqueued(scheduledReleaseTimeMs = departure, waitMs = max(0L, departure - now))
    }

    private fun drop(packet: ByteArray): PacketResult {
        totalDroppedPackets++
        totalDroppedBytes += packet.size
        return PacketResult.Dropped
    }

    private fun release(packet: ByteArray): PacketResult {
        totalReleasedPackets++
        totalReleasedBytes += packet.size
        return PacketResult.Released(packet)
    }

    /**
     * Drena y devuelve, en orden, los paquetes que ya cumplieron su latencia y disponen de tokens.
     * El carril interactivo tiene prioridad; si su cabeza está lista pero sin tokens, nada sale.
     */
    @Synchronized
    fun drainReady(currentTimeMs: Long = timeSourceMs()): List<ByteArray> {
        val readyList = mutableListOf<ByteArray>()
        var lane = readyLane(currentTimeMs)
        while (lane != null && bandwidthLimiter.tryConsume(lane.peekHeadSize() ?: 0)) {
            val packet = lane.pollReady(currentTimeMs) ?: break
            if (lane === interactiveQueue) {
                interactiveBytes -= packet.data.size
            } else {
                bulkBytes -= packet.data.size
                releaseFlowSlot(packet.flowId)
            }
            totalReleasedPackets++
            totalReleasedBytes += packet.data.size
            readyList.add(packet.data)
            lane = readyLane(currentTimeMs)
        }
        return readyList
    }

    /** Carril cuya cabeza ya cumplió su latencia, con prioridad para el interactivo. */
    private fun readyLane(currentTimeMs: Long): LatencyQueue? = when {
        interactiveQueue.peekReady(currentTimeMs) != null -> interactiveQueue
        latencyQueue.peekReady(currentTimeMs) != null -> latencyQueue
        else -> null
    }

    private fun releaseFlowSlot(flowId: Long) {
        if (flowId == DelayedPacket.NO_FLOW) return
        val remaining = (bulkPacketsByFlow[flowId] ?: 0) - 1
        if (remaining <= 0) bulkPacketsByFlow.remove(flowId) else bulkPacketsByFlow[flowId] = remaining
    }

    /**
     * Devuelve el tiempo en milisegundos hasta el próximo evento programado (liberación por tiempo
     * o recarga de tokens para la cabeza de algún carril), o null si ambos carriles están vacíos.
     */
    @Synchronized
    fun timeUntilNextEventMs(currentTimeMs: Long = timeSourceMs()): Long? =
        listOfNotNull(
            laneEventMs(interactiveQueue, currentTimeMs),
            laneEventMs(latencyQueue, currentTimeMs)
        ).minOrNull()

    /** Espera hasta que la cabeza del carril cumpla su latencia o, si ya la cumplió, hasta tener tokens. */
    private fun laneEventMs(lane: LatencyQueue, currentTimeMs: Long): Long? {
        val wait = lane.timeUntilNextReadyMs(currentTimeMs)
        val headSize = lane.peekHeadSize()
        return when {
            wait == null || headSize == null -> null
            wait > 0L -> wait
            else -> bandwidthLimiter.calculateWaitTimeMs(headSize)
        }
    }

    /**
     * Actualiza los parámetros de degradación de este pipeline de forma sincronizada.
     */
    @Synchronized
    fun updateParameters(
        rateKbps: Int,
        latencyMs: Int,
        jitterMs: Int,
        packetLossPercent: Float
    ) {
        bandwidthLimiter.updateRate(newBytesPerSecond = kbpsToBytesPerSecond(rateKbps))
        latencyQueue.updateLatencyAndJitter(
            newBaseLatencyMs = max(0L, latencyMs.toLong()),
            newJitterMs = max(0L, jitterMs.toLong())
        )
        packetLossSimulator.updateLossPercent(packetLossPercent)
    }

    /**
     * Actualiza la configuración de todos los submódulos de forma sincronizada (asumiendo downlink).
     */
    @Synchronized
    fun updateConfig(config: ThrottleConfig) {
        updateParameters(
            rateKbps = config.downKbps,
            latencyMs = config.latencyMs,
            jitterMs = config.jitterMs,
            packetLossPercent = config.packetLossPercent
        )
    }

    /**
     * Consulta las métricas actuales del motor.
     */
    @Synchronized
    fun getMetrics(): EngineMetrics {
        return EngineMetrics(
            totalReceivedPackets = totalReceivedPackets,
            totalReleasedPackets = totalReleasedPackets,
            totalDroppedPackets = totalDroppedPackets,
            totalReceivedBytes = totalReceivedBytes,
            totalReleasedBytes = totalReleasedBytes,
            totalDroppedBytes = totalDroppedBytes
        )
    }

    /**
     * Restablece las métricas contables a cero.
     */
    @Synchronized
    fun resetMetrics() {
        totalReceivedPackets = 0L
        totalReleasedPackets = 0L
        totalDroppedPackets = 0L
        totalReceivedBytes = 0L
        totalReleasedBytes = 0L
        totalDroppedBytes = 0L
    }

    /**
     * Limpia las colas de retención y reinicia el estado.
     */
    @Synchronized
    fun clear() {
        latencyQueue.clear()
        interactiveQueue.clear()
        bulkBytes = 0L
        interactiveBytes = 0L
        bulkPacketsByFlow.clear()
        bandwidthLimiter.reset()
        lastBulkScheduledMs = 0L
        lastInteractiveScheduledMs = 0L
    }

    companion object {
        const val DEFAULT_QUEUE_WINDOW_MS = 500L
        const val MAX_INTERACTIVE_PACKETS = 128

        /** Mínimo para tener un paquete en serialización y otro esperando; más rompe la cota a tasas bajas. */
        private const val MIN_BULK_PACKETS = 2L
        private const val MTU_BYTES = 1500L
        private const val MS_PER_SECOND = 1000L
        private const val BACKPRESSURE_NUMERATOR = 3L
        private const val BACKPRESSURE_DENOMINATOR = 4L
        private const val BITS_PER_KILOBIT = 1000L
        private const val BITS_PER_BYTE = 8L
        private const val MIN_RATE_BYTES_PER_SECOND = 1024L

        /** Tasa del token bucket en B/s, con un mínimo de 1 KB/s. */
        fun kbpsToBytesPerSecond(rateKbps: Int): Long =
            max(MIN_RATE_BYTES_PER_SECOND, rateKbps * BITS_PER_KILOBIT / BITS_PER_BYTE)

        @Suppress("LongParameterList") // Los relojes son parámetros por defecto, inyectables en tests.
        fun createPipeline(
            rateKbps: Int,
            latencyMs: Int,
            jitterMs: Int,
            packetLossPercent: Float,
            timeSourceMs: () -> Long = System::currentTimeMillis,
            timeSourceNanos: () -> Long = System::nanoTime
        ): ThrottlingEngine {
            val bytesPerSec = kbpsToBytesPerSecond(rateKbps)
            val limiter = TokenBucketLimiter(
                bytesPerSecond = bytesPerSec,
                burstCapacityBytes = TokenBucketLimiter.defaultBurstCapacity(bytesPerSec),
                timeSourceNanos = timeSourceNanos
            )
            val queue = LatencyQueue(
                baseLatencyMs = max(0L, latencyMs.toLong()),
                jitterMs = max(0L, jitterMs.toLong()),
                timeSourceMs = timeSourceMs
            )
            val lossSim = PacketLossSimulator(
                initialLossProbability = (packetLossPercent / 100.0).toDouble()
            )

            return ThrottlingEngine(
                bandwidthLimiter = limiter,
                latencyQueue = queue,
                packetLossSimulator = lossSim,
                timeSourceMs = timeSourceMs
            )
        }

        fun fromConfig(
            config: ThrottleConfig,
            timeSourceMs: () -> Long = System::currentTimeMillis
        ): ThrottlingEngine {
            return createPipeline(
                rateKbps = config.downKbps,
                latencyMs = config.latencyMs,
                jitterMs = config.jitterMs,
                packetLossPercent = config.packetLossPercent,
                timeSourceMs = timeSourceMs
            )
        }
    }
}
