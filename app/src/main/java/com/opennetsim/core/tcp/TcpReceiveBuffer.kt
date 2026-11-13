package com.opennetsim.core.tcp

import java.util.TreeMap

/**
 * Reensamblado del flujo cliente → servidor en secuencias absolutas.
 *
 * Entrega los bytes en orden estricto, retiene segmentos fuera de orden hasta [maxOutOfOrderBytes] y
 * solo consume el FIN del cliente cuando todos los bytes anteriores a él se han entregado.
 */
class TcpReceiveBuffer(initialRcvNxt: Long, private val maxOutOfOrderBytes: Int) {

    /** Siguiente secuencia absoluta esperada del cliente (RCV.NXT). */
    var rcvNxt: Long = initialRcvNxt
        private set

    /** Bytes retenidos fuera de orden. */
    var outOfOrderBytes: Int = 0
        private set

    /** True una vez entregados todos los datos anteriores al FIN y consumido el FIN. */
    var finConsumed: Boolean = false
        private set

    private val outOfOrder = TreeMap<Long, ByteArray>()
    private var finSequence: Long? = null

    /**
     * Procesa un segmento del cliente que empieza en [seqAbs].
     *
     * @param deliver recibe, en orden, los bytes que pasan a ser contiguos.
     * @return true si este segmento hizo consumir el FIN del cliente.
     */
    fun accept(seqAbs: Long, payload: ByteArray, fin: Boolean, deliver: (ByteArray) -> Unit): Boolean {
        if (fin && finSequence == null) {
            finSequence = seqAbs + payload.size
        }
        if (payload.isNotEmpty()) {
            acceptData(seqAbs, payload, deliver)
        }
        return consumeFinIfReady()
    }

    private fun acceptData(seqAbs: Long, payload: ByteArray, deliver: (ByteArray) -> Unit) {
        val end = seqAbs + payload.size
        when {
            end <= rcvNxt -> Unit // Duplicado completo: ya entregado.
            seqAbs > rcvNxt -> retain(seqAbs, payload)
            else -> {
                deliver(slice(payload, (rcvNxt - seqAbs).toInt()))
                rcvNxt = end
                drainContiguous(deliver)
            }
        }
    }

    private fun retain(seqAbs: Long, payload: ByteArray) {
        val existing = outOfOrder[seqAbs]
        if (existing != null && existing.size >= payload.size) return
        val delta = payload.size - (existing?.size ?: 0)
        if (outOfOrderBytes + delta > maxOutOfOrderBytes) return // El cliente retransmitirá.
        outOfOrder[seqAbs] = payload
        outOfOrderBytes += delta
    }

    private fun drainContiguous(deliver: (ByteArray) -> Unit) {
        while (true) {
            val entry = outOfOrder.firstEntry() ?: return
            if (entry.key > rcvNxt) return
            outOfOrder.remove(entry.key)
            outOfOrderBytes -= entry.value.size
            val end = entry.key + entry.value.size
            if (end > rcvNxt) {
                deliver(slice(entry.value, (rcvNxt - entry.key).toInt()))
                rcvNxt = end
            }
        }
    }

    private fun consumeFinIfReady(): Boolean {
        val ready = !finConsumed && finSequence == rcvNxt
        if (ready) {
            rcvNxt += 1
            finConsumed = true
        }
        return ready
    }

    /** True si el cliente ha enviado (aunque aún no se haya consumido) un FIN. */
    val finSeen: Boolean get() = finSequence != null

    fun clear() {
        outOfOrder.clear()
        outOfOrderBytes = 0
    }

    private fun slice(payload: ByteArray, offset: Int): ByteArray =
        if (offset <= 0) payload else payload.copyOfRange(offset, payload.size)
}
