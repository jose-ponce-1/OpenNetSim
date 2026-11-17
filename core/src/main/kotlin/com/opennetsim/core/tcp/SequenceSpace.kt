package com.opennetsim.core.tcp

/**
 * Conversión entre números de secuencia TCP de 32 bits y secuencias absolutas de 64 bits.
 *
 * El estado interno de [TcpConnection] usa secuencias absolutas que nunca desbordan, de modo que
 * las comparaciones son aritmética normal; solo al construir o leer segmentos se pasa a 32 bits.
 */
object SequenceSpace {
    private const val MASK_32 = 0xFFFFFFFFL

    /** Valor de 32 bits que viaja en la cabecera TCP. */
    fun wire(absolute: Long): Long = absolute and MASK_32

    /**
     * Interpreta [wire32] como la secuencia absoluta más cercana a [referenceAbsolute]
     * (diferencia con signo de 32 bits).
     */
    fun unwrap(wire32: Long, referenceAbsolute: Long): Long {
        val diff = ((wire32 and MASK_32) - (referenceAbsolute and MASK_32)).toInt()
        return referenceAbsolute + diff
    }
}
