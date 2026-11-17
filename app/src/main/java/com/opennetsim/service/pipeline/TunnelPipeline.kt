package com.opennetsim.service.pipeline

import com.opennetsim.core.engine.BidirectionalThrottlingEngine
import com.opennetsim.core.engine.DelayedPacket
import com.opennetsim.core.engine.PacketResult
import com.opennetsim.core.engine.ThrottlingEngine
import com.opennetsim.core.engine.TrafficClass
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.packet.PacketMeta
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.TelemetrySampler
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.TrafficCounters
import com.opennetsim.service.diag.error
import com.opennetsim.service.diag.warn
import com.opennetsim.service.forwarder.ForwarderEnvironment
import com.opennetsim.service.forwarder.IcmpEchoTransport
import com.opennetsim.service.forwarder.IcmpForwarder
import com.opennetsim.service.forwarder.TcpForwarder
import com.opennetsim.service.forwarder.UdpForwarder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline de un túnel activo: TUN → motor de subida → forwarders → Internet → motor de bajada → TUN.
 *
 * El ping también va a la red real ([IcmpForwarder] sobre [icmpTransport]): su RTT es el real más la
 * degradación configurada.
 *
 * No depende de Android: el servicio le entrega un [TunDevice] ya establecido y un
 * [ForwarderEnvironment] (protector de sockets, ámbito, reloj y diagnóstico), y recibe los fallos
 * no recuperables y la telemetría por [Listener]. Así se prueba en la JVM con un TUN en memoria.
 *
 * Bucles (todos en el ámbito propio del pipeline, que [close] cancela):
 * - lector del TUN: solo encola en el motor de subida; nunca espera ni despacha.
 * - drenaje de subida: único punto de despacho a los forwarders, en el orden del motor.
 * - drenaje de bajada: único escritor de datos hacia el TUN.
 * - telemetría: una muestra por segundo.
 */
