package com.opennetsim.profiles

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedProfilesCodecTest {

    private val metro = SavedProfile(
        id = "a1",
        name = "Metro, hora punta. Línea 3",
        config = ThrottleConfig(
            downKbps = 900,
            upKbps = 120,
            latencyMs = 350,
            jitterMs = 70,
            upLossPercent = 1.5f,
            downLossPercent = 4f,
            lossBurstLength = 3,
            reorderPercent = 2.5f,
            preset = ThrottlePreset.CUSTOM
        )
    )
    private val hotel = SavedProfile("b2", "Wi-Fi del hotel", ThrottlePreset.fromPreset(ThrottlePreset.UNSTABLE_WIFI))

    @Test
    fun roundTripKeepsOrderNamesAndValues() {
        val decoded = SavedProfilesCodec.decode(SavedProfilesCodec.encode(listOf(metro, hotel)))

        assertEquals(listOf("a1", "b2"), decoded.map { it.id })
        assertEquals(metro, decoded[0])
        assertEquals("Wi-Fi del hotel", decoded[1].name)
        assertEquals("Un perfil guardado nunca es un predefinido", ThrottlePreset.CUSTOM, decoded[1].config.preset)
        assertEquals(hotel.config.downKbps, decoded[1].config.downKbps)
    }

    @Test
    fun emptyOrMissingStoreHasNoProfiles() {
        assertTrue(SavedProfilesCodec.decode(emptyMap()).isEmpty())
        assertTrue(SavedProfilesCodec.decode(SavedProfilesCodec.encode(emptyList())).isEmpty())
    }

    @Test
    fun corruptProfileIsDroppedWithoutLosingTheOthers() {
        val values = SavedProfilesCodec.encode(listOf(metro, hotel)).toMutableMap<String, String?>()
        values["a1.latency_ms"] = "mucho"
        val decoded = SavedProfilesCodec.decode(values)
        assertEquals(listOf("b2"), decoded.map { it.id })

        values["b2.name"] = " "
        assertTrue("Sin nombre no hay perfil", SavedProfilesCodec.decode(values).isEmpty())
    }

    @Test
    fun repeatedIdsInTheOrderAreReadOnce() {
        val values = SavedProfilesCodec.encode(listOf(metro)) + (SavedProfilesCodec.KEY_ORDER to "a1,,a1")
        assertEquals(listOf(metro), SavedProfilesCodec.decode(values))
    }

    @Test
    fun inMemoryStoreKeepsWhatWasSaved() {
        val store = InMemoryProfileStore()
        store.save(listOf(hotel))
        assertEquals(listOf(hotel), store.load())
    }
}
