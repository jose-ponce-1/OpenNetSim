package com.opennetsim.core.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyQueueTest {

    private var simulatedTimeMs: Long = 10_000L

    @Test
    fun testReorderingByScheduledReleaseTime() {
        // Cola sin jitter pero con latencia variable por paquete simulada
        val queue = LatencyQueue(
            baseLatencyMs = 100L,
            jitterMs = 0L,
            timeSourceMs = { simulatedTimeMs }
        )

        val packetA = byteArrayOf(1)
        val packetB = byteArrayOf(2)

        // Encolar A a T=10_000 con latencia base 100 -> entrega a 10_100
        queue.enqueue(packetA, currentTimeMs = simulatedTimeMs)

        // Cambiar temporalmente latencia base a 50 para el paquete B -> entrega a 10_050
        queue.baseLatencyMs = 50L
        queue.enqueue(packetB, currentTimeMs = simulatedTimeMs)

        // El paquete B tiene entrega a 10_050, antes que A (10_100).
        // A T=10_020, ninguno está listo.
        simulatedTimeMs = 10_020L
        assertNull(queue.pollReady(simulatedTimeMs))

        // A T=10_060, B debe estar listo primero
        simulatedTimeMs = 10_060L
        val polledFirst = queue.pollReady(simulatedTimeMs)
        assertNotNull(polledFirst)
        assertArrayEquals(packetB, polledFirst?.data)

        // A aún no está listo
        assertNull(queue.pollReady(simulatedTimeMs))

        // A T=10_100, A está listo
        simulatedTimeMs = 10_100L
        val polledSecond = queue.pollReady(simulatedTimeMs)
        assertNotNull(polledSecond)
        assertArrayEquals(packetA, polledSecond?.data)

        assertTrue(queue.isEmpty)
    }

    @Test
    fun testDrainReadyDrainsAllExpiredPackets() {
        val queue = LatencyQueue(
            baseLatencyMs = 50L,
            jitterMs = 0L,
            timeSourceMs = { simulatedTimeMs }
        )

        queue.enqueue(byteArrayOf(1), currentTimeMs = 10_000L) // Release: 10_050
        queue.enqueue(byteArrayOf(2), currentTimeMs = 10_000L) // Release: 10_050
        queue.enqueue(byteArrayOf(3), currentTimeMs = 10_050L) // Release: 10_100

        // A T=10_050, deben drenarse los dos primeros
        val drained = queue.drainReady(currentTimeMs = 10_050L)
        assertEquals(2, drained.size)
        assertArrayEquals(byteArrayOf(1), drained[0])
        assertArrayEquals(byteArrayOf(2), drained[1])
        assertEquals(1, queue.size)

        // A T=10_100, se drena el tercero
        val drainedRemaining = queue.drainReady(currentTimeMs = 10_100L)
        assertEquals(1, drainedRemaining.size)
        assertArrayEquals(byteArrayOf(3), drainedRemaining[0])
        assertTrue(queue.isEmpty)
    }

    @Test
    fun testTimeUntilNextReadyCalculation() {
        val queue = LatencyQueue(
            baseLatencyMs = 200L,
            jitterMs = 0L,
            timeSourceMs = { simulatedTimeMs }
        )

        assertNull(queue.timeUntilNextReadyMs(simulatedTimeMs))

        queue.enqueue(byteArrayOf(10), currentTimeMs = 10_000L) // Release: 10_200

        assertEquals(200L, queue.timeUntilNextReadyMs(currentTimeMs = 10_000L))
        assertEquals(50L, queue.timeUntilNextReadyMs(currentTimeMs = 10_150L))
        assertEquals(0L, queue.timeUntilNextReadyMs(currentTimeMs = 10_250L))
    }

    @Test
    fun testJitterBounds() {
        val jitterLimit = 30L
        val baseDelay = 100L

        val queue = LatencyQueue(
            baseLatencyMs = baseDelay,
            jitterMs = jitterLimit,
            timeSourceMs = { 1000L }
        )

        // Probar 100 inserciones y verificar que todos los retardos estén en [base - jitter, base + jitter]
        for (i in 0 until 100) {
            val delayed = queue.enqueue(byteArrayOf(i.toByte()), currentTimeMs = 1000L)
            val scheduled = delayed.scheduledReleaseTimeMs
            val minExpected = 1000L + (baseDelay - jitterLimit)
            val maxExpected = 1000L + (baseDelay + jitterLimit)

            assertTrue(
                "Scheduled $scheduled debe estar entre $minExpected y $maxExpected",
                scheduled in minExpected..maxExpected
            )
        }
    }
}

