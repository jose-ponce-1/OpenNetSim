package com.opennetsim.core.engine

import com.opennetsim.model.ThrottleConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottlingEngineTest {

    private var simulatedTimeMs: Long = 10_000L

    private fun createEngine(
        bytesPerSec: Long = 10_000L,
        baseLatencyMs: Long = 0L,
        jitterMs: Long = 0L,
        lossProbability: Double = 0.0
    ): ThrottlingEngine {
        val limiter = TokenBucketLimiter(
            bytesPerSecond = bytesPerSec,
            burstCapacityBytes = bytesPerSec,
            timeSourceNanos = { simulatedTimeMs * 1_000_000L }
        )
        val queue = LatencyQueue(
            baseLatencyMs = baseLatencyMs,
            jitterMs = jitterMs,
            timeSourceMs = { simulatedTimeMs },
            jitterRandom = { _, _ -> 0L }
        )
        val lossSim = PacketLossSimulator(initialLossProbability = lossProbability)

        return ThrottlingEngine(
            bandwidthLimiter = limiter,
            latencyQueue = queue,
            packetLossSimulator = lossSim,
            timeSourceMs = { simulatedTimeMs }
        )
    }

    @Test
    fun testImmediateReleaseWhenNoDegradation() {
        val engine = createEngine(bytesPerSec = 50_000L, baseLatencyMs = 0L, lossProbability = 0.0)
        val packet = byteArrayOf(1, 2, 3, 4, 5)

        val result = engine.processIncoming(packet, currentTimeMs = simulatedTimeMs)

        assertTrue(result is PacketResult.Released)
        assertArrayEquals(packet, (result as PacketResult.Released).packet)

        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalReceivedPackets)
        assertEquals(1L, metrics.totalReleasedPackets)
        assertEquals(0L, metrics.totalDroppedPackets)
        assertEquals(5L, metrics.totalReceivedBytes)
        assertEquals(5L, metrics.totalReleasedBytes)
    }

    @Test
    fun testPacketDropWhenLossProbabilityIsOne() {
        val engine = createEngine(lossProbability = 1.0)
        val packet = byteArrayOf(9, 9, 9)

        val result = engine.processIncoming(packet, currentTimeMs = simulatedTimeMs)

        assertTrue(result is PacketResult.Dropped)
        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalReceivedPackets)
        assertEquals(0L, metrics.totalReleasedPackets)
        assertEquals(1L, metrics.totalDroppedPackets)
        assertEquals(3L, metrics.totalDroppedBytes)
    }

    @Test
    fun testLatencyEnqueuingAndDrain() {
        val engine = createEngine(
            bytesPerSec = 100_000L,
            baseLatencyMs = 150L,
            jitterMs = 0L,
            lossProbability = 0.0
        )
        val packet = byteArrayOf(10, 20, 30)

        // Encolar a T=10_000 con latencia 150 ms -> scheduled = 10_150
        val result = engine.processIncoming(packet, currentTimeMs = simulatedTimeMs)
        assertTrue(result is PacketResult.Enqueued)
        val enqueued = result as PacketResult.Enqueued
        assertEquals(10_150L, enqueued.scheduledReleaseTimeMs)
        assertEquals(150L, enqueued.waitMs)

        // A T=10_100, no debe drenarse
        simulatedTimeMs = 10_100L
        val drainedEarly = engine.drainReady(currentTimeMs = simulatedTimeMs)
        assertTrue(drainedEarly.isEmpty())

        // A T=10_150, se drena correctamente
        simulatedTimeMs = 10_150L
        val drainedOnTime = engine.drainReady(currentTimeMs = simulatedTimeMs)
        assertEquals(1, drainedOnTime.size)
        assertArrayEquals(packet, drainedOnTime[0])

        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalReceivedPackets)
        assertEquals(1L, metrics.totalReleasedPackets)
    }

    @Test
    fun testUpdateConfigDynamically() {
        val engine = createEngine(bytesPerSec = 50_000L, baseLatencyMs = 0L)

        val newConfig = ThrottleConfig(
            downKbps = 384, // 384 kbps = 48_000 bytes/sec
            upKbps = 128,
            latencyMs = 250,
            jitterMs = 50,
            packetLossPercent = 10.0f
        )

        engine.updateConfig(newConfig)

        assertEquals(48_000L, engine.bandwidthLimiter.bytesPerSecond)
        assertEquals(250L, engine.latencyQueue.baseLatencyMs)
        assertEquals(50L, engine.latencyQueue.jitterMs)
        assertEquals(0.10, engine.packetLossSimulator.lossProbability, 0.001)
    }

    @Test
    fun testStrictFifoOrderWhenRateThrottledWithoutLatency() {
        // 10_000 bytes/sec = 10 bytes/ms
        val engine = createEngine(
            bytesPerSec = 10_000L,
            baseLatencyMs = 0L,
            jitterMs = 0L,
            lossProbability = 0.0
        )

        val p1 = byteArrayOf(1, 1)
        val p2 = byteArrayOf(2, 2)
        val p3 = byteArrayOf(3, 3)

        // Consumir el burst inicial (10_000 bytes)
        val burstEater = ByteArray(10_000)
        assertTrue(engine.processIncoming(burstEater, currentTimeMs = simulatedTimeMs) is PacketResult.Released)

        // Ahora el bucket está vacío. Llega p1 a T=10_000
        val res1 = engine.processIncoming(p1, currentTimeMs = simulatedTimeMs)
        assertTrue(res1 is PacketResult.Enqueued)
        val enq1 = res1 as PacketResult.Enqueued

        // Llega p2 a T=10_001
        val res2 = engine.processIncoming(p2, currentTimeMs = simulatedTimeMs + 1)
        assertTrue(res2 is PacketResult.Enqueued)
        val enq2 = res2 as PacketResult.Enqueued

        // Llega p3 a T=10_002
        val res3 = engine.processIncoming(p3, currentTimeMs = simulatedTimeMs + 2)
        assertTrue(res3 is PacketResult.Enqueued)
        val enq3 = res3 as PacketResult.Enqueued

        // Los tiempos deben ser estrictamente crecientes para preservar FIFO (sin inversión de prioridades)
        assertTrue("enq1 (${enq1.scheduledReleaseTimeMs}) debe ser <= enq2 (${enq2.scheduledReleaseTimeMs})",
            enq1.scheduledReleaseTimeMs <= enq2.scheduledReleaseTimeMs)
        assertTrue("enq2 (${enq2.scheduledReleaseTimeMs}) debe ser < enq3 (${enq3.scheduledReleaseTimeMs})",
            enq2.scheduledReleaseTimeMs < enq3.scheduledReleaseTimeMs)

        // Avanzar el tiempo más allá del último y drenar
        simulatedTimeMs = enq3.scheduledReleaseTimeMs + 100L
        val drained = engine.drainReady(currentTimeMs = simulatedTimeMs)

        assertEquals(3, drained.size)
        assertArrayEquals("p1 debe ser el primero", p1, drained[0])
        assertArrayEquals("p2 debe ser el segundo", p2, drained[1])
        assertArrayEquals("p3 debe ser el tercero", p3, drained[2])
    }

    @Test
    fun testTailDropWhenQueueReachesCapacity() {
        val limiter = TokenBucketLimiter(
            bytesPerSecond = 10_000L,
            burstCapacityBytes = 1500L,
            timeSourceNanos = { simulatedTimeMs * 1_000_000L }
        )
        // Cola con capacidad máxima de 2 paquetes
        val queue = LatencyQueue(
            baseLatencyMs = 200L,
            maxCapacity = 2,
            timeSourceMs = { simulatedTimeMs }
        )
        val engine = ThrottlingEngine(
            bandwidthLimiter = limiter,
            latencyQueue = queue,
            packetLossSimulator = PacketLossSimulator(0.0),
            timeSourceMs = { simulatedTimeMs }
        )

        val p1 = byteArrayOf(1)
        val p2 = byteArrayOf(2)
        val p3 = byteArrayOf(3)

        // Primeros 2 paquetes se encolan
        assertTrue(engine.processIncoming(p1, simulatedTimeMs) is PacketResult.Enqueued)
        assertTrue(engine.processIncoming(p2, simulatedTimeMs) is PacketResult.Enqueued)
        assertEquals(2, queue.size)
        assertTrue(queue.isFull)

        // El 3er paquete debe ser descartado por tail-drop
        val dropResult = engine.processIncoming(p3, simulatedTimeMs)
        assertTrue(dropResult is PacketResult.Dropped)

        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalDroppedPackets)
        assertEquals(1L, metrics.totalDroppedBytes)
        assertEquals(2, queue.size)
    }
}

