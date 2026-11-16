package com.opennetsim.core.engine

import com.opennetsim.core.time.MonotonicClock
import com.opennetsim.model.ThrottleConfig

/**
 * Orquestador simétrico que coordina dos pipelines independientes de degradación de red:
 * - [uplinkEngine]: Limita el tráfico saliente desde el TUN hacia el exterior con [ThrottleConfig.upKbps].
 * - [downlinkEngine]: Limita el tráfico entrante desde el exterior hacia el TUN con [ThrottleConfig.downKbps].
 *
 * La latencia y jitter se distribuyen equitativamente (RTT / 2 en subida y RTT / 2 en bajada)
 * de modo que el retardo acumulado percibido en peticiones y pings refleje el valor configurado.
 *
 * La pérdida se aplica de forma independiente en cada dirección: un intercambio de ida y vuelta
 * (p. ej. un ping) se pierde con probabilidad `1 − (1 − p)²` (ver [ThrottleConfig.roundTripLossPercent]).
 */
class BidirectionalThrottlingEngine(
    val uplinkEngine: ThrottlingEngine,
    val downlinkEngine: ThrottlingEngine,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM
) {
    /**
     * Procesa un paquete saliente (Uplink: TUN -> exterior).
     */
    fun processUplink(
        packet: ByteArray,
        currentTimeMs: Long = clock.nowMs(),
        trafficClass: TrafficClass = TrafficClass.BULK,
        flowId: Long = DelayedPacket.NO_FLOW
    ): PacketResult = uplinkEngine.processIncoming(packet, currentTimeMs, trafficClass, flowId)

    /**
     * Procesa un paquete entrante (Downlink: exterior -> TUN).
     */
    fun processDownlink(
        packet: ByteArray,
        currentTimeMs: Long = clock.nowMs(),
        trafficClass: TrafficClass = TrafficClass.BULK,
        flowId: Long = DelayedPacket.NO_FLOW
    ): PacketResult = downlinkEngine.processIncoming(packet, currentTimeMs, trafficClass, flowId)

    /**
     * True mientras la bajada admite más datos masivos (por debajo del 75 % de su cota): los
     * forwarders pueden seguir leyendo de los sockets exteriores.
     */
    val downlinkHasCapacity: Boolean get() = downlinkEngine.hasCapacity

    /**
     * Drena paquetes listos para ser enviados al exterior (Uplink).
     */
    fun drainUplinkReady(currentTimeMs: Long = clock.nowMs()): List<ByteArray> {
        return uplinkEngine.drainReady(currentTimeMs)
    }

    /**
     * Drena paquetes listos para ser inyectados al TUN (Downlink).
     */
    fun drainDownlinkReady(currentTimeMs: Long = clock.nowMs()): List<ByteArray> {
        return downlinkEngine.drainReady(currentTimeMs)
    }

    /**
     * Devuelve el tiempo en milisegundos hasta el próximo evento en Uplink, o null si está vacía.
     */
    fun timeUntilNextUplinkEventMs(currentTimeMs: Long = clock.nowMs()): Long? {
        return uplinkEngine.timeUntilNextEventMs(currentTimeMs)
    }

    /**
     * Devuelve el tiempo en milisegundos hasta el próximo evento en Downlink, o null si está vacía.
     */
    fun timeUntilNextDownlinkEventMs(currentTimeMs: Long = clock.nowMs()): Long? {
        return downlinkEngine.timeUntilNextEventMs(currentTimeMs)
    }

    /**
     * Actualiza la configuración de ambos pipelines de forma sincronizada.
     */
    @Synchronized
    fun updateConfig(config: ThrottleConfig) {
        val halfLatency = config.latencyMs / 2
        val halfJitter = config.jitterMs / 2

        uplinkEngine.updateParameters(
            rateKbps = config.upKbps,
            latencyMs = halfLatency,
            jitterMs = halfJitter,
            packetLossPercent = config.packetLossPercent
        )

        downlinkEngine.updateParameters(
            rateKbps = config.downKbps,
            latencyMs = halfLatency,
            jitterMs = halfJitter,
            packetLossPercent = config.packetLossPercent
        )
    }

    /**
     * Devuelve métricas consolidadas combinando subida y bajada.
     */
    @Synchronized
    fun getCombinedMetrics(): EngineMetrics {
        val up = uplinkEngine.getMetrics()
        val down = downlinkEngine.getMetrics()

        return EngineMetrics(
            totalReceivedPackets = up.totalReceivedPackets + down.totalReceivedPackets,
            totalReleasedPackets = up.totalReleasedPackets + down.totalReleasedPackets,
            totalDroppedPackets = up.totalDroppedPackets + down.totalDroppedPackets,
            totalReceivedBytes = up.totalReceivedBytes + down.totalReceivedBytes,
            totalReleasedBytes = up.totalReleasedBytes + down.totalReleasedBytes,
            totalDroppedBytes = up.totalDroppedBytes + down.totalDroppedBytes
        )
    }

    /**
     * Limpia colas y restablece buckets de ambos pipelines.
     */
    @Synchronized
    fun clear() {
        uplinkEngine.clear()
        downlinkEngine.clear()
    }

    companion object {
        /**
         * @param clock Reloj monotónico único para colas y token buckets de ambas direcciones.
         */
        fun fromConfig(
            config: ThrottleConfig,
            clock: MonotonicClock = MonotonicClock.SYSTEM
        ): BidirectionalThrottlingEngine {
            val halfLatency = config.latencyMs / 2
            val halfJitter = config.jitterMs / 2

            val uplink = ThrottlingEngine.createPipeline(
                rateKbps = config.upKbps,
                latencyMs = halfLatency,
                jitterMs = halfJitter,
                packetLossPercent = config.packetLossPercent,
                clock = clock
            )

            val downlink = ThrottlingEngine.createPipeline(
                rateKbps = config.downKbps,
                latencyMs = halfLatency,
                jitterMs = halfJitter,
                packetLossPercent = config.packetLossPercent,
                clock = clock
            )

            return BidirectionalThrottlingEngine(
                uplinkEngine = uplink,
                downlinkEngine = downlink,
                clock = clock
            )
        }
    }
}
