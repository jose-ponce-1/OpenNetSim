package com.opennetsim.core.tcp

import com.opennetsim.core.packet.TcpOptions
import com.opennetsim.core.packet.TcpPacket
import kotlin.math.min

/**
 * Parámetros de una conexión TCP terminada en espacio de usuario.
 */
data class TcpConnectionConfig(
    val mtu: Int = DEFAULT_MTU,
    val ourWindowScale: Int = DEFAULT_WINDOW_SCALE,
    val maxPendingWriteBytes: Int = DEFAULT_MAX_PENDING_WRITE_BYTES,
    val maxOutOfOrderBytes: Int = DEFAULT_MAX_OUT_OF_ORDER_BYTES,
    val maxInFlightBytes: Int = TcpWindowPolicy.MIN_IN_FLIGHT_BYTES,
    val initialRtoMs: Long = RtoEstimator.DEFAULT_INITIAL_RTO_MS,
    val establishedIdleTimeoutMs: Long = DEFAULT_ESTABLISHED_IDLE_TIMEOUT_MS,
    val closingIdleTimeoutMs: Long = DEFAULT_CLOSING_IDLE_TIMEOUT_MS,
    val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    val maxRetransmissions: Int = DEFAULT_MAX_RETRANSMISSIONS
) {
    companion object {
        const val DEFAULT_MTU = 1500
        const val DEFAULT_WINDOW_SCALE = 7
        const val DEFAULT_MAX_PENDING_WRITE_BYTES = 256 * 1024
        const val DEFAULT_MAX_OUT_OF_ORDER_BYTES = 256 * 1024
        const val DEFAULT_ESTABLISHED_IDLE_TIMEOUT_MS = 600_000L // 10 min
        const val DEFAULT_CLOSING_IDLE_TIMEOUT_MS = 60_000L
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_RETRANSMISSIONS = 10
    }
}

/**
 * Clase de un segmento emitido hacia el cliente:
 * - [CONTROL]: ACK puro, SYN-ACK, RST, actualización de ventana o sonda; puede adelantar a datos de
 *   otros flujos.
 * - [DATA]: datos o FIN; debe respetar el orden del flujo.
 */
enum class SegmentKind { CONTROL, DATA }

/**
 * Efectos de [TcpConnection] que el forwarder traduce a I/O real.
 */
interface TcpConnectionOutput {
    fun sendToClient(segment: TcpPacket, kind: SegmentKind, isRetransmission: Boolean)

    /** Escribe en el socket exterior y devuelve los bytes que quedan pendientes de escribir. */
    fun writeToServer(data: ByteArray): Int

    fun shutdownServerOutput()

    /** La conexión terminó (cierre ordenado o RST ya emitido): liberar el socket y la sesión. */
    fun closeConnection()
}

enum class ClientSegmentResult { HANDLED, NEW_CONNECTION_REQUESTED }

/**
 * Máquina de estados TCP de una conexión cliente (TUN) ↔ servidor exterior, en Kotlin puro.
 *
 * Recibe eventos (segmentos del cliente, datos/EOF del servidor, temporizadores) y emite efectos
 * por [TcpConnectionOutput]. Todo el estado usa secuencias absolutas ([SequenceSpace]).
 * No es thread-safe: el forwarder la usa desde un único hilo.
 */
