package com.opennetsim.ui

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import org.junit.Assert.assertEquals
import org.junit.Test

class UiFormattersTest {

    @Test
    fun bandwidthUsesKbpsBelowOneMbps() {
        assertEquals("384 kbps", UiFormatters.bandwidth(384))
        assertEquals("1 Mbps", UiFormatters.bandwidth(1000))
        assertEquals("1.5 Mbps", UiFormatters.bandwidth(1500))
        assertEquals("50 Mbps", UiFormatters.bandwidth(50_000))
    }

    @Test
    fun summaryOfAPreset() {
        assertEquals(
            "384 kbps ↓ · 128 kbps ↑ · +200 ms · 2.0%",
            UiFormatters.summary(ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G))
        )
    }

    @Test
    fun directionalLossShowsBothValuesAndRoundTrip() {
        val config = ThrottleConfig(upLossPercent = 1f, downLossPercent = 2f)
        assertEquals("↑ 1.0% · ↓ 2.0%", UiFormatters.loss(config))
        assertEquals("Ida y vuelta 3.0%", UiFormatters.roundTripLoss(config))
        assertEquals("Ida y vuelta 3.0% · ráfagas de 4", UiFormatters.roundTripLoss(config.copy(lossBurstLength = 4)))
    }

    @Test
    fun jitterMentionsReorderingOnlyWhenActive() {
        assertEquals("Jitter ±40 ms", UiFormatters.jitter(ThrottleConfig(jitterMs = 40)))
        val reordering = ThrottleConfig(jitterMs = 40, reorderPercent = 5f)
        assertEquals("Jitter ±40 ms · reord. 5.0%", UiFormatters.jitter(reordering))
    }

    @Test
    fun elapsedTimeAddsHoursOnlyWhenNeeded() {
        assertEquals("00:00", UiFormatters.elapsed(0))
        assertEquals("00:00", UiFormatters.elapsed(-3))
        assertEquals("12:34", UiFormatters.elapsed(754))
        assertEquals("1:02:34", UiFormatters.elapsed(3754))
    }
}
