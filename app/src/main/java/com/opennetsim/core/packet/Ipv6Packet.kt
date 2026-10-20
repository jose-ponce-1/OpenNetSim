package com.opennetsim.core.packet

import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Representación, parser y serializador de paquetes IPv6 (RFC 8200).
 */
data class Ipv6Packet(
    val version: Int = 6,
    val trafficClass: Int = 0,
    val flowLabel: Int = 0,
    val payloadLength: Int,
    val nextHeader: Int, // 6 = TCP, 17 = UDP, 58 = ICMPv6
    val hopLimit: Int = 64,
    val sourceIp: ByteArray,
    val destinationIp: ByteArray,
    val payload: ByteArray
) {
    init {
        require(sourceIp.size == 16) { "sourceIp IPv6 debe tener exactamente 16 bytes" }
        require(destinationIp.size == 16) { "destinationIp IPv6 debe tener exactamente 16 bytes" }
    }

    val sourceIpString: String
        get() = formatIpv6(sourceIp)

    val destinationIpString: String
        get() = formatIpv6(destinationIp)

    /**
     * Serializa el paquete IPv6 completo a un ByteArray.
     */
    fun toByteArray(): ByteArray {
        val totalLength = HEADER_SIZE + payload.size
        val buffer = ByteBuffer.allocate(totalLength)

        // Version (4 bits = 6) + Traffic Class (8 bits) + Flow Label (20 bits) = 32 bits
        val vtcFlow = ((version and 0x0F) shl 28) or
                ((trafficClass and 0xFF) shl 20) or
                (flowLabel and 0x000FFFFF)
        buffer.putInt(vtcFlow)

        // Payload Length (16 bits)
        buffer.putShort(payload.size.toShort())

        // Next Header (8 bits)
        buffer.put(nextHeader.toByte())

        // Hop Limit (8 bits)
        buffer.put(hopLimit.toByte())

        // Source IP (16 bytes)
        buffer.put(sourceIp)

        // Destination IP (16 bytes)
        buffer.put(destinationIp)

        // Payload
        buffer.put(payload)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Ipv6Packet
        if (version != other.version) return false
        if (nextHeader != other.nextHeader) return false
        if (!sourceIp.contentEquals(other.sourceIp)) return false
        if (!destinationIp.contentEquals(other.destinationIp)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + nextHeader
        result = 31 * result + sourceIp.contentHashCode()
        result = 31 * result + destinationIp.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 40
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val PROTOCOL_ICMPV6 = 58

        fun formatIpv6(ipBytes: ByteArray): String {
            return try {
                InetAddress.getByAddress(ipBytes).hostAddress ?: "::"
            } catch (e: Exception) {
                "::"
            }
        }

        fun parseIpv6(ipString: String): ByteArray {
            val addr = Inet6Address.getByName(ipString)
            return addr.address
        }

        /**
         * Parsea un paquete IPv6 desde un array de bytes.
         * Retorna null si los datos no son un paquete IPv6 válido o están truncados.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): Ipv6Packet? {
            if (length < HEADER_SIZE) return null

            val byte0 = data[offset].toInt() and 0xFF
            val version = byte0 shr 4
            if (version != 6) return null // Solo IPv6

            val trafficClass = ((data[offset].toInt() and 0x0F) shl 4) or ((data[offset + 1].toInt() and 0xF0) ushr 4)
            val flowLabel = ((data[offset + 1].toInt() and 0x0F) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)

            val payloadLength = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
            val totalPacketLength = HEADER_SIZE + payloadLength
            if (length < totalPacketLength) return null

            val nextHeader = data[offset + 6].toInt() and 0xFF
            val hopLimit = data[offset + 7].toInt() and 0xFF

            val sourceIp = data.copyOfRange(offset + 8, offset + 24)
            val destinationIp = data.copyOfRange(offset + 24, offset + 40)

            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + HEADER_SIZE, offset + totalPacketLength)
            } else {
                ByteArray(0)
            }

            return Ipv6Packet(
                version = version,
                trafficClass = trafficClass,
                flowLabel = flowLabel,
                payloadLength = payloadLength,
                nextHeader = nextHeader,
                hopLimit = hopLimit,
                sourceIp = sourceIp,
                destinationIp = destinationIp,
                payload = payload
            )
        }
    }
}