@Suppress("TooManyFunctions")
class TcpConnection(
    clientSyn: TcpPacket,
    private val isIpv6: Boolean,
    serverIsn: Long,
    private val config: TcpConnectionConfig,
    private val output: TcpConnectionOutput,
    nowMs: Long
) {
    private val clientPort = clientSyn.sourcePort
    private val serverPort = clientSyn.destinationPort
    private val clientIsn = clientSyn.sequenceNumber
    private val clientOptions = clientSyn.parsedOptions

    private val windowScalingEnabled = clientOptions.windowScale != null
    private val clientWindowShift = clientOptions.windowScale ?: 0
    private val ourWindowShift = if (windowScalingEnabled) config.ourWindowScale else 0

    /** Tamaño de segmento hacia el cliente: mínimo entre su MSS y el nuestro. */
    val segmentSize: Int = min(
        clientOptions.mss ?: defaultClientMss(isIpv6),
        TcpOptions.mssForMtu(config.mtu, isIpv6)
    )

    private val rto = RtoEstimator(initialRtoMs = config.initialRtoMs)
    private val retransmissions = TcpRetransmissionQueue(rto)
    private val receiveBuffer = TcpReceiveBuffer(clientIsn + 1, config.maxOutOfOrderBytes)

    private var sndUna: Long = serverIsn
    private var sndNxt: Long = serverIsn
    private var clientWindowBytes: Long = clientSyn.windowSize.toLong() // El SYN nunca va escalado.
    private var pendingWriteBytes = 0
    private var lastAdvertisedWindowBytes = 0
    private var dupAckCount = 0
    private var persistDeadlineMs: Long? = null

    private val createdAtMs = nowMs
    private var lastActivityMs = nowMs
    private var synAckSent = false
    private var serverFinSent = false
    private var serverFinAcked = false

    /** Tope de bytes en vuelo hacia el cliente; se actualiza al cambiar la configuración. */
    var maxInFlightBytes: Int = config.maxInFlightBytes

    var established: Boolean = false
        private set

    var isClosed: Boolean = false
        private set

    /** Bytes de payload retransmitidos hacia el cliente (para telemetría). */
    var retransmittedBytes: Long = 0L
        private set

    // ---------------------------------------------------------------- Eventos del servidor exterior

    /** El socket exterior terminó de conectar: emitir el SYN-ACK. */
    fun onUpstreamConnected(nowMs: Long) {
        if (synAckSent || isClosed) return
        synAckSent = true
        val segment = OutboundSegment(seq = sndNxt, payload = EMPTY, syn = true, sentAtMs = nowMs)
        sndNxt += 1
        retransmissions.add(segment, nowMs)
        emit(segment, isRetransmission = false)
    }

    /** Datos leídos del socket exterior: se segmentan y se envían al cliente. */
    fun onServerData(data: ByteArray, nowMs: Long) {
        if (isClosed || serverFinSent || data.isEmpty()) return
        lastActivityMs = nowMs
        var offset = 0
        while (offset < data.size) {
            val length = min(segmentSize, data.size - offset)
            val chunk = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
            val segment = OutboundSegment(seq = sndNxt, payload = chunk, sentAtMs = nowMs)
            sndNxt += length
            retransmissions.add(segment, nowMs)
            emit(segment, isRetransmission = false)
            offset += length
        }
    }

    /** El servidor cerró su mitad de envío: FIN hacia el cliente detrás de los datos ya emitidos. */
    fun onServerEof(nowMs: Long) {
        if (isClosed || serverFinSent) return
        serverFinSent = true
        lastActivityMs = nowMs
        val segment = OutboundSegment(seq = sndNxt, payload = EMPTY, fin = true, sentAtMs = nowMs)
        sndNxt += 1
        retransmissions.add(segment, nowMs)
        emit(segment, isRetransmission = false)
    }

    /**
     * El forwarder vació (parte de) sus escrituras pendientes. Si la ventana anunciada estaba reducida
     * y ahora hay espacio, se envía una actualización de ventana.
     */
    fun onServerWriteProgress(pendingBytes: Int) {
        if (isClosed) return
        pendingWriteBytes = pendingBytes.coerceAtLeast(0)
        val threshold = maxAdvertisableWindowBytes() / 2
        val nowAdvertisable = advertisableWindowBytes()
        if (lastAdvertisedWindowBytes < threshold && nowAdvertisable >= threshold) {
            sendAck()
        }
    }

    // ---------------------------------------------------------------- Eventos del cliente (TUN)

    fun onClientSegment(segment: TcpPacket, nowMs: Long): ClientSegmentResult = when {
        isClosed -> ClientSegmentResult.HANDLED
        segment.isRst -> {
            close()
            ClientSegmentResult.HANDLED
        }
        segment.isSyn -> handleSyn(segment, nowMs)
        else -> {
            handleSegment(segment, nowMs)
            ClientSegmentResult.HANDLED
        }
    }

    private fun handleSegment(segment: TcpPacket, nowMs: Long) {
        lastActivityMs = nowMs
        if (!synAckSent) return // El socket exterior aún no conectó: el cliente reintentará.
        if (segment.isAck) handleAck(segment, nowMs)
        if (segment.payload.isNotEmpty() || segment.isFin) handleContent(segment)
        closeIfFinished()
    }

    private fun handleSyn(segment: TcpPacket, nowMs: Long): ClientSegmentResult {
        if (segment.sequenceNumber != clientIsn) return ClientSegmentResult.NEW_CONNECTION_REQUESTED
        lastActivityMs = nowMs
        val head = retransmissions.first()
        when {
            head != null && head.syn -> {
                // SYN duplicado: reenviar el mismo SYN-ACK, sin abrir otra conexión exterior.
                head.markRetransmitted(nowMs)
                emit(head, isRetransmission = true)
            }
            established -> sendAck()
            else -> Unit // El socket exterior aún está conectando.
        }
        return ClientSegmentResult.HANDLED
    }

    private fun handleAck(segment: TcpPacket, nowMs: Long) {
        val ackAbs = SequenceSpace.unwrap(segment.acknowledgmentNumber, sndUna)
        if (ackAbs > sndNxt) {
            sendAck() // Confirma algo que nunca se envió.
            return
        }
        val previousWindow = clientWindowBytes
        clientWindowBytes = segment.windowSize.toLong() shl clientWindowShift

        if (ackAbs > sndUna) {
            val outcome = retransmissions.onAck(ackAbs, nowMs)
            sndUna = ackAbs
            dupAckCount = 0
            if (outcome.synAcknowledged) established = true
            if (outcome.finAcknowledged) serverFinAcked = true
        } else if (isDuplicateAck(segment, ackAbs, previousWindow)) {
            dupAckCount++
            if (dupAckCount == DUP_ACK_THRESHOLD) {
                retransmissions.fastRetransmit(nowMs)?.let { emit(it, isRetransmission = true) }
            }
        }
        updatePersistTimer(nowMs)
    }

    private fun isDuplicateAck(segment: TcpPacket, ackAbs: Long, previousWindow: Long): Boolean =
        ackAbs == sndUna &&
            segment.payload.isEmpty() &&
            !segment.isFin &&
            !retransmissions.isEmpty &&
            clientWindowBytes == previousWindow

    private fun handleContent(segment: TcpPacket) {
        val seqAbs = SequenceSpace.unwrap(segment.sequenceNumber, receiveBuffer.rcvNxt)
        val finConsumed = receiveBuffer.accept(seqAbs, segment.payload, segment.isFin) { bytes ->
            pendingWriteBytes = output.writeToServer(bytes)
        }
        if (finConsumed) output.shutdownServerOutput()
        sendAck()
    }

    // ---------------------------------------------------------------- Temporizadores y cierre

    /** Procesa temporizadores vencidos: retransmisión, sonda de ventana cero e inactividad. */
    fun onTimer(nowMs: Long) {
        if (isClosed) return
        val timedOut = isTimedOut(nowMs)
        val retransmission = if (timedOut) null else retransmissions.onTimer(nowMs)
        val retransmissionsExhausted = (retransmission?.transmissions ?: 0) > config.maxRetransmissions + 1
        when {
            timedOut || retransmissionsExhausted -> abort()
            else -> {
                retransmission?.let { emit(it, isRetransmission = true) }
                probeWindowIfDue(nowMs)
            }
        }
    }

    private fun probeWindowIfDue(nowMs: Long) {
        val persist = persistDeadlineMs ?: return
        if (nowMs >= persist) {
            sendWindowProbe()
            persistDeadlineMs = nowMs + rto.rtoMs
        }
    }

    private fun isTimedOut(nowMs: Long): Boolean {
        if (!established && nowMs - createdAtMs >= config.handshakeTimeoutMs) return true
        return nowMs - lastActivityMs >= idleLimitMs()
    }

    private fun idleLimitMs(): Long =
        if (receiveBuffer.finSeen || serverFinSent) config.closingIdleTimeoutMs else config.establishedIdleTimeoutMs

    /** Próximo instante en que [onTimer] tiene trabajo, para acotar la espera del selector. */
    fun nextDeadlineMs(): Long? {
        if (isClosed) return null
        var deadline = lastActivityMs + idleLimitMs()
        if (!established) deadline = min(deadline, createdAtMs + config.handshakeTimeoutMs)
        retransmissions.timerDeadlineMs?.let { deadline = min(deadline, it) }
        persistDeadlineMs?.let { deadline = min(deadline, it) }
        return deadline
    }

    /** Aborta la conexión enviando RST al cliente y liberando la sesión. */
    fun abort() {
        if (isClosed) return
        val rst = TcpPacket.buildRstAck(
            srcPort = serverPort,
            dstPort = clientPort,
            seqNum = SequenceSpace.wire(sndNxt),
            ackNum = SequenceSpace.wire(receiveBuffer.rcvNxt)
        )
        output.sendToClient(rst, SegmentKind.CONTROL, isRetransmission = false)
        close()
    }

    private fun closeIfFinished() {
        if (receiveBuffer.finConsumed && serverFinAcked) close()
    }

    private fun close() {
        if (isClosed) return
        isClosed = true
        retransmissions.clear()
        receiveBuffer.clear()
        persistDeadlineMs = null
        output.closeConnection()
    }

    // ---------------------------------------------------------------- Ventanas

    /**
     * Bytes que el forwarder puede leer del servidor y enviar ya al cliente, limitados por la ventana
     * del cliente y por [maxInFlightBytes].
     */
    fun availableSendWindow(): Int {
        if (isClosed || !established || serverFinSent) return 0
        val inFlight = sndNxt - sndUna
        val window = min(clientWindowBytes, maxInFlightBytes.toLong())
        return (window - inFlight).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun updatePersistTimer(nowMs: Long) {
        val needsProbe = established && !serverFinSent && clientWindowBytes == 0L && retransmissions.isEmpty
        persistDeadlineMs = when {
            !needsProbe -> null
            persistDeadlineMs == null -> nowMs + rto.rtoMs
            else -> persistDeadlineMs
        }
    }

    private fun advertisableWindowBytes(): Int {
        val free = config.maxPendingWriteBytes - pendingWriteBytes - receiveBuffer.outOfOrderBytes
        return free.coerceIn(0, maxAdvertisableWindowBytes())
    }

    private fun maxAdvertisableWindowBytes(): Int =
        min(config.maxPendingWriteBytes.toLong(), MAX_WINDOW_FIELD.toLong() shl ourWindowShift).toInt()

    /** Valor del campo ventana (escalado) y registro de lo anunciado. */
    private fun windowField(): Int {
        val field = (advertisableWindowBytes() ushr ourWindowShift).coerceAtMost(MAX_WINDOW_FIELD)
        lastAdvertisedWindowBytes = field shl ourWindowShift
        return field
    }

    // ---------------------------------------------------------------- Emisión de segmentos

    private fun sendAck() {
        val ack = TcpPacket.buildAck(
            srcPort = serverPort,
            dstPort = clientPort,
            seqNum = SequenceSpace.wire(sndNxt),
            ackNum = SequenceSpace.wire(receiveBuffer.rcvNxt),
            windowSize = windowField()
        )
        output.sendToClient(ack, SegmentKind.CONTROL, isRetransmission = false)
    }

    /** Sonda de ventana cero: ACK con una secuencia ya confirmada, que obliga al cliente a responder. */
    private fun sendWindowProbe() {
        val probe = TcpPacket.buildAck(
            srcPort = serverPort,
            dstPort = clientPort,
            seqNum = SequenceSpace.wire(sndUna - 1),
            ackNum = SequenceSpace.wire(receiveBuffer.rcvNxt),
            windowSize = windowField()
        )
        output.sendToClient(probe, SegmentKind.CONTROL, isRetransmission = false)
    }

    private fun emit(segment: OutboundSegment, isRetransmission: Boolean) {
        val seq = SequenceSpace.wire(segment.seq)
        val ack = SequenceSpace.wire(receiveBuffer.rcvNxt)
        val packet = when {
            segment.syn -> {
                // La ventana de un SYN-ACK nunca va escalada (RFC 7323).
                val synWindow = advertisableWindowBytes().coerceAtMost(MAX_WINDOW_FIELD)
                lastAdvertisedWindowBytes = synWindow
                TcpPacket.buildSynAck(
                    srcPort = serverPort,
                    dstPort = clientPort,
                    seqNum = seq,
                    ackNum = ack,
                    windowSize = synWindow,
                    options = TcpOptions.forSynAck(clientOptions, config.mtu, isIpv6, config.ourWindowScale)
                )
            }
            segment.fin -> TcpPacket.buildFinAck(serverPort, clientPort, seq, ack, windowField())
            else -> TcpPacket.buildPshAck(serverPort, clientPort, seq, ack, segment.payload, windowField())
        }
        if (isRetransmission) retransmittedBytes += segment.payload.size
        val kind = if (segment.syn) SegmentKind.CONTROL else SegmentKind.DATA
        output.sendToClient(packet, kind, isRetransmission)
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val MAX_WINDOW_FIELD = 65535
        private const val DUP_ACK_THRESHOLD = 3

        /** MSS por defecto si el cliente no lo anuncia (RFC 879 / RFC 8200). */
        private const val DEFAULT_IPV4_MSS = 536
        private const val DEFAULT_IPV6_MSS = 1220

        private fun defaultClientMss(isIpv6: Boolean): Int = if (isIpv6) DEFAULT_IPV6_MSS else DEFAULT_IPV4_MSS
    }
}
