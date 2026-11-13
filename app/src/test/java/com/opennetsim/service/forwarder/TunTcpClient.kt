package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.TcpOptions
import com.opennetsim.core.packet.TcpPacket
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Cliente TCP mínimo que habla con [TcpForwarder] como lo haría una app a través del TUN.
 * Solo acepta datos en orden (como un receptor sin SACK) y respeta la ventana anunciada.
 */
@Suppress("LongParameterList") // Utilidad de test: todos los parámetros describen la conexión simulada.
class TunTcpClient(
    private val forwarder: TcpForwarder,
    private val inbound: LinkedBlockingQueue<ByteArray>,
    private val clientIp: ByteArray,
    private val serverIp: ByteArray,
    private val clientPort: Int,
    private val serverPort: Int,
    private val windowScale: Int? = 7,
    private val advertisedWindowField: Int = 65535
) {
    private val isn = 10_000L
    var sndNxt = isn + 1
        private set
    var sndUna = isn + 1
        private set
    var rcvNxt = 0L
        private set

    private var peerWindowShift = 0
    var peerWindowBytes = 0L
        private set

    val received = ByteArrayOutputStream()
    var finReceived = false
        private set
    var rstReceived: TcpPacket? = null
        private set

    fun connect(timeoutMs: Long = 3000L): TcpPacket {
        val options = TcpOptions(mss = 1460, windowScale = windowScale).encode()
        send(
            TcpPacket(
                sourcePort = clientPort,
                destinationPort = serverPort,
                sequenceNumber = isn,
                acknowledgmentNumber = 0L,
                dataOffset = 5 + options.size / 4,
                flags = TcpPacket.FLAG_SYN,
                windowSize = advertisedWindowField,
                options = options
            )
        )
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val segment = poll(50L) ?: continue
            if (segment.isSyn && segment.isAck) {
                rcvNxt = (segment.sequenceNumber + 1) and MASK_32
                sndUna = segment.acknowledgmentNumber
                if (windowScale != null) peerWindowShift = segment.parsedOptions.windowScale ?: 0
                peerWindowBytes = segment.windowSize.toLong()
                ack()
                return segment
            }
        }
        error("No llegó el SYN-ACK")
    }

    fun sendSegment(flags: Int, payload: ByteArray = ByteArray(0), seq: Long = sndNxt, ackNum: Long = rcvNxt) {
        send(
            TcpPacket(
                sourcePort = clientPort,
                destinationPort = serverPort,
                sequenceNumber = seq and 0xFFFFFFFFL,
                acknowledgmentNumber = ackNum and 0xFFFFFFFFL,
                flags = flags,
                windowSize = advertisedWindowField,
                payload = payload
            )
        )
        if (seq == sndNxt) sndNxt += payload.size + (if ((flags and TcpPacket.FLAG_FIN) != 0) 1 else 0)
    }

    fun ack() = sendSegment(TcpPacket.FLAG_ACK)

    fun sendFin() = sendSegment(TcpPacket.FLAG_ACK or TcpPacket.FLAG_FIN)

    /** Envía [data] respetando la ventana anunciada por el forwarder. */
    fun upload(data: ByteArray, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var offset = 0
        while (offset < data.size) {
            check(System.currentTimeMillis() < deadline) { "Subida sin completar: $offset/${data.size}" }
            val inFlight = sndNxt - sndUna
            val room = peerWindowBytes - inFlight
            if (room <= 0) {
                poll(20L)
                continue
            }
            val length = minOf(MSS.toLong(), room, (data.size - offset).toLong()).toInt()
            sendSegment(TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH, data.copyOfRange(offset, offset + length))
            offset += length
            while (poll(0L) != null) Unit
        }
        while (sndUna < sndNxt && System.currentTimeMillis() < deadline) poll(20L)
    }

    /** Recibe datos (confirmando cada segmento) hasta el FIN del servidor. */
    fun receiveUntilFin(timeoutMs: Long = 10_000L, ackData: Boolean = true) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!finReceived && rstReceived == null && System.currentTimeMillis() < deadline) {
            val segment = poll(50L) ?: continue
            if (ackData && (segment.payload.isNotEmpty() || segment.isFin)) ack()
        }
    }

    /** Espera un RST durante [timeoutMs]. */
    fun awaitRst(timeoutMs: Long): TcpPacket? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (rstReceived == null && System.currentTimeMillis() < deadline) poll(50L)
        return rstReceived
    }

    /** Lee un segmento del TUN y actualiza el estado del cliente. */
    fun poll(timeoutMs: Long): TcpPacket? {
        val raw = inbound.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return null
        val segment = TcpPacket.parse(IpPacket.parse(raw)!!.payload)!!
        if (segment.isRst) rstReceived = segment else absorb(segment)
        return segment
    }

    private fun absorb(segment: TcpPacket) {
        if (segment.isAck && !segment.isSyn) {
            if (segment.acknowledgmentNumber > sndUna) sndUna = segment.acknowledgmentNumber
            peerWindowBytes = segment.windowSize.toLong() shl peerWindowShift
        }
        if (!segment.isSyn && segment.sequenceNumber == rcvNxt) {
            if (segment.payload.isNotEmpty()) {
                received.write(segment.payload)
                rcvNxt = (rcvNxt + segment.payload.size) and MASK_32
            }
            if (segment.isFin) {
                rcvNxt = (rcvNxt + 1) and MASK_32
                finReceived = true
            }
        }
    }

    private fun send(segment: TcpPacket) {
        forwarder.processOutbound(segment.toIpPacket(clientIp, serverIp))
    }

    companion object {
        const val MSS = 1400
        private const val MASK_32 = 0xFFFFFFFFL
    }
}
