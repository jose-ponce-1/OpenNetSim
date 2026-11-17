package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IcmpPacket
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.service.diag.DiagLevel
import com.opennetsim.service.diag.error
import com.opennetsim.service.diag.warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Reenvía los ping (ICMP/ICMPv6 Echo) de las apps a la red real con sockets de ping sin privilegios.
 *
 * El RTT que ve la app es el real más la degradación configurada, y un host que no responde tampoco
 * responde a través del túnel. El kernel asigna su propio identificador ICMP a cada socket; la respuesta
 * se devuelve a la app con el identificador original de su ping.
 *
 * Modelo actor: las sesiones solo se tocan en el hilo de [IcmpEchoTransport.awaitReadable]; el resto
 * de hilos encolan tareas y despiertan la espera con [IcmpEchoTransport.wakeup].
 *
 * @param maxSessions sesiones (cliente, destino, identificador) simultáneas; al alcanzarlo se cierra
 * la menos recientemente activa.
 */
@Suppress("TooManyFunctions")
class IcmpForwarder(
    private val environment: ForwarderEnvironment,
    private val transport: IcmpEchoTransport,
    private val onInboundPacket: (ByteArray) -> Unit,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS
) {
    private data class SessionKey(
        val clientIp: String,
        val serverIp: String,
        val identifier: Int,
        val isIpv6: Boolean
    )

    private class Session(
        val key: SessionKey,
        val socket: IcmpEchoSocket,
        val clientIpBytes: ByteArray,
        val serverIpBytes: ByteArray,
        var lastActiveMs: Long
    )

    private val diagnostics = environment.diagnostics
    private val clock = environment.clock

    // Solo el hilo del bucle escribe; ConcurrentHashMap permite consultar el tamaño desde los tests.
    private val sessions = ConcurrentHashMap<SessionKey, Session>()
    private val sessionsBySocket = HashMap<IcmpEchoSocket, Session>()
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val receiveBuffer = ByteArray(RECEIVE_BUFFER_BYTES)
    private var lastCleanupMs = clock.nowMs()

    @Volatile
    private var isRunning = true
    private var workerJob: Job? = null

    init {
        startWorkerLoop()
    }

    /** Sesiones de ping abiertas. */
    fun activeSessionCount(): Int = sessions.size

    /** Procesa un paquete IPv4 ICMP saliente del TUN. Solo se reenvían los Echo Request. */
    fun processOutboundIpv4(ipPacket: IpPacket) {
        val icmp = IcmpPacket.parse(ipPacket.payload)?.takeIf { it.isEchoRequestV4 } ?: return
        enqueue(
            SessionKey(ipPacket.sourceIpString, ipPacket.destinationIpString, icmp.identifier, false),
            ipPacket.sourceIp,
            ipPacket.destinationIp,
            icmp
        )
    }

    /** Procesa un paquete IPv6 ICMPv6 saliente del TUN. Solo se reenvían los Echo Request. */
    fun processOutboundIpv6(ipv6Packet: Ipv6Packet) {
        val icmp = IcmpPacket.parse(ipv6Packet.payload)?.takeIf { it.isEchoRequestV6 } ?: return
        enqueue(
            SessionKey(ipv6Packet.sourceIpString, ipv6Packet.destinationIpString, icmp.identifier, true),
            ipv6Packet.sourceIp,
            ipv6Packet.destinationIp,
            icmp
        )
    }

    private fun enqueue(key: SessionKey, clientIp: ByteArray, serverIp: ByteArray, echo: IcmpPacket) {
        if (!isRunning) return
        tasks.add(Runnable { sendEcho(key, clientIp, serverIp, echo) })
        transport.wakeup()
    }

    // ------------------------------------------------------------------ Hilo del bucle

    @Suppress("TooGenericExceptionCaught")
    private fun startWorkerLoop() {
        val job = environment.coroutineScope.launch(Dispatchers.IO) {
            while (isActive && isRunning) {
                try {
                    runPendingTasks()
                    val ready = transport.awaitReadable(sessionsBySocket.keys.toList(), WAIT_TIMEOUT_MS)
                    ready.forEach { socket -> sessionsBySocket[socket]?.let(::receiveReply) }
                    cleanupIdleSessions()
                } catch (e: RuntimeException) {
                    // Un error con un ping concreto no debe detener el reenvío ICMP.
                    diagnostics.error("icmp.loop", "Error inesperado en el bucle ICMP", e)
                }
            }
        }
        // También si el ámbito se cancela antes de que el bucle llegue a arrancar.
        job.invokeOnCompletion { releaseAll() }
        workerJob = job
    }

    private fun releaseAll() {
        sessions.values.forEach { closeQuietly(it.socket) }
        sessions.clear()
        sessionsBySocket.clear()
        transport.close()
    }

    private fun runPendingTasks() {
        while (true) {
            val task = tasks.poll() ?: break
            task.run()
        }
    }

    private fun sendEcho(key: SessionKey, clientIp: ByteArray, serverIp: ByteArray, echo: IcmpPacket) {
        val session = sessions[key] ?: openSession(key, clientIp, serverIp) ?: return
        session.lastActiveMs = clock.nowMs()
        // El identificador lo reescribe el kernel con el del socket; el checksum también lo calcula él.
        val request = IcmpPacket(
            type = echo.type,
            code = 0,
            identifier = 0,
            sequenceNumber = echo.sequenceNumber,
            payload = echo.payload
        ).toByteArray(isIpv6 = false)
        try {
            session.socket.send(request)
        } catch (e: IOException) {
            // Red o destino inalcanzables: como en una red real, el ping simplemente no tiene respuesta.
            diagnostics.log(DiagLevel.DEBUG, "icmp.send", "Ping sin enviar a ${key.serverIp}: ${e.message}", e)
        }
    }

    private fun openSession(key: SessionKey, clientIp: ByteArray, serverIp: ByteArray): Session? {
        if (sessions.size >= maxSessions) evictLeastRecentlyActive()
        val socket = try {
            transport.open(InetAddress.getByAddress(serverIp))
        } catch (e: IOException) {
            diagnostics.warn("icmp.open", "No se pudo abrir un socket de ping hacia ${key.serverIp}: ${e.message}", e)
            return null
        }
        val session = Session(key, socket, clientIp.copyOf(), serverIp.copyOf(), clock.nowMs())
        sessions[key] = session
        sessionsBySocket[socket] = session
        return session
    }

    private fun receiveReply(session: Session) {
        val reply = readEchoReply(session) ?: return
        session.lastActiveMs = clock.nowMs()
        onInboundPacket(buildReplyPacket(session, reply))
    }

    /** Lee la respuesta pendiente del socket; null si hubo un error o no es un Echo Reply. */
    private fun readEchoReply(session: Session): IcmpPacket? {
        val length = try {
            session.socket.receive(receiveBuffer)
        } catch (e: IOException) {
            diagnostics.log(
                DiagLevel.DEBUG,
                "icmp.read",
                "Error de ping desde ${session.key.serverIp}: ${e.message}",
                e
            )
            return null
        }
        val expectedType = if (session.key.isIpv6) IcmpPacket.TYPE_ECHO_REPLY_V6 else IcmpPacket.TYPE_ECHO_REPLY_V4
        return IcmpPacket.parse(receiveBuffer.copyOf(length))?.takeIf { it.type == expectedType }
    }

    /** Respuesta hacia la app con el identificador de su ping original. */
    private fun buildReplyPacket(session: Session, reply: IcmpPacket): ByteArray {
        val toClient = reply.copy(identifier = session.key.identifier)
        return if (session.key.isIpv6) {
            val icmpBytes = toClient.toByteArray(
                isIpv6 = true,
                srcIp = session.serverIpBytes,
                dstIp = session.clientIpBytes
            )
            Ipv6Packet(
                version = IP_V6,
                payloadLength = icmpBytes.size,
                nextHeader = Ipv6Packet.PROTOCOL_ICMPV6,
                sourceIp = session.serverIpBytes,
                destinationIp = session.clientIpBytes,
                payload = icmpBytes
            ).toByteArray()
        } else {
            val icmpBytes = toClient.toByteArray(isIpv6 = false)
            IpPacket(
                version = IP_V4,
                ihl = IPV4_IHL,
                totalLength = IPV4_HEADER_BYTES + icmpBytes.size,
                protocol = IpPacket.PROTOCOL_ICMP,
                sourceIp = session.serverIpBytes,
                destinationIp = session.clientIpBytes,
                payload = icmpBytes
            ).toByteArray()
        }
    }

    private fun cleanupIdleSessions() {
        val now = clock.nowMs()
        if (now - lastCleanupMs < CLEANUP_INTERVAL_MS) return
        lastCleanupMs = now
        sessions.values.filter { now - it.lastActiveMs >= idleTimeoutMs }.forEach(::closeSession)
    }

    private fun evictLeastRecentlyActive() {
        val oldest = sessions.values.minByOrNull { it.lastActiveMs } ?: return
        closeSession(oldest)
        diagnostics.log(
            DiagLevel.INFO,
            "icmp.evict",
            "Límite de $maxSessions sesiones de ping: se cierra ${oldest.key.serverIp}"
        )
    }

    private fun closeSession(session: Session) {
        sessions.remove(session.key)
        sessionsBySocket.remove(session.socket)
        closeQuietly(session.socket)
    }

    private fun closeQuietly(socket: IcmpEchoSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "icmp.close", "Error al cerrar un socket de ping", e)
        }
    }

    /** Detiene el bucle; las sesiones y el transporte se cierran en el hilo del bucle. */
    fun close() {
        isRunning = false
        tasks.clear()
        transport.wakeup()
        workerJob?.cancel()
        workerJob = null
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 60_000L
        const val DEFAULT_MAX_SESSIONS = 256
        private const val WAIT_TIMEOUT_MS = 1_000L
        private const val CLEANUP_INTERVAL_MS = 1_000L
        private const val RECEIVE_BUFFER_BYTES = 65_535
        private const val IP_V4 = 4
        private const val IP_V6 = 6
        private const val IPV4_IHL = 5
        private const val IPV4_HEADER_BYTES = 20
    }
}
