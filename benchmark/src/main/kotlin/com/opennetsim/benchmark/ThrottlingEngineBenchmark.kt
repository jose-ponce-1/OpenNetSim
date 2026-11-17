package com.opennetsim.benchmark

import com.opennetsim.core.engine.ImpairmentParams
import com.opennetsim.core.engine.ThrottlingEngine
import com.opennetsim.core.engine.TrafficClass
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.PacketMeta
import com.opennetsim.core.packet.TcpPacket
import com.opennetsim.core.time.MonotonicClock
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Coste del motor por paquete: encolar como hace `TunnelPipeline` (nunca salida inmediata) y drenar.
 *
 * El reloj es simulado y avanza 1 ms por operación, con una tasa de 1 Gbps: siempre hay tokens, así que
 * se mide el motor y no la espera del ancho de banda. Con latencia, la cola llega a un estado estable
 * de unos [latencyMs] paquetes encolados y cada operación drena el que entró hace [latencyMs] ms.
 */
@State(Scope.Benchmark)
class ThrottlingEngineBenchmark {
    @Param("0", "50")
    var latencyMs: Int = 0

    private var nowMs = 0L
    private lateinit var engine: ThrottlingEngine
    private lateinit var packet: ByteArray
    private var flowId = 0L

    @Setup
    fun setUp() {
        nowMs = 0L
        val clock = MonotonicClock.ofMillis { nowMs }
        engine = ThrottlingEngine.createPipeline(
            ImpairmentParams(rateKbps = RATE_KBPS, latencyMs = latencyMs, jitterMs = latencyMs / JITTER_DIVISOR),
            clock
        )
        packet = TcpPacket.buildPshAck(CLIENT_PORT, SERVER_PORT, 1L, 1L, ByteArray(PAYLOAD_BYTES))
            .toIpPacket(IpPacket.parseIp("10.0.0.2"), IpPacket.parseIp("93.184.216.34"))
            .toByteArray()
        flowId = PacketMeta.parse(packet)?.flowId ?: 0L
    }

    @Benchmark
    fun enqueueAndDrain(): Int {
        nowMs++
        engine.processIncoming(
            packet = packet,
            currentTimeMs = nowMs,
            trafficClass = TrafficClass.BULK,
            flowId = flowId,
            allowImmediateRelease = false
        )
        return engine.drainReady(nowMs).size
    }

    private companion object {
        const val RATE_KBPS = 1_000_000
        const val JITTER_DIVISOR = 5
        const val PAYLOAD_BYTES = 1460
        const val CLIENT_PORT = 52_100
        const val SERVER_PORT = 443
    }
}
