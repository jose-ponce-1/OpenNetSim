package com.opennetsim.tunnel

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThrottleConfigCodecTest {

    @Test
    fun roundTripPreservesEveryField() {
        val config = ThrottleConfig(
            downKbps = 2000,
            upKbps = 300,
            latencyMs = 450,
            jitterMs = 35,
            upLossPercent = 2.5f,
            downLossPercent = 2.5f,
            preset = ThrottlePreset.CUSTOM
        )
        assertEquals(config, ThrottleConfigCodec.decode(ThrottleConfigCodec.encode(config)))
    }

    @Test
    fun everyPresetRoundTrips() {
        ThrottlePreset.entries.forEach { preset ->
            val config = ThrottlePreset.fromPreset(preset)
            assertEquals(config, ThrottleConfigCodec.decode(ThrottleConfigCodec.encode(config)))
        }
    }

    @Test
    fun unknownPresetFallsBackToCustom() {
        val stored = ThrottleConfigCodec.encode(ThrottleConfig()) + (ThrottleConfigCodec.KEY_PRESET to "PRESET_BORRADO")
        assertEquals(ThrottlePreset.CUSTOM, ThrottleConfigCodec.decode(stored)?.preset)
    }

    @Test
    fun incompleteOrCorruptEntryIsDiscarded() {
        val complete = ThrottleConfigCodec.encode(ThrottleConfig())
        assertNull(ThrottleConfigCodec.decode(emptyMap()))
        assertNull(ThrottleConfigCodec.decode(complete - ThrottleConfigCodec.KEY_LATENCY_MS))
        assertNull(ThrottleConfigCodec.decode(complete + (ThrottleConfigCodec.KEY_DOWN_KBPS to "rápido")))
    }

    @Test
    fun inMemoryStoreKeepsLastSavedConfig() {
        val store = InMemoryConfigStore()
        assertNull(store.load())
        store.save(ThrottlePreset.fromPreset(ThrottlePreset.EDGE_2G))
        store.save(ThrottlePreset.fromPreset(ThrottlePreset.HIGH_LATENCY))
        assertEquals(ThrottlePreset.HIGH_LATENCY, store.load()?.preset)
    }

    @Test
    fun directionalLossBurstsAndReorderingRoundTrip() {
        val config = ThrottleConfig(
            upLossPercent = 1.5f,
            downLossPercent = 7f,
            lossBurstLength = 5,
            reorderPercent = 3f
        )
        assertEquals(config, ThrottleConfigCodec.decode(ThrottleConfigCodec.encode(config)))
    }

    @Test
    fun legacyEntryWithSingleLossAppliesItToBothDirections() {
        val legacy = mapOf(
            ThrottleConfigCodec.KEY_DOWN_KBPS to "384",
            ThrottleConfigCodec.KEY_UP_KBPS to "128",
            ThrottleConfigCodec.KEY_LATENCY_MS to "200",
            ThrottleConfigCodec.KEY_JITTER_MS to "40",
            ThrottleConfigCodec.KEY_LEGACY_LOSS_PERCENT to "2.0",
            ThrottleConfigCodec.KEY_PRESET to "SLOW_3G"
        )
        assertEquals(ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G), ThrottleConfigCodec.decode(legacy))
    }
}
