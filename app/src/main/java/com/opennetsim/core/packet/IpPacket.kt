package com.opennetsim.core.packet

import java.nio.ByteBuffer

/**
 * Representación y parser de paquetes IPv4 (RFC 791).
 */
data class IpPacket(
    val version: Int = 4,
    val ihl: Int = 5,
    val dscpEcn: Int = 0,
    val totalLength: Int,
    val identification: Int = 0,
    val flags: Int = 0x40, // Don't Fragment flag (DF)
    val fragmentOffset: Int = 0,
    val ttl: Int = 64,
    val protocol: Int, // 6 = TCP, 17 = UDP
    val headerChecksum: Int = 0,
    val sourceIp: ByteArray,
    val destinationIp: ByteArray,
    val payload: ByteArray
) {
    init {
        require(sourceIp.size == 4) { "sourceIp debe tener exactamente 4 bytes" }
        require(destinationIp.size == 4) { "destinationIp debe tener exactamente 4 bytes" }
    }

    val sourceIpString: String
        get() = formatIp(sourceIp)

    val destinationIpString: String
        get() = formatIp(destinationIp)

    /**
     * Serializa el paquete IPv4 completo a un ByteArray con el checksum de cabecera recalculado.
     */
    fun toByteArray(): ByteArray {
        val headerLength = ihl * 4
        val totalPacketLength = headerLength + payload.size
        val buffer = ByteBuffer.allocate(totalPacketLength)

        // Byte 0: Version (4 bits) + IHL (4 bits)
        val verIhl = ((version and 0x0F) shl 4) or (ihl and 0x0F)
        buffer.put(verIhl.toByte())

        // Byte 1: DSCP / ECN
        buffer.put(dscpEcn.toByte())

        // Bytes 2-3: Total Length
        buffer.putShort(totalPacketLength.toShort())

        // Bytes 4-5: Identification
        buffer.putShort(identification.toShort())

        // Bytes 6-7: Flags (3 bits) + Fragment Offset (13 bits)
        val flagsAndOffset = ((flags and 0x07) shl 13) or (fragmentOffset and 0x1FFF)
        buffer.putShort(flagsAndOffset.toShort())

        // Byte 8: TTL
        buffer.put(ttl.toByte())

        // Byte 9: Protocol
        buffer.put(protocol.toByte())

        // Bytes 10-11: Checksum inicializado a 0 para el cálculo
        buffer.putShort(0.toShort())

        // Bytes 12-15: Source IP
        buffer.put(sourceIp)

        // Bytes 16-19: Destination IP
        buffer.put(destinationIp)

        // Calcular y escribir el checksum de la cabecera IPv4
        val headerBytes = buffer.array()
        val calculatedChecksum = ChecksumUtils.calculateChecksum(headerBytes, 0, headerLength)
        buffer.putShort(10, calculatedChecksum.toShort())

        // Payload
        buffer.position(headerLength)
        buffer.put(payload)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IpPacket
        if (version != other.version) return false
        if (ihl != other.ihl) return false
        if (protocol != other.protocol) return false
        if (!sourceIp.contentEquals(other.sourceIp)) return false
        if (!destinationIp.contentEquals(other.destinationIp)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + ihl
        result = 31 * result + protocol
        result = 31 * result + sourceIp.contentHashCode()
        result = 31 * result + destinationIp.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val PROTOCOL_ICMP = 1

        fun formatIp(ipBytes: ByteArray): String {
            if (ipBytes.size != 4) return "0.0.0.0"
            return "${ipBytes[0].toInt() and 0xFF}.${ipBytes[1].toInt() and 0xFF}.${ipBytes[2].toInt() and 0xFF}.${ipBytes[3].toInt() and 0xFF}"
        }

        fun parseIp(ipString: String): ByteArray {
            val parts = ipString.split(".")
            require(parts.size == 4) { "Formato IP inválido: $ipString" }
            return ByteArray(4) { parts[it].toInt().toByte() }
        }

        /**
         * Parsea un paquete IPv4 desde un array de bytes.
         * Retorna null si los datos no son un paquete IPv4 válido o están truncados.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): IpPacket? {
            if (length < 20) return null

            val byte0 = data[offset].toInt() and 0xFF
            val version = byte0 shr 4
            if (version != 4) return null // Solo IPv4

            val ihl = byte0 and 0x0F
            val headerLength = ihl * 4
            if (headerLength < 20 || length < headerLength) return null

            val dscpEcn = data[offset + 1].toInt() and 0xFF
            val totalLength = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (length < totalLength) return null

            val identification = ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
            val flagsAndOffset = ((data[offset + 6].toInt() and 0xFF) shl 8) or (data[offset + 7].toInt() and 0xFF)
            val flags = (flagsAndOffset shr 13) and 0x07
            val fragmentOffset = flagsAndOffset and 0x1FFF

            val ttl = data[offset + 8].toInt() and 0xFF
            val protocol = data[offset + 9].toInt() and 0xFF
            val headerChecksum = ((data[offset + 10].toInt() and 0xFF) shl 8) or (data[offset + 11].toInt() and 0xFF)

            val sourceIp = data.copyOfRange(offset + 12, offset + 16)
            val destinationIp = data.copyOfRange(offset + 16, offset + 20)

            val payloadLength = totalLength - headerLength
            val payload = if (payloadLength > 0) {
                data.copyOfRange(offset + headerLength, offset + totalLength)
            } else {
                ByteArray(0)
            }

            return IpPacket(
                version = version,
                ihl = ihl,
                dscpEcn = dscpEcn,
                totalLength = totalLength,
                identification = identification,
                flags = flags,
                fragmentOffset = fragmentOffset,
                ttl = ttl,
                protocol = protocol,
                headerChecksum = headerChecksum,
                sourceIp = sourceIp,
                destinationIp = destinationIp,
                payload = payload
            )
        }
    }
}

