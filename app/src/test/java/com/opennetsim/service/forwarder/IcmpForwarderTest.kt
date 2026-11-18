package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IcmpPacket
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.time.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class IcmpForwarderTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clock = FakeMonotonicClock()
    private val transport = FakeIcmpEchoTransport()
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val forwarder = IcmpForwarder(
        environment = ForwarderEnvironment(
            socketProtector = object : VpnSocketProtector {
                override fun protect(socket: Socket) = true
                override fun protect(socket: DatagramSocket) = true
            },
            coroutineScope = scope,
            clock = clock
        ),
        transport = transport,
        onInboundPacket = { inbound.add(it) }
    )

    private val clientIp = byteArrayOf(10, 0, 0, 2)
    private val serverIp = byteArrayOf(1, 1, 1, 1)
    private val clientIpv6 = InetAddress.getByName("fd00::2").address
    private val serverIpv6 = InetAddress.getByName("2606:4700:4700::1111").address

    @After
    fun tearDown() {
        forwarder.close()
        scope.cancel()
    }

    private fun echoRequestV4(identifier: Int, sequence: Int, destination: ByteArray = serverIp): IpPacket {
        val icmp = IcmpPacket(
            type = IcmpPacket.TYPE_ECHO_REQUEST_V4,
            identifier = identifier,
            sequenceNumber = sequence,
            payload = "abcdefgh".toByteArray()
        ).toByteArray(isIpv6 = false)
        return IpPacket(
            version = 4,
            ihl = 5,
            totalLength = 20 + icmp.size,
            protocol = IpPacket.PROTOCOL_ICMP,
            sourceIp = clientIp,
            destinationIp = destination,
            payload = icmp
        )
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Tiempo agotado esperando: $what" }
            Thread.sleep(5L)
        }
    }

    @Test
    fun echoRequestIsSentToTheRealNetworkAndReplyKeepsClientIdentifier() {
        forwarder.processOutboundIpv4(echoRequestV4(identifier = 7, sequence = 3))

        val raw = inbound.poll(3, TimeUnit.SECONDS)
        assertNotNull("La respuesta real vuelve a la app", raw)
        val ip = IpPacket.parse(raw!!)!!
        assertArrayEquals("Origen: el host al que se hizo ping", serverIp, ip.sourceIp)
        assertArrayEquals(clientIp, ip.destinationIp)
        val reply = IcmpPacket.parse(ip.payload)!!
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V4, reply.type)
        assertEquals("El identificador del kernel se sustituye por el de la app", 7, reply.identifier)
        assertEquals(3, reply.sequenceNumber)
        assertArrayEquals("abcdefgh".toByteArray(), reply.payload)

        val (destination, request) = transport.sent.single()
        assertEquals(InetAddress.getByAddress(serverIp), destination)
        assertEquals(IcmpPacket.TYPE_ECHO_REQUEST_V4, request.type)
    }

    @Test
    fun unreachableHostGetsNoReply() {
        transport.unreachable += InetAddress.getByAddress(serverIp)
        forwarder.processOutboundIpv4(echoRequestV4(identifier = 7, sequence = 1))

        awaitCondition("envío del ping") { transport.sent.isNotEmpty() }
        assertNull(
            "Un host que no responde tampoco responde a través del túnel",
            inbound.poll(200, TimeUnit.MILLISECONDS)
        )
    }

    @Test
    fun consecutivePingsReuseOneSocketPerIdentifier() {
        repeat(3) { sequence -> forwarder.processOutboundIpv4(echoRequestV4(identifier = 9, sequence = sequence)) }
        repeat(3) { assertNotNull(inbound.poll(3, TimeUnit.SECONDS)) }

        assertEquals(1, transport.openedSockets.get())
        assertEquals(1, forwarder.activeSessionCount())
    }

    @Test
    fun ipv6EchoIsForwardedWithIpv6Reply() {
        val icmp = IcmpPacket(
            type = IcmpPacket.TYPE_ECHO_REQUEST_V6,
            identifier = 11,
            sequenceNumber = 2,
            payload = ByteArray(4)
        )
            .toByteArray(isIpv6 = true, srcIp = clientIpv6, dstIp = serverIpv6)
        forwarder.processOutboundIpv6(
            Ipv6Packet(
                version = 6,
                payloadLength = icmp.size,
                nextHeader = Ipv6Packet.PROTOCOL_ICMPV6,
                sourceIp = clientIpv6,
                destinationIp = serverIpv6,
                payload = icmp
            )
        )

        val raw = inbound.poll(3, TimeUnit.SECONDS)
        assertNotNull(raw)
        val ipv6 = Ipv6Packet.parse(raw!!)!!
        assertArrayEquals(serverIpv6, ipv6.sourceIp)
        val reply = IcmpPacket.parse(ipv6.payload)!!
        assertEquals(IcmpPacket.TYPE_ECHO_REPLY_V6, reply.type)
        assertEquals(11, reply.identifier)
    }

    @Test
    fun idleSessionIsClosedAfterTimeout() {
        forwarder.processOutboundIpv4(echoRequestV4(identifier = 5, sequence = 1))
        assertNotNull(inbound.poll(3, TimeUnit.SECONDS))

        clock.advanceMs(IcmpForwarder.DEFAULT_IDLE_TIMEOUT_MS)
        // Otro ping a otro destino despierta el bucle, que pasa la limpieza.
        forwarder.processOutboundIpv4(
            echoRequestV4(identifier = 6, sequence = 1, destination = byteArrayOf(8, 8, 8, 8))
        )
        // La limpieza va al final de una vuelta del bucle y la sesión del ping nuevo se abre al principio de
        // la siguiente: el cierre se ve antes que la apertura, así que se esperan las dos cosas.
        awaitCondition("cierre de la sesión inactiva y apertura de la nueva") {
            transport.closedSockets.get() == 1 && forwarder.activeSessionCount() == 1
        }
    }

    @Test
    fun withoutPingSocketsTheEchoIsDropped() {
        transport.failOpen = true
        forwarder.processOutboundIpv4(echoRequestV4(identifier = 7, sequence = 1))

        assertNull(inbound.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(0, forwarder.activeSessionCount())
    }

    @Test
    fun closeReleasesSocketsAndTransport() {
        forwarder.processOutboundIpv4(echoRequestV4(identifier = 7, sequence = 1))
        assertNotNull(inbound.poll(3, TimeUnit.SECONDS))

        forwarder.close()
        awaitCondition("cierre del transporte") { transport.closed }
        assertEquals(1, transport.closedSockets.get())
        assertTrue(forwarder.activeSessionCount() == 0)
    }
}
