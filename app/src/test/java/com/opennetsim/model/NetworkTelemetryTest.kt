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
    fun testConnectionState() {
        val connected = NetworkTelemetry(isConnected = true, totalDroppedPackets = 5)
        assertTrue(connected.isConnected)
        assertEquals(5L, connected.totalDroppedPackets)
    }
}

