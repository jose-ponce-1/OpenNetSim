package com.opennetsim.core.packet

/**
 * Representación, parser y constructor de segmentos TCP (RFC 793).
 */
data class TcpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val dataOffset: Int = 5, // En palabras de 32 bits (5 = 20 bytes sin opciones)
    val flags: Int,
    val windowSize: Int = 65535,
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
            version = 4,
            ihl = 5,
            totalLength = 20 + tcpData.size,
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
            version = 6,
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
        result = 31 * result + destinationPort
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + acknowledgmentNumber.hashCode()
        result = 31 * result + flags
        result = 31 * result + windowSize
        result = 31 * result + options.contentHashCode()
        result = 31 * result + payload.contentHashCode()
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

        private const val WORD_BYTES = 4
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
            if (length < HEADER_MIN_SIZE) return null

            val sourcePort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val destinationPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

            val seqNum = (
                ((data[offset + 4].toLong() and 0xFF) shl 24) or
                    ((data[offset + 5].toLong() and 0xFF) shl 16) or
                    ((data[offset + 6].toLong() and 0xFF) shl 8) or
                    (data[offset + 7].toLong() and 0xFF)
                ) and 0xFFFFFFFFL

            val ackNum = (
                ((data[offset + 8].toLong() and 0xFF) shl 24) or
                    ((data[offset + 9].toLong() and 0xFF) shl 16) or
                    ((data[offset + 10].toLong() and 0xFF) shl 8) or
                    (data[offset + 11].toLong() and 0xFF)
                ) and 0xFFFFFFFFL

            val dataOffset = (data[offset + 12].toInt() and 0xF0) ushr 4
            val headerLength = dataOffset * 4
            if (headerLength < HEADER_MIN_SIZE || length < headerLength) return null

            val flags = data[offset + 13].toInt() and 0xFF
            val windowSize = ((data[offset + 14].toInt() and 0xFF) shl 8) or (data[offset + 15].toInt() and 0xFF)
            val checksum = ((data[offset + 16].toInt() and 0xFF) shl 8) or (data[offset + 17].toInt() and 0xFF)
            val urgentPointer = ((data[offset + 18].toInt() and 0xFF) shl 8) or (data[offset + 19].toInt() and 0xFF)

            val optionsLength = headerLength - HEADER_MIN_SIZE
            val options = if (optionsLength > 0) {
                data.copyOfRange(offset + HEADER_MIN_SIZE, offset + headerLength)
            } else {
                PacketBytes.EMPTY
            }

            val payloadLength = length - headerLength
            // Única copia del camino de subida: el búfer de recepción guarda y entrega este array.
            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + headerLength, offset + length)
            } else {
                PacketBytes.EMPTY
            }

            return TcpPacket(
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                dataOffset = dataOffset,
                flags = flags,
                windowSize = windowSize,
                checksum = checksum,
                urgentPointer = urgentPointer,
                options = options,
                payload = payload
            )
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
            windowSize: Int = 65535,
            options: TcpOptions = TcpOptions()
        ): TcpPacket {
            val encodedOptions = options.encode()
            return TcpPacket(
                sourcePort = srcPort,
                destinationPort = dstPort,
                sequenceNumber = seqNum,
                acknowledgmentNumber = ackNum,
                dataOffset = 5 + encodedOptions.size / 4,
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
            windowSize: Int = 65535
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
        fun buildPshAck(
            srcPort: Int,
            dstPort: Int,
            seqNum: Long,
            ackNum: Long,
            payload: ByteArray,
            windowSize: Int = 65535
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
            windowSize: Int = 65535
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
                sequenceNumber = seqNum and 0xFFFFFFFFL,
                acknowledgmentNumber = ackNum and 0xFFFFFFFFL,
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
