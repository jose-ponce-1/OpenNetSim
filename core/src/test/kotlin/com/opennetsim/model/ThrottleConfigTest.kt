package com.opennetsim.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleConfigTest {

    @Test
    fun testPresetConversionAndMatching() {
        val slow3gConfig = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G)

        assertEquals(384, slow3gConfig.downKbps)
        assertEquals(128, slow3gConfig.upKbps)
        assertEquals(200, slow3gConfig.latencyMs)
        assertEquals(40, slow3gConfig.jitterMs)
        assertEquals(2.0f, slow3gConfig.upLossPercent, 0.001f)
        assertEquals(2.0f, slow3gConfig.downLossPercent, 0.001f)
        assertTrue(slow3gConfig.matchesPreset(ThrottlePreset.SLOW_3G))
        assertFalse(slow3gConfig.matchesPreset(ThrottlePreset.EDGE_2G))
    }

    @Test
    fun testCustomConfigMatching() {
        val config = ThrottleConfig(
            downKbps = 999,
            upKbps = 111,
            latencyMs = 50,
            jitterMs = 10,
            upLossPercent = 1.0f,
            downLossPercent = 1.0f
        )
        assertFalse(config.matchesPreset(ThrottlePreset.SLOW_3G))
        assertFalse(config.matchesPreset(ThrottlePreset.CUSTOM))
    }

    @Test
    fun testBandwidthFormatting() {
        val configKbps = ThrottleConfig(downKbps = 384, upKbps = 64)
        assertEquals("384 kbps", configKbps.formattedDownBandwidth())
        assertEquals("64 kbps", configKbps.formattedUpBandwidth())

        val configMbps = ThrottleConfig(downKbps = 10000, upKbps = 2500)
        assertEquals("10.0 Mbps", configMbps.formattedDownBandwidth())
        assertEquals("2.5 Mbps", configMbps.formattedUpBandwidth())
    }

    @Test
    fun roundTripLossCompoundsPerDirectionLoss() {
        // "Wi-Fi inestable": 8 % por dirección → un ping se pierde el 15,36 % de las veces.
        assertEquals(15.36f, ThrottleConfig.roundTripLossPercent(8f), 0.01f)
        assertEquals(0f, ThrottleConfig.roundTripLossPercent(0f), 0.0001f)
        assertEquals(75f, ThrottleConfig.roundTripLossPercent(50f), 0.01f)
        assertEquals(100f, ThrottleConfig.roundTripLossPercent(150f), 0.01f)
    }

    @Test
    fun roundTripLossCombinesBothDirections() {
        val config = ThrottleConfig(upLossPercent = 10f, downLossPercent = 0f)
        assertEquals("Solo pierde la subida", 10f, config.roundTripLossPercent, 0.01f)
        assertEquals(1f - 0.9f * 0.8f, ThrottleConfig.roundTripLossPercent(10f, 20f) / 100f, 0.0001f)
    }

    @Test
    fun burstsReorderingOrAsymmetricLossMakeTheConfigCustom() {
        val slow3g = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G)
        assertTrue(slow3g.matchesPreset(ThrottlePreset.SLOW_3G))
        assertFalse(slow3g.copy(lossBurstLength = 3).matchesPreset(ThrottlePreset.SLOW_3G))
        assertFalse(slow3g.copy(reorderPercent = 5f).matchesPreset(ThrottlePreset.SLOW_3G))
        assertFalse(slow3g.copy(downLossPercent = 9f).matchesPreset(ThrottlePreset.SLOW_3G))
    }
}
