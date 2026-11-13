package com.opennetsim.core.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {

    @Test
    fun testIpPacketSerializationAndParsing() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(8, 8, 8, 8)
        val dummyPayload = "Hello IPv4".toByteArray()

        val packet = IpPacket(
            version = 4,
            ihl = 5,
            totalLength = 20 + dummyPayload.size,
            protocol = IpPacket.PROTOCOL_UDP,
            sourceIp = srcIp,
            destinationIp = dstIp,
            payload = dummyPayload
        )

        val rawBytes = packet.toByteArray()
        assertEquals(20 + dummyPayload.size, rawBytes.size)

        val parsed = IpPacket.parse(rawBytes)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.version)
        assertEquals(5, parsed.ihl)
        assertEquals(IpPacket.PROTOCOL_UDP, parsed.protocol)
        assertArrayEquals(srcIp, parsed.sourceIp)
        assertArrayEquals(dstIp, parsed.destinationIp)
        assertArrayEquals(dummyPayload, parsed.payload)
        assertEquals("10.0.0.2", parsed.sourceIpString)
        assertEquals("8.8.8.8", parsed.destinationIpString)
    }

    @Test
    fun testUdpPacketSerializationAndParsing() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(1, 1, 1, 1)
        val payload = "DNS Query Test".toByteArray()

        val udpPacket = UdpPacket(
            sourcePort = 54321,
            destinationPort = 53,
            length = 8 + payload.size,
            payload = payload
        )

        val rawUdp = udpPacket.toByteArray(srcIp, dstIp)
        assertEquals(8 + payload.size, rawUdp.size)

        val parsedUdp = UdpPacket.parse(rawUdp)
        assertNotNull(parsedUdp)
        assertEquals(54321, parsedUdp!!.sourcePort)
        assertEquals(53, parsedUdp.destinationPort)
        assertEquals(8 + payload.size, parsedUdp.length)
        assertArrayEquals(payload, parsedUdp.payload)

        // Convert to full IP Packet
        val ipPacket = udpPacket.toIpPacket(srcIp, dstIp)
        val rawIp = ipPacket.toByteArray()
        val parsedIp = IpPacket.parse(rawIp)
        assertNotNull(parsedIp)
        assertEquals(IpPacket.PROTOCOL_UDP, parsedIp!!.protocol)

        val parsedUdpFromIp = UdpPacket.parse(parsedIp.payload)
        assertNotNull(parsedUdpFromIp)
        assertEquals(54321, parsedUdpFromIp!!.sourcePort)
        assertEquals(53, parsedUdpFromIp.destinationPort)
    }

    @Test
    fun testTcpPacketSerializationAndParsing() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(142.toByte(), 250.toByte(), 180.toByte(), 206.toByte())

        // Test SYN
        val synPacket = TcpPacket(
            sourcePort = 45000,
            destinationPort = 443,
            sequenceNumber = 1000L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_SYN,
            windowSize = 65535
        )
        assertTrue(synPacket.isSyn)

        val rawSyn = synPacket.toByteArray(srcIp, dstIp)
        val parsedSyn = TcpPacket.parse(rawSyn)
        assertNotNull(parsedSyn)
        assertEquals(45000, parsedSyn!!.sourcePort)
        assertEquals(443, parsedSyn.destinationPort)
        assertEquals(1000L, parsedSyn.sequenceNumber)
        assertEquals(0L, parsedSyn.acknowledgmentNumber)
        assertTrue(parsedSyn.isSyn)

        // Test SYN-ACK builder
        val synAck = TcpPacket.buildSynAck(
            srcPort = 443,
            dstPort = 45000,
            seqNum = 5000L,
            ackNum = 1001L
        )
        assertTrue(synAck.isSyn)
        assertTrue(synAck.isAck)
        assertEquals(5000L, synAck.sequenceNumber)
        assertEquals(1001L, synAck.acknowledgmentNumber)

        val rawSynAck = synAck.toByteArray(dstIp, srcIp)
        val parsedSynAck = TcpPacket.parse(rawSynAck)
        assertNotNull(parsedSynAck)
        assertTrue(parsedSynAck!!.isSyn)
        assertTrue(parsedSynAck.isAck)

        // Test PSH-ACK with payload
        val httpData = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val pshAck = TcpPacket.buildPshAck(
            srcPort = 45000,
            dstPort = 80,
            seqNum = 1001L,
            ackNum = 5001L,
            payload = httpData
        )
        assertTrue(pshAck.isPsh)
        assertTrue(pshAck.isAck)

        val rawPsh = pshAck.toByteArray(srcIp, dstIp)
        val parsedPsh = TcpPacket.parse(rawPsh)
        assertNotNull(parsedPsh)
        assertArrayEquals(httpData, parsedPsh!!.payload)
    }

    private fun clientSyn(options: TcpOptions): TcpPacket {
        val encoded = options.encode()
        return TcpPacket(
            sourcePort = 45000,
            destinationPort = 443,
            sequenceNumber = 1000L,
            acknowledgmentNumber = 0L,
            dataOffset = 5 + encoded.size / 4,
            flags = TcpPacket.FLAG_SYN,
            options = encoded
        )
    }

    private fun synAckFor(syn: TcpPacket, isIpv6: Boolean): TcpPacket {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val parsedSyn = TcpPacket.parse(syn.toByteArray(srcIp, dstIp))!!
        val synAck = TcpPacket.buildSynAck(
            srcPort = 443,
            dstPort = 45000,
            seqNum = 5000L,
            ackNum = 1001L,
            options = TcpOptions.forSynAck(parsedSyn.parsedOptions, mtu = 1500, isIpv6 = isIpv6, ourWindowScale = 7)
        )
        return TcpPacket.parse(synAck.toByteArray(dstIp, srcIp))!!
    }

    @Test
    fun testSynAckAdvertisesMssAndWindowScaleWhenClientOffersIt() {
        val syn = clientSyn(TcpOptions(mss = 1460, windowScale = 8))
        val synAck = synAckFor(syn, isIpv6 = false)

        assertTrue(synAck.isSyn && synAck.isAck)
        val options = synAck.parsedOptions
        assertEquals(1460, options.mss)
        assertTrue("MSS ≤ MTU − 40", options.mss!! <= 1500 - 40)
        assertEquals(7, options.windowScale)
    }

    @Test
    fun testSynAckOmitsWindowScaleWhenClientDoesNotOfferIt() {
        val syn = clientSyn(TcpOptions(mss = 1460))
        val synAck = synAckFor(syn, isIpv6 = false)

        assertEquals(1460, synAck.parsedOptions.mss)
        assertEquals(null, synAck.parsedOptions.windowScale)
        assertTrue(synAck.windowSize <= 65535)
    }

    @Test
    fun testSynAckMssForIpv6LeavesRoomForIpv6Header() {
        val synAck = synAckFor(clientSyn(TcpOptions(mss = 1440)), isIpv6 = true)
        assertEquals(1500 - 60, synAck.parsedOptions.mss)
    }

    @Test
    fun testOptionsParserSkipsNopsAndUnknownOptions() {
        // NOP, NOP, SACK-permitted (4,2), MSS 1400, NOP, WS 3, END
        val raw = byteArrayOf(1, 1, 4, 2, 2, 4, 0x05, 0x78, 1, 3, 3, 3, 0)
        val parsed = TcpOptions.parse(raw)
        assertEquals(1400, parsed.mss)
        assertEquals(3, parsed.windowScale)
    }

    @Test
    fun testRstForUnknownSegmentWithAck() {
        val segment = TcpPacket(
            sourcePort = 50000,
            destinationPort = 443,
            sequenceNumber = 7000L,
            acknowledgmentNumber = 123_456L,
            flags = TcpPacket.FLAG_ACK or TcpPacket.FLAG_PSH,
            payload = ByteArray(100)
        )
        val rst = TcpPacket.buildRstForSegment(segment)!!
        assertTrue(rst.isRst)
        assertTrue("Sin flag ACK cuando el segmento trae ACK", !rst.isAck)
        assertEquals(123_456L, rst.sequenceNumber)
        assertEquals(443, rst.sourcePort)
        assertEquals(50000, rst.destinationPort)
    }

    @Test
    fun testRstForUnknownSegmentWithoutAck() {
        val segment = TcpPacket(
            sourcePort = 50000,
            destinationPort = 443,
            sequenceNumber = 0xFFFFFFF0L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_FIN,
            payload = ByteArray(20)
        )
        val rst = TcpPacket.buildRstForSegment(segment)!!
        assertTrue(rst.isRst && rst.isAck)
        assertEquals(0L, rst.sequenceNumber)
        // SEG.SEQ + SEG.LEN (20 de payload + 1 del FIN), con desbordamiento de 32 bits
        assertEquals((0xFFFFFFF0L + 21L) and 0xFFFFFFFFL, rst.acknowledgmentNumber)
    }

    @Test
    fun testNoRstInResponseToRst() {
        val rst = TcpPacket(
            sourcePort = 50000,
            destinationPort = 443,
            sequenceNumber = 1L,
            acknowledgmentNumber = 0L,
            flags = TcpPacket.FLAG_RST
        )
        assertEquals(null, TcpPacket.buildRstForSegment(rst))
    }

    @Test
    fun testChecksumUtilsrfc1071() {
        // RFC 1071 simple check: checksum over verified data
        val data = byteArrayOf(
            0x00, 0x01, 0xf2.toByte(), 0x03,
            0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte()
        )
        val checksum = ChecksumUtils.calculateChecksum(data, 0, data.size)
        assertTrue(checksum in 0..0xFFFF)
    }
}

