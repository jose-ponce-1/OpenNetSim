package com.opennetsim.core.packet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * El camino optimizado (vistas sin copia, serialización en un único array y checksum por palabras de
 * 32 bits) produce exactamente lo mismo que el camino anterior.
 */
class PacketCodecEquivalenceTest {

    private val random = Random(42)
    private val clientV4 = IpPacket.parseIp("10.0.0.2")
    private val serverV4 = IpPacket.parseIp("93.184.216.34")
    private val clientV6 = Ipv6Packet.parseIpv6("fd00::2")
    private val serverV6 = Ipv6Packet.parseIpv6("2606:2800:220:1:248:1893:25c8:1946")

    /** Implementación de referencia: palabras de 16 bits, como antes de la optimización. */
    private fun referenceChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Serialización TCP de referencia: el código con `ByteBuffer` anterior a la optimización. */
    private fun referenceTcpBytes(segment: TcpPacket, srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val headerLength = segment.dataOffset * 4
        val totalLength = headerLength + segment.payload.size
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.putShort(segment.sourcePort.toShort())
        buffer.putShort(segment.destinationPort.toShort())
        buffer.putInt((segment.sequenceNumber and 0xFFFFFFFFL).toInt())
        buffer.putInt((segment.acknowledgmentNumber and 0xFFFFFFFFL).toInt())
        buffer.put(((segment.dataOffset and 0x0F) shl 4).toByte())
        buffer.put((segment.flags and 0xFF).toByte())
        buffer.putShort(segment.windowSize.toShort())
        buffer.putShort(0.toShort())
        buffer.putShort(segment.urgentPointer.toShort())
        if (segment.options.isNotEmpty()) {
            buffer.put(segment.options)
            val padding = headerLength - 20 - segment.options.size
            if (padding > 0) buffer.put(ByteArray(padding))
        }
        buffer.position(headerLength)
        buffer.put(segment.payload)
        val data = buffer.array()
        val checksum = referenceTransportChecksum(srcIp, dstIp, IpPacket.PROTOCOL_TCP, data)
        buffer.putShort(16, checksum.toShort())
        return data
    }

