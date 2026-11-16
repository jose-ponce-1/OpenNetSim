package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.UdpPacket
import com.opennetsim.core.time.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Vida de las sesiones UDP (RFC 4787 REQ-5) y límite de sesiones, con reloj monotónico simulado.
 */
class UdpSessionLifetimeTest {

    private lateinit var echoServer: DatagramSocket
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clock = FakeMonotonicClock()
    private val inbound = CopyOnWriteArrayList<ByteArray>()

    /** Puerto de origen exterior de cada datagrama recibido por el servidor, en orden. */
    private val serverSeenSourcePorts = CopyOnWriteArrayList<Int>()
    private val forwarders = mutableListOf<UdpForwarder>()

    private val protector = object : VpnSocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val serverIp = byteArrayOf(127, 0, 0, 1)

    @Before
    fun setUp() {
        echoServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        Thread {
            val buffer = ByteArray(2048)
            try {
                while (!echoServer.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    echoServer.receive(packet)
                    serverSeenSourcePorts += packet.port
                    echoServer.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                }
            } catch (ignored: SocketException) {
                // Servidor cerrado al terminar el test
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun tearDown() {
        forwarders.forEach { it.close() }
        scope.cancel()
        echoServer.close()
    }

    private fun forwarder(policy: UdpSessionPolicy = UdpSessionPolicy()): UdpForwarder =
        UdpForwarder(
            environment = ForwarderEnvironment(
                socketProtector = protector,
                coroutineScope = scope,
                clock = clock,
                strictThreadChecks = true
            ),
            onInboundPacket = { inbound += it },
            policy = policy
        ).also { forwarders += it }

    private fun send(forwarder: UdpForwarder, clientPort: Int) {
        val expected = inbound.size + 1
        val packet = UdpPacket(
            sourcePort = clientPort,
            destinationPort = echoServer.localPort,
            length = UdpPacket.HEADER_SIZE + 4,
            payload = "ping".toByteArray()
        ).toIpPacket(clientIp, serverIp)
        forwarder.processOutbound(packet)
        awaitCondition("respuesta del eco") { inbound.size >= expected }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Tiempo agotado esperando: $what" }
            Thread.sleep(5L)
        }
    }

    @Test
    fun keepaliveEvery60SecondsKeepsTheSameExternalSocket() {
        val udp = forwarder()
        repeat(6) { index ->
            if (index > 0) {
                clock.advanceMs(60_000L)
                udp.expireIdleSessionsNow()
            }
            send(udp, clientPort = 45_000)
        }

        assertEquals("5 minutos de keepalive por un único socket exterior", 1, serverSeenSourcePorts.toSet().size)
        assertEquals(1, udp.activeSessionCount())
    }

    @Test
    fun idleSessionExpiresAfterPolicyTimeoutMeasuredWithMonotonicClock() {
        val udp = forwarder()
        send(udp, clientPort = 45_100)

        clock.advanceMs(UdpSessionPolicy.DEFAULT_IDLE_TIMEOUT_MS - 1)
        udp.expireIdleSessionsNow()
        Thread.sleep(50L)
        assertEquals("Aún dentro del tiempo de vida", 1, udp.activeSessionCount())

        clock.advanceMs(1L)
        udp.expireIdleSessionsNow()
        awaitCondition("caducidad de la sesión") { udp.activeSessionCount() == 0 }
    }

    @Test
    fun dnsSessionsUseShortTimeoutAndOthersAtLeastTwoMinutes() {
        val policy = UdpSessionPolicy()
        assertEquals(10_000L, policy.idleTimeoutFor(UdpSessionPolicy.DNS_PORT))
        assertTrue("RFC 4787 REQ-5: ≥ 2 min", policy.idleTimeoutFor(443) >= 120_000L)
    }

    @Test
    fun reachingSessionLimitEvictsLeastRecentlyActiveSession() {
        val udp = forwarder(UdpSessionPolicy(maxSessions = 3))
        listOf(46_001, 46_002, 46_003).forEach { port ->
            send(udp, clientPort = port)
            clock.advanceMs(1_000L)
        }
        val firstSocketPort = serverSeenSourcePorts.first()

        send(udp, clientPort = 46_004)
        assertEquals(3, udp.activeSessionCount())
        assertEquals(1L, udp.evictedSessionCount())

        // La sesión desalojada (la más antigua) vuelve a abrirse con otro socket exterior.
        send(udp, clientPort = 46_001)
        assertNotEquals(firstSocketPort, serverSeenSourcePorts.last())
        assertEquals(3, udp.activeSessionCount())
    }
}
