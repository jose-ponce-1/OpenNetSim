package com.opennetsim.core.packet

/**
 * Representación, parser y serializador de datagramas UDP (RFC 768).
 */
data class UdpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val length: Int,
    val checksum: Int = 0,
    val payload: ByteArray
) {
    /**
     * Serializa la cabecera y el payload UDP en un ByteArray, calculando el checksum con la pseudo-cabecera
     * IPv4 o IPv6 según el tamaño de [srcIp].
     */
    fun toByteArray(srcIp: ByteArray, dstIp: ByteArray): ByteArray =
        ByteArray(HEADER_SIZE + payload.size).also { writeTo(it, 0, srcIp, dstIp) }

    /** Paquete IP completo (IPv4 o IPv6 según el tamaño de las direcciones) en un único array. */
    fun toIpBytes(srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val out = IpFraming.allocate(srcIp, dstIp, IpPacket.PROTOCOL_UDP, HEADER_SIZE + payload.size)
        writeTo(out, IpFraming.headerSize(srcIp), srcIp, dstIp)
        return out
    }

    /** Escribe el datagrama con su checksum en [out] desde [offset]; ese tramo de [out] debe estar a cero. */
    private fun writeTo(out: ByteArray, offset: Int, srcIp: ByteArray, dstIp: ByteArray) {
        val totalLength = HEADER_SIZE + payload.size
        PacketBytes.writeU16(out, offset, sourcePort)
        PacketBytes.writeU16(out, offset + DESTINATION_PORT_OFFSET, destinationPort)
        PacketBytes.writeU16(out, offset + LENGTH_OFFSET, totalLength)
        System.arraycopy(payload, 0, out, offset + HEADER_SIZE, payload.size)
        val calculatedChecksum = ChecksumUtils.calculateTransportChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = IpPacket.PROTOCOL_UDP,
            segmentData = out,
            offset = offset,
            length = totalLength
        )
        // Si el checksum calculado es 0, en UDP se transmite como 0xFFFF (0 significa sin checksum en IPv4)
        val finalChecksum = if (calculatedChecksum == 0) NO_CHECKSUM_SUBSTITUTE else calculatedChecksum
        PacketBytes.writeU16(out, offset + CHECKSUM_OFFSET, finalChecksum)
    }

    /**
     * Empaqueta este datagrama UDP en un paquete IPv4 completo listo para transmitir o inyectar al TUN.
     */
    fun toIpPacket(srcIp: ByteArray, dstIp: ByteArray): IpPacket {
        val udpData = toByteArray(srcIp, dstIp)
        return IpPacket(
            version = 4,
            ihl = 5,
            totalLength = 20 + udpData.size,
            protocol = IpPacket.PROTOCOL_UDP,
            sourceIp = srcIp,
            destinationIp = dstIp,
            payload = udpData
        )
    }

    /**
     * Empaqueta este datagrama UDP en un paquete IPv6 completo listo para inyectar al TUN.
     */
    fun toIpv6Packet(srcIp: ByteArray, dstIp: ByteArray): Ipv6Packet {
        val udpData = toByteArray(srcIp, dstIp)
        return Ipv6Packet(
            version = 6,
            payloadLength = udpData.size,
            nextHeader = Ipv6Packet.PROTOCOL_UDP,
            sourceIp = srcIp,
            destinationIp = dstIp,
            payload = udpData
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UdpPacket
        if (sourcePort != other.sourcePort) return false
        if (destinationPort != other.destinationPort) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sourcePort
        result = 31 * result + destinationPort
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 8
        private const val DESTINATION_PORT_OFFSET = 2
        private const val LENGTH_OFFSET = 4
        private const val CHECKSUM_OFFSET = 6
        private const val NO_CHECKSUM_SUBSTITUTE = 0xFFFF

        /**
         * Parsea un datagrama UDP a partir de los bytes del payload de un paquete IP.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): UdpPacket? {
            if (length < HEADER_SIZE) return null

            val sourcePort = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val destinationPort = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val udpLength = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
            val checksum = ((data[offset + 6].toInt() and 0xFF) shl 8) or (data[offset + 7].toInt() and 0xFF)

            if (length < udpLength || udpLength < HEADER_SIZE) return null

            val payloadLength = udpLength - HEADER_SIZE
            // Única copia del camino de subida: el datagrama se envía después desde otro hilo.
            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + HEADER_SIZE, offset + udpLength)
            } else {
                PacketBytes.EMPTY
            }

            return UdpPacket(
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                length = udpLength,
                checksum = checksum,
                payload = payload
            )
        }
    }
}
