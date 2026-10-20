package com.opennetsim.core.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpPacketTest {

    @Test
    fun testIcmpV4EchoRequestAndReply() {
        val payload = "PingData123".toByteArray()
        val echoReq = IcmpPacket(
            type = IcmpPacket.TYPE_ECHO_REQUEST_V4,
            code = 0,
            identifier = 1234,
            sequenceNumber = 1,
            payload = payload
        )

        assertTrue(echoReq.isEchoRequestV4)

        val rawReq = echoReq.toByteArray(isIpv6 = false)
        assertEquals(8 + payload.size, rawReq.size)

        val parsedReq = IcmpPacket.parse(rawReq)
        assertNotNull(parsedReq)
        assertEquals(IcmpPacket.TYPE_ECHO_REQUEST_V4, parsedReq!!.type)
        assertEquals(1234, parsedReq.identifier)
        assertEquals(1, parsedReq.sequenceNumber)
        assertArrayEquals(payload, parsedReq.payload)

        // Generar Echo Reply
        val reply = parsedReq.createEchoReply(isIpv6 = false)
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V4, reply.type)
        assertEquals(0, reply.code)
        assertEquals(1234, reply.identifier)
        assertEquals(1, reply.sequenceNumber)
        assertArrayEquals(payload, reply.payload)

        val rawReply = reply.toByteArray(isIpv6 = false)
        val parsedReply = IcmpPacket.parse(rawReply)
        assertNotNull(parsedReply)
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V4, parsedReply!!.type)
    }

    @Test
    fun testIcmpV6EchoRequestAndReply() {
        val srcIp = Ipv6Packet.parseIpv6("fd00::1")
        val dstIp = Ipv6Packet.parseIpv6("2001:4860:4860::8888")
        val payload = "PingV6Data".toByteArray()

        val echoReq = IcmpPacket(
            type = IcmpPacket.TYPE_ECHO_REQUEST_V6,
            code = 0,
            identifier = 5678,
            sequenceNumber = 42,
            payload = payload
        )

        assertTrue(echoReq.isEchoRequestV6)

        val rawReq = echoReq.toByteArray(isIpv6 = true, srcIp = srcIp, dstIp = dstIp)
        val parsedReq = IcmpPacket.parse(rawReq)
        assertNotNull(parsedReq)
        assertEquals(IcmpPacket.TYPE_ECHO_REQUEST_V6, parsedReq!!.type)

        val reply = parsedReq.createEchoReply(isIpv6 = true)
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V6, reply.type)

        val rawReply = reply.toByteArray(isIpv6 = true, srcIp = dstIp, dstIp = srcIp)
        val parsedReply = IcmpPacket.parse(rawReply)
        assertNotNull(parsedReply)
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V6, parsedReply!!.type)
        assertEquals(5678, parsedReply.identifier)
        assertEquals(42, parsedReply.sequenceNumber)
        assertArrayEquals(payload, parsedReply.payload)
    }
}

