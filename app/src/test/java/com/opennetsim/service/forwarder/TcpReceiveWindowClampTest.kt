package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.TcpPacket
import com.opennetsim.core.tcp.TcpWindowPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Requisito "Recepción del socket exterior": tope de ventana proporcional y ajustable en caliente. */
class TcpReceiveWindowClampTest {

    private data class ClampCall(val remotePort: Int, val connected: Boolean, val bytes: Int)

    private lateinit var serverSocket: ServerSocket
    private val acceptedSockets = CopyOnWriteArrayList<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inboundTunPackets = CopyOnWriteArrayList<ByteArray>()
    private val clampCalls = CopyOnWriteArrayList<ClampCall>()
    private lateinit var forwarder: TcpForwarder

    private val protector = object : VpnSocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    private val recordingClamp = ReceiveWindowClamp { socket, bytes ->
        clampCalls.add(ClampCall(socket.port, socket.isConnected, bytes))
    }

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val serverIp = byteArrayOf(127, 0, 0, 1)

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        forwarder = TcpForwarder(
            environment = ForwarderEnvironment(
                socketProtector = protector,
                coroutineScope = scope,
                strictThreadChecks = true,
                receiveWindowClamp = recordingClamp
            ),
            onInboundPacket = { inboundTunPackets.add(it) }
        )
    }

    @After
    fun tearDown() {
        forwarder.close()
        scope.cancel()
        acceptedSockets.forEach { it.close() }
        serverSocket.close()
    }

    @Test
    fun clampIsAppliedOnConnectAndAdjustedOnOpenConnections() {
        forwarder.updateDegradation(downKbps = 10_000, latencyMs = 0, jitterMs = 0)
        openConnection(clientPort = 52_100)

        val onConnect = awaitCalls(1).last()
        assertTrue("el tope se aplica con el socket ya conectado", onConnect.connected)
        assertEquals(serverSocket.localPort, onConnect.remotePort)
        assertEquals(TcpWindowPolicy.receiveWindowClampBytes(10_000, 0, 0), onConnect.bytes)

        // Bajar la velocidad reajusta la conexión abierta, sin esperar a conexiones nuevas.
        forwarder.updateDegradation(downKbps = 1_000, latencyMs = 0, jitterMs = 0)
        assertEquals(TcpWindowPolicy.MIN_RECEIVE_WINDOW_BYTES, awaitCalls(2).last().bytes)

        // Y subirla también.
        forwarder.updateDegradation(downKbps = 50_000, latencyMs = 100, jitterMs = 0)
        assertEquals(TcpWindowPolicy.receiveWindowClampBytes(50_000, 100, 0), awaitCalls(3).last().bytes)
    }

    @Test
    fun withoutConfiguredDegradationTheSocketIsNotClamped() {
        openConnection(clientPort = 52_200)
        assertTrue("la conexión se establece", awaitSynAck())
        assertTrue(clampCalls.isEmpty())
    }

    private fun openConnection(clientPort: Int) {
        val syn = TcpPacket(
            sourcePort = clientPort,
            destinationPort = serverSocket.localPort,
            sequenceNumber = 1_000L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_SYN,
            windowSize = 65_535
        ).toIpPacket(clientIp, serverIp)
        forwarder.processOutbound(syn)
        acceptedSockets.add(serverSocket.accept())
    }

    private fun awaitSynAck(timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val synAck = inboundTunPackets.any { raw ->
                val tcp = IpPacket.parse(raw)?.let { TcpPacket.parse(it.payload) }
                tcp != null && tcp.isSyn && tcp.isAck
            }
            if (synAck) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun awaitCalls(count: Int, timeoutMs: Long = 3_000L): List<ClampCall> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (clampCalls.size < count && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertEquals("llamadas al tope de ventana", count, clampCalls.size)
        return clampCalls.toList()
    }

    private companion object {
        const val POLL_MS = 5L
    }
}