    /** Serialización UDP de referencia, anterior a la optimización. */
    private fun referenceUdpBytes(datagram: UdpPacket, srcIp: ByteArray, dstIp: ByteArray): ByteArray {
        val totalLength = 8 + datagram.payload.size
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.putShort(datagram.sourcePort.toShort())
        buffer.putShort(datagram.destinationPort.toShort())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0.toShort())
        buffer.put(datagram.payload)
        val data = buffer.array()
        val checksum = referenceTransportChecksum(srcIp, dstIp, IpPacket.PROTOCOL_UDP, data)
        buffer.putShort(6, (if (checksum == 0) 0xFFFF else checksum).toShort())
        return data
    }

    /** Checksum de transporte de referencia: pseudo-cabecera explícita más palabras de 16 bits. */
    private fun referenceTransportChecksum(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, data: ByteArray): Int {
        val pseudo = if (srcIp.size == 16) {
            ByteBuffer.allocate(40).put(srcIp).put(dstIp).putInt(data.size).putInt(protocol).array()
        } else {
            ByteBuffer.allocate(12).put(srcIp).put(dstIp).put(0).put(protocol.toByte())
                .putShort(data.size.toShort()).array()
        }
        // La pseudo-cabecera tiene longitud par: concatenarla no desalinea las palabras.
        val all = pseudo + data
        return referenceChecksum(all, 0, all.size)
    }

    /** Paquete IP de referencia: cabecera con [IpPacket.toByteArray] / [Ipv6Packet.toByteArray] (sin cambios). */
    private fun referenceIpBytes(transport: ByteArray, protocol: Int, srcIp: ByteArray, dstIp: ByteArray): ByteArray =
        if (srcIp.size == 16) {
            Ipv6Packet(
                payloadLength = transport.size,
                nextHeader = protocol,
                sourceIp = srcIp,
                destinationIp = dstIp,
                payload = transport
            ).toByteArray()
        } else {
            IpPacket(
                totalLength = IpPacket.HEADER_SIZE + transport.size,
                protocol = protocol,
                sourceIp = srcIp,
                destinationIp = dstIp,
                payload = transport
            ).toByteArray()
        }

    @Test
    fun checksumMatchesSixteenBitReferenceForEveryLengthAndOffset() {
        for (length in 0..200) {
            for (offset in 0..3) {
                val data = random.nextBytes(offset + length + 3)
                assertEquals(
                    "longitud $length, desplazamiento $offset",
                    referenceChecksum(data, offset, length),
                    ChecksumUtils.calculateChecksum(data, offset, length)
                )
            }
        }
    }

    @Test
    fun checksumMatchesReferenceWithAllOnesData() {
        // Máximo acarreo: todas las palabras a 0xFFFF.
        val data = ByteArray(1500) { 0xFF.toByte() }
        assertEquals(referenceChecksum(data, 0, data.size), ChecksumUtils.calculateChecksum(data, 0, data.size))
    }

    @Test
    fun tcpToIpBytesMatchesTwoStepSerialization() {
        for (payloadSize in listOf(0, 1, 63, 64, 1459, 1460)) {
            val syn = TcpPacket.buildSynAck(
                srcPort = 443,
                dstPort = 52_100,
                seqNum = 0xFFFF_FFF0L,
                ackNum = 12_345L,
                windowSize = 29_200,
                options = TcpOptions(mss = 1460, windowScale = 7)
            )
            // Mismas opciones que un SYN-ACK real, con datos, flags PSH|ACK y puntero urgente.
            val segment = TcpPacket(
                sourcePort = syn.sourcePort,
                destinationPort = syn.destinationPort,
                sequenceNumber = syn.sequenceNumber,
                acknowledgmentNumber = syn.acknowledgmentNumber,
                dataOffset = syn.dataOffset,
                flags = TcpPacket.FLAG_PSH or TcpPacket.FLAG_ACK,
                windowSize = syn.windowSize,
                urgentPointer = 7,
                options = syn.options,
                payload = random.nextBytes(payloadSize)
            )
            for ((src, dst) in listOf(serverV4 to clientV4, serverV6 to clientV6)) {
                val reference = referenceTcpBytes(segment, src, dst)
                val label = "TCP/IPv${if (src.size == 16) 6 else 4} con $payloadSize B"
                assertArrayEquals(label, reference, segment.toByteArray(src, dst))
                assertArrayEquals(
                    label,
                    referenceIpBytes(reference, IpPacket.PROTOCOL_TCP, src, dst),
                    segment.toIpBytes(src, dst)
                )
            }
        }
    }

    @Test
    fun udpToIpBytesMatchesTwoStepSerialization() {
        for (payloadSize in listOf(0, 1, 512, 1231)) {
            val payload = random.nextBytes(payloadSize)
            val datagram = UdpPacket(
                sourcePort = 443,
                destinationPort = 50_000,
                length = UdpPacket.HEADER_SIZE + payload.size,
                payload = payload
            )
            for ((src, dst) in listOf(serverV4 to clientV4, serverV6 to clientV6)) {
                val reference = referenceUdpBytes(datagram, src, dst)
                val label = "UDP/IPv${if (src.size == 16) 6 else 4} con $payloadSize B"
                assertArrayEquals(label, reference, datagram.toByteArray(src, dst))
                assertArrayEquals(
                    label,
                    referenceIpBytes(reference, IpPacket.PROTOCOL_UDP, src, dst),
                    datagram.toIpBytes(src, dst)
                )
            }
        }
    }

    @Test
    fun serializedPacketsHaveValidChecksums() {
        val segment = TcpPacket.buildPshAck(52_100, 443, 1L, 1L, random.nextBytes(1001))
        val raw = segment.toIpBytes(clientV4, serverV4)
        assertEquals("cabecera IPv4", 0, ChecksumUtils.calculateChecksum(raw, 0, IpPacket.HEADER_SIZE))
        assertEquals(
            "segmento TCP con pseudo-cabecera",
            0,
            ChecksumUtils.calculateTransportChecksum(
                clientV4,
                serverV4,
                IpPacket.PROTOCOL_TCP,
                raw,
                IpPacket.HEADER_SIZE,
                raw.size - IpPacket.HEADER_SIZE
            )
        )
    }

    @Test
    fun ipv4ParseKeepsPayloadAsViewWithoutCopy() {
        val segment = TcpPacket.buildPshAck(52_100, 443, 1L, 1L, random.nextBytes(300))
        val raw = segment.toIpBytes(clientV4, serverV4)
        // El paquete dentro de un array mayor, como si viniera con desplazamiento.
        val framed = ByteArray(7) + raw + ByteArray(5)

        val ip = IpPacket.parse(framed, offset = 7, length = raw.size)!!
        assertSame("sin copia del payload IP", framed, ip.payloadBuffer)
        assertEquals(7 + IpPacket.HEADER_SIZE, ip.payloadOffset)
        assertEquals(raw.size - IpPacket.HEADER_SIZE, ip.payloadSize)
        assertArrayEquals(raw.copyOfRange(IpPacket.HEADER_SIZE, raw.size), ip.payload)
        assertEquals(segment, TcpPacket.parse(ip.payloadBuffer, ip.payloadOffset, ip.payloadSize))
    }

    @Test
    fun ipv6ParseKeepsPayloadAsViewWithoutCopy() {
        val payload = random.nextBytes(200)
        val datagram = UdpPacket(53_000, 53, UdpPacket.HEADER_SIZE + payload.size, payload = payload)
        val raw = datagram.toIpBytes(clientV6, serverV6)

        val ip = Ipv6Packet.parse(raw)!!
        assertSame("sin copia del payload IPv6", raw, ip.payloadBuffer)
        assertEquals(Ipv6Packet.HEADER_SIZE, ip.payloadOffset)
        assertEquals(raw.size - Ipv6Packet.HEADER_SIZE, ip.payloadSize)
        assertEquals(datagram, UdpPacket.parse(ip.payloadBuffer, ip.payloadOffset, ip.payloadSize))
    }

    @Test
    fun constructedPacketReturnsItsOwnPayloadArray() {
        val payload = random.nextBytes(40)
        val ip = IpPacket(
            totalLength = IpPacket.HEADER_SIZE + payload.size,
            protocol = IpPacket.PROTOCOL_UDP,
            sourceIp = clientV4,
            destinationIp = serverV4,
            payload = payload
        )
        assertSame(payload, ip.payload)
    }
}
