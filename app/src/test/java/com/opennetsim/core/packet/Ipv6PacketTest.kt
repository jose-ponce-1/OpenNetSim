package com.opennetsim.core.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6PacketTest {

    @Test
    fun testIpv6PacketSerializationAndParsing() {
        val srcIp = Ipv6Packet.parseIpv6("2001:db8::1")
        val dstIp = Ipv6Packet.parseIpv6("2001:db8::2")
        val payload = "Hello IPv6".toByteArray()

        val packet = Ipv6Packet(
            version = 6,
            trafficClass = 0,
            flowLabel = 0x12345,
            payloadLength = payload.size,
            nextHeader = Ipv6Packet.PROTOCOL_UDP,
            hopLimit = 64,
            sourceIp = srcIp,
            destinationIp = dstIp,
            payload = payload
        )

        val rawBytes = packet.toByteArray()
        assertEquals(40 + payload.size, rawBytes.size)

        val parsed = Ipv6Packet.parse(rawBytes)
        assertNotNull(parsed)
        assertEquals(6, parsed!!.version)
        assertEquals(Ipv6Packet.PROTOCOL_UDP, parsed.nextHeader)
        assertEquals(0x12345, parsed.flowLabel)
        assertEquals(64, parsed.hopLimit)
        assertArrayEquals(srcIp, parsed.sourceIp)
        assertArrayEquals(dstIp, parsed.destinationIp)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun testUdpOverIpv6() {
        val srcIp = Ipv6Packet.parseIpv6("fd00::1")
        val dstIp = Ipv6Packet.parseIpv6("2001:4860:4860::8888")
        val payload = "DNS Over IPv6 Query".toByteArray()

        val udp = UdpPacket(
            sourcePort = 55555,
            destinationPort = 53,
            length = 8 + payload.size,
            payload = payload
        )

        val ipv6 = udp.toIpv6Packet(srcIp, dstIp)
        val raw = ipv6.toByteArray()

        val parsedIp = Ipv6Packet.parse(raw)
        assertNotNull(parsedIp)
        assertEquals(Ipv6Packet.PROTOCOL_UDP, parsedIp!!.nextHeader)

        val parsedUdp = UdpPacket.parse(parsedIp.payload)
        assertNotNull(parsedUdp)
        assertEquals(55555, parsedUdp!!.sourcePort)
        assertEquals(53, parsedUdp.destinationPort)
        assertArrayEquals(payload, parsedUdp.payload)
    }

    @Test
    fun testTcpOverIpv6() {
        val srcIp = Ipv6Packet.parseIpv6("fd00::2")
        val dstIp = Ipv6Packet.parseIpv6("2606:4700:4700::1111")

        val syn = TcpPacket(
            sourcePort = 48000,
            destinationPort = 443,
            sequenceNumber = 12345L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_SYN
        )

        val rawTcp = syn.toByteArray(srcIp, dstIp)
        val parsedTcp = TcpPacket.parse(rawTcp)
        assertNotNull(parsedTcp)
        assertEquals(48000, parsedTcp!!.sourcePort)
        assertEquals(443, parsedTcp.destinationPort)
        assertTrue(parsedTcp.isSyn)

        val ipv6 = syn.toIpv6Packet(srcIp, dstIp)
        val rawIp = ipv6.toByteArray()
        val parsedIp = Ipv6Packet.parse(rawIp)
        assertNotNull(parsedIp)
        assertEquals(Ipv6Packet.PROTOCOL_TCP, parsedIp!!.nextHeader)
    }
}

