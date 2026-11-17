package com.opennetsim.service.pipeline

import com.opennetsim.core.packet.IcmpPacket
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.UdpPacket
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.service.forwarder.ForwarderEnvironment
import com.opennetsim.service.forwarder.VpnSocketProtector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** TUN en memoria: el test inyecta paquetes "de las apps" y observa lo que el pipeline escribe. */
private class FakeTunDevice : TunDevice {
    val fromApps = LinkedBlockingQueue<ByteArray>()
    val toApps = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var failReads = false

    @Volatile
    var closed = false

    override fun read(buffer: ByteArray): Int {
        if (closed) throw IOException("TUN cerrado")
        if (failReads) throw IOException("Descriptor inválido")
        val packet = fromApps.poll(20, TimeUnit.MILLISECONDS) ?: return 0
        packet.copyInto(buffer)
        return packet.size
    }

    override fun write(packet: ByteArray) {
        if (closed) throw IOException("TUN cerrado")
        toApps.add(packet)
    }

    override fun close() {
        closed = true
    }
}

private class RecordingListener : TunnelPipeline.Listener {
    val fatalErrors = CopyOnWriteArrayList<String>()
    val telemetry = CopyOnWriteArrayList<NetworkTelemetry>()

    override fun onFatalError(message: String) {
        fatalErrors += message
    }

    override fun onTelemetry(telemetry: NetworkTelemetry) {
        this.telemetry += telemetry
    }
}

class TunnelPipelineTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tun = FakeTunDevice()
    private val listener = RecordingListener()
    private lateinit var echoServer: DatagramSocket
    private lateinit var pipeline: TunnelPipeline

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val loopback = byteArrayOf(127, 0, 0, 1)

    private val protector = object : VpnSocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    /** 200 ms de RTT (100 ms por dirección), sin pérdida y con ancho de banda holgado. */
    private val config = ThrottleConfig(
        downKbps = 10_000,
        upKbps = 10_000,
        latencyMs = 200,
        jitterMs = 0,
        packetLossPercent = 0f
    )

    @Before
    fun setUp() {
        echoServer = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        Thread {
            val buffer = ByteArray(2048)
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
        pipeline = TunnelPipeline(
            tun = tun,
            initialConfig = config,
            environment = ForwarderEnvironment(socketProtector = protector, coroutineScope = scope),
            listener = listener
        )
        pipeline.start()
    }

    @After
    fun tearDown() {
        pipeline.close()
        scope.cancel()
        echoServer.close()
    }

    @Test
    fun udpDatagramRoundTripsThroughTheTunnelWithConfiguredLatency() {
        val payload = "consulta".toByteArray()
        val request = UdpPacket(40_000, echoServer.localPort, UdpPacket.HEADER_SIZE + payload.size, payload = payload)
            .toIpPacket(clientIp, loopback)
            .toByteArray()

        val sentAt = System.nanoTime()
        tun.fromApps.add(request)
        val response = tun.toApps.poll(3, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - sentAt) / 1_000_000L

        assertNotNull("La respuesta vuelve a las apps por el TUN", response)
        val udp = UdpPacket.parse(IpPacket.parse(response!!)!!.payload)!!
        assertArrayEquals(payload, udp.payload)
        assertEquals(40_000, udp.destinationPort)
        assertTrue("RTT de $elapsedMs ms: debe incluir los 200 ms configurados", elapsedMs >= 190L)
    }

    @Test
    fun pingIsAnsweredLocallyWithConfiguredLatency() {
        val echo = IcmpPacket(
            type = IcmpPacket.TYPE_ECHO_REQUEST_V4,
            code = 0,
            identifier = 7,
            sequenceNumber = 1,
            payload = ByteArray(16)
        )
        val icmpBytes = echo.toByteArray(isIpv6 = false)
        val request = IpPacket(
            version = 4,
            ihl = 5,
            totalLength = 20 + icmpBytes.size,
            protocol = IpPacket.PROTOCOL_ICMP,
            sourceIp = clientIp,
            destinationIp = byteArrayOf(8, 8, 8, 8),
            payload = icmpBytes
        ).toByteArray()

        val sentAt = System.nanoTime()
        tun.fromApps.add(request)
        val reply = tun.toApps.poll(3, TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - sentAt) / 1_000_000L

        assertNotNull(reply)
        val icmp = IcmpPacket.parse(IpPacket.parse(reply!!)!!.payload)!!
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V4, icmp.type)
        assertTrue("RTT de $elapsedMs ms", elapsedMs >= 190L)
    }

    @Test
    fun tunReadFailureIsReportedOnceAsFatal() {
        tun.failReads = true
        val deadline = System.nanoTime() + 2_000_000_000L
        while (listener.fatalErrors.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10L)

        assertEquals(listOf("Error leyendo el descriptor del TUN"), listener.fatalErrors)
        Thread.sleep(100L)
        assertEquals("Un solo aviso de fallo", 1, listener.fatalErrors.size)
    }

    @Test
    fun closeStopsLoopsClosesTunAndNeverReportsAFailure() {
        pipeline.close()
        pipeline.close() // Idempotente
        Thread.sleep(150L)

        assertTrue("El TUN queda cerrado", tun.closed)
        assertTrue("Cerrar no es un fallo del túnel", listener.fatalErrors.isEmpty())
    }

    @Test
    fun telemetryReportsConnectedTunnel() {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (listener.telemetry.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10L)
        assertTrue(listener.telemetry.first().isConnected)
    }
}
