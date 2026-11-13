package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.UdpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

class UdpForwarderTest {

    private lateinit var echoServer: DatagramSocket
    private lateinit var echoThread: Thread
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inboundTunPackets = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
    private lateinit var forwarder: UdpForwarder

    private val protector = object : VpnSocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val serverIp = byteArrayOf(127, 0, 0, 1)

    @Before
    fun setUp() {
        echoServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        echoThread = Thread {
            val buffer = ByteArray(65_535)
            try {
                while (!echoServer.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    echoServer.receive(packet)
                    echoServer.send(DatagramPacket(packet.data, packet.length, packet.socketAddress))
                }
            } catch (ignored: SocketException) {
                // Servidor cerrado al terminar el test
            }
        }.apply {
            isDaemon = true
            start()
        }

        forwarder = UdpForwarder(
            environment = ForwarderEnvironment(
                socketProtector = protector,
                coroutineScope = scope,
                strictThreadChecks = true
            ),
            onInboundPacket = { inboundTunPackets.add(System.nanoTime() to it) }
        )
    }

    @After
    fun tearDown() {
        forwarder.close()
        scope.cancel()
        echoServer.close()
    }

    private fun outboundDatagram(clientPort: Int, payload: ByteArray): IpPacket =
        UdpPacket(
            sourcePort = clientPort,
            destinationPort = echoServer.localPort,
            length = UdpPacket.HEADER_SIZE + payload.size,
            payload = payload
        ).toIpPacket(clientIp, serverIp)

    private fun awaitInbound(count: Int, timeoutMs: Long = 3000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inboundTunPackets.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    @Test
    fun burstOfNewSessionsDoesNotBlockCallerThread() {
        // Calentamiento (carga de clases y JIT) con un puerto distinto
        forwarder.processOutbound(outboundDatagram(40000, "warmup".toByteArray()))
        awaitInbound(1)
        inboundTunPackets.clear()

        val packets = (1..20).map { outboundDatagram(41000 + it, "q$it".toByteArray()) }
        val callDurationsNanos = packets.map { packet ->
            val start = System.nanoTime()
            forwarder.processOutbound(packet)
            System.nanoTime() - start
        }

        val slowest = callDurationsNanos.maxOrNull() ?: 0L
        assertTrue("Alta de sesión bloqueó el hilo llamante ${slowest / 1_000_000.0} ms", slowest < 5_000_000L)

        awaitInbound(20)
        assertEquals(20, inboundTunPackets.size)
        val responsePorts = inboundTunPackets.map { (_, raw) ->
            val ip = IpPacket.parse(raw)!!
            UdpPacket.parse(ip.payload)!!.destinationPort
        }.toSet()
        assertEquals((1..20).map { 41000 + it }.toSet(), responsePorts)
    }

    @Test
    fun largeResponseIsDeliveredAsSingleIpPacket() {
        val request = ByteArray(3000) { (it % 251).toByte() }
        forwarder.processOutbound(outboundDatagram(43000, request))
        awaitInbound(1)

        assertEquals("Un único paquete IP para el datagrama completo", 1, inboundTunPackets.size)
        val raw = inboundTunPackets.single().second
        val ip = IpPacket.parse(raw)!!
        assertEquals(20 + 8 + 3000, raw.size)
        assertEquals(raw.size, ip.totalLength)
        val udp = UdpPacket.parse(ip.payload)!!
        org.junit.Assert.assertArrayEquals("Datagrama completo, sin truncar", request, udp.payload)
    }

    @Test
    fun closedPortIsReportedWithIcmpPortUnreachable() {
        // Windows desactiva SIO_UDP_CONNRESET en la JVM: el ICMP no llega al socket. Android es Linux.
        org.junit.Assume.assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val closedPort = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val packet = UdpPacket(44000, closedPort, UdpPacket.HEADER_SIZE + 4, payload = "ping".toByteArray())
            .toIpPacket(clientIp, serverIp)

        forwarder.processOutbound(packet)
        Thread.sleep(100L)
        forwarder.processOutbound(packet) // La segunda lectura/escritura recibe el error del ICMP
        awaitInbound(1)

        val icmpRaw = inboundTunPackets.map { it.second }.firstOrNull {
            IpPacket.parse(it)?.protocol == IpPacket.PROTOCOL_ICMP
        }
        assertNotNull("Debe llegar un ICMP de puerto inalcanzable", icmpRaw)
        val icmp = com.opennetsim.core.packet.IcmpPacket.parse(IpPacket.parse(icmpRaw!!)!!.payload)!!
        assertEquals(3, icmp.type)
        assertEquals(3, icmp.code)
    }

    @Test
    fun responseToNewSessionIsDeliveredWithoutWaitingForSelectorTimeout() {
        val sentAt = System.nanoTime()
        forwarder.processOutbound(outboundDatagram(42000, "hola".toByteArray()))
        awaitInbound(1)

        val response = inboundTunPackets.firstOrNull()
        assertNotNull("Debe llegar la respuesta del servidor", response)
        val latencyMs = (response!!.first - sentAt) / 1_000_000L
        // El selector UDP espera hasta 1000 ms sin trabajo: la respuesta debe llegar mucho antes.
        assertTrue("Respuesta entregada en $latencyMs ms", latencyMs < 200L)

        val udp = UdpPacket.parse(IpPacket.parse(response.second)!!.payload)!!
        assertEquals("hola", String(udp.payload))
        assertEquals(42000, udp.destinationPort)
    }
}
