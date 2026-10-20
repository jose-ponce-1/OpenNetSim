package com.opennetsim.core.packet

import java.nio.ByteBuffer

/**
 * Representación, parser y serializador de mensajes ICMPv4 (RFC 792) e ICMPv6 (RFC 4443).
 */
data class IcmpPacket(
    val type: Int,
    val code: Int = 0,
    val checksum: Int = 0,
    val identifier: Int,
    val sequenceNumber: Int,
    val payload: ByteArray = ByteArray(0)
) {
    val isEchoRequestV4: Boolean get() = type == TYPE_ECHO_REQUEST_V4 && code == 0
    val isEchoRequestV6: Boolean get() = type == TYPE_ECHO_REQUEST_V6 && code == 0

    /**
     * Serializa el paquete ICMP a un ByteArray calculando el checksum según corresponda:
     * - En ICMPv4: checksum directo sobre el datagrama ICMP (RFC 792).
     * - En ICMPv6: checksum sobre pseudo-cabecera IPv6 + datagrama ICMP (RFC 4443).
     */
    fun toByteArray(isIpv6: Boolean = false, srcIp: ByteArray? = null, dstIp: ByteArray? = null): ByteArray {
        val totalLength = HEADER_SIZE + payload.size
        val buffer = ByteBuffer.allocate(totalLength)

        buffer.put(type.toByte())
        buffer.put(code.toByte())
        buffer.putShort(0.toShort()) // Checksum inicial a 0
        buffer.putShort(identifier.toShort())
        buffer.putShort(sequenceNumber.toShort())
        buffer.put(payload)

        val raw = buffer.array()
        val finalChecksum = if (isIpv6 && srcIp != null && dstIp != null) {
            ChecksumUtils.calculateTransportChecksum(
                srcIp = srcIp,
                dstIp = dstIp,
                protocol = Ipv6Packet.PROTOCOL_ICMPV6,
                segmentData = raw,
                offset = 0,
                length = totalLength
            )
        } else {
            ChecksumUtils.calculateChecksum(raw, 0, totalLength)
        }

        buffer.putShort(2, finalChecksum.toShort())
        return buffer.array()
    }

    /**
     * Genera un mensaje ICMP Echo Reply correspondiente a este Echo Request.
     */
    fun createEchoReply(isIpv6: Boolean): IcmpPacket {
        val replyType = if (isIpv6) TYPE_ECHO_REPLY_V6 else TYPE_ECHO_REPLY_V4
        return IcmpPacket(
            type = replyType,
            code = 0,
            identifier = this.identifier,
            sequenceNumber = this.sequenceNumber,
            payload = this.payload
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IcmpPacket
        if (type != other.type) return false
        if (code != other.code) return false
        if (identifier != other.identifier) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + code
        result = 31 * result + identifier
        result = 31 * result + sequenceNumber
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 8

        const val TYPE_ECHO_REPLY_V4 = 0
        const val TYPE_ECHO_REQUEST_V4 = 8

        const val TYPE_ECHO_REQUEST_V6 = 128
        const val TYPE_ECHO_REPLY_V6 = 129

        /**
         * Parsea un datagrama ICMP a partir de los bytes del payload de un paquete IP.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): IcmpPacket? {
            if (length < HEADER_SIZE) return null

            val type = data[offset].toInt() and 0xFF
            val code = data[offset + 1].toInt() and 0xFF
            val checksum = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val identifier = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
            val seqNumber = ((data[offset + 6].toInt() and 0xFF) shl 8) or (data[offset + 7].toInt() and 0xFF)

            val payloadLength = length - HEADER_SIZE
            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + HEADER_SIZE, offset + length)
            } else {
                ByteArray(0)
            }

            return IcmpPacket(
                type = type,
                code = code,
                checksum = checksum,
                identifier = identifier,
                sequenceNumber = seqNumber,
                payload = payload
            )
        }
    }
}

