package com.opennetsim.core.packet

/**
 * Representación, parser y constructor de segmentos TCP (RFC 793).
 */
data class TcpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    /** Longitud de la cabecera en palabras de 32 bits (5 = 20 bytes, sin opciones). */
    val dataOffset: Int = MIN_DATA_OFFSET,
    val flags: Int,
    val windowSize: Int = DEFAULT_WINDOW,
    val checksum: Int = 0,
    val urgentPointer: Int = 0,
    val options: ByteArray = PacketBytes.EMPTY,
    val payload: ByteArray = PacketBytes.EMPTY
) {
    val isFin: Boolean get() = (flags and FLAG_FIN) != 0
    val isSyn: Boolean get() = (flags and FLAG_SYN) != 0
    val isRst: Boolean get() = (flags and FLAG_RST) != 0
    val isPsh: Boolean get() = (flags and FLAG_PSH) != 0
    val isAck: Boolean get() = (flags and FLAG_ACK) != 0
    val isUrg: Boolean get() = (flags and FLAG_URG) != 0

    /** Opciones TCP parseadas (MSS y window scale). */
    val parsedOptions: TcpOptions get() = TcpOptions.parse(options)

    /** Longitud en el espacio de secuencias: payload más SYN y FIN, que consumen un número cada uno. */
    val sequenceLength: Long
        get() = payload.size.toLong() + (if (isSyn) 1L else 0L) + (if (isFin) 1L else 0L)

    /**
     * Serializa la cabecera TCP, opciones y payload, calculando el checksum con la pseudo-cabecera IPv4
     * o IPv6 según el tamaño de [srcIp].
     */
    fun toByteArray(srcIp: ByteArray, dstIp: ByteArray): ByteArray =
        ByteArray(dataOffset * WORD_BYTES + payload.size).also { writeTo(it, 0, srcIp, dstIp) }

    /**
     * Paquete IP completo (IPv4 o IPv6 según el tamaño de las direcciones) listo para el TUN, en un único
     * array: el camino de bajada no crea el segmento por separado para copiarlo después.
     */
    fun toIpBytes(srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val out = IpFraming.allocate(srcIp, dstIp, IpPacket.PROTOCOL_TCP, dataOffset * WORD_BYTES + payload.size)
        writeTo(out, IpFraming.headerSize(srcIp), srcIp, dstIp)
        return out
    }

    /** Escribe el segmento con su checksum en [out] desde [offset]; ese tramo de [out] debe estar a cero. */
    private fun writeTo(out: ByteArray, offset: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val headerLength = dataOffset * WORD_BYTES
        PacketBytes.writeU16(out, offset, sourcePort)
        PacketBytes.writeU16(out, offset + DESTINATION_PORT_OFFSET, destinationPort)
        PacketBytes.writeU32(out, offset + SEQUENCE_OFFSET, sequenceNumber)
        PacketBytes.writeU32(out, offset + ACK_OFFSET, acknowledgmentNumber)
        out[offset + DATA_OFFSET_OFFSET] = ((dataOffset and LOW_NIBBLE_MASK) shl NIBBLE_BITS).toByte()
        out[offset + FLAGS_OFFSET] = flags.toByte()
        PacketBytes.writeU16(out, offset + WINDOW_OFFSET, windowSize)
        PacketBytes.writeU16(out, offset + URGENT_OFFSET, urgentPointer)
        // Las opciones ocupan desde el byte 20; el relleno hasta dataOffset ya está a cero.
        System.arraycopy(options, 0, out, offset + HEADER_MIN_SIZE, options.size)
        System.arraycopy(payload, 0, out, offset + headerLength, payload.size)
        val checksum = ChecksumUtils.calculateTransportChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = IpPacket.PROTOCOL_TCP,
            segmentData = out,
            offset = offset,
            length = headerLength + payload.size
        )
        PacketBytes.writeU16(out, offset + CHECKSUM_OFFSET, checksum)
    }

    /**
     * Empaqueta este segmento TCP en un paquete IPv4 completo listo para transmitir al TUN.
     */
    fun toIpPacket(srcIp: ByteArray, dstIp: ByteArray): IpPacket {
        val tcpData = toByteArray(srcIp, dstIp)
        return IpPacket(
            totalLength = IpPacket.HEADER_SIZE + tcpData.size,
            protocol = IpPacket.PROTOCOL_TCP,
            sourceIp = srcIp,
            destinationIp = dstIp,
            payload = tcpData
        )
    }

    /**
     * Empaqueta este segmento TCP en un paquete IPv6 completo listo para transmitir al TUN.
     */
    fun toIpv6Packet(srcIp: ByteArray, dstIp: ByteArray): Ipv6Packet {
        val tcpData = toByteArray(srcIp, dstIp)
        return Ipv6Packet(
            payloadLength = tcpData.size,
            nextHeader = Ipv6Packet.PROTOCOL_TCP,
            sourceIp = srcIp,
            destinationIp = dstIp,
            payload = tcpData
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TcpPacket
        if (sourcePort != other.sourcePort) return false
        if (destinationPort != other.destinationPort) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (acknowledgmentNumber != other.acknowledgmentNumber) return false
        if (flags != other.flags) return false
        if (windowSize != other.windowSize) return false
        if (!options.contentEquals(other.options)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sourcePort
        result = HASH_MULTIPLIER * result + destinationPort
        result = HASH_MULTIPLIER * result + sequenceNumber.hashCode()
        result = HASH_MULTIPLIER * result + acknowledgmentNumber.hashCode()
        result = HASH_MULTIPLIER * result + flags
        result = HASH_MULTIPLIER * result + windowSize
        result = HASH_MULTIPLIER * result + options.contentHashCode()
        result = HASH_MULTIPLIER * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10
        const val FLAG_URG = 0x20

        const val HEADER_MIN_SIZE = 20

        /** Ventana por defecto: el máximo sin escala. */
        const val DEFAULT_WINDOW = 65_535
        private const val MIN_DATA_OFFSET = 5
        private const val WORD_BYTES = 4
        private const val BYTE_MASK = 0xFF
        private const val HIGH_NIBBLE_MASK = 0xF0
        private const val SEQUENCE_MASK = 0xFFFFFFFFL
        private const val HASH_MULTIPLIER = 31
        private const val LOW_NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4
        private const val DESTINATION_PORT_OFFSET = 2
        private const val SEQUENCE_OFFSET = 4
        private const val ACK_OFFSET = 8
        private const val DATA_OFFSET_OFFSET = 12
        private const val FLAGS_OFFSET = 13
        private const val WINDOW_OFFSET = 14
        private const val CHECKSUM_OFFSET = 16
        private const val URGENT_OFFSET = 18

        /**
         * Parsea un segmento TCP a partir de los bytes del payload de un paquete IP.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): TcpPacket? {
            val headerLength = validHeaderLength(data, offset, length)
            if (headerLength == PacketBytes.INVALID) return null
            return TcpPacket(
                sourcePort = PacketBytes.readU16(data, offset),
                destinationPort = PacketBytes.readU16(data, offset + DESTINATION_PORT_OFFSET),
                sequenceNumber = PacketBytes.readU32(data, offset + SEQUENCE_OFFSET),
                acknowledgmentNumber = PacketBytes.readU32(data, offset + ACK_OFFSET),
                dataOffset = headerLength / WORD_BYTES,
                flags = data[offset + FLAGS_OFFSET].toInt() and BYTE_MASK,
                windowSize = PacketBytes.readU16(data, offset + WINDOW_OFFSET),
                checksum = PacketBytes.readU16(data, offset + CHECKSUM_OFFSET),
                urgentPointer = PacketBytes.readU16(data, offset + URGENT_OFFSET),
                options = PacketBytes.slice(data, offset + HEADER_MIN_SIZE, offset + headerLength),
                // Única copia del camino de subida: el búfer de recepción guarda y entrega este array.
                payload = PacketBytes.slice(data, offset + headerLength, offset + length)
            )
        }

        /** Longitud de la cabecera si [data] empieza por una cabecera TCP completa; si no, [PacketBytes.INVALID]. */
        private fun validHeaderLength(data: ByteArray, offset: Int, length: Int): Int {
            if (length < HEADER_MIN_SIZE) return PacketBytes.INVALID
            val dataOffset = (data[offset + DATA_OFFSET_OFFSET].toInt() and HIGH_NIBBLE_MASK) ushr NIBBLE_BITS
            val headerLength = dataOffset * WORD_BYTES
            return if (headerLength >= HEADER_MIN_SIZE && length >= headerLength) headerLength else PacketBytes.INVALID
        }

        /**
         * Crea un paquete de respuesta SYN-ACK con las [options] indicadas (MSS / window scale).
         */
        @Suppress("LongParameterList") // windowSize y options tienen valores por defecto.
        fun buildSynAck(
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long,
            windowSize: Int = DEFAULT_WINDOW,
            options: TcpOptions = TcpOptions()
        ): TcpPacket {
            val encodedOptions = options.encode()
            return TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                dataOffset = MIN_DATA_OFFSET + encodedOptions.size / WORD_BYTES,
                flags = FLAG_SYN or FLAG_ACK,
                windowSize = windowSize,
                options = encodedOptions
            )
        }

        /**
         * Crea un paquete ACK estándar.
         */
        fun buildAck(
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long,
            windowSize: Int = DEFAULT_WINDOW
        ): TcpPacket {
            return TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                flags = FLAG_ACK,
                windowSize = windowSize
            )
        }

        /**
         * Crea un paquete PSH-ACK con datos de carga útil (payload).
         */
        @Suppress("LongParameterList") // Un segmento por llamada en la bajada: agruparlos reservaría otro objeto.
        fun buildPshAck(
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long,
            payload: ByteArray,
            windowSize: Int = DEFAULT_WINDOW
        ): TcpPacket {
            return TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                flags = FLAG_PSH or FLAG_ACK,
                windowSize = windowSize,
                payload = payload
            )
        }

        /**
         * Crea un paquete FIN-ACK para cierre ordenado.
         */
        fun buildFinAck(
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long,
            windowSize: Int = DEFAULT_WINDOW
        ): TcpPacket {
            return TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                flags = FLAG_FIN or FLAG_ACK,
                windowSize = windowSize
            )
        }

        /**
         * Crea un paquete RST para rechazar o abortar la conexión.
         */
        fun buildRst(
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long = 0L
        ): TcpPacket {
            return TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                flags = if (ackNum > 0L) (FLAG_RST or FLAG_ACK) else FLAG_RST,
                windowSize = 0
            )
        }

        /**
         * Crea un RST|ACK para abortar una sesión conocida, siempre con flag ACK.
         */
        fun buildRstAck(srcPort: Int, dstPort: Int, seqNum: Long, ackNum: Long): TcpPacket =
            TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum and SEQUENCE_MASK,
                acknowledgmentNumber = ackNum and SEQUENCE_MASK,
                flags = FLAG_RST or FLAG_ACK,
                windowSize = 0
            )

        /**
         * Crea el RST de respuesta a un [segment] que no pertenece a ninguna conexión (RFC 793, 3.4):
         * - Si el segmento trae ACK: `seq = SEG.ACK`, sin flag ACK.
         * - Si no: `seq = 0`, `ack = SEG.SEQ + SEG.LEN`, con RST|ACK.
         *
         * Devuelve null si el segmento es a su vez un RST (nunca se responde a un RST).
         */
        fun buildRstForSegment(segment: TcpPacket): TcpPacket? {
            if (segment.isRst) return null
            return if (segment.isAck) {
                TcpPacket(
                    sourcePort = segment.destinationPort,
                    destinationPort = segment.sourcePort,
                    sequenceNumber = segment.acknowledgmentNumber,
                    acknowledgmentNumber = 0L,
                    flags = FLAG_RST,
                    windowSize = 0
                )
            } else {
                buildRstAck(
                    srcPort = segment.destinationPort,
                    dstPort = segment.sourcePort,
                    seqNum = 0L,
                    ackNum = segment.sequenceNumber + segment.sequenceLength
                )
            }
        }
    }
}
