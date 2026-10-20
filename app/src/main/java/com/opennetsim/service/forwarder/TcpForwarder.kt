package com.opennetsim.service.forwarder

import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.packet.TcpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Reenvía conexiones TCP en espacio de usuario desde la interfaz TUN hacia la red exterior
 * utilizando Java NIO ([Selector], [SocketChannel]) protegidos con [VpnSocketProtector].
 *
 * Implementa:
 * - Handshake sincronizado (SYN-ACK emitido tras finishConnect exitoso hacia el servidor remoto).
 * - Control de flujo por ventana deslizante dinámica (anuncio de Zero-Window si se satura la salida).
 * - Deduplicación de retransmisiones del cliente.
 * - Soporte dual stack transparente (IPv4 e IPv6).
 */
class TcpForwarder(
    private val socketProtector: VpnSocketProtector,
    private val coroutineScope: CoroutineScope,
    private val onInboundPacket: (ByteArray) -> Unit,
    private val bufferPool: ByteBufferPool = ByteBufferPool.defaultInstance,
    private val canReadDownlink: () -> Boolean = { true },
    private val onControlPacket: (ByteArray) -> Unit = onInboundPacket
) {
    private data class SessionKey(
        val clientIp: String,
        val clientPort: Int,
        val serverIp: String,
        val serverPort: Int,
        val isIpv6: Boolean = false
    )

    private class Session(
        val key: SessionKey,
        val channel: SocketChannel,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        val isIpv6: Boolean,
        @Volatile var clientExpectedSeq: Long,
        @Volatile var serverSeq: Long,
        @Volatile var synAckSent: Boolean = false,
        @Volatile var lastActiveTimeMs: Long
    ) {
        val pendingWrites = ConcurrentLinkedQueue<ByteBuffer>()
        @Volatile var selectionKey: SelectionKey? = null
        val outOfOrderBuffer = java.util.TreeMap<Long, ByteArray>()
        @Volatile var outOfOrderBufferedBytes: Int = 0
    }

    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val selector: Selector = Selector.open()
    private val pendingRegistrations = ConcurrentLinkedQueue<Runnable>()

    private var workerJob: Job? = null
    private var cleanupJob: Job? = null

    @Volatile
    private var isRunning = true

    init {
        startWorkerLoop()
        startCleanupLoop()
    }

    /**
     * Procesa un segmento TCP saliente proveniente de un paquete IPv4 en la interfaz TUN.
     */
    fun processOutbound(ipPacket: IpPacket) {
        processOutboundInternal(
            srcIpBytes = ipPacket.sourceIp,
            dstIpBytes = ipPacket.destinationIp,
            srcIpString = ipPacket.sourceIpString,
            dstIpString = ipPacket.destinationIpString,
            payload = ipPacket.payload,
            isIpv6 = false
        )
    }

    /**
     * Procesa un segmento TCP saliente proveniente de un paquete IPv6 en la interfaz TUN.
     */
    fun processOutboundIpv6(ipv6Packet: Ipv6Packet) {
        processOutboundInternal(
            srcIpBytes = ipv6Packet.sourceIp,
            dstIpBytes = ipv6Packet.destinationIp,
            srcIpString = ipv6Packet.sourceIpString,
            dstIpString = ipv6Packet.destinationIpString,
            payload = ipv6Packet.payload,
            isIpv6 = true
        )
    }

    private fun processOutboundInternal(
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        srcIpString: String,
        dstIpString: String,
        payload: ByteArray,
        isIpv6: Boolean
    ) {
        if (!isRunning) return

        val tcp = TcpPacket.parse(payload) ?: return
        val key = SessionKey(
            clientIp = srcIpString,
            clientPort = tcp.sourcePort,
            serverIp = dstIpString,
            serverPort = tcp.destinationPort,
            isIpv6 = isIpv6
        )

        if (tcp.isRst) {
            closeSession(key)
            return
        }

        if (tcp.isSyn) {
            handleSyn(key, srcIpBytes, dstIpBytes, tcp, isIpv6)
            return
        }

        val session = sessions[key] ?: return
        session.lastActiveTimeMs = System.currentTimeMillis()

        if (tcp.isFin) {
            handleFin(session, tcp)
            return
        }

        if (tcp.payload.isNotEmpty()) {
            handleData(session, tcp)
        }
    }

    private fun handleSyn(
        key: SessionKey,
        srcIpBytes: ByteArray,
        dstIpBytes: ByteArray,
        tcp: TcpPacket,
        isIpv6: Boolean
    ) {
        val existing = sessions[key]
        if (existing != null) {
            // Si la conexión ya está en progreso o establecida, ignorar SYN duplicado
            if (existing.channel.isOpen && existing.channel.isConnectionPending) {
                existing.lastActiveTimeMs = System.currentTimeMillis()
                return
            }
            closeSession(key)
        }

        val channel = try {
            SocketChannel.open().apply {
                configureBlocking(false)
                socketProtector.protect(socket())
            }
        } catch (e: Exception) {
            return
        }

        val clientSeq = tcp.sequenceNumber
        val clientExpectedSeq = (clientSeq + 1) and 0xFFFFFFFFL
        val serverInitialSeq = (Random.nextLong(1000, 100000)) and 0xFFFFFFFFL

        val session = Session(
            key = key,
            channel = channel,
            clientIpBytes = srcIpBytes.copyOf(),
            serverIpBytes = dstIpBytes.copyOf(),
            clientPort = key.clientPort,
            serverPort = key.serverPort,
            isIpv6 = isIpv6,
            clientExpectedSeq = clientExpectedSeq,
            serverSeq = serverInitialSeq,
            synAckSent = false,
            lastActiveTimeMs = System.currentTimeMillis()
        )

        sessions[key] = session

        // Iniciar conexión física hacia el servidor exterior
        try {
            val connected = channel.connect(InetSocketAddress(key.serverIp, key.serverPort))
            if (connected) {
                // Conectado de inmediato
                sendSynAck(session)
                pendingRegistrations.add(Runnable {
                    try {
                        if (channel.isOpen) {
                            session.selectionKey = channel.register(selector, SelectionKey.OP_READ, session)
                        }
                    } catch (e: Exception) {
                        closeSession(key)
                    }
                })
            } else {
                // Pendiente de conectar: registrar OP_CONNECT
                pendingRegistrations.add(Runnable {
                    try {
                        if (channel.isOpen) {
                            session.selectionKey = channel.register(
                                selector,
                                SelectionKey.OP_CONNECT or SelectionKey.OP_READ,
                                session
                            )
                        }
                    } catch (e: Exception) {
                        closeSession(key)
                    }
                })
            }
            selector.wakeup()
        } catch (e: Exception) {
            // Rechazo inmediato: emitir RST
            val rst = TcpPacket.buildRst(
                srcPort = key.serverPort,
                dstPort = key.clientPort,
                seqNum = session.serverSeq,
                ackNum = session.clientExpectedSeq
            )
            sendToTun(session, rst, isControl = true)
            closeSession(key)
        }
    }

    private fun sendSynAck(session: Session) {
        if (session.synAckSent) return
        session.synAckSent = true

        val synAck = TcpPacket.buildSynAck(
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seqNum = session.serverSeq,
            ackNum = session.clientExpectedSeq,
            windowSize = calculateAdvertisedWindow(session)
        )
        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
        sendToTun(session, synAck, isControl = true)
    }

    private fun handleData(session: Session, tcp: TcpPacket) {
        val payload = tcp.payload
        val seq = tcp.sequenceNumber
        val payloadEnd = (seq + payload.size) and 0xFFFFFFFFL

        // 1. Deduplicación: Si los datos recibidos caen en un rango de secuencia ya confirmado,
        // responder con ACK acumulativo sin reenviar datos duplicados al SocketChannel.
        val consumedDiff = (session.clientExpectedSeq - payloadEnd).toInt()
        if (consumedDiff >= 0) {
            val ack = TcpPacket.buildAck(
                srcPort = session.serverPort,
                dstPort = session.clientPort,
                seqNum = session.serverSeq,
                ackNum = session.clientExpectedSeq,
                windowSize = calculateAdvertisedWindow(session)
            )
            sendToTun(session, ack, isControl = true)
            return
        }

        // 2. Comprobar si hay un hueco (out-of-order segment)
        val seqGap = (seq - session.clientExpectedSeq).toInt()
        if (seqGap > 0) {
            // Buffer de desorden: retener segmento si cabe en el búfer
            synchronized(session.outOfOrderBuffer) {
                if (session.outOfOrderBufferedBytes + payload.size <= MAX_PENDING_WRITES_BYTES) {
                    if (!session.outOfOrderBuffer.containsKey(seq)) {
                        session.outOfOrderBuffer[seq] = payload
                        session.outOfOrderBufferedBytes += payload.size
                    }
                }
            }
            // Enviar ACK duplicado para señalar el hueco manteniendo clientExpectedSeq continuo
            val ack = TcpPacket.buildAck(
                srcPort = session.serverPort,
                dstPort = session.clientPort,
                seqNum = session.serverSeq,
                ackNum = session.clientExpectedSeq,
                windowSize = calculateAdvertisedWindow(session)
            )
            sendToTun(session, ack, isControl = true)
            return
        }

        // 3. Segmento contiguo (seq <= clientExpectedSeq && payloadEnd > clientExpectedSeq)
        val sliceOffset = (session.clientExpectedSeq - seq).toInt()
        val slice = if (sliceOffset > 0) payload.copyOfRange(sliceOffset, payload.size) else payload

        writePayloadToChannel(session, slice)
        session.clientExpectedSeq = payloadEnd

        // 4. Drenar segmentos contiguos acumulados en outOfOrderBuffer
        synchronized(session.outOfOrderBuffer) {
            while (true) {
                val nextEntry = session.outOfOrderBuffer.firstEntry() ?: break
                val nextSeq = nextEntry.key
                val nextData = nextEntry.value
                val nextEnd = (nextSeq + nextData.size) and 0xFFFFFFFFL

                val diff = (session.clientExpectedSeq - nextSeq).toInt()
                if (diff >= 0) {
                    session.outOfOrderBuffer.remove(nextSeq)
                    session.outOfOrderBufferedBytes -= nextData.size

                    val overlap = (session.clientExpectedSeq - nextSeq).toInt()
                    if (nextEnd > session.clientExpectedSeq) {
                        val contiguousSlice = if (overlap > 0) nextData.copyOfRange(overlap, nextData.size) else nextData
                        writePayloadToChannel(session, contiguousSlice)
                        session.clientExpectedSeq = nextEnd
                    }
                } else {
                    break
                }
            }
        }

        // 5. Enviar ACK acumulativo con la secuencia continua confirmada
        val ack = TcpPacket.buildAck(
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seqNum = session.serverSeq,
            ackNum = session.clientExpectedSeq,
            windowSize = calculateAdvertisedWindow(session)
        )
        sendToTun(session, ack, isControl = true)
    }

    private fun writePayloadToChannel(session: Session, payload: ByteArray) {
        if (payload.isEmpty()) return
        val writeBuffer = ByteBuffer.wrap(payload)
        try {
            if (session.channel.isConnected) {
                session.channel.write(writeBuffer)
            }
            if (writeBuffer.hasRemaining()) {
                session.pendingWrites.add(writeBuffer)
                session.selectionKey?.let { key ->
                    key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                }
            }
        } catch (e: IOException) {
            closeSession(session.key)
        }
    }

    private fun handleFin(session: Session, tcp: TcpPacket) {
        session.clientExpectedSeq = (tcp.sequenceNumber + 1) and 0xFFFFFFFFL

        // Acknowledge FIN
        val ack = TcpPacket.buildAck(
            srcPort = session.serverPort,
            dstPort = session.clientPort,
            seqNum = session.serverSeq,
            ackNum = session.clientExpectedSeq,
            windowSize = calculateAdvertisedWindow(session)
        )
        sendToTun(session, ack, isControl = true)

        // Cerrar salida en el canal exterior
        try {
            session.channel.shutdownOutput()
        } catch (e: Exception) {
            // Ignorar
        }
    }

    private fun sendToTun(session: Session, tcpPacket: TcpPacket, isControl: Boolean = false) {
        val packetBytes = if (session.isIpv6) {
            tcpPacket.toIpv6Packet(session.serverIpBytes, session.clientIpBytes).toByteArray()
        } else {
            tcpPacket.toIpPacket(session.serverIpBytes, session.clientIpBytes).toByteArray()
        }
        if (isControl) {
            onControlPacket(packetBytes)
        } else {
            onInboundPacket(packetBytes)
        }
    }

    private fun calculateAdvertisedWindow(session: Session): Int {
        var pendingBytes = 0
        for (buf in session.pendingWrites) {
            pendingBytes += buf.remaining()
        }
        pendingBytes += session.outOfOrderBufferedBytes
        return when {
            pendingBytes >= MAX_PENDING_WRITES_BYTES -> 0 // Zero-Window para pausar emisor
            pendingBytes >= (MAX_PENDING_WRITES_BYTES / 2) -> (MAX_WINDOW_SIZE / 4)
            else -> MAX_WINDOW_SIZE
        }
    }

    private fun startWorkerLoop() {
        workerJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                try {
                    // Procesar registros pendientes en el selector
                    while (true) {
                        val task = pendingRegistrations.poll() ?: break
                        task.run()
                    }

                    val readyCount = selector.select(SELECTOR_TIMEOUT_MS)
                    var throttledRead = false
                    if (readyCount > 0) {
                        val keys = selector.selectedKeys().iterator()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            keys.remove()

                            if (!key.isValid) continue

                            val session = key.attachment() as? Session ?: continue

                            if (key.isConnectable) {
                                handleConnect(key, session)
                            }

                            if (key.isValid && key.isWritable) {
                                handleWrite(key, session)
                            }

                            if (key.isValid && key.isReadable) {
                                if (canReadDownlink()) {
                                    handleRead(session)
                                } else {
                                    throttledRead = true
                                }
                            }
                        }
                    }
                    if (throttledRead) {
                        delay(1L)
                    }
                } catch (e: ClosedSelectorException) {
                    break
                } catch (e: Exception) {
                    // Continuar en caso de fallo transitorio
                }
            }
        }
    }

    private fun handleConnect(key: SelectionKey, session: Session) {
        try {
            if (session.channel.finishConnect()) {
                key.interestOps(SelectionKey.OP_READ)
                sendSynAck(session)
                flushPendingWrites(key, session)
            }
        } catch (e: IOException) {
            key.cancel()
            // Enviar RST al TUN si falló la conexión al servidor
            val rst = TcpPacket.buildRst(
                srcPort = session.serverPort,
                dstPort = session.clientPort,
                seqNum = session.serverSeq,
                ackNum = session.clientExpectedSeq
            )
            sendToTun(session, rst, isControl = true)
            closeSession(session.key)
        }
    }

    private fun handleWrite(key: SelectionKey, session: Session) {
        try {
            flushPendingWrites(key, session)
        } catch (e: IOException) {
            closeSession(session.key)
        }
    }

    private fun flushPendingWrites(key: SelectionKey, session: Session) {
        while (true) {
            val buffer = session.pendingWrites.peek() ?: break
            session.channel.write(buffer)
            if (buffer.hasRemaining()) {
                key.interestOps(key.interestOps() or SelectionKey.OP_WRITE)
                return
            } else {
                session.pendingWrites.poll()
            }
        }
        key.interestOps(key.interestOps() and SelectionKey.OP_WRITE.inv())
    }

    private fun handleRead(session: Session) {
        val buffer = bufferPool.acquire()
        try {
            buffer.limit(TCP_MAX_SEGMENT_SIZE)
            val bytesRead = session.channel.read(buffer)
            if (bytesRead > 0) {
                session.lastActiveTimeMs = System.currentTimeMillis()
                buffer.flip()
                val data = ByteArray(buffer.remaining())
                buffer.get(data)

                // Dividir en segmentos con tamaño máximo de MSS (1360 bytes para MTU 1500)
                var offset = 0
                val window = calculateAdvertisedWindow(session)
                while (offset < data.size) {
                    val chunkSize = minOf(TCP_MAX_SEGMENT_SIZE, data.size - offset)
                    val chunk = data.copyOfRange(offset, offset + chunkSize)

                    val pshAck = TcpPacket.buildPshAck(
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seqNum = session.serverSeq,
                        ackNum = session.clientExpectedSeq,
                        payload = chunk,
                        windowSize = window
                    )
                    session.serverSeq = (session.serverSeq + chunk.size) and 0xFFFFFFFFL
                    sendToTun(session, pshAck)

                    offset += chunkSize
                }
            } else if (bytesRead < 0) {
                // El servidor cerró la conexión (EOF): enviar FIN-ACK hacia el TUN
                val finAck = TcpPacket.buildFinAck(
                    srcPort = session.serverPort,
                    dstPort = session.clientPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientExpectedSeq,
                    windowSize = calculateAdvertisedWindow(session)
                )
                session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                sendToTun(session, finAck, isControl = true)
                closeSession(session.key)
            }
        } catch (e: IOException) {
            closeSession(session.key)
        } finally {
            bufferPool.release(buffer)
        }
    }

    private fun startCleanupLoop() {
        cleanupJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                delay(CLEANUP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val it = sessions.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    if (now - entry.value.lastActiveTimeMs > SESSION_TIMEOUT_MS) {
                        try {
                            entry.value.channel.close()
                        } catch (e: Exception) {
                            // Ignorar
                        }
                        it.remove()
                    }
                }
            }
        }
    }

    private fun closeSession(key: SessionKey) {
        val session = sessions.remove(key)
        try {
            session?.channel?.close()
        } catch (e: Exception) {
            // Ignorar
        }
        session?.outOfOrderBuffer?.clear()
        session?.outOfOrderBufferedBytes = 0
    }

    fun close() {
        isRunning = false
        workerJob?.cancel()
        cleanupJob?.cancel()
        workerJob = null
        cleanupJob = null

        sessions.values.forEach {
            try {
                it.channel.close()
            } catch (e: Exception) {
                // Ignorar
            }
        }
        sessions.clear()

        try {
            selector.close()
        } catch (e: Exception) {
            // Ignorar
        }
    }

    companion object {
        private const val MAX_WINDOW_SIZE = 65535
        private const val MAX_PENDING_WRITES_BYTES = 65536 // 64 KB
        private const val TCP_MAX_SEGMENT_SIZE = 1360
        private const val SELECTOR_TIMEOUT_MS = 10L
        private const val SESSION_TIMEOUT_MS = 60_000L // 60 segundos de inactividad
        private const val CLEANUP_INTERVAL_MS = 5_000L
    }
}

