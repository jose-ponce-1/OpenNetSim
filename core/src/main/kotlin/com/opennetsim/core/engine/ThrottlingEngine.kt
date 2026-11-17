package com.opennetsim.core.engine

import com.opennetsim.core.time.MonotonicClock
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
 * @param clock Reloj monotónico; debe ser el mismo que usan [bandwidthLimiter] y [latencyQueue]
 * (lo garantiza [createPipeline]).
 * @param queueWindowMs Retardo de cola máximo (además de la latencia) que admite el carril masivo.
 * @param reorderRandom Generador uniforme en [0, 1) para decidir qué paquetes se reordenan.
 */
@Suppress("TooManyFunctions")
class ThrottlingEngine(
    val bandwidthLimiter: TokenBucketLimiter,
    val latencyQueue: LatencyQueue,
    val packetLossSimulator: PacketLossSimulator,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    private val queueWindowMs: Long = DEFAULT_QUEUE_WINDOW_MS,
    private val reorderRandom: () -> Double = { kotlin.random.Random.nextDouble() }
) {
    private val interactiveQueue = LatencyQueue(clock = clock)

    /**
     * Probabilidad de que un paquete salga sin la latencia configurada (solo espera al ancho de
     * banda) y adelante así a los paquetes encolados, como `netem reorder`.
     */
    var reorderProbability: Double = 0.0
        @Synchronized get

        @Synchronized set(value) {
            field = value.coerceIn(0.0, 1.0)
        }

    /** Paquete que entra en el motor. */
    private class Arrival(
        val packet: ByteArray,
        val now: Long,
        val flowId: Long,
        val allowImmediate: Boolean,
        /** Sale sin latencia, adelantando a los encolados. */
        val reordered: Boolean
    )

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
        currentTimeMs: Long = clock.nowMs(),
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
        val reordered = reorderProbability > 0.0 && reorderRandom() < reorderProbability
        val arrival = Arrival(packet, currentTimeMs, flowId, allowImmediateRelease, reordered)
        return if (useInteractiveLane) enqueueInteractive(arrival) else enqueueBulk(arrival, demoted)
    }

    private fun enqueueInteractive(arrival: Arrival): PacketResult {
        val packet = arrival.packet
        val now = arrival.now
        val latencyReady = laneSchedule(arrival, interactiveQueue.isEmpty, lastInteractiveScheduledMs)
        return when {
            interactiveQueue.size >= MAX_INTERACTIVE_PACKETS -> drop(packet)
            arrival.allowImmediate && interactiveQueue.isEmpty && tryReleaseNow(latencyReady, now, packet) -> release(
                packet
            )
            else -> {
                if (!arrival.reordered) lastInteractiveScheduledMs = latencyReady
                interactiveQueue.enqueueWithScheduledTime(packet, latencyReady, now, arrival.flowId)
                interactiveBytes += packet.size
                estimateDeparture(latencyReady, interactiveBytes - packet.size, packet.size, now)
            }
        }
    }

    private fun enqueueBulk(arrival: Arrival, demoted: Boolean): PacketResult {
        val packet = arrival.packet
        val now = arrival.now
        val flowId = arrival.flowId
        val latencyReady = laneSchedule(arrival, latencyQueue.isEmpty, lastBulkScheduledMs)
        val lanesEmpty = latencyQueue.isEmpty && interactiveQueue.isEmpty
        return when {
            arrival.allowImmediate && lanesEmpty && tryReleaseNow(latencyReady, now, packet) -> release(packet)
            // Tail-drop por bytes de lo que se encola. Un paquete de control relegado detrás de su
            // flujo no se descarta.
            !demoted && bulkBytes + packet.size > bulkCapacityBytes -> drop(packet)
            else -> {
                if (!arrival.reordered) lastBulkScheduledMs = latencyReady
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

    /**
     * Instante en que el paquete cumple su latencia, sin adelantar al anterior del carril (FIFO). Un
     * paquete reordenado sale sin latencia y adelanta a los que esperan.
     */
    private fun laneSchedule(arrival: Arrival, laneEmpty: Boolean, lastScheduled: Long): Long {
        if (arrival.reordered) return arrival.now
        val latencyScheduled = arrival.now + latencyQueue.calculateDelayMs(arrival.now)
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
    fun drainReady(currentTimeMs: Long = clock.nowMs()): List<ByteArray> {
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
    fun timeUntilNextEventMs(currentTimeMs: Long = clock.nowMs()): Long? =
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
        updateParameters(ImpairmentParams(rateKbps, latencyMs, jitterMs, packetLossPercent))
    }

    /** Actualiza todos los parámetros de degradación de esta dirección de forma sincronizada. */
    @Synchronized
    fun updateParameters(params: ImpairmentParams) {
        bandwidthLimiter.updateRate(newBytesPerSecond = kbpsToBytesPerSecond(params.rateKbps))
        latencyQueue.updateLatencyAndJitter(
            newBaseLatencyMs = max(0L, params.latencyMs.toLong()),
            newJitterMs = max(0L, params.jitterMs.toLong())
        )
        packetLossSimulator.updateLossPercent(params.lossPercent)
        packetLossSimulator.updateBurstLength(params.lossBurstLength)
        reorderProbability = params.reorderPercent / PERCENT
    }

    /**
     * Actualiza la configuración de todos los submódulos de forma sincronizada (asumiendo downlink).
     */
    @Synchronized
    fun updateConfig(config: ThrottleConfig) {
        updateParameters(config.downlinkParams())
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
        private const val PERCENT = 100.0

        /** Tasa del token bucket en B/s, con un mínimo de 1 KB/s. */
        fun kbpsToBytesPerSecond(rateKbps: Int): Long =
            max(MIN_RATE_BYTES_PER_SECOND, rateKbps * BITS_PER_KILOBIT / BITS_PER_BYTE)

        /**
         * Crea un pipeline cuyo token bucket, cola de latencia y carril interactivo comparten [clock].
         */
        fun createPipeline(
            rateKbps: Int,
            latencyMs: Int,
            jitterMs: Int,
            packetLossPercent: Float,
            clock: MonotonicClock = MonotonicClock.SYSTEM
        ): ThrottlingEngine = createPipeline(ImpairmentParams(rateKbps, latencyMs, jitterMs, packetLossPercent), clock)

        /**
         * Crea el pipeline de una dirección con todos sus parámetros de degradación.
         */
        fun createPipeline(params: ImpairmentParams, clock: MonotonicClock = MonotonicClock.SYSTEM): ThrottlingEngine {
            val bytesPerSec = kbpsToBytesPerSecond(params.rateKbps)
            val limiter = TokenBucketLimiter(
                bytesPerSecond = bytesPerSec,
                burstCapacityBytes = TokenBucketLimiter.defaultBurstCapacity(bytesPerSec),
                clock = clock
            )
            val queue = LatencyQueue(
                baseLatencyMs = max(0L, params.latencyMs.toLong()),
                jitterMs = max(0L, params.jitterMs.toLong()),
                clock = clock
            )
            val lossSim = PacketLossSimulator(
                initialLossProbability = params.lossPercent / PERCENT,
                initialBurstLength = params.lossBurstLength
            )

            return ThrottlingEngine(
                bandwidthLimiter = limiter,
                latencyQueue = queue,
                packetLossSimulator = lossSim,
                clock = clock
            ).also { it.reorderProbability = params.reorderPercent / PERCENT }
        }

        fun fromConfig(
            config: ThrottleConfig,
            clock: MonotonicClock = MonotonicClock.SYSTEM
        ): ThrottlingEngine = createPipeline(config.downlinkParams(), clock)
    }
}
