package com.opennetsim.core.packet

/**
 * Cabeceras de un paquete IP leídas sin copiar el payload, para clasificar y enrutar en el hot-path.
 *
 * @param protocol Protocolo de transporte (IPv4 `protocol` / IPv6 `next header`).
 * @param transportPayloadLength Bytes de datos del protocolo de transporte (sin su cabecera).
 * @param flowId Hash de la 5-tupla (versión, protocolo, direcciones y puertos), estable por dirección.
 */
@Suppress("LongParameterList") // Constructor privado: solo lo usa el parser.
class PacketMeta private constructor(
    val version: Int,
    val protocol: Int,
    val sourcePort: Int,
    val destinationPort: Int,
    val tcpFlags: Int,
    val transportPayloadLength: Int,
    val flowId: Long
) {
    val isTcp: Boolean get() = protocol == IpPacket.PROTOCOL_TCP
    val isUdp: Boolean get() = protocol == IpPacket.PROTOCOL_UDP
    val isIcmp: Boolean
        get() = (version == IPV4 && protocol == IpPacket.PROTOCOL_ICMP) ||
            (version == IPV6 && protocol == Ipv6Packet.PROTOCOL_ICMPV6)

    /** Segmento TCP sin datos: ACK puro, SYN, FIN o RST. */
    val isTcpWithoutPayload: Boolean get() = isTcp && transportPayloadLength == 0

    val isDns: Boolean get() = isUdp && (sourcePort == DNS_PORT || destinationPort == DNS_PORT)

    /** Ubicación de las cabeceras dentro del paquete. */
    private class Layout(
        val version: Int,
        val protocol: Int,
        val addressOffset: Int,
        val addressLength: Int,
        /** Inicio de la cabecera de transporte, o -1 si no hay (fragmento no inicial). */
        val transportOffset: Int,
        val totalLength: Int
    )

    companion object {
        private const val IPV4 = 4
        private const val IPV6 = 6
        private const val BYTE_MASK = 0xFF
        private const val HIGH_NIBBLE_MASK = 0xF0
        private const val LOW_NIBBLE_MASK = 0x0F
        private const val NIBBLE_BITS = 4
        private const val BYTE_BITS = 8
        private const val WORD_BYTES = 4

        private const val IPV4_MIN_HEADER = 20
        private const val IPV4_TOTAL_LENGTH_OFFSET = 2
        private const val IPV4_FRAGMENT_OFFSET = 6
        private const val IPV4_PROTOCOL_OFFSET = 9
        private const val IPV4_ADDRESS_OFFSET = 12
        private const val IPV4_ADDRESS_LENGTH = 4
        private const val FRAGMENT_OFFSET_MASK = 0x1FFF

        private const val IPV6_HEADER = 40
        private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
        private const val IPV6_NEXT_HEADER_OFFSET = 6
        private const val IPV6_ADDRESS_OFFSET = 8
        private const val IPV6_ADDRESS_LENGTH = 16

        private const val TCP_MIN_HEADER = 20
        private const val TCP_DATA_OFFSET_OFFSET = 12
        private const val TCP_FLAGS_OFFSET = 13
        private const val UDP_HEADER = 8
        private const val DESTINATION_PORT_OFFSET = 2
        private const val DNS_PORT = 53

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L

        /**
         * Lee las cabeceras de [data] (primeros [length] bytes). Devuelve null si no es IPv4/IPv6 válido.
         */
        fun parse(data: ByteArray, length: Int = data.size): PacketMeta? {
            val version = if (length < 1) 0 else (data[0].toInt() and HIGH_NIBBLE_MASK) ushr NIBBLE_BITS
            val layout = when (version) {
                IPV4 -> ipv4Layout(data, length)
                IPV6 -> ipv6Layout(data, length)
                else -> null
            }
            return layout?.let { build(data, it) }
        }

        private fun ipv4Layout(data: ByteArray, length: Int): Layout? {
            if (length < IPV4_MIN_HEADER) return null
            val headerLength = (data[0].toInt() and LOW_NIBBLE_MASK) * WORD_BYTES
            val totalLength = minOf(u16(data, IPV4_TOTAL_LENGTH_OFFSET), length)
            // Los fragmentos no iniciales no llevan cabecera de transporte.
            val isLaterFragment = (u16(data, IPV4_FRAGMENT_OFFSET) and FRAGMENT_OFFSET_MASK) != 0
            val valid = headerLength >= IPV4_MIN_HEADER && totalLength >= headerLength
            return if (!valid) {
                null
            } else {
                Layout(
                    version = IPV4,
                    protocol = data[IPV4_PROTOCOL_OFFSET].toInt() and BYTE_MASK,
                    addressOffset = IPV4_ADDRESS_OFFSET,
                    addressLength = IPV4_ADDRESS_LENGTH,
                    transportOffset = if (isLaterFragment) -1 else headerLength,
                    totalLength = totalLength
                )
            }
        }

        private fun ipv6Layout(data: ByteArray, length: Int): Layout? {
            if (length < IPV6_HEADER) return null
            return Layout(
                version = IPV6,
                protocol = data[IPV6_NEXT_HEADER_OFFSET].toInt() and BYTE_MASK,
                addressOffset = IPV6_ADDRESS_OFFSET,
                addressLength = IPV6_ADDRESS_LENGTH,
                transportOffset = IPV6_HEADER,
                totalLength = minOf(IPV6_HEADER + u16(data, IPV6_PAYLOAD_LENGTH_OFFSET), length)
            )
        }

        private fun build(data: ByteArray, layout: Layout): PacketMeta {
            val offset = layout.transportOffset
            val transportLength = layout.totalLength - offset
            val hasTransport = offset >= 0
            val hasTcpHeader = hasTransport &&
                layout.protocol == IpPacket.PROTOCOL_TCP &&
                transportLength >= TCP_MIN_HEADER
            val hasUdpHeader = hasTransport &&
                layout.protocol == IpPacket.PROTOCOL_UDP &&
                transportLength >= UDP_HEADER
            val hasPorts = hasTcpHeader || hasUdpHeader

            val sourcePort = if (hasPorts) u16(data, offset) else 0
            val destinationPort = if (hasPorts) u16(data, offset + DESTINATION_PORT_OFFSET) else 0
            val tcpFlags = if (hasTcpHeader) data[offset + TCP_FLAGS_OFFSET].toInt() and BYTE_MASK else 0
            val payloadLength = when {
                hasTcpHeader -> {
                    val dataOffsetWords =
                        (data[offset + TCP_DATA_OFFSET_OFFSET].toInt() and HIGH_NIBBLE_MASK) ushr NIBBLE_BITS
                    val tcpHeaderLength = dataOffsetWords * WORD_BYTES
                    (transportLength - tcpHeaderLength).coerceAtLeast(0)
                }
                hasUdpHeader -> transportLength - UDP_HEADER
                hasTransport -> transportLength.coerceAtLeast(0)
                else -> 0
            }

            return PacketMeta(
                version = layout.version,
                protocol = layout.protocol,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                tcpFlags = tcpFlags,
                transportPayloadLength = payloadLength,
                flowId = flowHash(data, layout, sourcePort, destinationPort)
            )
        }

        /** FNV-1a de 64 bits sobre la 5-tupla. Nunca devuelve Long.MIN_VALUE (reservado: "sin flujo"). */
        private fun flowHash(data: ByteArray, layout: Layout, sourcePort: Int, destinationPort: Int): Long {
            var hash = FNV_OFFSET
            hash = mix(hash, layout.version)
            hash = mix(hash, layout.protocol)
            val addressEnd = layout.addressOffset + 2 * layout.addressLength
            for (i in layout.addressOffset until addressEnd) hash = mix(hash, data[i].toInt() and BYTE_MASK)
            hash = mix(hash, sourcePort ushr BYTE_BITS)
            hash = mix(hash, sourcePort and BYTE_MASK)
            hash = mix(hash, destinationPort ushr BYTE_BITS)
            hash = mix(hash, destinationPort and BYTE_MASK)
            return if (hash == Long.MIN_VALUE) hash + 1 else hash
        }

        private fun mix(hash: Long, octet: Int): Long = (hash xor octet.toLong()) * FNV_PRIME

        private fun u16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and BYTE_MASK) shl BYTE_BITS) or (data[offset + 1].toInt() and BYTE_MASK)
    }
}
