package com.opennetsim.ui

import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputHistoryTest {

    // 384 kbps de bajada = 48 000 B/s; 128 kbps de subida = 16 000 B/s.
    private val config = ThrottleConfig(downKbps = 384, upKbps = 128)

    @Test
    fun keepsOnlyTheLastSixtySamples() {
        var history = emptyList<ThroughputSample>()
        repeat(90) { second ->
            history = ThroughputHistory.append(history, NetworkTelemetry(downBytesPerSecond = second.toLong()))
        }
        assertEquals(60, history.size)
        assertEquals("La más antigua es la del segundo 30", 30L, history.first().downBytesPerSecond)
        assertEquals(89L, history.last().downBytesPerSecond)
    }

    @Test
    fun chartScalesToTheCapWithHeadroom() {
        val history = listOf(ThroughputSample(24_000, 8_000))
        val chart = ThroughputHistory.chart(history, config, TrafficDirection.DOWN)

        assertNull("En «Bajada» no se pinta la subida", chart.up)
        assertEquals(1 / 1.15f, chart.capFraction, 1e-4f)
        assertEquals(0.5f / 1.15f, chart.down!!.single(), 1e-4f)
    }

    @Test
    fun uploadChartUsesTheUploadCap() {
        val chart = ThroughputHistory.chart(listOf(ThroughputSample(40_000, 8_000)), config, TrafficDirection.UP)
        assertNull(chart.down)
        assertEquals(1 / 1.15f, chart.capFraction, 1e-4f)
        assertEquals(0.5f / 1.15f, chart.up!!.single(), 1e-4f)
    }

    @Test
    fun burstAboveTheCapStillFits() {
        val chart = ThroughputHistory.chart(listOf(ThroughputSample(100_000, 0)), config, TrafficDirection.BOTH)
        assertEquals(1 / 1.05f, chart.down!!.single(), 1e-4f)
        assertEquals(0f, chart.up!!.single(), 0f)
    }

    @Test
    fun headlineFollowsTheDirectionAndState() {
        val telemetry = NetworkTelemetry(bytesPerSecond = 50_000, downBytesPerSecond = 42_000, upBytesPerSecond = 8_000)
        fun rendered(headline: ThroughputHeadline) = Triple(headline.label.es(), headline.value, headline.capLabel.es())
        assertEquals(
            Triple("Bajada actual", "42.0 KB/s", "Límite 384 kbps"),
            rendered(ThroughputHistory.headline(telemetry, config, TrafficDirection.DOWN, active = true))
        )
        assertEquals(
            Triple("Subida actual", "8.0 KB/s", "Límite 128 kbps"),
            rendered(ThroughputHistory.headline(telemetry, config, TrafficDirection.UP, active = true))
        )
        val both = ThroughputHistory.headline(telemetry, config, TrafficDirection.BOTH, active = false)
        assertEquals(Triple("Caudal útil total", "0 B/s", "Límite ↓ 384 kbps"), rendered(both))
        assertEquals("Total goodput", both.label.en())
        assertEquals("Limit ↓ 384 kbps", both.capLabel.en())
    }
}
