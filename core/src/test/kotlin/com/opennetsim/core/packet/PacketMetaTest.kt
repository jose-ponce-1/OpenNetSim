package com.opennetsim.core.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketMetaTest {

    private val clientV4 = byteArrayOf(10, 0, 0, 2)
    private val serverV4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
    private val clientV6 = ipv6("fd00::2")
    private val serverV6 = ipv6("2001::88")

    private fun ipv6(address: String): ByteArray = java.net.InetAddress.getByName(address).address

    private fun tcp(payload: ByteArray, flags: Int = TcpPacket.FLAG_ACK, srcPort: Int = 50000) =
        TcpPacket(srcPort, 443, 1L, 1L, flags = flags, payload = payload)

    @Test
    fun parsesIpv4TcpHeadersWithoutPayloadCopy() {
        val segment = tcp(ByteArray(300), TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH)
        val raw = segment.toIpPacket(clientV4, serverV4).toByteArray()
        val meta = PacketMeta.parse(raw)!!
        assertEquals(4, meta.version)
        assertTrue(meta.isTcp)
        assertEquals(50000, meta.sourcePort)
        assertEquals(443, meta.destinationPort)
        assertEquals(300, meta.transportPayloadLength)
        assertFalse(meta.isTcpWithoutPayload)
        assertEquals(TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH, meta.tcpFlags)
    }

    @Test
    fun recognisesPureAckAndFinAsTcpWithoutPayload() {
        val ack = PacketMeta.parse(tcp(ByteArray(0)).toIpPacket(clientV4, serverV4).toByteArray())!!
        val fin = PacketMeta.parse(
            tcp(ByteArray(0), TcpPacket.FLAG_FIN or TcpPacket.FLAG_ACK).toIpPacket(clientV4, serverV4).toByteArray()
        )!!
        assertTrue(ack.isTcpWithoutPayload)
        assertTrue(fin.isTcpWithoutPayload)
        assertEquals(ack.flowId, fin.flowId)
    }

    @Test
    fun parsesIpv6TcpAndUdp() {
        val tcpMeta = PacketMeta.parse(tcp(ByteArray(10)).toIpv6Packet(clientV6, serverV6).toByteArray())!!
        assertEquals(6, tcpMeta.version)
        assertTrue(tcpMeta.isTcp)
        assertEquals(10, tcpMeta.transportPayloadLength)

        val dnsQuery = UdpPacket(40000, 53, UdpPacket.HEADER_SIZE + 30, payload = ByteArray(30))
        val udpMeta = PacketMeta.parse(dnsQuery.toIpv6Packet(clientV6, serverV6).toByteArray())!!
        assertTrue(udpMeta.isUdp)
        assertTrue(udpMeta.isDns)
        assertEquals(30, udpMeta.transportPayloadLength)
    }

    @Test
    fun detectsDnsByEitherPort() {
        val response = UdpPacket(53, 40000, UdpPacket.HEADER_SIZE + 64, payload = ByteArray(64))
        val meta = PacketMeta.parse(response.toIpPacket(serverV4, clientV4).toByteArray())!!
        assertTrue(meta.isDns)

        val quic = UdpPacket(40000, 443, UdpPacket.HEADER_SIZE + 64, payload = ByteArray(64))
        assertFalse(PacketMeta.parse(quic.toIpPacket(clientV4, serverV4).toByteArray())!!.isDns)
    }

    @Test
    fun recognisesIcmpV4AndV6() {
        val echo = IcmpPacket(type = 8, code = 0, identifier = 1, sequenceNumber = 1, payload = ByteArray(8))
        val v4 = IpPacket(
            version = 4,
            ihl = 5,
            totalLength = 20 + 16,
            protocol = IpPacket.PROTOCOL_ICMP,
            sourceIp = clientV4,
            destinationIp = serverV4,
            payload = echo.toByteArray(isIpv6 = false)
        ).toByteArray()
        assertTrue(PacketMeta.parse(v4)!!.isIcmp)

        val echoV6 = IcmpPacket(type = 128, code = 0, identifier = 1, sequenceNumber = 1, payload = ByteArray(8))
        val v6Payload = echoV6.toByteArray(isIpv6 = true, srcIp = clientV6, dstIp = serverV6)
        val v6 = Ipv6Packet(
            version = 6,
            payloadLength = v6Payload.size,
            nextHeader = Ipv6Packet.PROTOCOL_ICMPV6,
            sourceIp = clientV6,
            destinationIp = serverV6,
            payload = v6Payload
        ).toByteArray()
        assertTrue(PacketMeta.parse(v6)!!.isIcmp)
    }

    @Test
    fun flowIdDistinguishesFiveTuples() {
        val a = PacketMeta.parse(tcp(ByteArray(0), srcPort = 50000).toIpPacket(clientV4, serverV4).toByteArray())!!
        val b = PacketMeta.parse(tcp(ByteArray(0), srcPort = 50001).toIpPacket(clientV4, serverV4).toByteArray())!!
        val withPayload = tcp(ByteArray(900), srcPort = 50000).toIpPacket(clientV4, serverV4).toByteArray()
        val sameAsA = PacketMeta.parse(withPayload)!!
        assertNotEquals(a.flowId, b.flowId)
        assertEquals("El flujo no depende del payload", a.flowId, sameAsA.flowId)
        assertNotEquals(Long.MIN_VALUE, a.flowId)
    }

    @Test
    fun rejectsTruncatedOrUnknownPackets() {
        assertNull(PacketMeta.parse(ByteArray(0)))
        assertNull(PacketMeta.parse(ByteArray(10) { 0x45 }))
        assertNull(PacketMeta.parse(ByteArray(40) { 0x10 })) // versión 1
        assertNotNull(PacketMeta.parse(tcp(ByteArray(0)).toIpPacket(clientV4, serverV4).toByteArray()))
    }
}
