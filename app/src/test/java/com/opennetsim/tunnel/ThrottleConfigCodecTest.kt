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
            packetLossPercent = 2.5f,
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
}
