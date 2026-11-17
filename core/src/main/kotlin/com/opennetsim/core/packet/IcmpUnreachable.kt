package com.opennetsim.core.packet

/**
 * Motivo de un ICMP / ICMPv6 de destino inalcanzable.
 */
enum class UnreachableKind(val ipv4Code: Int, val ipv6Code: Int) {
    /** IPv4 código 0 (net unreachable) / ICMPv6 código 0 (no route to destination). */
    NETWORK(ipv4Code = 0, ipv6Code = 0),

    /** IPv4 código 1 (host unreachable) / ICMPv6 código 3 (address unreachable). */
    HOST(ipv4Code = 1, ipv6Code = 3),

    /** IPv4 código 3 (port unreachable) / ICMPv6 código 4 (port unreachable). */
    PORT(ipv4Code = 3, ipv6Code = 4)
}

/**
 * Construcción de mensajes ICMP de destino inalcanzable (RFC 792 tipo 3, RFC 4443 tipo 1) que el
 * túnel devuelve a la app cuando no puede entregar un datagrama.
 */
object IcmpUnreachable {
    const val TYPE_DEST_UNREACHABLE_V4 = 3
    const val TYPE_DEST_UNREACHABLE_V6 = 1

    /** Bytes del datagrama original que se citan tras la cabecera IPv4 (RFC 792). */
    private const val IPV4_QUOTED_TRANSPORT_BYTES = 8

    /** Un mensaje ICMPv6 de error no debe superar el MTU mínimo de IPv6 (RFC 4443, 2.4). */
    private const val IPV6_MIN_MTU = 1280
    private const val IPV6_HEADER = 40
    private const val IPV4_HEADER = 20
    private const val IHL_MASK = 0x0F
    private const val WORD_BYTES = 4

    /**
     * Mensaje ICMP que cita [originalPacket] (paquete IP completo enviado por la app).
     */
    fun buildIcmp(originalPacket: ByteArray, isIpv6: Boolean, kind: UnreachableKind): IcmpPacket {
        val quoteLength = if (isIpv6) {
            minOf(originalPacket.size, IPV6_MIN_MTU - IPV6_HEADER - IcmpPacket.HEADER_SIZE)
        } else {
            val ihl = if (originalPacket.isNotEmpty()) originalPacket[0].toInt() and IHL_MASK else 0
            minOf(originalPacket.size, ihl * WORD_BYTES + IPV4_QUOTED_TRANSPORT_BYTES)
        }
        return IcmpPacket(
            type = if (isIpv6) TYPE_DEST_UNREACHABLE_V6 else TYPE_DEST_UNREACHABLE_V4,
            code = if (isIpv6) kind.ipv6Code else kind.ipv4Code,
            identifier = 0, // Campo "unused" de los mensajes de error
            sequenceNumber = 0,
            payload = originalPacket.copyOf(quoteLength)
        )
    }

    /**
     * Paquete IP completo, de [reporterIp] a [clientIp], con el ICMP de destino inalcanzable.
     */
    fun buildIpPacket(
        originalPacket: ByteArray,
        isIpv6: Boolean,
        kind: UnreachableKind,
        reporterIp: ByteArray,
        clientIp: ByteArray
    ): ByteArray {
        val icmp = buildIcmp(originalPacket, isIpv6, kind)
        return if (isIpv6) {
            val icmpBytes = icmp.toByteArray(isIpv6 = true, srcIp = reporterIp, dstIp = clientIp)
            Ipv6Packet(
                version = 6,
                payloadLength = icmpBytes.size,
                nextHeader = Ipv6Packet.PROTOCOL_ICMPV6,
                sourceIp = reporterIp,
                destinationIp = clientIp,
                payload = icmpBytes
            ).toByteArray()
        } else {
            val icmpBytes = icmp.toByteArray(isIpv6 = false)
            IpPacket(
                version = 4,
                ihl = 5,
                totalLength = IPV4_HEADER + icmpBytes.size,
                protocol = IpPacket.PROTOCOL_ICMP,
                sourceIp = reporterIp,
                destinationIp = clientIp,
                payload = icmpBytes
            ).toByteArray()
        }
    }
}
