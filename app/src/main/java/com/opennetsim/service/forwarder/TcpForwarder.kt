package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.packet.TcpPacket
import com.opennetsim.core.tcp.ClientSegmentResult
import com.opennetsim.core.tcp.SegmentKind
import com.opennetsim.core.tcp.TcpConnection
import com.opennetsim.core.tcp.TcpConnectionConfig
import com.opennetsim.core.tcp.TcpConnectionOutput
import com.opennetsim.core.tcp.TcpWindowPolicy
import com.opennetsim.service.diag.DiagLevel
import com.opennetsim.service.diag.error
import com.opennetsim.service.diag.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Reenvía conexiones TCP en espacio de usuario desde la interfaz TUN hacia la red exterior
 * utilizando Java NIO ([Selector], [SocketChannel]) protegidos con [VpnSocketProtector].
 *
 * Es solo la capa de I/O: la lógica TCP (handshake, reensamblado, retransmisión, ventanas, cierre)
 * vive en [TcpConnection]. Modelo actor: todo el estado se toca en el hilo del selector.
 *
 * @param onInboundPacket recibe segmentos de datos y FIN (deben respetar el orden del flujo).
 * @param canReadDownlink false cuando el pipeline de bajada no admite más datos.
 * @param onControlPacket recibe ACKs, SYN-ACK, RST y sondas.
 */
