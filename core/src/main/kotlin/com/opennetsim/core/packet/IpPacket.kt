package com.opennetsim.core.packet

/**
 * Representación y parser de paquetes IPv4 (RFC 791).
 *
 * El payload es una vista: está en [payloadBuffer] entre [payloadOffset] y [payloadOffset] +
 * [payloadSize]. Tras [parse], [payloadBuffer] es el paquete original y no se copia nada; los
 * parsers de transporte leen directamente de ahí. [payload] da un array propio, copiado la primera vez
 * que se pide si la vista no ocupa el array entero.
 */
@Suppress("LongParameterList") // Campos de la cabecera IPv4, casi todos con valor por defecto.
class IpPacket(
    val version: Int = VERSION,
    val ihl: Int = MIN_IHL,
    val dscpEcn: Int = 0,
    val totalLength: Int,
    val identification: Int = 0,
    /**
     * Flags de 3 bits. El valor por defecto (0x40, el bit DF en su posición del byte 6) no cabe en 3 bits
     * y [toByteArray] lo serializa como 0: los paquetes que crea la app salen sin flags.
     */
    val flags: Int = DEFAULT_FLAGS,
    val fragmentOffset: Int = 0,
    val ttl: Int = DEFAULT_TTL,
    val protocol: Int,
    val headerChecksum: Int = 0,
    val sourceIp: ByteArray,
    val destinationIp: ByteArray,
    payload: ByteArray,
    val payloadOffset: Int = 0,
    val payloadSize: Int = payload.size - payloadOffset
) {
    init {
        require(sourceIp.size == ADDRESS_BYTES) { "sourceIp debe tener exactamente 4 bytes" }
        require(destinationIp.size == ADDRESS_BYTES) { "destinationIp debe tener exactamente 4 bytes" }
        require(payloadOffset >= 0 && payloadSize >= 0 && payloadOffset + payloadSize <= payload.size) {
            "La vista del payload se sale del array"
        }
    }

    /** Array que contiene el payload; tras [parse], el paquete entero. */
    val payloadBuffer: ByteArray = payload

    private var ownedPayload: ByteArray? = null

    /** Payload en un array propio. Sin copia si la vista ocupa todo [payloadBuffer]. */
    val payload: ByteArray
        get() = ownedPayload ?: copyPayload().also { ownedPayload = it }

    private fun copyPayload(): ByteArray =
        if (payloadOffset == 0 && payloadSize == payloadBuffer.size) {
            payloadBuffer
        } else {
            payloadBuffer.copyOfRange(payloadOffset, payloadOffset + payloadSize)
        }

    val sourceIpString: String
        get() = formatIp(sourceIp)

    val destinationIpString: String
        get() = formatIp(destinationIp)

    /**
     * Serializa el paquete IPv4 completo a un ByteArray con el checksum de cabecera recalculado. La
     * longitud total se calcula de la cabecera y el payload, no se toma de [totalLength].
     */
    fun toByteArray(): ByteArray {
        val headerLength = ihl * WORD_BYTES
        val out = ByteArray(headerLength + payloadSize)
        out[0] = (((version and NIBBLE_MASK) shl NIBBLE_BITS) or (ihl and NIBBLE_MASK)).toByte()
        out[DSCP_ECN_OFFSET] = dscpEcn.toByte()
        PacketBytes.writeU16(out, TOTAL_LENGTH_OFFSET, out.size)
        PacketBytes.writeU16(out, IDENTIFICATION_OFFSET, identification)
        val flagsAndOffset = ((flags and FLAGS_MASK) shl FLAGS_SHIFT) or (fragmentOffset and FRAGMENT_OFFSET_MASK)
        PacketBytes.writeU16(out, FLAGS_OFFSET, flagsAndOffset)
        out[TTL_OFFSET] = ttl.toByte()
        out[PROTOCOL_OFFSET] = protocol.toByte()
        System.arraycopy(sourceIp, 0, out, SOURCE_OFFSET, ADDRESS_BYTES)
        System.arraycopy(destinationIp, 0, out, DESTINATION_OFFSET, ADDRESS_BYTES)
        PacketBytes.writeU16(out, CHECKSUM_OFFSET, ChecksumUtils.calculateChecksum(out, 0, headerLength))
        System.arraycopy(payloadBuffer, payloadOffset, out, headerLength, payloadSize)
        return out
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
        result = HASH_MULTIPLIER * result + ihl
        result = HASH_MULTIPLIER * result + protocol
        result = HASH_MULTIPLIER * result + sourceIp.contentHashCode()
        result = HASH_MULTIPLIER * result + destinationIp.contentHashCode()
        result = HASH_MULTIPLIER * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val PROTOCOL_TCP = 6
        const val PROTOCOL_UDP = 17
        const val PROTOCOL_ICMP = 1

        /** Cabecera sin opciones (IHL = 5), la única que construye la app. */
        const val HEADER_SIZE = 20
        private const val VERSION = 4
        private const val MIN_IHL = 5
        private const val VERSION_IHL = 0x45
        private const val DEFAULT_TTL = 64
        private const val DEFAULT_FLAGS = 0x40
        private const val WORD_BYTES = 4
        private const val NIBBLE_BITS = 4
        private const val NIBBLE_MASK = 0x0F
        private const val BYTE_MASK = 0xFF
        private const val FLAGS_MASK = 0x07
        private const val FLAGS_SHIFT = 13
        private const val FRAGMENT_OFFSET_MASK = 0x1FFF
        private const val HASH_MULTIPLIER = 31
        private const val DSCP_ECN_OFFSET = 1
        private const val TOTAL_LENGTH_OFFSET = 2
        private const val IDENTIFICATION_OFFSET = 4
        private const val FLAGS_OFFSET = 6
        private const val TTL_OFFSET = 8
        private const val PROTOCOL_OFFSET = 9
        private const val CHECKSUM_OFFSET = 10
        private const val SOURCE_OFFSET = 12
        private const val DESTINATION_OFFSET = 16
        private const val ADDRESS_BYTES = 4

        /**
         * Escribe en [out] desde [offset] una cabecera IPv4 de [HEADER_SIZE] bytes con su checksum, para
         * un paquete de [totalLength] bytes. Es la misma cabecera que [toByteArray] con los valores por
         * defecto (identificación 0, sin flags, TTL 64); el resto de [out] debe estar a cero.
         */
        @Suppress("LongParameterList")
        fun writeHeader(
            out: ByteArray,
            offset: Int,
            protocol: Int,
            totalLength: Int,
            sourceIp: ByteArray,
            destinationIp: ByteArray
        ) {
            out[offset] = VERSION_IHL.toByte()
            PacketBytes.writeU16(out, offset + TOTAL_LENGTH_OFFSET, totalLength)
            out[offset + TTL_OFFSET] = DEFAULT_TTL.toByte()
            out[offset + PROTOCOL_OFFSET] = protocol.toByte()
            System.arraycopy(sourceIp, 0, out, offset + SOURCE_OFFSET, ADDRESS_BYTES)
            System.arraycopy(destinationIp, 0, out, offset + DESTINATION_OFFSET, ADDRESS_BYTES)
            val checksum = ChecksumUtils.calculateChecksum(out, offset, HEADER_SIZE)
            PacketBytes.writeU16(out, offset + CHECKSUM_OFFSET, checksum)
        }

        fun formatIp(ipBytes: ByteArray): String {
            if (ipBytes.size != ADDRESS_BYTES) return "0.0.0.0"
            return ipBytes.joinToString(".") { (it.toInt() and BYTE_MASK).toString() }
        }

        fun parseIp(ipString: String): ByteArray {
            val parts = ipString.split(".")
            require(parts.size == ADDRESS_BYTES) { "Formato IP inválido: $ipString" }
            return ByteArray(ADDRESS_BYTES) { parts[it].toInt().toByte() }
        }

        /**
         * Parsea un paquete IPv4 desde un array de bytes.
         * Retorna null si los datos no son un paquete IPv4 válido o están truncados.
         */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): IpPacket? {
            val headerLength = validHeaderLength(data, offset, length)
            if (headerLength == PacketBytes.INVALID) return null
            val totalLength = PacketBytes.readU16(data, offset + TOTAL_LENGTH_OFFSET)
            val flagsAndOffset = PacketBytes.readU16(data, offset + FLAGS_OFFSET)
            return IpPacket(
                version = VERSION,
                ihl = headerLength / WORD_BYTES,
                dscpEcn = data[offset + DSCP_ECN_OFFSET].toInt() and BYTE_MASK,
                totalLength = totalLength,
                identification = PacketBytes.readU16(data, offset + IDENTIFICATION_OFFSET),
                flags = (flagsAndOffset shr FLAGS_SHIFT) and FLAGS_MASK,
                fragmentOffset = flagsAndOffset and FRAGMENT_OFFSET_MASK,
                ttl = data[offset + TTL_OFFSET].toInt() and BYTE_MASK,
                protocol = data[offset + PROTOCOL_OFFSET].toInt() and BYTE_MASK,
                headerChecksum = PacketBytes.readU16(data, offset + CHECKSUM_OFFSET),
                sourceIp = address(data, offset + SOURCE_OFFSET),
                destinationIp = address(data, offset + DESTINATION_OFFSET),
                payload = data,
                payloadOffset = offset + headerLength,
                // Un totalLength menor que la cabecera da un payload vacío.
                payloadSize = (totalLength - headerLength).coerceAtLeast(0)
            )
        }

        /**
         * Longitud de la cabecera si [data] empieza por un paquete IPv4 completo (la cabecera y los
         * `totalLength` bytes que declara caben en [length]); si no, [PacketBytes.INVALID].
         */
        private fun validHeaderLength(data: ByteArray, offset: Int, length: Int): Int {
            if (length < HEADER_SIZE) return PacketBytes.INVALID
            val byte0 = data[offset].toInt() and BYTE_MASK
            val headerLength = (byte0 and NIBBLE_MASK) * WORD_BYTES
            val totalLength = PacketBytes.readU16(data, offset + TOTAL_LENGTH_OFFSET)
            val valid = byte0 ushr NIBBLE_BITS == VERSION && headerLength >= HEADER_SIZE &&
                length >= headerLength && length >= totalLength
            return if (valid) headerLength else PacketBytes.INVALID
        }

        private fun address(data: ByteArray, start: Int): ByteArray = data.copyOfRange(start, start + ADDRESS_BYTES)
    }
}
