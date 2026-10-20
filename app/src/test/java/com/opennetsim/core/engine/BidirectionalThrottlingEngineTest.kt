package com.opennetsim.core.engine

import com.opennetsim.model.ThrottleConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BidirectionalThrottlingEngineTest {

    private var simulatedTimeMs: Long = 10_000L

    @Test
    fun testUplinkAndDownlinkIndependentRates() {
        val config = ThrottleConfig(
            downKbps = 1000, // 125_000 B/s
            upKbps = 200,    // 25_000 B/s
            latencyMs = 100,  // 50ms up, 50ms down
            jitterMs = 0,
            packetLossPercent = 0.0f
        )

        val engine = BidirectionalThrottlingEngine.fromConfig(
            config = config,
            timeSourceMs = { simulatedTimeMs }
        )

        assertEquals(25_000L, engine.uplinkEngine.bandwidthLimiter.bytesPerSecond)
        assertEquals(125_000L, engine.downlinkEngine.bandwidthLimiter.bytesPerSecond)

        assertEquals(50L, engine.uplinkEngine.latencyQueue.baseLatencyMs)
        assertEquals(50L, engine.downlinkEngine.latencyQueue.baseLatencyMs)
    }

    @Test
    fun testUplinkAndDownlinkPipelineProcessing() {
        val config = ThrottleConfig(
            downKbps = 1000,
            upKbps = 500,
            latencyMs = 80, // 40ms por sentido
            jitterMs = 0,
            packetLossPercent = 0.0f
        )

        val engine = BidirectionalThrottlingEngine.fromConfig(
            config = config,
            timeSourceMs = { simulatedTimeMs }
        )

        val upPacket = byteArrayOf(1, 2, 3)
        val downPacket = byteArrayOf(4, 5, 6)

        val upResult = engine.processUplink(upPacket, currentTimeMs = simulatedTimeMs)
        val downResult = engine.processDownlink(downPacket, currentTimeMs = simulatedTimeMs)

        assertTrue(upResult is PacketResult.Enqueued)
        assertTrue(downResult is PacketResult.Enqueued)

        assertEquals(10_040L, (upResult as PacketResult.Enqueued).scheduledReleaseTimeMs)
        assertEquals(10_040L, (downResult as PacketResult.Enqueued).scheduledReleaseTimeMs)

        // A T=10_020, nada listo
        simulatedTimeMs = 10_020L
        assertTrue(engine.drainUplinkReady(simulatedTimeMs).isEmpty())
        assertTrue(engine.drainDownlinkReady(simulatedTimeMs).isEmpty())

        // A T=10_040, ambos listos
        simulatedTimeMs = 10_040L
        val drainedUp = engine.drainUplinkReady(simulatedTimeMs)
        val drainedDown = engine.drainDownlinkReady(simulatedTimeMs)

        assertEquals(1, drainedUp.size)
        assertEquals(1, drainedDown.size)
        assertArrayEquals(upPacket, drainedUp[0])
        assertArrayEquals(downPacket, drainedDown[0])

        // Métricas combinadas
        val metrics = engine.getCombinedMetrics()
        assertEquals(2L, metrics.totalReceivedPackets)
        assertEquals(2L, metrics.totalReleasedPackets)
        assertEquals(6L, metrics.totalReceivedBytes)
        assertEquals(6L, metrics.totalReleasedBytes)
    }

    @Test
    fun testDynamicConfigUpdate() {
        val config = ThrottleConfig(
            downKbps = 1000,
            upKbps = 500,
            latencyMs = 100,
            jitterMs = 20,
            packetLossPercent = 5.0f
        )

        val engine = BidirectionalThrottlingEngine.fromConfig(
            config = config,
            timeSourceMs = { simulatedTimeMs }
        )

        val newConfig = config.copy(
            downKbps = 2000,
            upKbps = 800,
            latencyMs = 200,
            jitterMs = 40,
            packetLossPercent = 10.0f
        )

        engine.updateConfig(newConfig)

        assertEquals(100_000L, engine.uplinkEngine.bandwidthLimiter.bytesPerSecond)
        assertEquals(250_000L, engine.downlinkEngine.bandwidthLimiter.bytesPerSecond)

        assertEquals(100L, engine.uplinkEngine.latencyQueue.baseLatencyMs)
        assertEquals(100L, engine.downlinkEngine.latencyQueue.baseLatencyMs)

        assertEquals(20L, engine.uplinkEngine.latencyQueue.jitterMs)
        assertEquals(20L, engine.downlinkEngine.latencyQueue.jitterMs)

        assertEquals(0.10, engine.uplinkEngine.packetLossSimulator.lossProbability, 0.001)
        assertEquals(0.10, engine.downlinkEngine.packetLossSimulator.lossProbability, 0.001)
    }

    @Test
    fun testAsymmetricRateLimitingWithMtuBurstFloor() {
        // Asymmetric configuration: Uplink heavily throttled (10 kbps = 1250 B/s), Downlink fast (1000 kbps = 125,000 B/s)
        val config = ThrottleConfig(
            downKbps = 1000,
            upKbps = 10,
            latencyMs = 0,
            jitterMs = 0,
            packetLossPercent = 0.0f
        )

        val engine = BidirectionalThrottlingEngine.fromConfig(
            config = config,
            timeSourceMs = { simulatedTimeMs }
        )

        // Verify MTU burst capacity floor on uplink (10 kbps -> 1250 B/s, but burst floor is MIN_BURST_CAPACITY_BYTES = 1500B)
        assertEquals(1500L, engine.uplinkEngine.bandwidthLimiter.burstCapacityBytes)
        // Downlink 1000 kbps -> 125,000 B/s -> burst is 125,000B
        assertEquals(125_000L, engine.downlinkEngine.bandwidthLimiter.burstCapacityBytes)

        val fullMtuPacket = ByteArray(1500) { 0x01 }

        // Both uplink and downlink can immediately transmit the first 1500-byte packet due to MTU burst floor
        val upResult1 = engine.processUplink(fullMtuPacket, simulatedTimeMs)
        val downResult1 = engine.processDownlink(fullMtuPacket, simulatedTimeMs)

        assertTrue(upResult1 is PacketResult.Released)
        assertTrue(downResult1 is PacketResult.Released)

        // Second full MTU packet on uplink must wait for tokens to refill at 10 kbps (1250 B/s)
        val upResult2 = engine.processUplink(fullMtuPacket, simulatedTimeMs)
        assertTrue(upResult2 is PacketResult.Enqueued)

        val enqueuedUp = upResult2 as PacketResult.Enqueued
        // 1500 bytes needed at 1250 B/s = 1200 ms wait
        assertEquals(1200L, enqueuedUp.waitMs)
        assertEquals(simulatedTimeMs + 1200L, enqueuedUp.scheduledReleaseTimeMs)

        // Check combined telemetry
        val metrics = engine.getCombinedMetrics()
        assertEquals(2L, metrics.totalReleasedPackets)
        assertEquals(3000L, metrics.totalReleasedBytes)
        assertEquals(3L, metrics.totalReceivedPackets)
        assertEquals(4500L, metrics.totalReceivedBytes)
    }

    @Test
    fun testAsymmetricFifoOrderingUnderRateThrottling() {
        val config = ThrottleConfig(
            downKbps = 1000,
            upKbps = 10, // 1250 B/s, burst floor = 1500B initial tokens
            latencyMs = 0,
            jitterMs = 0,
            packetLossPercent = 0.0f
        )

        val engine = BidirectionalThrottlingEngine.fromConfig(
            config = config,
            timeSourceMs = { simulatedTimeMs }
        )

        val p1 = ByteArray(1000) { 1 }
        val p2 = ByteArray(1000) { 2 }
        val p3 = ByteArray(1000) { 3 }

        // P1 consumes 1000B, 500B left in bucket
        val r1 = engine.processUplink(p1, simulatedTimeMs)
        assertTrue(r1 is PacketResult.Released)

        // P2 needs 1000B, only 500B available -> enqueued (needs 500B at 1250 B/s = 400ms)
        val r2 = engine.processUplink(p2, simulatedTimeMs)
        assertTrue(r2 is PacketResult.Enqueued)
        val p2ReleaseTime = (r2 as PacketResult.Enqueued).scheduledReleaseTimeMs

        // P3 arrives -> must be scheduled strictly AFTER P2
        val r3 = engine.processUplink(p3, simulatedTimeMs)
        assertTrue(r3 is PacketResult.Enqueued)
        val p3ReleaseTime = (r3 as PacketResult.Enqueued).scheduledReleaseTimeMs

        assertTrue("P3 scheduled time ($p3ReleaseTime) must be strictly after P2 ($p2ReleaseTime)", p3ReleaseTime > p2ReleaseTime)

        // Advance time to release P2
        simulatedTimeMs = p2ReleaseTime
        val drainedP2 = engine.drainUplinkReady(simulatedTimeMs)
        assertEquals(1, drainedP2.size)
        assertArrayEquals(p2, drainedP2[0])

        // Advance time to release P3
        simulatedTimeMs = p3ReleaseTime
        val drainedP3 = engine.drainUplinkReady(simulatedTimeMs)
        assertEquals(1, drainedP3.size)
        assertArrayEquals(p3, drainedP3[0])

        // Final telemetry check
        val metrics = engine.getCombinedMetrics()
        assertEquals(3L, metrics.totalReleasedPackets)
        assertEquals(3000L, metrics.totalReleasedBytes)
        assertEquals(0L, metrics.totalDroppedPackets)
    }

    @Test
    fun testTailDropBackpressureAndCombinedTelemetry() {
        val config = ThrottleConfig(
            downKbps = 1000,
            upKbps = 20,
            latencyMs = 500,
            jitterMs = 0,
            packetLossPercent = 0.0f
        )

        val engine = BidirectionalThrottlingEngine.fromConfig(
            config = config,
            timeSourceMs = { simulatedTimeMs }
        )

        val packet = ByteArray(100) { 0x07 }

        // Enqueue 100 packets into uplink (saturating the queue capacity)
        for (i in 1..100) {
            val res = engine.processUplink(packet, simulatedTimeMs)
            assertTrue("Packet $i should be enqueued", res is PacketResult.Enqueued)
        }

        // 101st packet must be tail-dropped
        val dropRes = engine.processUplink(packet, simulatedTimeMs)
        assertTrue("101st packet should be dropped", dropRes is PacketResult.Dropped)

        // Downlink packet should still be successfully processed
        val downRes = engine.processDownlink(packet, simulatedTimeMs)
        assertTrue("Downlink packet should be enqueued normally", downRes is PacketResult.Enqueued)

        val metrics = engine.getCombinedMetrics()
        assertEquals(102L, metrics.totalReceivedPackets)
        assertEquals(1L, metrics.totalDroppedPackets)
        assertEquals(100L, metrics.totalDroppedBytes)
    }
}

