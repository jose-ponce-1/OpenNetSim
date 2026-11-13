package com.opennetsim.core.tcp

import com.opennetsim.core.packet.TcpOptions
import com.opennetsim.core.packet.TcpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TcpConnectionTest {

    private class RecordingOutput : TcpConnectionOutput {
        data class Sent(val segment: TcpPacket, val kind: SegmentKind, val retransmission: Boolean)

        val sent = mutableListOf<Sent>()
        val written = ByteArrayOutputStream()
        var pendingAfterWrite = 0
        var shutdownCalled = false
        var closed = false

        override fun sendToClient(segment: TcpPacket, kind: SegmentKind, isRetransmission: Boolean) {
            sent += Sent(segment, kind, isRetransmission)
        }

        override fun writeToServer(data: ByteArray): Int {
            written.write(data)
            return pendingAfterWrite
        }

        override fun shutdownServerOutput() {
            shutdownCalled = true
        }

        override fun closeConnection() {
            closed = true
        }

        fun last(): TcpPacket = sent.last().segment
        fun clear() = sent.clear()
    }

    private val clientPort = 50000
    private val serverPort = 443
    private val clientIsn = 1_000L
    private val serverIsn = 5_000L

    private val output = RecordingOutput()
    private var now = 0L

    private fun newConnection(
        config: TcpConnectionConfig = TcpConnectionConfig(initialRtoMs = 400L, maxInFlightBytes = 1_000_000),
        windowScale: Int? = 6,
        clientWindow: Int = 65535
    ): TcpConnection {
        val options = TcpOptions(mss = 1460, windowScale = windowScale).encode()
        val syn = TcpPacket(
            sourcePort = clientPort,
            destinationPort = serverPort,
            sequenceNumber = clientIsn,
            acknowledgmentNumber = 0L,
            dataOffset = 5 + options.size / 4,
            flags = TcpPacket.FLAG_SYN,
            windowSize = clientWindow,
            options = options
        )
        return TcpConnection(syn, isIpv6 = false, serverIsn = serverIsn, config = config, output = output, nowMs = now)
    }

    private fun clientSegment(
        seq: Long,
        ack: Long,
        flags: Int = TcpPacket.FLAG_ACK,
        payload: ByteArray = ByteArray(0),
        window: Int = 65535
    ) = TcpPacket(
        sourcePort = clientPort,
        destinationPort = serverPort,
        sequenceNumber = seq and 0xFFFFFFFFL,
        acknowledgmentNumber = ack and 0xFFFFFFFFL,
        flags = flags,
        windowSize = window,
        payload = payload
    )

    /** Conexión establecida: SYN-ACK emitido y confirmado por el cliente. */
    private fun establishedConnection(
        config: TcpConnectionConfig = TcpConnectionConfig(initialRtoMs = 400L, maxInFlightBytes = 1_000_000),
        clientWindow: Int = 65535,
        windowScale: Int? = 6
    ): TcpConnection {
        val connection = newConnection(config, windowScale, clientWindow)
        connection.onUpstreamConnected(now)
        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1, window = clientWindow), now)
        assertTrue(connection.established)
        output.clear()
        return connection
    }

    // ------------------------------------------------------------------ Handshake

    @Test
    fun lostSynAckIsRetransmittedWithSameIsn() {
        val connection = newConnection()
        connection.onUpstreamConnected(now)
        val synAck = output.last()
        assertTrue(synAck.isSyn && synAck.isAck)
        assertEquals(serverIsn, synAck.sequenceNumber)
        assertEquals(clientIsn + 1, synAck.acknowledgmentNumber)

        now = 399L
        connection.onTimer(now)
        assertEquals("Aún no vence el RTO", 1, output.sent.size)

        now = 400L
        connection.onTimer(now)
        val retransmitted = output.sent.last()
        assertTrue(retransmitted.retransmission)
        assertTrue(retransmitted.segment.isSyn && retransmitted.segment.isAck)
        assertEquals(serverIsn, retransmitted.segment.sequenceNumber)

        // El cliente confirma: la conexión se establece sin esperar a que reintente su SYN.
        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1), 450L)
        assertTrue(connection.established)
    }

    @Test
    fun duplicateSynResendsOriginalSynAckWithoutNewConnection() {
        val connection = newConnection()
        connection.onUpstreamConnected(now)
        output.clear()

        val result = connection.onClientSegment(
            TcpPacket(clientPort, serverPort, clientIsn, 0L, flags = TcpPacket.FLAG_SYN),
            nowMs = 200L
        )
        assertEquals(ClientSegmentResult.HANDLED, result)
        val resent = output.last()
        assertTrue(resent.isSyn && resent.isAck)
        assertEquals(serverIsn, resent.sequenceNumber)
        assertFalse(output.closed)

        val otherSyn = TcpPacket(clientPort, serverPort, clientIsn + 77_777L, 0L, flags = TcpPacket.FLAG_SYN)
        assertEquals(ClientSegmentResult.NEW_CONNECTION_REQUESTED, connection.onClientSegment(otherSyn, 300L))
    }

    @Test
    fun synAckAdvertisesOptions() {
        newConnection(windowScale = 6).onUpstreamConnected(now)
        val options = output.last().parsedOptions
        assertEquals(1460, options.mss)
        assertEquals(TcpConnectionConfig.DEFAULT_WINDOW_SCALE, options.windowScale)
    }

    // ------------------------------------------------------------------ Cierre y FIN

    @Test
    fun finWithPayloadDeliversDataAndAcksPayloadPlusFin() {
        val connection = establishedConnection()
        val data = ByteArray(200) { it.toByte() }

        connection.onClientSegment(
            clientSegment(clientIsn + 1, serverIsn + 1, TcpPacket.FLAG_ACK or TcpPacket.FLAG_FIN, data),
            now
        )

        assertArrayEquals(data, output.written.toByteArray())
        assertTrue(output.shutdownCalled)
        assertEquals(clientIsn + 1 + 200 + 1, output.last().acknowledgmentNumber)
    }

    @Test
    fun finArrivingBeforeQueuedDataIsHeldUntilGapIsFilled() {
        val connection = establishedConnection()
        val data = ByteArray(100) { 7 }
        val dataSeq = clientIsn + 1

        // El FIN (sin datos) llega antes que el último segmento de datos.
        connection.onClientSegment(
            clientSegment(dataSeq + 100, serverIsn + 1, TcpPacket.FLAG_ACK or TcpPacket.FLAG_FIN),
            now
        )
        assertFalse("No se cierra la mitad de envío con un hueco", output.shutdownCalled)
        assertEquals(0, output.written.size())
        assertEquals(dataSeq, output.last().acknowledgmentNumber)

        connection.onClientSegment(clientSegment(dataSeq, serverIsn + 1, payload = data), now)
        assertArrayEquals("Ningún byte se descarta como duplicado", data, output.written.toByteArray())
        assertTrue(output.shutdownCalled)
        assertEquals(dataSeq + 100 + 1, output.last().acknowledgmentNumber)
    }

    @Test
    fun lostTailSegmentIsRetransmittedBeforeClosing() {
        val connection = establishedConnection()
        val segmentSize = connection.segmentSize
        val response = ByteArray(segmentSize * 3) { (it % 251).toByte() }

        connection.onServerData(response, now)
        connection.onServerEof(now)
        val dataSegments = output.sent.filter { it.segment.payload.isNotEmpty() }
        assertEquals(3, dataSegments.size)
        val fin = output.sent.last().segment
        assertTrue(fin.isFin)
        assertEquals(SegmentKind.DATA, output.sent.last().kind)

        // El cliente recibe los dos primeros segmentos; el tercero se pierde.
        val secondEnd = serverIsn + 1 + 2L * segmentSize
        now = 100L
        connection.onClientSegment(clientSegment(clientIsn + 1, secondEnd), now)
        output.clear()

        // Vence el RTO: se retransmite el último segmento de datos.
        connection.onTimer(now + 2_000L)
        val retransmitted = output.sent.single()
        assertTrue(retransmitted.retransmission)
        assertEquals(secondEnd, retransmitted.segment.sequenceNumber)
        assertFalse("La sesión sigue viva hasta confirmar datos y FIN", output.closed)

        // El cliente confirma datos y FIN, y envía su propio FIN: cierre ordenado.
        val finEnd = serverIsn + 1 + 3L * segmentSize + 1
        connection.onClientSegment(clientSegment(clientIsn + 1, finEnd), now + 2_100L)
        assertFalse(output.closed)
        connection.onClientSegment(
            clientSegment(clientIsn + 1, finEnd, TcpPacket.FLAG_ACK or TcpPacket.FLAG_FIN),
            now + 2_150L
        )
        assertTrue(output.closed)
    }

    @Test
    fun lostFinIsRetransmitted() {
        val connection = establishedConnection()
        connection.onServerEof(now)
        output.clear()

        connection.onTimer(now + 400L)
        val retransmitted = output.sent.single()
        assertTrue(retransmitted.retransmission)
        assertTrue(retransmitted.segment.isFin)
        assertEquals(serverIsn + 1, retransmitted.segment.sequenceNumber)

        connection.onClientSegment(
            clientSegment(clientIsn + 1, serverIsn + 2, TcpPacket.FLAG_ACK or TcpPacket.FLAG_FIN),
            now + 500L
        )
        assertTrue("Con ambos FIN confirmados la conexión termina", output.closed)
    }

    // ------------------------------------------------------------------ Inactividad y RTO

    @Test
    fun idleSessionIsResetAfterTimeout() {
        val connection = establishedConnection()
        connection.onTimer(TcpConnectionConfig.DEFAULT_ESTABLISHED_IDLE_TIMEOUT_MS - 1)
        assertFalse(output.closed)

        connection.onTimer(TcpConnectionConfig.DEFAULT_ESTABLISHED_IDLE_TIMEOUT_MS)
        val rst = output.last()
        assertTrue(rst.isRst && rst.isAck)
        assertEquals(serverIsn + 1, rst.sequenceNumber)
        assertEquals(clientIsn + 1, rst.acknowledgmentNumber)
        assertTrue(output.closed)
    }

    @Test
    fun responseAfterIdleIsNotRetransmittedEarly() {
        // Handshake confirmado en el mismo instante: muestra de RTT 0 → RTO mínimo (200 ms).
        val connection = establishedConnection()
        val rtoMs = RtoEstimator.DEFAULT_MIN_RTO_MS
        now = 5_000L
        connection.onTimer(now) // Varios segundos sin nada en vuelo

        connection.onServerData(ByteArray(500), now)
        output.clear()

        connection.onTimer(now + 2L)
        connection.onTimer(now + rtoMs - 1)
        assertTrue("Sin retransmisiones antes del RTO medido desde el envío", output.sent.isEmpty())

        connection.onTimer(now + rtoMs)
        assertTrue(output.sent.single().retransmission)
    }

    @Test
    fun highLatencyProfileProducesNoTimeoutRetransmissions() {
        val config = TcpConnectionConfig(
            initialRtoMs = TcpWindowPolicy.initialRtoMs(latencyMs = 600, jitterMs = 20),
            maxInFlightBytes = TcpWindowPolicy.bdpCapBytes(10_000, 600, 20)
        )
        // El handshake también sufre los 600 ms de RTT simulado.
        val connection = newConnection(config)
        connection.onUpstreamConnected(now)
        now += 600L
        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1), now)
        assertTrue(connection.established)
        output.clear()
        var acked = serverIsn + 1

        repeat(20) {
            val chunk = ByteArray(32 * 1024)
            connection.onServerData(chunk, now)
            val sentAt = now
            // Revisar temporizadores mientras la confirmación viaja (600 ms de RTT)
            for (t in sentAt + 50L until sentAt + 600L step 50L) connection.onTimer(t)
            now = sentAt + 600L
            acked += chunk.size
            connection.onClientSegment(clientSegment(clientIsn + 1, acked), now)
        }

        assertTrue("No debe haber retransmisiones por timeout", output.sent.none { it.retransmission })
        assertEquals(0L, connection.retransmittedBytes)
    }

    // ------------------------------------------------------------------ Ventanas

    @Test
    fun smallClientWindowLimitsBytesInFlight() {
        val connection = establishedConnection(clientWindow = 8192, windowScale = null)
        assertEquals(8192, connection.availableSendWindow())

        connection.onServerData(ByteArray(8192), now)
        assertEquals(0, connection.availableSendWindow())

        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1 + 4096, window = 8192), now + 50L)
        assertEquals(4096, connection.availableSendWindow())
    }

    @Test
    fun inFlightCapFollowsConfiguredBdp() {
        val connection = establishedConnection(
            config = TcpConnectionConfig(maxInFlightBytes = 100_000),
            clientWindow = 65535,
            windowScale = 6
        )
        // Ventana del cliente escalada: 65535 << 6 ≈ 4 MB; el tope BDP manda.
        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1, window = 65535), now)
        assertEquals(100_000, connection.availableSendWindow())
        connection.maxInFlightBytes = 200_000
        assertEquals(200_000, connection.availableSendWindow())
    }

    @Test
    fun windowUpdateIsSentAfterZeroWindow() {
        val connection = establishedConnection(windowScale = null)
        output.pendingAfterWrite = TcpConnectionConfig.DEFAULT_MAX_PENDING_WRITE_BYTES

        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1, payload = ByteArray(1000)), now)
        assertEquals("Búfer de escritura lleno: ventana cero", 0, output.last().windowSize)
        output.clear()

        connection.onServerWriteProgress(pendingBytes = 0)
        val update = output.sent.single().segment
        assertTrue(update.isAck)
        assertTrue("Actualización de ventana distinta de cero", update.windowSize > 0)
        assertEquals(clientIsn + 1 + 1000, update.acknowledgmentNumber)
    }

    @Test
    fun zeroClientWindowTriggersWindowProbe() {
        val connection = establishedConnection(windowScale = null)
        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1, window = 0), now)
        assertEquals(0, connection.availableSendWindow())
        output.clear()

        connection.onTimer(now + 400L)
        val probe = output.sent.single().segment
        assertTrue(probe.isAck)
        assertEquals("La sonda usa una secuencia ya confirmada", serverIsn, probe.sequenceNumber)
    }

    @Test
    fun clientRstClosesWithoutReplying() {
        val connection = establishedConnection()
        connection.onClientSegment(clientSegment(clientIsn + 1, serverIsn + 1, TcpPacket.FLAG_RST), now)
        assertTrue(output.closed)
        assertTrue(output.sent.isEmpty())
    }

    @Test
    fun sequenceNumbersWrapAround32Bits() {
        val nearWrapIsn = 0xFFFFFFF0L
        val options = TcpOptions(mss = 1460).encode()
        val syn = TcpPacket(
            sourcePort = clientPort,
            destinationPort = serverPort,
            sequenceNumber = nearWrapIsn,
            acknowledgmentNumber = 0L,
            dataOffset = 5 + options.size / 4,
            flags = TcpPacket.FLAG_SYN,
            options = options
        )
        val connection = TcpConnection(syn, false, serverIsn, TcpConnectionConfig(), output, now)
        connection.onUpstreamConnected(now)
        connection.onClientSegment(clientSegment(nearWrapIsn + 1, serverIsn + 1), now)

        val data = ByteArray(64) { 1 }
        connection.onClientSegment(clientSegment(nearWrapIsn + 1, serverIsn + 1, payload = data), now)
        assertEquals((nearWrapIsn + 1 + 64) and 0xFFFFFFFFL, output.last().acknowledgmentNumber)
        assertEquals(64, output.written.size())
    }
}
