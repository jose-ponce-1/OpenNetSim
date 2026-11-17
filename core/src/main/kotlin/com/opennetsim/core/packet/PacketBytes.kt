package com.opennetsim.core.packet

/**
 * Escritura big-endian (orden de red) directamente sobre un array, sin `ByteBuffer`: en el camino de
 * cada paquete se evita reservar el objeto del búfer.
 */
internal object PacketBytes {
    /** Array vacío compartido para opciones y payloads ausentes: un array de longitud 0 no se puede modificar. */
    val EMPTY = ByteArray(0)

    /**
     * Resultado de una validación de cabecera que no se cumple. Los validadores devuelven `Int` con este
     * valor en lugar de `Int?`: un `Int?` empaqueta el entero en cada paquete (y reserva un `Integer` si
     * pasa de 127, como la longitud de un payload IPv6 o UDP).
     */
    const val INVALID = -1

    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val U32_MASK = 0xFFFFFFFFL
    private const val THIRD_BYTE = 2
    private const val FOURTH_BYTE = 3
    private const val SHIFT_16 = 16
    private const val SHIFT_24 = 24

    fun writeU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr BYTE_BITS).toByte()
        out[offset + 1] = (value and BYTE_MASK).toByte()
    }

    fun writeU32(out: ByteArray, offset: Int, value: Long) {
        val v = value.toInt()
        out[offset] = (v ushr SHIFT_24).toByte()
        out[offset + 1] = (v ushr SHIFT_16).toByte()
        out[offset + THIRD_BYTE] = (v ushr BYTE_BITS).toByte()
        out[offset + FOURTH_BYTE] = v.toByte()
    }

    fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and BYTE_MASK) shl BYTE_BITS) or (data[offset + 1].toInt() and BYTE_MASK)

    /** Entero sin signo de 32 bits (números de secuencia TCP). */
    fun readU32(data: ByteArray, offset: Int): Long {
        val value = ((data[offset].toInt() and BYTE_MASK) shl SHIFT_24) or
            ((data[offset + 1].toInt() and BYTE_MASK) shl SHIFT_16) or
            ((data[offset + THIRD_BYTE].toInt() and BYTE_MASK) shl BYTE_BITS) or
            (data[offset + FOURTH_BYTE].toInt() and BYTE_MASK)
        return value.toLong() and U32_MASK
    }

    /** Copia de `[from, to)`, o [EMPTY] si el tramo está vacío. */
    fun slice(data: ByteArray, from: Int, to: Int): ByteArray = if (to > from) data.copyOfRange(from, to) else EMPTY
}
