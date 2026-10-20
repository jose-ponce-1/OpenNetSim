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
        assertEquals(2.0f, slow3gConfig.packetLossPercent, 0.001f)
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
            packetLossPercent = 1.0f
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
}