@Suppress("TooManyFunctions")
class TcpForwarder(
    private val environment: ForwarderEnvironment,
    private val onInboundPacket: (ByteArray) -> Unit,
    private val canReadDownlink: () -> Boolean = { true },
    private val onControlPacket: (ByteArray) -> Unit = onInboundPacket,
    initialTcpConfig: TcpConnectionConfig = TcpConnectionConfig()
) {
    private data class SessionKey(
        val clientIp: String,
        val clientPort: Int,
        val serverIp: String,
        val serverPort: Int,
        val isIpv6: Boolean
    )

    /** Sesión: socket exterior + [TcpConnection]. También es la salida de efectos de la conexión. */
    private inner class Session(
        val key: SessionKey,
        val channel: SocketChannel,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray
    ) : TcpConnectionOutput {
        lateinit var connection: TcpConnection
        var selectionKey: SelectionKey? = null
        val pendingWrites = ArrayDeque<ByteBuffer>()
        var pendingWriteBytes = 0
        var readsPaused = false
        var serverEof = false
        var shutdownAfterFlush = false
        var ioFailure: IOException? = null
        var removed = false

        override fun sendToClient(segment: TcpPacket, kind: SegmentKind, isRetransmission: Boolean) {
            val packet = if (key.isIpv6) {
                segment.toIpv6Packet(serverIpBytes, clientIpBytes).toByteArray()
            } else {
                segment.toIpPacket(serverIpBytes, clientIpBytes).toByteArray()
            }
            // Paquetes completos, como los cuenta el motor, para restarlos del caudal de bajada.
            if (isRetransmission) retransmittedBytesCounter.addAndGet(packet.size.toLong())
            if (kind == SegmentKind.CONTROL) onControlPacket(packet) else onInboundPacket(packet)
        }

        override fun writeToServer(data: ByteArray): Int {
            val buffer = ByteBuffer.wrap(data)
            try {
                if (pendingWrites.isEmpty() && channel.isConnected) channel.write(buffer)
            } catch (e: IOException) {
                // Se aborta después del evento: abortar aquí reentraría en TcpConnection.
                ioFailure = e
                return pendingWriteBytes
            }
            if (buffer.hasRemaining()) {
                pendingWrites.addLast(buffer)
                pendingWriteBytes += buffer.remaining()
                peakPendingWriteBytesValue = maxOf(peakPendingWriteBytesValue, pendingWriteBytes)
                setInterest(SelectionKey.OP_WRITE, enabled = true)
            }
            return pendingWriteBytes
        }

        override fun shutdownServerOutput() {
            if (pendingWrites.isEmpty()) shutdownChannelOutput(this) else shutdownAfterFlush = true
        }

        override fun closeConnection() {
            removeSession(this)
        }

        fun setInterest(op: Int, enabled: Boolean) {
            taskQueue.checkOwnerThread()
            val selKey = selectionKey?.takeIf { it.isValid } ?: return
            val current = selKey.interestOps()
            val updated = if (enabled) current or op else current and op.inv()
            if (updated != current) selKey.interestOps(updated)
        }
    }

    private val diagnostics = environment.diagnostics
    private val bufferPool = environment.bufferPool

    // Solo el hilo del selector escribe; ConcurrentHashMap permite que close() itere con seguridad.
    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val selector: Selector = Selector.open()
    private val taskQueue = SelectorTaskQueue(selector, environment.strictThreadChecks)
    private var tcpConfig = initialTcpConfig

    private val retransmittedBytesCounter = AtomicLong(0L)
    private var peakPendingWriteBytesValue = 0

    /** Iteraciones del bucle del selector (para tests de consumo de CPU). */
    internal val loopIterations = AtomicLong(0L)

    /** Máximo de bytes pendientes de escribir observado en una sesión (para tests). */
    internal val peakPendingWriteBytes: Int get() = peakPendingWriteBytesValue

    private var workerJob: Job? = null

    @Volatile
    private var isRunning = true

    init {
        startWorkerLoop()
    }

    /** Bytes (paquetes IP completos) retransmitidos hacia el cliente desde el arranque. */
    fun retransmittedBytes(): Long = retransmittedBytesCounter.get()

    /**
     * Ajusta el tope de bytes en vuelo y el RTO inicial a la degradación configurada.
     */
    fun updateDegradation(downKbps: Int, latencyMs: Int, jitterMs: Int) {
        val maxInFlight = TcpWindowPolicy.bdpCapBytes(downKbps, latencyMs, jitterMs)
        val initialRto = TcpWindowPolicy.initialRtoMs(latencyMs, jitterMs)
        taskQueue.post {
            tcpConfig = tcpConfig.copy(maxInFlightBytes = maxInFlight, initialRtoMs = initialRto)
            sessions.values.forEach { it.connection.maxInFlightBytes = maxInFlight }
        }
    }

    /** El pipeline de bajada vuelve a admitir datos: reanudar lecturas pausadas. */
    fun resumeReads() {
        taskQueue.post { sessions.values.forEach { resumeReadsIfPossible(it) } }
    }

    /**
     * Procesa un segmento TCP saliente proveniente de un paquete IPv4 en la interfaz TUN.
     */
    fun processOutbound(ipPacket: IpPacket) {
        enqueueOutbound(ipPacket.sourceIp, ipPacket.destinationIp, ipPacket.sourceIpString, ipPacket.destinationIpString, ipPacket.payload, false)
    }

    /**
     * Procesa un segmento TCP saliente proveniente de un paquete IPv6 en la interfaz TUN.
     */
    fun processOutboundIpv6(ipv6Packet: Ipv6Packet) {
        enqueueOutbound(
            ipv6Packet.sourceIp,
            ipv6Packet.destinationIp,
            ipv6Packet.sourceIpString,
            ipv6Packet.destinationIpString,
            ipv6Packet.payload,
            true
        )
    }

    @Suppress("LongParameterList")
    private fun enqueueOutbound(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcIpString: String,
        dstIpString: String,
        payload: ByteArray,
        isIpv6: Boolean
    ) {
        if (!isRunning) return
        val tcp = TcpPacket.parse(payload) ?: return
        val key = SessionKey(srcIpString, tcp.sourcePort, dstIpString, tcp.destinationPort, isIpv6)
        // Modelo actor: el estado de la sesión solo se toca en el hilo del selector.
        taskQueue.post { handleClientSegment(key, srcIpBytes, dstIpBytes, tcp) }
    }

    // ------------------------------------------------------------------ Segmentos del cliente

    private fun handleClientSegment(key: SessionKey, clientIp: ByteArray, serverIp: ByteArray, tcp: TcpPacket) {
        taskQueue.checkOwnerThread()
        val now = System.currentTimeMillis()
        val session = sessions[key]
        if (session == null) {
            if (tcp.isSyn && !tcp.isAck) {
                openSession(key, clientIp, serverIp, tcp, now)
            } else {
                // Segmento de una sesión que ya no existe (p. ej. conexión del pool expirada): RST.
                TcpPacket.buildRstForSegment(tcp)?.let { sendDetached(key, clientIp, serverIp, it) }
            }
            return
        }

        if (session.connection.onClientSegment(tcp, now) == ClientSegmentResult.NEW_CONNECTION_REQUESTED) {
            // El cliente abandonó la conexión anterior con la misma 4-tupla y abre otra.
            removeSession(session)
            openSession(key, clientIp, serverIp, tcp, now)
            return
        }
        afterEvent(session)
    }

    private fun openSession(key: SessionKey, clientIp: ByteArray, serverIp: ByteArray, syn: TcpPacket, now: Long) {
        val channel = try {
            SocketChannel.open().apply { configureBlocking(false) }
        } catch (e: IOException) {
            diagnostics.warn("tcp.open", "No se pudo crear el socket TCP hacia ${describe(key)}", e)
            TcpPacket.buildRstForSegment(syn)?.let { sendDetached(key, clientIp, serverIp, it) }
            return
        }
        if (!environment.socketProtector.protect(channel.socket())) {
            diagnostics.warn("tcp.protect", "protect() rechazó el socket TCP hacia ${describe(key)}")
            closeChannelQuietly(channel)
            TcpPacket.buildRstForSegment(syn)?.let { sendDetached(key, clientIp, serverIp, it) }
            return
        }

        val session = Session(key, channel, clientIp.copyOf(), serverIp.copyOf())
        session.connection = TcpConnection(
            clientSyn = syn,
            isIpv6 = key.isIpv6,
            serverIsn = Random.nextLong(0L, ISN_SPACE),
            config = tcpConfig,
            output = session,
            nowMs = now
        )
        sessions[key] = session

        try {
            val connected = channel.connect(InetSocketAddress(InetAddress.getByAddress(serverIp), key.serverPort))
            val interest = if (connected) SelectionKey.OP_READ else SelectionKey.OP_CONNECT
            session.selectionKey = channel.register(selector, interest, session)
            if (connected) session.connection.onUpstreamConnected(now)
        } catch (e: IOException) {
            diagnostics.warn("tcp.connect", "Conexión rechazada hacia ${describe(key)}: ${e.message}", e)
            session.connection.abort()
        }
    }

    /** Envía un segmento de control que no pertenece a ninguna sesión (RST de respuesta). */
    private fun sendDetached(key: SessionKey, clientIp: ByteArray, serverIp: ByteArray, segment: TcpPacket) {
        val packet = if (key.isIpv6) {
            segment.toIpv6Packet(serverIp, clientIp).toByteArray()
        } else {
            segment.toIpPacket(serverIp, clientIp).toByteArray()
        }
        onControlPacket(packet)
    }

    /** Tras cada evento: aplicar fallos de I/O diferidos y reanudar lecturas si hay ventana. */
    private fun afterEvent(session: Session) {
        if (session.removed) return
        val failure = session.ioFailure
        if (failure != null) {
            diagnostics.warn("tcp.write", "Fallo al escribir hacia ${describe(session.key)}: ${failure.message}", failure)
            session.connection.abort()
            return
        }
        resumeReadsIfPossible(session)
    }

    // ------------------------------------------------------------------ Bucle del selector

    @Suppress("TooGenericExceptionCaught")
    private fun startWorkerLoop() {
        workerJob = environment.coroutineScope.launch(Dispatchers.IO) {
            taskQueue.bindToCurrentThread()
            while (isActive && isRunning) {
                try {
                    loopIterations.incrementAndGet()
                    taskQueue.runPending()
                    if (selector.select(selectTimeoutMs()) > 0) {
                        handleSelectedKeys()
                    }
                    runTimers()
                } catch (e: ClosedSelectorException) {
                    break
                } catch (e: IOException) {
                    diagnostics.warn("tcp.selector", "Error de I/O en el selector TCP", e)
                } catch (e: RuntimeException) {
                    // Un error con un segmento concreto no debe detener el reenvío TCP.
                    diagnostics.error("tcp.loop", "Error inesperado en el bucle TCP", e)
                }
            }
        }
    }

    private fun handleSelectedKeys() {
        val keys = selector.selectedKeys().iterator()
        while (keys.hasNext()) {
            val selKey = keys.next()
            keys.remove()
            val session = selKey.attachment() as? Session ?: continue
            if (selKey.isValid && selKey.isConnectable) handleConnect(session)
            if (selKey.isValid && selKey.isWritable) handleWrite(session)
            if (selKey.isValid && selKey.isReadable) handleRead(session)
            afterEvent(session)
        }
    }

    /**
     * Espera del selector: hasta el próximo temporizador de cualquier sesión, acotada a [5, 250] ms.
     * Si hay lecturas pausadas por la bajada se sondea cada 5 ms hasta que llegue [resumeReads].
     */
    private fun selectTimeoutMs(): Long {
        val now = System.currentTimeMillis()
        var timeout = MAX_SELECT_TIMEOUT_MS
        for (session in sessions.values) {
            if (session.readsPaused && !canReadDownlink()) timeout = MIN_SELECT_TIMEOUT_MS
            val deadline = session.connection.nextDeadlineMs() ?: continue
            timeout = minOf(timeout, deadline - now)
        }
        return timeout.coerceIn(MIN_SELECT_TIMEOUT_MS, MAX_SELECT_TIMEOUT_MS)
    }

    private fun runTimers() {
        val now = System.currentTimeMillis()
        for (session in sessions.values) {
            val deadline = session.connection.nextDeadlineMs() ?: continue
            if (now >= deadline) session.connection.onTimer(now)
            if (session.readsPaused) resumeReadsIfPossible(session)
        }
    }

    // ------------------------------------------------------------------ Socket exterior

    private fun handleConnect(session: Session) {
        try {
            if (session.channel.finishConnect()) {
                session.selectionKey?.interestOps(SelectionKey.OP_READ)
                session.connection.onUpstreamConnected(System.currentTimeMillis())
            }
        } catch (e: IOException) {
            diagnostics.warn("tcp.connect", "finishConnect falló hacia ${describe(session.key)}: ${e.message}", e)
            session.connection.abort()
        }
    }

    private fun handleRead(session: Session) {
        val window = session.connection.availableSendWindow()
        if (window <= 0 || !canReadDownlink()) {
            pauseReads(session)
            return
        }
        val buffer = bufferPool.acquire()
        try {
            buffer.limit(minOf(window, buffer.capacity()))
            val bytesRead = session.channel.read(buffer)
            val now = System.currentTimeMillis()
            if (bytesRead > 0) {
                buffer.flip()
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                session.connection.onServerData(data, now)
            } else if (bytesRead < 0) {
                // EOF del servidor: dejar de vigilar OP_READ (un canal en EOF siempre está "legible").
                session.serverEof = true
                session.setInterest(SelectionKey.OP_READ, enabled = false)
                session.connection.onServerEof(now)
            }
        } catch (e: IOException) {
            diagnostics.warn("tcp.read", "Fallo al leer de ${describe(session.key)}: ${e.message}", e)
            session.connection.abort()
        } finally {
            bufferPool.release(buffer)
        }
    }

    private fun handleWrite(session: Session) {
        try {
            while (true) {
                val buffer = session.pendingWrites.firstOrNull() ?: break
                val written = session.channel.write(buffer)
                session.pendingWriteBytes -= written
                if (buffer.hasRemaining()) break
                session.pendingWrites.removeFirst()
            }
        } catch (e: IOException) {
            session.ioFailure = e
            return
        }
        if (session.pendingWrites.isEmpty()) {
            session.setInterest(SelectionKey.OP_WRITE, enabled = false)
            if (session.shutdownAfterFlush) shutdownChannelOutput(session)
        }
        session.connection.onServerWriteProgress(session.pendingWriteBytes)
    }

    private fun pauseReads(session: Session) {
        session.readsPaused = true
        session.setInterest(SelectionKey.OP_READ, enabled = false)
    }

    private fun resumeReadsIfPossible(session: Session) {
        if (!session.readsPaused || session.serverEof || session.removed) return
        if (session.connection.availableSendWindow() <= 0 || !canReadDownlink()) return
        session.readsPaused = false
        session.setInterest(SelectionKey.OP_READ, enabled = true)
    }

    private fun shutdownChannelOutput(session: Session) {
        session.shutdownAfterFlush = false
        try {
            session.channel.shutdownOutput()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "tcp.shutdown", "shutdownOutput falló hacia ${describe(session.key)}", e)
        }
    }

    // ------------------------------------------------------------------ Cierre

    private fun removeSession(session: Session) {
        if (session.removed) return
        session.removed = true
        if (sessions[session.key] === session) sessions.remove(session.key)
        session.selectionKey?.cancel()
        closeChannelQuietly(session.channel)
        session.pendingWrites.clear()
        session.pendingWriteBytes = 0
    }

    private fun closeChannelQuietly(channel: SocketChannel) {
        try {
            channel.close()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "tcp.close", "Error al cerrar un socket TCP", e)
        }
    }

    private fun describe(key: SessionKey): String =
        if (key.isIpv6) "[${key.serverIp}]:${key.serverPort}" else "${key.serverIp}:${key.serverPort}"

    fun close() {
        isRunning = false
        workerJob?.cancel()
        workerJob = null
        taskQueue.clear()

        sessions.values.forEach { closeChannelQuietly(it.channel) }
        sessions.clear()

        try {
            selector.close()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "tcp.close", "Error al cerrar el selector TCP", e)
        }
    }

    companion object {
        private const val ISN_SPACE = 1L shl 32
        private const val MIN_SELECT_TIMEOUT_MS = 5L
        private const val MAX_SELECT_TIMEOUT_MS = 250L
    }
}
