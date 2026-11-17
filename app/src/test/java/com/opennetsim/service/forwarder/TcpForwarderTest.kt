package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.TcpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class TcpForwarderTest {

    private lateinit var serverSocket: ServerSocket
    private var serverPort: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inboundTunPackets = CopyOnWriteArrayList<ByteArray>()
    private lateinit var forwarder: TcpForwarder

    private val protector = object : VpnSocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val serverIp = byteArrayOf(127, 0, 0, 1)
    private val clientPort = 52345

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverPort = serverSocket.localPort

        forwarder = TcpForwarder(
            environment = ForwarderEnvironment(
                socketProtector = protector,
                coroutineScope = scope,
                strictThreadChecks = true
            ),
            onInboundPacket = { inboundTunPackets.add(it) }
        )
    }

    @After
    fun tearDown() {
        forwarder.close()
        scope.cancel()
        serverSocket.close()
    }

    @Test
    fun testOutOfOrderSegmentBufferingAndAssembly() {
        // 1. Cliente envía SYN y el servidor local acepta la conexión.
        forwarder.processOutbound(clientSegment(seq = 1000L, ack = 0L, flags = TcpPacket.FLAG_SYN))
        val acceptedSocket = serverSocket.accept()
        assertNotNull(acceptedSocket)

        val synAck = awaitSynAck()
        assertNotNull("Debe recibir SYN-ACK", synAck)
        assertEquals(1001L, synAck!!.acknowledgmentNumber)
        val serverAck = synAck.sequenceNumber + 1

        // 2. Cliente envía Segmento 2 PRIMERO (out-of-order: seq = 1007, payload = 'World!')
        val seg2 = clientSegment(seq = 1007L, ack = serverAck, payload = "World!".toByteArray())
        // Debe emitir un ACK repetido con ackNum = 1001 indicando el hueco
        assertEquals(1001L, sendAndAwaitAck(seg2))

        // 3. Ahora el cliente envía el Segmento 1 faltante (seq = 1001, payload = 'Hello ')
        val seg1 = clientSegment(seq = 1001L, ack = serverAck, payload = "Hello ".toByteArray())
        inboundTunPackets.clear()
        forwarder.processOutbound(seg1)

        // 4. El servidor físico debe leer ambos segmentos reensamblados en orden contiguo: 'Hello World!'
        val inputStream = acceptedSocket.getInputStream()
        assertEquals("Hello World!", String(readExactly(inputStream, 12)))

        // 5. El forwarder debe emitir un ACK acumulativo por toda la secuencia (1001 + 6 + 6 = 1013)
        Thread.sleep(100)
        assertEquals(1013L, lastEmittedAck())

        // 6. Si se retransmite el Segmento 1 duplicado, no debe inyectarse al servidor
        assertEquals(1013L, sendAndAwaitAck(seg1))
        assertEquals("No debe haber datos adicionales en el socket", 0, inputStream.available())

        acceptedSocket.close()
    }

    private fun clientSegment(
        seq: Long,
        ack: Long,
        flags: Int = TcpPacket.FLAG_ACK,
        payload: ByteArray = ByteArray(0)
    ): IpPacket = TcpPacket(
        sourcePort = clientPort,
        destinationPort = serverPort,
        sequenceNumber = seq,
        acknowledgmentNumber = ack,
        flags = flags,
        windowSize = 65535,
        payload = payload
    ).toIpPacket(clientIp, serverIp)

    /** Último segmento TCP emitido hacia el TUN, si lo hay. */
    private fun lastEmittedSegment(): TcpPacket? =
        inboundTunPackets.lastOrNull()?.let { IpPacket.parse(it) }?.let { TcpPacket.parse(it.payload) }

    private fun lastEmittedAck(): Long {
        val segment = lastEmittedSegment()
        assertNotNull("Debe emitir un ACK", segment)
        return segment!!.acknowledgmentNumber
    }

    /** Envía [segment] y devuelve el número de ACK con el que responde el forwarder. */
    private fun sendAndAwaitAck(segment: IpPacket): Long {
        inboundTunPackets.clear()
        forwarder.processOutbound(segment)
        Thread.sleep(100)
        return lastEmittedAck()
    }

    private fun awaitSynAck(timeoutMs: Long = 3000L): TcpPacket? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val segment = lastEmittedSegment()
            if (segment != null && segment.isSyn && segment.isAck) return segment
            Thread.sleep(20)
        }
        return null
    }

    private fun readExactly(input: InputStream, count: Int, timeoutMs: Long = 3000L): ByteArray {
        val buffer = ByteArray(count)
        val deadline = System.currentTimeMillis() + timeoutMs
        var totalRead = 0
        var lastRead = 1
        while (totalRead < count && lastRead > 0 && System.currentTimeMillis() < deadline) {
            lastRead = input.read(buffer, totalRead, count - totalRead)
            if (lastRead > 0) totalRead += lastRead
        }
        assertEquals(count, totalRead)
        return buffer
    }
}
