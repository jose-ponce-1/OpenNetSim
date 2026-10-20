package com.opennetsim.core.buffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteBufferPoolTest {

    @Test
    fun testAcquireAndReleaseReusesBuffer() {
        val pool = ByteBufferPool(bufferSize = 1024, maxPoolSize = 5)
        assertEquals(0, pool.availableCount)

        val buf1 = pool.acquire()
        assertNotNull(buf1)
        assertEquals(1024, buf1.capacity())
        assertEquals(0, buf1.position())
        assertEquals(1024, buf1.limit())

        // Escribir datos para ensuciar el buffer
        buf1.put("test".toByteArray())
        assertTrue(buf1.position() > 0)

        // Liberar al pool
        pool.release(buf1)
        assertEquals(1, pool.availableCount)

        // Volver a adquirir debe devolver la misma instancia limpia
        val buf2 = pool.acquire()
        assertSame(buf1, buf2)
        assertEquals(0, buf2.position())
        assertEquals(1024, buf2.limit())
        assertEquals(0, pool.availableCount)
    }

    @Test
    fun testMaxPoolSizeLimit() {
        val pool = ByteBufferPool(bufferSize = 512, maxPoolSize = 2)

        val b1 = pool.acquire()
        val b2 = pool.acquire()
        val b3 = pool.acquire()

        pool.release(b1)
        pool.release(b2)
        pool.release(b3) // Este debe ser ignorado por el límite de 2

        assertEquals(2, pool.availableCount)
    }

    @Test
    fun testUseScopedBorrowing() {
        val pool = ByteBufferPool(bufferSize = 256, maxPoolSize = 2)

        val result = pool.use { buffer ->
            buffer.putInt(12345)
            buffer.flip()
            buffer.getInt()
        }

        assertEquals(12345, result)
        assertEquals(1, pool.availableCount)
    }
}

