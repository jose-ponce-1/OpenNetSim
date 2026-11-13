package com.opennetsim.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTelemetryTest {

    @Test
    fun testDefaultValues() {
        val telemetry = NetworkTelemetry()
        assertEquals(0L, telemetry.bytesPerSecond)
        assertEquals(0L, telemetry.totalTransferredBytes)
        assertEquals(0L, telemetry.totalDroppedPackets)
        assertFalse(telemetry.isConnected)
        assertEquals("0 B/s", telemetry.formattedThroughput())
        assertEquals("0 B", telemetry.formattedTotalData())
    }

    @Test
    fun testThroughputFormatting() {
        val bytes = NetworkTelemetry(bytesPerSecond = 500)
        assertEquals("500 B/s", bytes.formattedThroughput())

        val kb = NetworkTelemetry(bytesPerSecond = 128_000)
        assertEquals("128.0 KB/s", kb.formattedThroughput())

        val mb = NetworkTelemetry(bytesPerSecond = 2_500_000)
        assertEquals("2.5 MB/s", mb.formattedThroughput())
    }

    @Test
    fun testTotalDataFormatting() {
        val bytes = NetworkTelemetry(totalTransferredBytes = 850)
        assertEquals("850 B", bytes.formattedTotalData())

        val kb = NetworkTelemetry(totalTransferredBytes = 450_000)
        assertEquals("450.0 KB", kb.formattedTotalData())

        val mb = NetworkTelemetry(totalTransferredBytes = 15_200_000)
        assertEquals("15.2 MB", mb.formattedTotalData())
    }

    @Test
    fun testDownloadWithRetransmissionsExcludesThemFromGoodput() {
        val sampler = TelemetrySampler(startMs = 0L)
        sampler.sample(TrafficCounters(), nowMs = 0L)

        // En 1 s salen 1_000_000 B de bajada, de los que 100_000 B son retransmisiones del túnel.
        val telemetry = sampler.sample(
            TrafficCounters(
                downReleasedBytes = 1_000_000L,
                upReleasedBytes = 50_000L,
                retransmittedBytes = 100_000L,
                droppedPackets = 3L
            ),
            nowMs = 1_000L
        )

        assertEquals("Caudal de bajada sin retransmisiones", 900_000L, telemetry.downBytesPerSecond)
        assertEquals(50_000L, telemetry.upBytesPerSecond)
        assertEquals(950_000L, telemetry.bytesPerSecond)
        assertEquals("Las retransmisiones se informan aparte", 100_000L, telemetry.retransmittedBytes)
        assertEquals(950_000L, telemetry.totalTransferredBytes)
        assertEquals(3L, telemetry.totalDroppedPackets)
        assertEquals("900.0 KB/s", telemetry.formattedDownThroughput())
    }

    @Test
    fun testSamplerUsesPerIntervalDeltas() {
        val sampler = TelemetrySampler(startMs = 0L)
        sampler.sample(TrafficCounters(downReleasedBytes = 500_000L, retransmittedBytes = 10_000L), nowMs = 1_000L)
        val second = sampler.sample(
            TrafficCounters(downReleasedBytes = 700_000L, retransmittedBytes = 10_000L),
            nowMs = 3_000L
        )
        // 200_000 B en 2 s, sin retransmisiones nuevas en el intervalo
        assertEquals(100_000L, second.downBytesPerSecond)
    }

    @Test
    fun testConnectionState() {
        val connected = NetworkTelemetry(isConnected = true, totalDroppedPackets = 5)
        assertTrue(connected.isConnected)
        assertEquals(5L, connected.totalDroppedPackets)
    }
}