@Suppress("TooManyFunctions") // Un método por bucle y por paso del camino de un paquete.
class TunnelPipeline(
    private val tun: TunDevice,
    initialConfig: ThrottleConfig,
    environment: ForwarderEnvironment,
    private val listener: Listener,
    private val mtu: Int = DEFAULT_MTU,
    icmpTransport: IcmpEchoTransport = IcmpEchoTransport.UNAVAILABLE
) {
    /** Avisos del pipeline al servicio. Se invocan desde hilos de I/O. */
    interface Listener {
        /** Fallo no recuperable: el pipeline ya no reenvía tráfico. Se invoca como mucho una vez. */
        fun onFatalError(message: String)

        fun onTelemetry(telemetry: NetworkTelemetry)
    }

    private val diagnostics = environment.diagnostics
    private val clock = environment.clock
    private val bufferPool = environment.bufferPool

    private val job = SupervisorJob(environment.coroutineScope.coroutineContext[Job])
    private val scope = CoroutineScope(
        environment.coroutineScope.coroutineContext + job + CoroutineExceptionHandler { _, throwable ->
            fail("pipeline.coroutine", "Excepción no capturada en una corrutina del túnel", throwable)
        }
    )
    private val forwarderEnvironment = environment.copy(coroutineScope = scope)

    private val failed = AtomicBoolean(false)

    @Volatile
    private var closed = false
    private var started = false
    private val tunWriteLock = Any()

    private val engine = BidirectionalThrottlingEngine.fromConfig(initialConfig, clock)

    /** Despierta al drenaje de subida cuando el lector del TUN encola un paquete. */
    private val uplinkWake = Channel<Unit>(Channel.CONFLATED)

    /** Despierta al drenaje de bajada cuando un forwarder encola un paquete. */
    private val downlinkWake = Channel<Unit>(Channel.CONFLATED)

    private val toDownlink: (ByteArray) -> Unit = { packet -> enqueue(packet, engine.downlinkEngine, downlinkWake) }
    private val icmpForwarder = IcmpForwarder(
        environment = forwarderEnvironment,
        transport = icmpTransport,
        onInboundPacket = toDownlink
    )
    private val udpForwarder = UdpForwarder(environment = forwarderEnvironment, onInboundPacket = toDownlink)
    private val tcpForwarder = TcpForwarder(
        environment = forwarderEnvironment,
        onInboundPacket = toDownlink,
        canReadDownlink = { engine.downlinkHasCapacity },
        onControlPacket = toDownlink
    ).apply { updateDegradation(initialConfig.downKbps, initialConfig.latencyMs, initialConfig.jitterMs) }

    /** Arranca los bucles. Solo puede llamarse una vez. */
    fun start() {
        check(!started) { "El pipeline ya está arrancado" }
        check(!closed) { "El pipeline está cerrado" }
        started = true
        launchTunReader()
        launchUplinkDrain()
        launchDownlinkDrain()
        launchTelemetry()
    }

    /** Aplica una configuración nueva sin reiniciar el pipeline. */
    fun updateConfig(config: ThrottleConfig) {
        engine.updateConfig(config)
        tcpForwarder.updateDegradation(config.downKbps, config.latencyMs, config.jitterMs)
    }

    /** Detiene los bucles, cierra los forwarders y el TUN y vacía el motor. Idempotente. */
    fun close() {
        if (closed) return
        closed = true
        job.cancel()
        udpForwarder.close()
        tcpForwarder.close()
        icmpForwarder.close()
        synchronized(tunWriteLock) { tun.close() }
        engine.clear()
    }

    // ------------------------------------------------------------------ Bucles

    @Suppress("LoopWithTooManyJumpStatements") // Fin del bucle por error o EOF; lectura vacía.
    private fun launchTunReader() = scope.launch(Dispatchers.IO) {
        val buffer = bufferPool.acquire()
        val rawArray = buffer.array()
        try {
            while (isActive) {
                val bytesRead = try {
                    tun.read(rawArray)
                } catch (e: IOException) {
                    if (isActive) fail("tun.read", "Error leyendo el descriptor del TUN", e)
                    break
                }
                if (bytesRead < 0) {
                    if (isActive) fail("tun.read", "Fin de flujo inesperado en el TUN", null)
                    break
                }
                if (bytesRead == 0) continue
                guardPacket("uplink.read") {
                    enqueue(rawArray.copyOf(bytesRead), engine.uplinkEngine, uplinkWake)
                }
            }
        } finally {
            bufferPool.release(buffer)
        }
    }

    private fun launchUplinkDrain() = scope.launch {
        while (isActive) {
            var readyPackets: List<ByteArray> = emptyList()
            guardPacket("uplink.drain") { readyPackets = engine.drainUplinkReady() }
            for (packet in readyPackets) {
                guardPacket("uplink.dispatch") { dispatchOutbound(packet) }
            }
            awaitNextEvent(engine.timeUntilNextUplinkEventMs(), readyPackets.isNotEmpty(), uplinkWake)
        }
    }

    private fun launchDownlinkDrain() = scope.launch {
        var hadCapacity = engine.downlinkHasCapacity
        while (isActive) {
            var readyPackets: List<ByteArray> = emptyList()
            guardPacket("downlink.drain") { readyPackets = engine.drainDownlinkReady() }
            for (packet in readyPackets) {
                guardPacket("downlink.write") { writeToTun(packet) }
            }
            // Señal de capacidad: reanudar lecturas TCP pausadas en cuanto la bajada tiene hueco.
            val hasCapacity = engine.downlinkHasCapacity
            if (hasCapacity && !hadCapacity) tcpForwarder.resumeReads()
            hadCapacity = hasCapacity
            awaitNextEvent(engine.timeUntilNextDownlinkEventMs(), readyPackets.isNotEmpty(), downlinkWake)
        }
    }

    private fun launchTelemetry() = scope.launch {
        val sampler = TelemetrySampler(startMs = clock.nowMs())
        listener.onTelemetry(NetworkTelemetry(isConnected = true))
        while (isActive) {
            delay(TELEMETRY_INTERVAL_MS)
            val down = engine.downlinkEngine.getMetrics()
            val up = engine.uplinkEngine.getMetrics()
            val counters = TrafficCounters(
                downReleasedBytes = down.totalReleasedBytes,
                upReleasedBytes = up.totalReleasedBytes,
                retransmittedBytes = tcpForwarder.retransmittedBytes(),
                droppedPackets = down.totalDroppedPackets + up.totalDroppedPackets
            )
            listener.onTelemetry(sampler.sample(counters, clock.nowMs()))
        }
    }

    // ------------------------------------------------------------------ Paquetes

    /**
     * Encola un paquete en una dirección. Nunca se libera al instante: lo saca el bucle de drenaje
     * de esa dirección, en el orden del motor, para no reordenar paquetes de un mismo flujo.
     */
    private fun enqueue(packet: ByteArray, direction: ThrottlingEngine, wake: Channel<Unit>) {
        val meta = PacketMeta.parse(packet)
        val result = direction.processIncoming(
            packet = packet,
            trafficClass = classify(meta),
            flowId = meta?.flowId ?: DelayedPacket.NO_FLOW,
            allowImmediateRelease = false
        )
        if (result is PacketResult.Enqueued) wake.trySend(Unit)
    }

    private fun dispatchOutbound(packet: ByteArray) {
        when (packet.firstOrNull()?.let { (it.toInt() and VERSION_MASK) ushr VERSION_SHIFT }) {
            IP_V4 -> IpPacket.parse(packet)?.let(::dispatchIpv4)
            IP_V6 -> Ipv6Packet.parse(packet)?.let(::dispatchIpv6)
        }
    }

    private fun dispatchIpv4(ipPacket: IpPacket) {
        when (ipPacket.protocol) {
            IpPacket.PROTOCOL_UDP -> udpForwarder.processOutbound(ipPacket)
            IpPacket.PROTOCOL_TCP -> tcpForwarder.processOutbound(ipPacket)
            IpPacket.PROTOCOL_ICMP -> icmpForwarder.processOutboundIpv4(ipPacket)
        }
    }

    private fun dispatchIpv6(ipv6Packet: Ipv6Packet) {
        when (ipv6Packet.nextHeader) {
            Ipv6Packet.PROTOCOL_UDP -> udpForwarder.processOutboundIpv6(ipv6Packet)
            Ipv6Packet.PROTOCOL_TCP -> tcpForwarder.processOutboundIpv6(ipv6Packet)
            Ipv6Packet.PROTOCOL_ICMPV6 -> icmpForwarder.processOutboundIpv6(ipv6Packet)
        }
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            if (closed) return
            try {
                tun.write(packet)
            } catch (e: IOException) {
                if (packet.size > mtu) {
                    // Excepción a la regla de fallo fatal (design D12): un paquete entero mayor que el
                    // MTU que el kernel rechaza solo se descarta; para la app equivale a una pérdida.
                    diagnostics.warn(
                        "tun.write.oversize",
                        "El TUN rechazó un paquete de ${packet.size} B; se descarta",
                        e
                    )
                } else {
                    fail("tun.write", "Error escribiendo en el descriptor del TUN", e)
                }
            }
        }
    }

    /**
     * Ejecuta [block] para un único paquete: un error inesperado descarta ese paquete y se registra,
     * sin detener el bucle que lo invoca. La cancelación de la corrutina se propaga.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guardPacket(key: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.error(key, "Paquete descartado por un error inesperado", e)
        }
    }

    /** Espera hasta el próximo evento del motor o hasta que se encole un paquete nuevo ([wake]). */
    private suspend fun awaitNextEvent(nextEventMs: Long?, releasedPackets: Boolean, wake: Channel<Unit>) {
        val waitMs = when {
            nextEventMs == null -> IDLE_WAIT_MS
            nextEventMs <= 0L -> if (releasedPackets) 0L else 1L
            else -> nextEventMs.coerceIn(1L, IDLE_WAIT_MS)
        }
        if (waitMs == 0L) {
            yield()
        } else {
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    /** Registra un fallo no recuperable y lo notifica una sola vez, salvo que el pipeline ya esté cerrado. */
    private fun fail(key: String, message: String, error: Throwable?) {
        if (closed || !failed.compareAndSet(false, true)) return
        diagnostics.error(key, message, error)
        listener.onFatalError(message)
    }

    companion object {
        const val DEFAULT_MTU = 1500
        private const val IDLE_WAIT_MS = 250L
        private const val TELEMETRY_INTERVAL_MS = 1_000L
        private const val VERSION_MASK = 0xF0
        private const val VERSION_SHIFT = 4
        private const val IP_V4 = 4
        private const val IP_V6 = 6

        /**
         * Interactivo: segmentos TCP sin datos (ACK/SYN/FIN/RST), ICMP y DNS. El motor mantiene el
         * orden por flujo, así que un FIN nunca adelanta a datos de su propia conexión.
         */
        internal fun classify(meta: PacketMeta?): TrafficClass = when {
            meta == null -> TrafficClass.BULK
            meta.isIcmp || meta.isTcpWithoutPayload || meta.isDns -> TrafficClass.INTERACTIVE
            else -> TrafficClass.BULK
        }
    }
}
