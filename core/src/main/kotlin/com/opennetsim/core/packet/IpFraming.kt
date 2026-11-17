package com.opennetsim.core.packet

/**
 * Paquete IP de salida en un único array: cabecera IPv4 o IPv6 (según el tamaño de las direcciones)
 * seguida del espacio para el transporte, que escribe quien llama a partir de [headerSize].
 */
internal object IpFraming {
    private const val IPV6_ADDRESS_BYTES = 16

    fun headerSize(srcIp: ByteArray): Int =
        if (srcIp.size == IPV6_ADDRESS_BYTES) Ipv6Packet.HEADER_SIZE else IpPacket.HEADER_SIZE

    /**
     * Reserva el paquete y escribe la cabecera IP. Los números de protocolo de TCP y UDP coinciden en
     * IPv4 (`protocol`) e IPv6 (`next header`).
     */
    fun allocate(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, transportLength: Int): ByteArray {
        val headerSize = headerSize(srcIp)
        val out = ByteArray(headerSize + transportLength)
        if (headerSize == Ipv6Packet.HEADER_SIZE) {
            Ipv6Packet.writeHeader(out, 0, protocol, transportLength, srcIp, dstIp)
        } else {
            IpPacket.writeHeader(out, 0, protocol, headerSize + transportLength, srcIp, dstIp)
        }
        return out
    }
}
