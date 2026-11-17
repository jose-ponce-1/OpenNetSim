package com.opennetsim.core.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class IcmpUnreachableTest {

    private val clientV4 = byteArrayOf(10, 0, 0, 2)
    private val serverV4 = byteArrayOf(8, 8, 8, 8)
    private val clientV6 = ipv6("fd00::2")
    private val serverV6 = ipv6("2001:4860::88")

    private fun ipv6(address: String): ByteArray = java.net.InetAddress.getByName(address).address

    private fun dnsQuery(payloadSize: Int) =
        UdpPacket(40000, 53, UdpPacket.HEADER_SIZE + payloadSize, payload = ByteArray(payloadSize) { 7 })

    @Test
    fun ipv4PortUnreachableQuotesHeaderPlusEightBytes() {
        val original = dnsQuery(40).toIpPacket(clientV4, serverV4).toByteArray()
        val raw = IcmpUnreachable.buildIpPacket(original, isIpv6 = false, UnreachableKind.PORT, serverV4, clientV4)

        val ip = IpPacket.parse(raw)!!
        assertEquals(IpPacket.PROTOCOL_ICMP, ip.protocol)
        assertArrayEquals(serverV4, ip.sourceIp)
        assertArrayEquals(clientV4, ip.destinationIp)

        val icmp = IcmpPacket.parse(ip.payload)!!
        assertEquals(IcmpUnreachable.TYPE_DEST_UNREACHABLE_V4, icmp.type)
        assertEquals(3, icmp.code)
        assertArrayEquals("Cabecera IP (20) + 8 bytes de UDP", original.copyOf(28), icmp.payload)
        assertEquals("Checksum ICMP válido", 0, ChecksumUtils.calculateChecksum(ip.payload, 0, ip.payload.size))
    }

    @Test
    fun ipv4NetworkAndHostCodes() {
        val original = dnsQuery(10).toIpPacket(clientV4, serverV4).toByteArray()
        val net = IcmpPacket.parse(IcmpUnreachable.buildIcmp(original, false, UnreachableKind.NETWORK).toByteArray())!!
        val host = IcmpPacket.parse(IcmpUnreachable.buildIcmp(original, false, UnreachableKind.HOST).toByteArray())!!
        assertEquals(0, net.code)
        assertEquals(1, host.code)
    }

    @Test
    fun ipv6NoRouteQuotesInvokingPacketWithinMinimumMtu() {
        val original = dnsQuery(2000).toIpv6Packet(clientV6, serverV6).toByteArray()
        val raw = IcmpUnreachable.buildIpPacket(original, isIpv6 = true, UnreachableKind.NETWORK, serverV6, clientV6)

        assertEquals("El error ICMPv6 no supera 1280 bytes", 1280, raw.size)
        val ipv6 = Ipv6Packet.parse(raw)!!
        assertEquals(Ipv6Packet.PROTOCOL_ICMPV6, ipv6.nextHeader)
        val icmp = IcmpPacket.parse(ipv6.payload)!!
        assertEquals(IcmpUnreachable.TYPE_DEST_UNREACHABLE_V6, icmp.type)
        assertEquals(0, icmp.code)
        assertArrayEquals(original.copyOf(icmp.payload.size), icmp.payload)

        // Checksum con pseudo-cabecera IPv6: recalcular sobre el mensaje recibido da 0.
        val verify = ChecksumUtils.calculateTransportChecksum(
            srcIp = serverV6,
            dstIp = clientV6,
            protocol = Ipv6Packet.PROTOCOL_ICMPV6,
            segmentData = ipv6.payload,
            offset = 0,
            length = ipv6.payload.size
        )
        assertEquals(0, verify)
    }

    @Test
    fun ipv6PortUnreachableCode() {
        val original = dnsQuery(10).toIpv6Packet(clientV6, serverV6).toByteArray()
        assertEquals(4, IcmpUnreachable.buildIcmp(original, true, UnreachableKind.PORT).code)
    }
}
