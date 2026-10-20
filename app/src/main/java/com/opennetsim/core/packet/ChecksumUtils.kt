package com.opennetsim.core.packet

import java.nio.ByteBuffer

/**
 * Utilidades para cálculo de Internet Checksum (RFC 1071) en paquetes IP, TCP y UDP.
 */
object ChecksumUtils {

    /**
     * Calcula la suma de verificación estándar de Internet (RFC 1071) sobre un array de bytes.
     * @param data Array que contiene los datos.
     * @param offset Posición de inicio en el array.
     * @param length Cantidad de bytes a sumar.
     * @return Checksum de 16 bits (como Int no negativo en rango 0..0xFFFF).
     */
    fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        while (i < end - 1) {
            val byte1 = data[i].toInt() and 0xFF
            val byte2 = data[i + 1].toInt() and 0xFF
            val word16 = (byte1 shl 8) or byte2
            sum += word16
            i += 2
        }

        // Si la longitud es impar, agregar el último byte alineado a la izquierda
        if (i < end) {
            val lastByte = data[i].toInt() and 0xFF
            sum += (lastByte shl 8)
        }

        // Plegar sumas de acarreo de 32 bits a 16 bits
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Calcula el checksum sobre un ByteBuffer entre su posición actual y su límite.
     */
    fun calculateChecksum(buffer: ByteBuffer): Int {
        val remaining = buffer.remaining()
        val temp = ByteArray(remaining)
        val pos = buffer.position()
        buffer.get(temp)
        buffer.position(pos)
        return calculateChecksum(temp, 0, remaining)
    }

    /**
     * Calcula el checksum para la pseudo-cabecera IPv4 utilizada por TCP y UDP.
     * Pseudo-header (12 bytes):
     * - Source IP (4 bytes)
     * - Destination IP (4 bytes)
     * - Zero (1 byte = 0)
     * - Protocol (1 byte = 6 para TCP, 17 para UDP)
     * - Length (2 bytes = longitud del segmento TCP o UDP)
     */
    fun calculatePseudoHeaderChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        length: Int
    ): Long {
        var sum = 0L

        // Source IP (2 palabras de 16 bits)
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)

        // Destination IP (2 palabras de 16 bits)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)

        // Protocol (1 byte con cero prefijo = palabra de 16 bits)
        sum += protocol and 0xFF

        // Segment Length (16 bits)
        sum += length and 0xFFFF

        return sum
    }

    /**
     * Calcula el checksum para la pseudo-cabecera IPv6 (RFC 2460 / RFC 8200) utilizada por TCP, UDP e ICMPv6.
     * Pseudo-header (40 bytes):
     * - Source IP (16 bytes)
     * - Destination IP (16 bytes)
     * - Upper-Layer Packet Length (4 bytes = 32 bits)
     * - Next Header (4 bytes = 3 bytes de ceros + 1 byte nextHeader)
     */
    fun calculateIpv6PseudoHeaderChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        nextHeader: Int,
        length: Int
    ): Long {
        require(srcIp.size == 16) { "IPv6 srcIp debe tener 16 bytes" }
        require(dstIp.size == 16) { "IPv6 dstIp debe tener 16 bytes" }
        var sum = 0L

        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }

        for (i in 0 until 16 step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }

        sum += (length ushr 16) and 0xFFFF
        sum += length and 0xFFFF

        sum += nextHeader and 0xFF

        return sum
    }

    /**
     * Calcula el checksum de un segmento TCP o UDP incluyendo la pseudo-cabecera IPv4 o IPv6 según corresponda.
     */
    fun calculateTransportChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        segmentData: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = if (srcIp.size == 16 && dstIp.size == 16) {
            calculateIpv6PseudoHeaderChecksum(srcIp, dstIp, protocol, length)
        } else {
            calculatePseudoHeaderChecksum(srcIp, dstIp, protocol, length)
        }

        var i = offset
        val end = offset + length

        while (i < end - 1) {
            val byte1 = segmentData[i].toInt() and 0xFF
            val byte2 = segmentData[i + 1].toInt() and 0xFF
            val word16 = (byte1 shl 8) or byte2
            sum += word16
            i += 2
        }

        if (i < end) {
            val lastByte = segmentData[i].toInt() and 0xFF
            sum += (lastByte shl 8)
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        return (sum.inv() and 0xFFFF).toInt()
    }
}

