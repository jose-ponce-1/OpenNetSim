package com.opennetsim.benchmark

import com.opennetsim.core.packet.ChecksumUtils
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.PacketMeta
import com.opennetsim.core.packet.TcpPacket
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

/**
 * Parseo y construcción de un segmento TCP sobre IPv4, como en el camino de cada paquete del túnel:
 * - Subida (TUN → exterior): `PacketMeta` para clasificar en el motor y, al drenar, `IpPacket` y
 *   `TcpPacket` para el forwarder.
 * - Bajada (exterior → TUN): el forwarder construye el segmento y lo serializa con sus checksums.
 */
@State(Scope.Benchmark)
class PacketCodecBenchmark {
    /** Bytes de datos TCP: ACK pequeño de una app interactiva o segmento lleno (MSS con MTU 1500). */
    @Param("64", "1460")
    var payloadSize: Int = 0

    private val clientIp = IpPacket.parseIp(CLIENT_IP)
    private val serverIp = IpPacket.parseIp(SERVER_IP)
    private lateinit var payload: ByteArray
    private lateinit var rawPacket: ByteArray

    @Setup
    fun setUp() {
        payload = ByteArray(payloadSize) { it.toByte() }
        rawPacket = segment().toIpBytes(clientIp, serverIp)
    }

    /** Clasificación en el motor: cabeceras sin copiar el payload. */
    @Benchmark
    fun classify(): PacketMeta? = PacketMeta.parse(rawPacket)

    /**
     * Subida completa: clasificación al encolar y, al drenar, parseo IP (vista sin copia) y TCP desde
     * la vista, como `TcpForwarder.processOutbound`.
     */
    @Benchmark
    fun parseUplink(blackhole: Blackhole) {
        blackhole.consume(PacketMeta.parse(rawPacket))
        val ip = IpPacket.parse(rawPacket)
        blackhole.consume(ip?.let { TcpPacket.parse(it.payloadBuffer, it.payloadOffset, it.payloadSize) })
    }

    /** Bajada: segmento con datos del servidor, serializado con sus checksums en un único array. */
    @Benchmark
    fun buildDownlink(): ByteArray = segment().toIpBytes(serverIp, clientIp)

    /** Checksum de Internet sobre el paquete entero, la parte de [buildDownlink] que crece con el tamaño. */
    @Benchmark
    fun checksum(): Int = ChecksumUtils.calculateChecksum(rawPacket, 0, rawPacket.size)

    private fun segment(): TcpPacket = TcpPacket.buildPshAck(
        srcPort = CLIENT_PORT,
        dstPort = SERVER_PORT,
        seqNum = SEQUENCE,
        ackNum = ACKNOWLEDGMENT,
        payload = payload
    )

    private companion object {
        const val CLIENT_IP = "10.0.0.2"
        const val SERVER_IP = "93.184.216.34"
        const val CLIENT_PORT = 52_100
        const val SERVER_PORT = 443
        const val SEQUENCE = 1_000_000L
        const val ACKNOWLEDGMENT = 2_000_000L
    }
}
