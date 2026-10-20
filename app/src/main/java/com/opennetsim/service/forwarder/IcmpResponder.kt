package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IcmpPacket
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet

/**
 * Responde a solicitudes ICMP Echo Request (IPv4 e IPv6) procedentes del TUN.
 * Permite que comandos de diagnóstico como 'ping' funcionen de forma realista
 * y reporten la latencia y pérdida de paquetes configuradas en el motor.
 */
class IcmpResponder(
    private val onInboundPacket: (ByteArray) -> Unit
) {
    /**
     * Procesa un paquete IPv4 con protocolo ICMP (1).
     */
    fun processOutboundIpv4(ipPacket: IpPacket) {
        val icmp = IcmpPacket.parse(ipPacket.payload) ?: return
        if (icmp.isEchoRequestV4) {
            val echoReply = icmp.createEchoReply(isIpv6 = false)
            val replyIcmpBytes = echoReply.toByteArray(isIpv6 = false)

            val responseIp = IpPacket(
                version = 4,
                ihl = 5,
                totalLength = 20 + replyIcmpBytes.size,
                protocol = IpPacket.PROTOCOL_ICMP,
                sourceIp = ipPacket.destinationIp,
                destinationIp = ipPacket.sourceIp,
                payload = replyIcmpBytes
            )

            onInboundPacket(responseIp.toByteArray())
        }
    }

    /**
     * Procesa un paquete IPv6 con Next Header ICMPv6 (58).
     */
    fun processOutboundIpv6(ipv6Packet: Ipv6Packet) {
        val icmp = IcmpPacket.parse(ipv6Packet.payload) ?: return
        if (icmp.isEchoRequestV6) {
            val echoReply = icmp.createEchoReply(isIpv6 = true)
            val replyIcmpBytes = echoReply.toByteArray(
                isIpv6 = true,
                srcIp = ipv6Packet.destinationIp,
                dstIp = ipv6Packet.sourceIp
            )

            val responseIpv6 = Ipv6Packet(
                version = 6,
                payloadLength = replyIcmpBytes.size,
                nextHeader = Ipv6Packet.PROTOCOL_ICMPV6,
                sourceIp = ipv6Packet.destinationIp,
                destinationIp = ipv6Packet.sourceIp,
                payload = replyIcmpBytes
            )

            onInboundPacket(responseIpv6.toByteArray())
        }
    }
}

