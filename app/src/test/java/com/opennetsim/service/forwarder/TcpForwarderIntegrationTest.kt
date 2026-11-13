package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.TcpPacket
import com.opennetsim.core.tcp.TcpConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TcpForwarderIntegrationTest {

    private lateinit var serverSocket: ServerSocket
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val forwarders = mutableListOf<TcpForwarder>()
    private val serverThreads = mutableListOf<Thread>()

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val serverIp = byteArrayOf(127, 0, 0, 1)

    private val allowingProtector = object : VpnSocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    @Before
    fun setUp() {
        serverSocket = ServerSocket()
        serverSocket.receiveBufferSize = 8 * 1024
        serverSocket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }

    @After
    fun tearDown() {
        forwarders.forEach { it.close() }
        scope.cancel()
        serverSocket.close()
        serverThreads.forEach { it.interrupt() }
    }

    private fun forwarder(
        config: TcpConnectionConfig = TcpConnectionConfig(),
        protector: VpnSocketProtector = allowingProtector
    ): TcpForwarder = TcpForwarder(
        environment = ForwarderEnvironment(
            socketProtector = protector,
            coroutineScope = scope,
            strictThreadChecks = true
        ),
        onInboundPacket = { inbound.add(it) },
        initialTcpConfig = config
    ).also { forwarders += it }

    private fun client(forwarder: TcpForwarder, clientPort: Int, windowField: Int = 65535, windowScale: Int? = 7) =
        TunTcpClient(
            forwarder = forwarder,
            inbound = inbound,
            clientIp = clientIp,
            serverIp = serverIp,
            clientPort = clientPort,
            serverPort = serverSocket.localPort,
            windowScale = windowScale,
            advertisedWindowField = windowField
        )

    private fun serve(handler: (Socket) -> Unit) {
        serverThreads += Thread {
            try {
                serverSocket.accept().use(handler)
            } catch (ignored: IOException) {
                // Conexión cerrada por el test
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

    // ------------------------------------------------------------------ 6.1

    @Test
    fun requestAndOneMegabyteResponseArriveCompleteAndInOrder() {
        val response = pattern(1024 * 1024)
        val request = "GET /big HTTP/1.0\r\n\r\n".toByteArray()
        val receivedRequest = ByteArrayOutputStream()
        serve { socket ->
            val input = socket.getInputStream()
            val buffer = ByteArray(request.size)
            var read = 0
            while (read < request.size) {
                val n = input.read(buffer, read, request.size - read)
                if (n < 0) break
                read += n
            }
            receivedRequest.write(buffer, 0, read)
            socket.getOutputStream().write(response)
            socket.shutdownOutput()
        }

        val tcp = forwarder()
        val client = client(tcp, 50001)
        client.connect()
        client.sendSegment(TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH, request)
        client.receiveUntilFin(timeoutMs = 15_000L)

        assertTrue("Debe llegar el FIN del servidor", client.finReceived)
        assertArrayEquals(request, receivedRequest.toByteArray())
        assertEquals(response.size, client.received.size())
        assertArrayEquals("Respuesta completa y en orden", response, client.received.toByteArray())
    }

    // ------------------------------------------------------------------ 6.2

    @Test
    fun selectorDoesNotSpinWhileClientWindowIsFull() {
        serve { socket ->
            val out = socket.getOutputStream()
            val chunk = ByteArray(64 * 1024)
            repeat(64) { out.write(chunk) } // Mucho más de lo que cabe en la ventana del cliente
        }

        val tcp = forwarder(TcpConnectionConfig(initialRtoMs = 200L))
        val client = client(tcp, 50002, windowField = 16 * 1024, windowScale = null)
        client.connect()
        // El cliente nunca confirma: la ventana se llena y el forwarder debe pausar lecturas.
        Thread.sleep(500L)
        inbound.clear()

        val before = tcp.loopIterations.get()
        Thread.sleep(1_000L)
        val iterationsPerSecond = tcp.loopIterations.get() - before
        assertTrue("Iteraciones del selector por segundo: $iterationsPerSecond", iterationsPerSecond <= 250L)
    }

    @Test
    fun pausedReadsResumeWhenDownlinkSignalsCapacity() {
        serve { socket ->
            socket.getOutputStream().write(pattern(32 * 1024))
            Thread.sleep(3_000L)
        }
        val downlinkHasCapacity = java.util.concurrent.atomic.AtomicBoolean(false)
        val tcp = TcpForwarder(
            environment = ForwarderEnvironment(allowingProtector, scope, strictThreadChecks = true),
            onInboundPacket = { inbound.add(it) },
            canReadDownlink = { downlinkHasCapacity.get() }
        ).also { forwarders += it }
        val client = client(tcp, 50009)
        client.connect()

        // Sin capacidad en la bajada no se lee del servidor.
        Thread.sleep(400L)
        while (client.poll(0L) != null) Unit
        assertEquals("Lecturas pausadas", 0, client.received.size())

        downlinkHasCapacity.set(true)
        tcp.resumeReads()
        val deadline = System.currentTimeMillis() + 2_000L
        while (client.received.size() == 0 && System.currentTimeMillis() < deadline) client.poll(20L)
        assertTrue("Las lecturas se reanudan con la señal de capacidad", client.received.size() > 0)
    }

    // ------------------------------------------------------------------ 6.3

    @Test
    fun idleSessionIsResetAndExteriorSocketReleased() {
        var serverSawEof = false
        serve { socket ->
            serverSawEof = socket.getInputStream().read() < 0
        }

        val tcp = forwarder(TcpConnectionConfig(establishedIdleTimeoutMs = 300L))
        val client = client(tcp, 50003)
        client.connect()

        val rst = client.awaitRst(timeoutMs = 3_000L)
        assertNotNull("Una sesión inactiva debe recibir RST", rst)
        Thread.sleep(200L)
        assertTrue("El socket exterior se libera", serverSawEof)
    }

    @Test
    fun exteriorSocketErrorResetsClient() {
        serve { socket ->
            socket.setSoLinger(true, 0) // close() con RST
            socket.close()
        }

        val tcp = forwarder()
        val client = client(tcp, 50004)
        client.connect()
        val deadline = System.currentTimeMillis() + 3_000L
        while (client.rstReceived == null && System.currentTimeMillis() < deadline) {
            client.sendSegment(TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH, pattern(512))
            client.poll(100L)
        }
        assertNotNull("El cliente recibe RST tras el error del socket exterior", client.rstReceived)
    }

    @Test
    fun socketOpenFailureResetsClientInsteadOfHanging() {
        val refusingProtector = object : VpnSocketProtector {
            override fun protect(socket: Socket) = false
            override fun protect(socket: DatagramSocket) = false
        }
        val tcp = forwarder(protector = refusingProtector)
        val syn = TcpPacket(
            sourcePort = 50005,
            destinationPort = serverSocket.localPort,
            sequenceNumber = 777L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_SYN
        )
        tcp.processOutbound(syn.toIpPacket(clientIp, serverIp))

        val raw = inbound.poll(2, TimeUnit.SECONDS)
        assertNotNull(raw)
        val rst = TcpPacket.parse(IpPacket.parse(raw!!)!!.payload)!!
        assertTrue(rst.isRst && rst.isAck)
        assertEquals(778L, rst.acknowledgmentNumber)
    }

    @Test
    fun segmentForUnknownSessionIsAnsweredWithRst() {
        val tcp = forwarder()
        val stale = TcpPacket(
            sourcePort = 50006,
            destinationPort = serverSocket.localPort,
            sequenceNumber = 4_000L,
            acknowledgmentNumber = 9_999L,
            flags = TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH,
            payload = pattern(100)
        )
        tcp.processOutbound(stale.toIpPacket(clientIp, serverIp))

        val raw = inbound.poll(2, TimeUnit.SECONDS)
        assertNotNull("Debe responder en un RTT", raw)
        val rst = TcpPacket.parse(IpPacket.parse(raw!!)!!.payload)!!
        assertTrue(rst.isRst)
        assertFalse(rst.isAck)
        assertEquals(9_999L, rst.sequenceNumber)
    }

    // ------------------------------------------------------------------ 6.4

    @Test
    fun uploadToSlowServerCompletesWithinPendingWriteLimit() {
        val upload = pattern(1024 * 1024)
        val received = ByteArrayOutputStream()
        serve { socket ->
            val input = socket.getInputStream()
            val buffer = ByteArray(16 * 1024)
            while (received.size() < upload.size) {
                val n = input.read(buffer)
                if (n < 0) break
                received.write(buffer, 0, n)
                Thread.sleep(2L) // Servidor lento
            }
        }

        val tcp = forwarder()
        val client = client(tcp, 50007)
        client.connect()
        client.upload(upload, timeoutMs = 20_000L)

        val deadline = System.currentTimeMillis() + 5_000L
        while (received.size() < upload.size && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertArrayEquals("La subida llega completa", upload, received.toByteArray())
        val limit = TcpConnectionConfig.DEFAULT_MAX_PENDING_WRITE_BYTES + 2 * TunTcpClient.MSS
        assertTrue("Escrituras pendientes ${tcp.peakPendingWriteBytes} B", tcp.peakPendingWriteBytes <= limit)
    }

    // ------------------------------------------------------------------ 6.5

    @Test
    fun retransmissionsAreCounted() {
        serve { socket ->
            socket.getOutputStream().write(pattern(10 * 1024))
            Thread.sleep(3_000L)
        }

        val tcp = forwarder(TcpConnectionConfig(initialRtoMs = 200L))
        val client = client(tcp, 50008)
        client.connect()
        // Sin confirmar los datos: vence el RTO y el forwarder retransmite.
        val deadline = System.currentTimeMillis() + 3_000L
        while (tcp.retransmittedBytes() == 0L && System.currentTimeMillis() < deadline) {
            client.poll(50L)
        }
        assertTrue("Contador de retransmisiones: ${tcp.retransmittedBytes()}", tcp.retransmittedBytes() > 0L)
    }
}
