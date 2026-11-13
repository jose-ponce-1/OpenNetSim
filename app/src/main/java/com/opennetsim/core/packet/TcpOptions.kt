package com.opennetsim.core.packet

/**
 * Opciones TCP relevantes para el handshake (RFC 793 MSS, RFC 7323 window scale).
 *
 * @param mss Maximum Segment Size anunciado, o null si no está presente.
 * @param windowScale Desplazamiento de window scale (0..14), o null si no está presente.
 */
data class TcpOptions(
    val mss: Int? = null,
    val windowScale: Int? = null
) {
    /**
     * Codifica las opciones en bytes con padding a múltiplo de 4 (listas para la cabecera TCP).
     */
    fun encode(): ByteArray {
        val bytes = ArrayList<Byte>(OPTIONS_CAPACITY)
        mss?.let { value ->
            bytes += KIND_MSS.toByte()
            bytes += MSS_LENGTH.toByte()
            bytes += ((value ushr BYTE_BITS) and BYTE_MASK).toByte()
            bytes += (value and BYTE_MASK).toByte()
        }
        windowScale?.let { shift ->
            bytes += KIND_NOP.toByte()
            bytes += KIND_WINDOW_SCALE.toByte()
            bytes += WINDOW_SCALE_LENGTH.toByte()
            bytes += shift.coerceIn(0, MAX_WINDOW_SCALE).toByte()
        }
        while (bytes.size % WORD_BYTES != 0) {
            bytes += KIND_NOP.toByte()
        }
        return bytes.toByteArray()
    }

    companion object {
        const val KIND_END = 0
        const val KIND_NOP = 1
        const val KIND_MSS = 2
        const val KIND_WINDOW_SCALE = 3

        private const val MSS_LENGTH = 4
        private const val WINDOW_SCALE_LENGTH = 3
        private const val OPTIONS_CAPACITY = 8
        private const val WORD_BYTES = 4
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFF
        private const val VALUE_OFFSET = 2

        /** Máximo desplazamiento permitido por RFC 7323. */
        const val MAX_WINDOW_SCALE = 14

        /** Cabeceras IPv4 (20) + TCP (20) sin opciones. */
        const val IPV4_TCP_HEADERS = 40

        /** Cabeceras IPv6 (40) + TCP (20) sin opciones. */
        const val IPV6_TCP_HEADERS = 60

        /**
         * Parsea las opciones de un segmento TCP. Ignora opciones desconocidas y deja de leer ante una
         * opción mal formada o el marcador de fin.
         */
        fun parse(options: ByteArray): TcpOptions {
            var mss: Int? = null
            var windowScale: Int? = null
            var index = 0
            while (index < options.size) {
                val kind = options[index].toInt() and BYTE_MASK
                val length = optionLength(options, index, kind)
                if (kind == KIND_MSS && length == MSS_LENGTH) {
                    mss = u16(options, index + VALUE_OFFSET)
                } else if (kind == KIND_WINDOW_SCALE && length == WINDOW_SCALE_LENGTH) {
                    val shift = options[index + VALUE_OFFSET].toInt() and BYTE_MASK
                    windowScale = shift.coerceAtMost(MAX_WINDOW_SCALE)
                }
                // Longitud 0: fin de opciones u opción mal formada → salir del bucle.
                index = if (length == 0) options.size else index + length
            }
            return TcpOptions(mss, windowScale)
        }

        /** Longitud de la opción en [index]: 1 para NOP, 0 para fin o malformada. */
        private fun optionLength(options: ByteArray, index: Int, kind: Int): Int {
            val declared = if (index + 1 < options.size) options[index + 1].toInt() and BYTE_MASK else 0
            return when {
                kind == KIND_END -> 0
                kind == KIND_NOP -> 1
                declared < VALUE_OFFSET || index + declared > options.size -> 0
                else -> declared
            }
        }

        private fun u16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and BYTE_MASK) shl BYTE_BITS) or (data[offset + 1].toInt() and BYTE_MASK)

        /** MSS máximo que cabe en el MTU del túnel para la familia indicada. */
        fun mssForMtu(mtu: Int, isIpv6: Boolean): Int =
            mtu - if (isIpv6) IPV6_TCP_HEADERS else IPV4_TCP_HEADERS

        /**
         * Opciones del SYN-ACK del túnel: siempre MSS ajustado al MTU; window scale [ourWindowScale]
         * solo si el cliente lo ofreció en su SYN (RFC 7323: ambos extremos deben enviarlo).
         */
        fun forSynAck(clientOptions: TcpOptions, mtu: Int, isIpv6: Boolean, ourWindowScale: Int): TcpOptions =
            TcpOptions(
                mss = mssForMtu(mtu, isIpv6),
                windowScale = if (clientOptions.windowScale != null) ourWindowScale else null
            )
    }
}
