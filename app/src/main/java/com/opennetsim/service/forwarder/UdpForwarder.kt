package com.opennetsim.service.forwarder

import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.core.packet.IcmpUnreachable
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.packet.UdpPacket
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
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap

/**
 * Reenvía datagramas UDP (como consultas DNS y tráfico QUIC) desde la interfaz TUN
 * hacia la red física exterior utilizando sockets protegidos con [VpnSocketProtector].
 *
 * Modelo actor: todo el estado de sesión y toda operación sobre el [Selector] se ejecutan en el
 * hilo del selector. Los paquetes del TUN solo se encolan con [SelectorTaskQueue.post], de modo que
 * dar de alta una sesión nunca bloquea al hilo que despacha el uplink.
 */
class UdpForwarder(
    private val environment: ForwarderEnvironment,
    private val onInboundPacket: (ByteArray) -> Unit
) {
    private data class SessionKey(
        val clientIp: String,
        val clientPort: Int,
        val serverIp: String,
        val serverPort: Int,
        val isIpv6: Boolean
    )

    private class Session(
        val key: SessionKey,
        val channel: DatagramChannel,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray,
        var lastActiveTimeMs: Long
    )

    private val diagnostics = environment.diagnostics

    /** Pool dedicado de búferes de 64 KB: un datagrama UDP nunca se trunca al leerlo. */
    private val receivePool = ByteBufferPool(bufferSize = RECEIVE_BUFFER_BYTES, maxPoolSize = RECEIVE_POOL_SIZE)

    // Solo el hilo del selector escribe; ConcurrentHashMap permite que close() itere con seguridad.
    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val selector: Selector = Selector.open()
    private val taskQueue = SelectorTaskQueue(selector, environment.strictThreadChecks)

    private var workerJob: Job? = null
    private var lastCleanupMs = System.currentTimeMillis()

    @Volatile
    private var isRunning = true

    init {
        startWorkerLoop()
    }

    /**
     * Procesa un paquete IPv4 saliente con protocolo UDP proveniente del TUN.
     */
    fun processOutbound(ipPacket: IpPacket) {
        enqueueOutbound(
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
        enqueueOutbound(
            srcIpBytes = ipv6Packet.sourceIp,
            dstIpBytes = ipv6Packet.destinationIp,
            srcIpString = ipv6Packet.sourceIpString,
            dstIpString = ipv6Packet.destinationIpString,
            payload = ipv6Packet.payload,
            isIpv6 = true
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

        val udp = UdpPacket.parse(payload) ?: return
        val key = SessionKey(
            clientIp = srcIpString,
            clientPort = udp.sourcePort,
            serverIp = dstIpString,
            serverPort = udp.destinationPort,
            isIpv6 = isIpv6
        )
        taskQueue.post { sendDatagram(key, srcIpBytes, dstIpBytes, udp.payload) }
    }

    private fun sendDatagram(key: SessionKey, clientIpBytes: ByteArray, serverIpBytes: ByteArray, payload: ByteArray) {
        val existing = sessions[key]
        val session = if (existing != null && existing.channel.isOpen) {
            existing
        } else {
            openSession(key, clientIpBytes, serverIpBytes, payload) ?: return
        }

        session.lastActiveTimeMs = System.currentTimeMillis()
        try {
            session.channel.write(ByteBuffer.wrap(payload))
        } catch (e: IOException) {
            diagnostics.warn("udp.write", "Fallo al enviar a ${describe(key)}: ${e.message}", e)
            closeSession(key)
            emitUnreachable(key, clientIpBytes, serverIpBytes, payload, e)
        }
    }

    /**
     * Devuelve a la app un ICMP de destino inalcanzable que cita su datagrama, para que su resolver o
     * su pila no esperen al timeout. No hace nada si el error no indica inalcanzabilidad.
     */
    private fun emitUnreachable(
        key: SessionKey,
        clientIpBytes: ByteArray,
        serverIpBytes: ByteArray,
        payload: ByteArray,
        error: IOException
    ) {
        val kind = UnreachableClassifier.classify(error) ?: return
        val original = UdpPacket(
            sourcePort = key.clientPort,
            destinationPort = key.serverPort,
            length = UdpPacket.HEADER_SIZE + payload.size,
            payload = payload
        )
        val originalBytes = if (key.isIpv6) {
            original.toIpv6Packet(clientIpBytes, serverIpBytes).toByteArray()
        } else {
            original.toIpPacket(clientIpBytes, serverIpBytes).toByteArray()
        }
        onInboundPacket(
            IcmpUnreachable.buildIpPacket(
                originalPacket = originalBytes,
                isIpv6 = key.isIpv6,
                kind = kind,
                reporterIp = serverIpBytes,
                clientIp = clientIpBytes
            )
        )
    }

    private fun openSession(
        key: SessionKey,
        clientIpBytes: ByteArray,
        serverIpBytes: ByteArray,
        firstPayload: ByteArray
    ): Session? {
        taskQueue.checkOwnerThread()
        val channel = try {
            DatagramChannel.open()
        } catch (e: IOException) {
            diagnostics.warn("udp.open", "No se pudo crear el socket UDP hacia ${describe(key)}", e)
            return null
        }

        return try {
            channel.configureBlocking(false)
            if (!environment.socketProtector.protect(channel.socket())) {
                diagnostics.warn("udp.protect", "protect() rechazó el socket UDP hacia ${describe(key)}")
                channel.close()
                return null
            }
            channel.connect(InetSocketAddress(InetAddress.getByAddress(serverIpBytes), key.serverPort))

            val session = Session(
                key = key,
                channel = channel,
                clientIpBytes = clientIpBytes.copyOf(),
                serverIpBytes = serverIpBytes.copyOf(),
                lastActiveTimeMs = System.currentTimeMillis()
            )
            channel.register(selector, SelectionKey.OP_READ, session)
            sessions[key] = session
            session
        } catch (e: IOException) {
            diagnostics.warn("udp.connect", "No se pudo conectar el socket UDP a ${describe(key)}: ${e.message}", e)
            closeQuietly(channel)
            emitUnreachable(key, clientIpBytes, serverIpBytes, firstPayload, e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startWorkerLoop() {
        workerJob = environment.coroutineScope.launch(Dispatchers.IO) {
            taskQueue.bindToCurrentThread()
            while (isActive && isRunning) {
                try {
                    taskQueue.runPending()
                    if (selector.select(SELECTOR_TIMEOUT_MS) > 0) {
                        handleSelectedKeys()
                    }
                    cleanupIdleSessions()
                } catch (e: ClosedSelectorException) {
                    break
                } catch (e: IOException) {
                    diagnostics.warn("udp.selector", "Error de I/O en el selector UDP", e)
                } catch (e: RuntimeException) {
                    // Un error con un datagrama concreto no debe detener el reenvío UDP.
                    diagnostics.error("udp.loop", "Error inesperado en el bucle UDP", e)
                }
            }
        }
    }

    private fun handleSelectedKeys() {
        val selectedKeys = selector.selectedKeys().iterator()
        while (selectedKeys.hasNext()) {
            val selKey = selectedKeys.next()
            selectedKeys.remove()
            if (!selKey.isValid || !selKey.isReadable) continue
            val session = selKey.attachment() as? Session ?: continue
            handleIncomingData(session)
        }
    }

    private fun handleIncomingData(session: Session) {
        val buffer = receivePool.acquire()
        try {
            val bytesRead = session.channel.read(buffer)
            if (bytesRead > 0) {
                session.lastActiveTimeMs = System.currentTimeMillis()
                buffer.flip()
                val maxPayload = if (session.key.isIpv6) MAX_UDP_PAYLOAD_IPV6 else MAX_UDP_PAYLOAD_IPV4
                if (buffer.remaining() > maxPayload) {
                    // No cabe en un único paquete IP (65.535 bytes): se descarta, no se trunca.
                    diagnostics.warn("udp.oversize", "Datagrama de ${buffer.remaining()} B de ${describe(session.key)} descartado")
                    return
                }
                val payload = ByteArray(buffer.remaining())
                buffer.get(payload)

                // Un único paquete IP entero aunque supere el MTU del túnel (design D12).
                val responseUdp = UdpPacket(
                    sourcePort = session.key.serverPort,
                    destinationPort = session.key.clientPort,
                    length = UdpPacket.HEADER_SIZE + payload.size,
                    payload = payload
                )

                val packetBytes = if (session.key.isIpv6) {
                    responseUdp.toIpv6Packet(session.serverIpBytes, session.clientIpBytes).toByteArray()
                } else {
                    responseUdp.toIpPacket(session.serverIpBytes, session.clientIpBytes).toByteArray()
                }

                onInboundPacket(packetBytes)
            }
        } catch (e: IOException) {
            diagnostics.warn("udp.read", "Fallo al recibir de ${describe(session.key)}: ${e.message}", e)
            closeSession(session.key)
            emitUnreachable(session.key, session.clientIpBytes, session.serverIpBytes, EMPTY_PAYLOAD, e)
        } finally {
            receivePool.release(buffer)
        }
    }

    private fun cleanupIdleSessions() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) return
        lastCleanupMs = now

        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastActiveTimeMs > SESSION_TIMEOUT_MS) {
                closeQuietly(entry.value.channel)
                it.remove()
            }
        }
    }

    private fun closeSession(key: SessionKey) {
        sessions.remove(key)?.let { closeQuietly(it.channel) }
    }

    private fun closeQuietly(channel: DatagramChannel) {
        try {
            channel.close()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "udp.close", "Error al cerrar un socket UDP", e)
        }
    }

    private fun describe(key: SessionKey): String =
        if (key.isIpv6) "[${key.serverIp}]:${key.serverPort}" else "${key.serverIp}:${key.serverPort}"

    fun close() {
        isRunning = false
        workerJob?.cancel()
        workerJob = null
        taskQueue.clear()

        sessions.values.forEach { closeQuietly(it.channel) }
        sessions.clear()

        try {
            selector.close()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "udp.close", "Error al cerrar el selector UDP", e)
        }
    }

    companion object {
        private const val SELECTOR_TIMEOUT_MS = 1_000L
        private const val RECEIVE_BUFFER_BYTES = 64 * 1024
        private const val RECEIVE_POOL_SIZE = 2

        /** Payload UDP máximo en un paquete IPv4 de 65.535 bytes (65.535 − 20 − 8). */
        private const val MAX_UDP_PAYLOAD_IPV4 = 65_507

        /** Payload UDP máximo con el campo de longitud de payload IPv6 a 65.535 (65.535 − 8). */
        private const val MAX_UDP_PAYLOAD_IPV6 = 65_527
        private val EMPTY_PAYLOAD = ByteArray(0)
        private const val SESSION_TIMEOUT_MS = 30_000L // 30 segundos de inactividad
        private const val CLEANUP_INTERVAL_MS = 5_000L
    }
}
