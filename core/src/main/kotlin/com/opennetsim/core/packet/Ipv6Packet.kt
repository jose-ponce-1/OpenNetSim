package com.opennetsim.core.packet

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Representación, parser y serializador de paquetes IPv6 (RFC 8200).
 *
 * Como en [IpPacket], el payload es una vista sobre [payloadBuffer]; tras [parse] no se copia.
 */
@Suppress("LongParameterList") // Campos de la cabecera IPv6, casi todos con valor por defecto.
class Ipv6Packet(
    val version: Int = VERSION,
    val trafficClass: Int = 0,
    val flowLabel: Int = 0,
    val payloadLength: Int,
    val nextHeader: Int,
    val hopLimit: Int = DEFAULT_HOP_LIMIT,
    val sourceIp: ByteArray,
    val destinationIp: ByteArray,
    payload: ByteArray,
    val payloadOffset: Int = 0,
    val payloadSize: Int = payload.size - payloadOffset
) {
    init {
        require(sourceIp.size == ADDRESS_BYTES) { "sourceIp IPv6 debe tener exactamente 16 bytes" }
        require(destinationIp.size == ADDRESS_BYTES) { "destinationIp IPv6 debe tener exactamente 16 bytes" }
        require(payloadOffset >= 0 && payloadSize >= 0 && payloadOffset + payloadSize <= payload.size) {
            "La vista del payload se sale del array"
        }
    }

    /** Array que contiene el payload; tras [parse], el paquete entero. */
    val payloadBuffer: ByteArray = payload

    private var ownedPayload: ByteArray? = null

    /** Payload en un array propio. Sin copia si la vista ocupa todo [payloadBuffer]. */
    val payload: ByteArray
        get() = ownedPayload ?: copyPayload().also { ownedPayload = it }

    private fun copyPayload(): ByteArray =
        if (payloadOffset == 0 && payloadSize == payloadBuffer.size) {
            payloadBuffer
        } else {
            payloadBuffer.copyOfRange(payloadOffset, payloadOffset + payloadSize)
        }

    val sourceIpString: String
        get() = formatIpv6(sourceIp)

    val destinationIpString: String
        get() = formatIpv6(destinationIp)

    /**
     * Serializa el paquete IPv6 completo a un ByteArray. La longitud del payload se toma de la vista, no de
     * [payloadLength].
     */
    fun toByteArray(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payloadSize)
        // Versión (4 bits), clase de tráfico (8 bits) y etiqueta de flujo (20 bits).
        val versionClassFlow = ((version and NIBBLE_MASK) shl VERSION_SHIFT) or
            ((trafficClass and BYTE_MASK) shl TRAFFIC_CLASS_SHIFT) or
            (flowLabel and FLOW_LABEL_MASK)
        PacketBytes.writeU32(out, 0, versionClassFlow.toLong())
        PacketBytes.writeU16(out, PAYLOAD_LENGTH_OFFSET, payloadSize)
        out[NEXT_HEADER_OFFSET] = nextHeader.toByte()
        out[HOP_LIMIT_OFFSET] = hopLimit.toByte()
        System.arraycopy(sourceIp, 0, out, SOURCE_OFFSET, ADDRESS_BYTES)
        System.arraycopy(destinationIp, 0, out, DESTINATION_OFFSET, ADDRESS_BYTES)
        System.arraycopy(payloadBuffer, payloadOffset, out, HEADER_SIZE, payloadSize)
        return out
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
        result = HASH_MULTIPLIER * result + nextHeader
        result = HASH_MULTIPLIER * result + sourceIp.contentHashCode()
        result = HASH_MULTIPLIER * result + destinationIp.contentHashCode()
        result = HASH_MULTIPLIER * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 40
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val PROTOCOL_ICMPV6 = 58
        private const val VERSION = 6
        private const val VERSION_BYTE = 0x60
        private const val VERSION_SHIFT = 28
        private const val TRAFFIC_CLASS_SHIFT = 20
        private const val FLOW_LABEL_MASK = 0x000FFFFF
        private const val FLOW_LABEL_HIGH_SHIFT = 16
        private const val NIBBLE_BITS = 4
        private const val NIBBLE_MASK = 0x0F
        private const val HIGH_NIBBLE_MASK = 0xF0
        private const val BYTE_MASK = 0xFF
        private const val HASH_MULTIPLIER = 31
        private const val FLOW_LABEL_LOW_OFFSET = 2
        private const val DEFAULT_HOP_LIMIT = 64
        private const val PAYLOAD_LENGTH_OFFSET = 4
        private const val NEXT_HEADER_OFFSET = 6
        private const val HOP_LIMIT_OFFSET = 7
        private const val SOURCE_OFFSET = 8
        private const val DESTINATION_OFFSET = 24
        private const val ADDRESS_BYTES = 16

        /**
         * Escribe en [out] desde [offset] una cabecera IPv6 de [HEADER_SIZE] bytes para [payloadLength]
         * bytes de payload. Es la misma cabecera que [toByteArray] con los valores por defecto (clase de
         * tráfico y etiqueta de flujo 0, hop limit 64); el resto de [out] debe estar a cero.
         */
        @Suppress("LongParameterList")
        fun writeHeader(
            out: ByteArray,
            offset: Int,
            nextHeader: Int,
            payloadLength: Int,
            sourceIp: ByteArray,
            destinationIp: ByteArray
        ) {
            out[offset] = VERSION_BYTE.toByte()
            PacketBytes.writeU16(out, offset + PAYLOAD_LENGTH_OFFSET, payloadLength)
            out[offset + NEXT_HEADER_OFFSET] = nextHeader.toByte()
            out[offset + HOP_LIMIT_OFFSET] = DEFAULT_HOP_LIMIT.toByte()
            System.arraycopy(sourceIp, 0, out, offset + SOURCE_OFFSET, ADDRESS_BYTES)
            System.arraycopy(destinationIp, 0, out, offset + DESTINATION_OFFSET, ADDRESS_BYTES)
        }

        fun formatIpv6(ipBytes: ByteArray): String =
            try {
                InetAddress.getByAddress(ipBytes).hostAddress ?: "::"
            } catch (ignored: UnknownHostException) {
                // Solo ocurre con una longitud que no es de IPv4 ni de IPv6.
                "::"
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
            val payloadLength = validPayloadLength(data, offset, length)
            if (payloadLength == PacketBytes.INVALID) return null
            val byte0 = data[offset].toInt() and BYTE_MASK
            val byte1 = data[offset + 1].toInt() and BYTE_MASK
            return Ipv6Packet(
                version = VERSION,
                trafficClass = ((byte0 and NIBBLE_MASK) shl NIBBLE_BITS) or
                    ((byte1 and HIGH_NIBBLE_MASK) ushr NIBBLE_BITS),
                flowLabel = ((byte1 and NIBBLE_MASK) shl FLOW_LABEL_HIGH_SHIFT) or
                    PacketBytes.readU16(data, offset + FLOW_LABEL_LOW_OFFSET),
                payloadLength = payloadLength,
                nextHeader = data[offset + NEXT_HEADER_OFFSET].toInt() and BYTE_MASK,
                hopLimit = data[offset + HOP_LIMIT_OFFSET].toInt() and BYTE_MASK,
                sourceIp = address(data, offset + SOURCE_OFFSET),
                destinationIp = address(data, offset + DESTINATION_OFFSET),
                payload = data,
                payloadOffset = offset + HEADER_SIZE,
                payloadSize = payloadLength
            )
        }

        /** Longitud del payload si [data] empieza por un paquete IPv6 completo; si no, [PacketBytes.INVALID]. */
        private fun validPayloadLength(data: ByteArray, offset: Int, length: Int): Int {
            val isIpv6 = length >= HEADER_SIZE && (data[offset].toInt() and BYTE_MASK) ushr NIBBLE_BITS == VERSION
            val payloadLength = if (isIpv6) PacketBytes.readU16(data, offset + PAYLOAD_LENGTH_OFFSET) else 0
            return if (isIpv6 && length >= HEADER_SIZE + payloadLength) payloadLength else PacketBytes.INVALID
        }

        private fun address(data: ByteArray, start: Int): ByteArray = data.copyOfRange(start, start + ADDRESS_BYTES)
    }
}
