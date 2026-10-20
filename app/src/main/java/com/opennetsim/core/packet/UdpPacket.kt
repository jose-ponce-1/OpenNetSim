package com.opennetsim.core.packet

import java.nio.ByteBuffer

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
     * Serializa la cabecera y el payload UDP en un ByteArray, calculando el checksum con la pseudo-cabecera IPv4.
     */
    fun toByteArray(srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val totalLength = 8 + payload.size
        val buffer = ByteBuffer.allocate(totalLength)

        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0.toShort()) // Checksum temporal en 0
        buffer.put(payload)

        val segmentData = buffer.array()
        val calculatedChecksum = ChecksumUtils.calculateTransportChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = IpPacket.PROTOCOL_UDP,
            segmentData = segmentData,
            offset = 0,
            length = totalLength
        )

        // Si el checksum calculado es 0, en UDP se transmite como 0xFFFF (0 significa sin checksum en IPv4)
        val finalChecksum = if (calculatedChecksum == 0) 0xFFFF else calculatedChecksum
        buffer.putShort(6, finalChecksum.toShort())

        return segmentData
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
            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + HEADER_SIZE, offset + udpLength)
            } else {
                ByteArray(0)
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

