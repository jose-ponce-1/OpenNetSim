package com.opennetsim.core.buffer

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Pool thread-safe y reciclable de [ByteBuffer] para mitigar la saturación
 * del Garbage Collector (GC churn) durante transferencias intensivas de datos.
 *
 * @param bufferSize Tamaño en bytes de cada búfer asignado (por defecto 32 KB).
 * @param maxPoolSize Número máximo de búferes retenidos en el pool.
 */
class ByteBufferPool(
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE
) {
    private val pool = ConcurrentLinkedQueue<ByteBuffer>()

    /**
     * Devuelve el número aproximado de búferes inactivos disponibles en el pool.
     */
    val availableCount: Int
        get() = pool.size

    /**
     * Obtiene un [ByteBuffer] listo para usarse (posición 0 y límite = bufferSize).
     * Si no hay búferes disponibles en el pool, asigna uno nuevo.
     */
    fun acquire(): ByteBuffer {
        val buffer = pool.poll()
        return if (buffer != null) {
            buffer.clear()
            buffer
        } else {
            ByteBuffer.allocate(bufferSize)
        }
    }

    /**
     * Devuelve un [ByteBuffer] al pool si no se ha alcanzado [maxPoolSize].
     * Si el búfer es de tamaño diferente o el pool está saturado, se desecha para que el GC lo libere.
     */
    fun release(buffer: ByteBuffer) {
        if (buffer.capacity() == bufferSize && pool.size < maxPoolSize) {
            buffer.clear()
            pool.offer(buffer)
        }
    }

    /**
     * Ejecuta una operación utilizando un búfer prestado y garantiza su liberación al finalizar.
     */
    inline fun <T> use(block: (ByteBuffer) -> T): T {
        val buffer = acquire()
        return try {
            block(buffer)
        } finally {
            release(buffer)
        }
    }

    /**
     * Limpia todos los búferes almacenados en el pool.
     */
    fun clear() {
        pool.clear()
    }

    companion object {
        const val DEFAULT_BUFFER_SIZE = 32768 // 32 KB
        const val DEFAULT_MAX_POOL_SIZE = 64  // 64 * 32 KB = 2 MB de retención máxima

        val defaultInstance by lazy { ByteBufferPool() }
    }
}

