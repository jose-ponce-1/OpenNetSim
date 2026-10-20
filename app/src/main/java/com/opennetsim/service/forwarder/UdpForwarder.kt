package com.opennetsim.service.forwarder

import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.packet.UdpPacket
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
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap

/**
 * Reenvía datagramas UDP (como consultas DNS y tráfico QUIC) desde la interfaz TUN
 * hacia la red física exterior utilizando sockets protegidos con [VpnSocketProtector].
 * Soporta IPv4 e IPv6 concurrentemente y reutiliza búferes con [ByteBufferPool].
 */
class UdpForwarder(
    private val socketProtector: VpnSocketProtector,
    private val coroutineScope: CoroutineScope,
    private val onInboundPacket: (ByteArray) -> Unit,
    private val bufferPool: ByteBufferPool = ByteBufferPool.defaultInstance
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
        val channel: DatagramChannel,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        val isIpv6: Boolean,
        @Volatile var lastActiveTimeMs: Long
    )

    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val selector: Selector = Selector.open()

    private var workerJob: Job? = null
    private var cleanupJob: Job? = null

    @Volatile
    private var isRunning = true

    init {
        startWorkerLoop()
        startCleanupLoop()
    }

    /**
     * Procesa un paquete IPv4 saliente con protocolo UDP proveniente del TUN.
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
     * Procesa un paquete IPv6 saliente con protocolo UDP proveniente del TUN.
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

        val udp = UdpPacket.parse(payload) ?: return
        val key = SessionKey(
            clientIp = srcIpString,
            clientPort = udp.sourcePort,
            serverIp = dstIpString,
            serverPort = udp.destinationPort,
            isIpv6 = isIpv6
        )

        try {
            var session = sessions[key]
            if (session == null || !session.channel.isOpen) {
                session = createSession(key, srcIpBytes, dstIpBytes, isIpv6)
                if (session == null) return
                sessions[key] = session
            }

            session.lastActiveTimeMs = System.currentTimeMillis()
            val byteBuffer = ByteBuffer.wrap(udp.payload)
            session.channel.write(byteBuffer)
        } catch (e: Exception) {
            closeSession(key)
        }
    }

    private fun createSession(
        key: SessionKey,
        clientIpBytes: ByteArray,
        serverIpBytes: ByteArray,
        isIpv6: Boolean
    ): Session? {
        return try {
            val channel = DatagramChannel.open().apply {
                configureBlocking(false)
                socketProtector.protect(socket())
                connect(InetSocketAddress(key.serverIp, key.serverPort))
            }

            val session = Session(
                key = key,
                channel = channel,
                clientIpBytes = clientIpBytes.copyOf(),
                serverIpBytes = serverIpBytes.copyOf(),
                clientPort = key.clientPort,
                serverPort = key.serverPort,
                isIpv6 = isIpv6,
                lastActiveTimeMs = System.currentTimeMillis()
            )

            // Despertar el selector para registrar el nuevo canal
            channel.register(selector, SelectionKey.OP_READ, session)
            selector.wakeup()

            session
        } catch (e: Exception) {
            null
        }
    }

    private fun startWorkerLoop() {
        workerJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                try {
                    val readyCount = selector.select(SELECTOR_TIMEOUT_MS)
                    if (readyCount > 0) {
                        val selectedKeys = selector.selectedKeys().iterator()
                        while (selectedKeys.hasNext()) {
                            val selKey = selectedKeys.next()
                            selectedKeys.remove()

                            if (!selKey.isValid) continue

                            if (selKey.isReadable) {
                                val session = selKey.attachment() as? Session
                                if (session != null) {
                                    handleIncomingData(session)
                                }
                            }
                        }
                    }
                } catch (e: ClosedSelectorException) {
                    break
                } catch (e: Exception) {
                    // Continuar bucle si ocurre un error temporal de I/O
                }
            }
        }
    }

    private fun handleIncomingData(session: Session) {
        val buffer = bufferPool.acquire()
        try {
            val bytesRead = session.channel.read(buffer)
            if (bytesRead > 0) {
                session.lastActiveTimeMs = System.currentTimeMillis()
                buffer.flip()
                val payload = ByteArray(buffer.remaining())
                buffer.get(payload)

                val responseUdp = UdpPacket(
                    sourcePort = session.serverPort,
                    destinationPort = session.clientPort,
                    length = UdpPacket.HEADER_SIZE + payload.size,
                    payload = payload
                )

                val packetBytes = if (session.isIpv6) {
                    responseUdp.toIpv6Packet(session.serverIpBytes, session.clientIpBytes).toByteArray()
                } else {
                    responseUdp.toIpPacket(session.serverIpBytes, session.clientIpBytes).toByteArray()
                }

                onInboundPacket(packetBytes)
            }
        } catch (e: IOException) {
            // El canal experimentó un error de lectura
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
                            // Ignorar cierre
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
        private const val SELECTOR_TIMEOUT_MS = 100L
        private const val SESSION_TIMEOUT_MS = 30_000L // 30 segundos de inactividad
        private const val CLEANUP_INTERVAL_MS = 5_000L
    }
}

