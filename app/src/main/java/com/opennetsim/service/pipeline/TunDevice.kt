package com.opennetsim.service.pipeline

import java.io.Closeable
import java.io.IOException

/**
 * Interfaz TUN vista por el pipeline: paquetes IP completos en ambos sentidos.
 *
 * En Android la implementa [ParcelFileDescriptorTunDevice]; en los tests JVM, un TUN en memoria.
 */
interface TunDevice : Closeable {
    /**
     * Bloquea hasta leer un paquete en [buffer]. Devuelve los bytes leídos, 0 si no llegó ningún
     * paquete o un valor negativo en fin de flujo.
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    /** Escribe un paquete IP completo hacia las apps. */
    @Throws(IOException::class)
    fun write(packet: ByteArray)
}
