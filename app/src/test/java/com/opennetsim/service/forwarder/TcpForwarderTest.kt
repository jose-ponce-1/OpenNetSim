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
import org.junit.Assert.assertTrue
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
            socketProtector = protector,
            coroutineScope = scope,
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
        // 1. Cliente envía SYN
        val syn = TcpPacket(
            sourcePort = clientPort,
            destinationPort = serverPort,
            sequenceNumber = 1000L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_SYN,
            windowSize = 65535
        ).toIpPacket(clientIp, serverIp)

        forwarder.processOutbound(syn)

        // Servidor local acepta la conexión
        val acceptedSocket = serverSocket.accept()
        assertNotNull(acceptedSocket)

        // Esperar a que forwarder emita SYN-ACK hacia el TUN
        var synAckPacket: TcpPacket? = null
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            val last = inboundTunPackets.lastOrNull()
            if (last != null) {
                val ip = IpPacket.parse(last)
                if (ip != null) {
                    val tcp = TcpPacket.parse(ip.payload)
                    if (tcp != null && tcp.isSyn && tcp.isAck) {
                        synAckPacket = tcp
                        break
                    }
                }
            }
            Thread.sleep(20)
        }
        assertNotNull("Debe recibir SYN-ACK", synAckPacket)
        assertEquals(1001L, synAckPacket!!.acknowledgmentNumber)

        // 2. Cliente envía Segmento 2 PRIMERO (out-of-order: seq = 1007, payload = 'World!')
        val seg2Data = "World!".toByteArray()
        val seg2 = TcpPacket(
            sourcePort = clientPort,
            destinationPort = serverPort,
            sequenceNumber = 1007L,
            acknowledgmentNumber = synAckPacket.sequenceNumber + 1,
            flags = TcpPacket.FLAG_ACK,
            windowSize = 65535,
            payload = seg2Data
        ).toIpPacket(clientIp, serverIp)

        inboundTunPackets.clear()
        forwarder.processOutbound(seg2)

        // Debe emitir un ACK repetido con ackNum = 1001 indicando el hueco
        Thread.sleep(100)
        val gapAckRaw = inboundTunPackets.lastOrNull()
        assertNotNull(gapAckRaw)
        val gapAck = TcpPacket.parse(IpPacket.parse(gapAckRaw!!)!!.payload)!!
        assertEquals(1001L, gapAck.acknowledgmentNumber)

        // 3. Ahora el cliente envía el Segmento 1 faltante (seq = 1001, payload = 'Hello ')
        val seg1Data = "Hello ".toByteArray()
        val seg1 = TcpPacket(
            sourcePort = clientPort,
            destinationPort = serverPort,
            sequenceNumber = 1001L,
            acknowledgmentNumber = synAckPacket.sequenceNumber + 1,
            flags = TcpPacket.FLAG_ACK,
            windowSize = 65535,
            payload = seg1Data
        ).toIpPacket(clientIp, serverIp)

        inboundTunPackets.clear()
        forwarder.processOutbound(seg1)

        // 4. El servidor físico debe leer ambos segmentos reensamblados en orden contiguo: 'Hello World!'
        val inputStream: InputStream = acceptedSocket.getInputStream()
        val receivedBuffer = ByteArray(12)
        var totalRead = 0
        while (totalRead < 12 && System.currentTimeMillis() < deadline) {
            val r = inputStream.read(receivedBuffer, totalRead, 12 - totalRead)
            if (r > 0) totalRead += r
            else break
        }
        assertEquals(12, totalRead)
        assertEquals("Hello World!", String(receivedBuffer))

        // 5. El forwarder debe emitir un ACK acumulativo por toda la secuencia (1001 + 6 + 6 = 1013)
        Thread.sleep(100)
        val cumulativeAckRaw = inboundTunPackets.lastOrNull()
        assertNotNull(cumulativeAckRaw)
        val cumulativeAck = TcpPacket.parse(IpPacket.parse(cumulativeAckRaw!!)!!.payload)!!
        assertEquals(1013L, cumulativeAck.acknowledgmentNumber)

        // 6. Si se retransmite el Segmento 1 duplicado, no debe inyectarse al servidor
        inboundTunPackets.clear()
        forwarder.processOutbound(seg1)
        Thread.sleep(100)
        val dupAckRaw = inboundTunPackets.lastOrNull()
        assertNotNull(dupAckRaw)
        val dupAck = TcpPacket.parse(IpPacket.parse(dupAckRaw!!)!!.payload)!!
        assertEquals(1013L, dupAck.acknowledgmentNumber)
        assertEquals("No debe haber datos adicionales en el socket", 0, inputStream.available())

        acceptedSocket.close()
    }
}

