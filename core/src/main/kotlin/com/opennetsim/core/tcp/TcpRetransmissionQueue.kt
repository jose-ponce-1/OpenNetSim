package com.opennetsim.core.tcp

/**
 * Segmento emitido hacia el cliente que espera confirmación.
 *
 * @param seq Secuencia absoluta del primer número que ocupa.
 * @param syn True para el SYN-ACK (ocupa un número de secuencia).
 * @param fin True para el FIN (ocupa un número de secuencia).
 */
class OutboundSegment(
    val seq: Long,
    val payload: ByteArray,
    val syn: Boolean = false,
    val fin: Boolean = false,
    var sentAtMs: Long
) {
    var retransmitted: Boolean = false
        private set
    var transmissions: Int = 1
        private set

    val length: Long get() = payload.size + (if (syn) 1L else 0L) + (if (fin) 1L else 0L)
    val end: Long get() = seq + length

    fun markRetransmitted(nowMs: Long) {
        retransmitted = true
        transmissions++
        sentAtMs = nowMs
    }
}

/**
 * Resultado de procesar una confirmación acumulativa.
 */
data class AckOutcome(
    val acknowledgedBytes: Long,
    val synAcknowledged: Boolean,
    val finAcknowledged: Boolean
) {
    companion object {
        val NONE = AckOutcome(0L, synAcknowledged = false, finAcknowledged = false)
    }
}

/**
 * Cola ordenada de segmentos sin confirmar con el temporizador de retransmisión (RFC 6298, sección 5).
 * El temporizador se arma al emitir el primer segmento sin confirmar y se rearma en cada avance de ACK.
 */
class TcpRetransmissionQueue(private val rto: RtoEstimator) {
    private val segments = ArrayDeque<OutboundSegment>()

    /** Instante en que vence el temporizador, o null si no hay nada en vuelo. */
    var timerDeadlineMs: Long? = null
        private set

    val isEmpty: Boolean get() = segments.isEmpty()

    fun first(): OutboundSegment? = segments.firstOrNull()

    fun add(segment: OutboundSegment, nowMs: Long) {
        segments.addLast(segment)
        if (timerDeadlineMs == null) {
            timerDeadlineMs = nowMs + rto.rtoMs
        }
    }

    /** Aplica una confirmación acumulativa hasta [ackAbs] (exclusive). */
    fun onAck(ackAbs: Long, nowMs: Long): AckOutcome {
        var acknowledged = 0L
        var synAcked = false
        var finAcked = false
        var anyRetransmitted = false
        var newestAcked: OutboundSegment? = null

        while (segments.isNotEmpty() && segments.first().end <= ackAbs) {
            val head = segments.removeFirst()
            acknowledged += head.length
            synAcked = synAcked || head.syn
            finAcked = finAcked || head.fin
            anyRetransmitted = anyRetransmitted || head.retransmitted
            newestAcked = head
        }

        val newest = newestAcked ?: return AckOutcome.NONE
        // Karn: si la confirmación cubre algún segmento retransmitido, la muestra es ambigua.
        rto.onAckOfSegment(newest.sentAtMs, nowMs, wasRetransmitted = anyRetransmitted)
        rto.resetBackoff()
        timerDeadlineMs = if (segments.isEmpty()) null else nowMs + rto.rtoMs
        return AckOutcome(acknowledged, synAcked, finAcked)
    }

    /**
     * Si el temporizador venció, marca el primer segmento como retransmitido, aplica backoff y rearma.
     * @return el segmento a retransmitir, o null si no toca.
     */
    fun onTimer(nowMs: Long): OutboundSegment? {
        val deadline = timerDeadlineMs
        if (deadline == null || nowMs < deadline) return null
        val head = segments.firstOrNull()
        if (head == null) {
            timerDeadlineMs = null
        } else {
            head.markRetransmitted(nowMs)
            rto.onTimeout()
            timerDeadlineMs = nowMs + rto.rtoMs
        }
        return head
    }

    /** Retransmisión rápida del primer segmento (tres ACK duplicados). */
    fun fastRetransmit(nowMs: Long): OutboundSegment? {
        val head = segments.firstOrNull() ?: return null
        head.markRetransmitted(nowMs)
        timerDeadlineMs = nowMs + rto.rtoMs
        return head
    }

    fun clear() {
        segments.clear()
        timerDeadlineMs = null
    }
}
