package com.opennetsim.core.packet

import java.nio.ByteBuffer

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
    val options: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0)
) {
    val isFin: Boolean get() = (flags and FLAG_FIN) != 0
    val isSyn: Boolean get() = (flags and FLAG_SYN) != 0
    val isRst: Boolean get() = (flags and FLAG_RST) != 0
    val isPsh: Boolean get() = (flags and FLAG_PSH) != 0
    val isAck: Boolean get() = (flags and FLAG_ACK) != 0
    val isUrg: Boolean get() = (flags and FLAG_URG) != 0

    /**
     * Serializa la cabecera TCP, opciones y payload, calculando el checksum con la pseudo-cabecera IPv4.
     */
    fun toByteArray(srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val headerLength = dataOffset * 4
        val totalLength = headerLength + payload.size
        val buffer = ByteBuffer.allocate(totalLength)

        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())
        buffer.putInt((sequenceNumber and 0xFFFFFFFFL).toInt())
        buffer.putInt((acknowledgmentNumber and 0xFFFFFFFFL).toInt())

        // Data Offset (4 bits) + Reserved (4 bits = 0)
        val dataOffsetByte = (dataOffset and 0x0F) shl 4
        buffer.put(dataOffsetByte.toByte())

        // Flags (8 bits)
        buffer.put((flags and 0xFF).toByte())

        // Window size
        buffer.putShort(windowSize.toShort())

        // Checksum inicializado a 0
        buffer.putShort(0.toShort())

        // Urgent Pointer
        buffer.putShort(urgentPointer.toShort())

        // Options (si las hay)
        if (options.isNotEmpty()) {
            buffer.put(options)
            // Padding a múltiplo de 4 bytes si hace falta
            val padding = (dataOffset * 4) - 20 - options.size
            if (padding > 0) {
                buffer.put(ByteArray(padding))
            }
        }

        // Payload
        buffer.position(headerLength)
        buffer.put(payload)

        val segmentData = buffer.array()
        val calculatedChecksum = ChecksumUtils.calculateTransportChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = IpPacket.PROTOCOL_TCP,
            segmentData = segmentData,
            offset = 0,
            length = totalLength
        )

        buffer.putShort(16, calculatedChecksum.toShort())

        return segmentData
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

        /**
         * Parsea un segmento TCP a partir de los bytes del payload de un paquete IP.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): TcpPacket? {
            if (length < HEADER_MIN_SIZE) return null

            val sourcePort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val destinationPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)

            val seqNum = (((data[offset + 4].toLong() and 0xFF) shl 24) or
                    ((data[offset + 5].toLong() and 0xFF) shl 16) or
                    ((data[offset + 6].toLong() and 0xFF) shl 8) or
                    (data[offset + 7].toLong() and 0xFF)) and 0xFFFFFFFFL

            val ackNum = (((data[offset + 8].toLong() and 0xFF) shl 24) or
                    ((data[offset + 9].toLong() and 0xFF) shl 16) or
                    ((data[offset + 10].toLong() and 0xFF) shl 8) or
                    (data[offset + 11].toLong() and 0xFF)) and 0xFFFFFFFFL

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
                ByteArray(0)
            }

            val payloadLength = length - headerLength
            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + headerLength, offset + length)
            } else {
                ByteArray(0)
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
         * Crea un paquete de respuesta SYN-ACK.
         */
        fun buildSynAck(
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
                flags = FLAG_SYN or FLAG_ACK,
                windowSize = windowSize
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
    }
}

