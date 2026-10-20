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
     * El paquete fue descartado probabilísticamente por el simulador de pérdida.
     */
    data object Dropped : PacketResult

    /**
     * El paquete fue retenido en la cola de latencia para su posterior liberación.
     * @param scheduledReleaseTimeMs Marca de tiempo absoluta programada.
     * @param waitMs Tiempo de espera restante en milisegundos desde el momento de encolado.
     */
    data class Enqueued(val scheduledReleaseTimeMs: Long, val waitMs: Long) : PacketResult
}

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
 * Coordinador central del pipeline de degradación de tráfico.
 * Integra descarte estocástico, cola de latencia/jitter y control de tasa por Token Bucket.
 */
class ThrottlingEngine(
    val bandwidthLimiter: TokenBucketLimiter,
    val latencyQueue: LatencyQueue,
    val packetLossSimulator: PacketLossSimulator,
    private val timeSourceMs: () -> Long = System::currentTimeMillis
) {
    private var totalReceivedPackets: Long = 0L
    private var totalReleasedPackets: Long = 0L
    private var totalDroppedPackets: Long = 0L
    private var totalReceivedBytes: Long = 0L
    private var totalReleasedBytes: Long = 0L
    private var totalDroppedBytes: Long = 0L

    private var lastScheduledReleaseTimeMs: Long = 0L

    /**
     * Indica si la cola de retención supera el umbral de backpressure para pausar lecturas NIO.
     */
    val isBackpressured: Boolean
        @Synchronized get() = latencyQueue.size >= BACKPRESSURE_QUEUE_THRESHOLD

    /**
     * Indica si existen efectos de latencia, jitter o pérdida configurados activamente.
     */
    val hasActiveImpairments: Boolean
        @Synchronized get() = latencyQueue.baseLatencyMs > 0L || latencyQueue.jitterMs > 0L || packetLossSimulator.lossProbability > 0.0

    /**
     * Procesa un paquete entrante a través del pipeline.
     */
    @Synchronized
    fun processIncoming(packet: ByteArray, currentTimeMs: Long = timeSourceMs()): PacketResult {
        totalReceivedPackets++
        totalReceivedBytes += packet.size

        // Paso 1: Evaluación de pérdida de paquetes estocástica
        if (packetLossSimulator.shouldDrop()) {
            totalDroppedPackets++
            totalDroppedBytes += packet.size
            return PacketResult.Dropped
        }

        // Paso 2: Tail-drop backpressure si la cola de retención está saturada
        if (latencyQueue.isFull) {
            totalDroppedPackets++
            totalDroppedBytes += packet.size
            return PacketResult.Dropped
        }

        val hasConfiguredLatency = latencyQueue.baseLatencyMs > 0L || latencyQueue.jitterMs > 0L

        // Paso 3: Si la cola no está vacía o hay latencia configurada, se encola para mantener el orden temporal estricto (FIFO)
        if (!latencyQueue.isEmpty || hasConfiguredLatency) {
            val latencyScheduledMs = currentTimeMs + latencyQueue.calculateDelayMs()

            val scheduledTimeMs = if (latencyQueue.isEmpty) {
                latencyScheduledMs
            } else {
                val serializationDelayMs = max(1L, (packet.size * 1000L) / bandwidthLimiter.bytesPerSecond)
                max(latencyScheduledMs, lastScheduledReleaseTimeMs + serializationDelayMs)
            }

            lastScheduledReleaseTimeMs = scheduledTimeMs
            val wait = max(0L, scheduledTimeMs - currentTimeMs)

            val delayed = latencyQueue.enqueueWithScheduledTime(
                data = packet,
                scheduledReleaseTimeMs = scheduledTimeMs,
                originalTimestampMs = currentTimeMs
            )
            return PacketResult.Enqueued(
                scheduledReleaseTimeMs = delayed.scheduledReleaseTimeMs,
                waitMs = wait
            )
        }

        // Paso 4: Sin latencia configurada ni elementos encolados: comprobar límite de ancho de banda inmediato
        if (bandwidthLimiter.tryConsume(packet.size)) {
            totalReleasedPackets++
            totalReleasedBytes += packet.size
            lastScheduledReleaseTimeMs = currentTimeMs
            return PacketResult.Released(packet)
        } else {
            // Tokens agotados: encolar con el retardo requerido para que el bucket se recargue
            val waitMs = max(1L, bandwidthLimiter.calculateWaitTimeMs(packet.size))
            val scheduledTimeMs = currentTimeMs + waitMs
            lastScheduledReleaseTimeMs = scheduledTimeMs

            val delayed = latencyQueue.enqueueWithScheduledTime(
                data = packet,
                scheduledReleaseTimeMs = scheduledTimeMs,
                originalTimestampMs = currentTimeMs
            )
            return PacketResult.Enqueued(
                scheduledReleaseTimeMs = delayed.scheduledReleaseTimeMs,
                waitMs = waitMs
            )
        }
    }

    /**
     * Drena y devuelve todos los paquetes retenidos que ya cumplieron su retardo programado
     * y disponen de tokens suficientes para su transmisión.
     */
    @Synchronized
    fun drainReady(currentTimeMs: Long = timeSourceMs()): List<ByteArray> {
        val readyList = mutableListOf<ByteArray>()

        while (true) {
            val candidate = latencyQueue.peekReady(currentTimeMs) ?: break

            // Verificar si el limitador de caudal permite emitir este paquete ahora
            if (bandwidthLimiter.tryConsume(candidate.data.size)) {
                latencyQueue.pollReady(currentTimeMs)
                totalReleasedPackets++
                totalReleasedBytes += candidate.data.size
                readyList.add(candidate.data)
            } else {
                // Tokens insuficientes para el siguiente paquete: se detiene el drenaje
                break
            }
        }

        return readyList
    }

    /**
     * Devuelve el tiempo en milisegundos hasta el próximo evento programado (liberación por tiempo
     * o recarga de tokens para el primer paquete retenido), o null si la cola está vacía.
     */
    @Synchronized
    fun timeUntilNextEventMs(currentTimeMs: Long = timeSourceMs()): Long? {
        val candidate = latencyQueue.peekReady(currentTimeMs)
        if (candidate != null) {
            // El paquete ya cumplió su latencia, pero puede estar esperando tokens de ancho de banda
            val waitNanos = bandwidthLimiter.calculateWaitTimeNanos(candidate.data.size)
            return if (waitNanos == 0L) 0L else (waitNanos + 999_999L) / 1_000_000L
        }

        return latencyQueue.timeUntilNextReadyMs(currentTimeMs)
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
        val bytesPerSecond = (rateKbps * 1000L) / 8L
        bandwidthLimiter.updateRate(newBytesPerSecond = max(1024L, bytesPerSecond))
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
        bandwidthLimiter.reset()
        lastScheduledReleaseTimeMs = 0L
    }

    companion object {
        private const val BACKPRESSURE_QUEUE_THRESHOLD = 8

        fun createPipeline(
            rateKbps: Int,
            latencyMs: Int,
            jitterMs: Int,
            packetLossPercent: Float,
            timeSourceMs: () -> Long = System::currentTimeMillis
        ): ThrottlingEngine {
            val bytesPerSec = max(1024L, (rateKbps * 1000L) / 8L)
            val limiter = TokenBucketLimiter(
                bytesPerSecond = bytesPerSec,
                burstCapacityBytes = bytesPerSec,
                timeSourceNanos = { timeSourceMs() * 1_000_000L }
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

