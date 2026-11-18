package com.opennetsim.ui

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.profiles.SavedProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCatalogTest {

    private val metroConfig = ThrottleConfig(downKbps = 900, upKbps = 120, latencyMs = 350, jitterMs = 70)
    private val metro = SavedProfile("m", "Metro", metroConfig)

    @Test
    fun savedProfilesComeFirstThenPresets() {
        val names = ProfileCatalog.all(listOf(metro)).map { it.name }
        assertEquals(
            listOf("Metro", "Personalizado", "3G Lento", "2G / EDGE", "Wi-Fi Inestable", "Alta Latencia"),
            names
        )
        assertTrue(ProfileCatalog.all(listOf(metro)).first().isSaved)
    }

    @Test
    fun matchPrefersExactPresetThenSavedThenCustom() {
        val slow3g = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G)
        assertEquals(ProfileRef.Preset(ThrottlePreset.SLOW_3G), ProfileCatalog.match(slow3g, listOf(metro)))
        val metroValues = metroConfig.copy(preset = ThrottlePreset.CUSTOM)
        assertEquals(ProfileRef.Saved("m"), ProfileCatalog.match(metroValues, listOf(metro)))
        assertEquals(ProfileRef.CUSTOM, ProfileCatalog.match(metroConfig.copy(latencyMs = 351), listOf(metro)))

        // Un perfil guardado con los valores de un predefinido se reconoce como el predefinido.
        val copyOf3g = SavedProfile("c", "Copia", slow3g.copy(preset = ThrottlePreset.CUSTOM))
        assertEquals(ProfileRef.Preset(ThrottlePreset.SLOW_3G), ProfileCatalog.match(slow3g, listOf(copyOf3g)))
    }

    @Test
    fun tagMarksTheMatchingPresetOrCustom() {
        val values = ThrottlePreset.fromPreset(ThrottlePreset.EDGE_2G).copy(preset = ThrottlePreset.CUSTOM)
        assertEquals(ThrottlePreset.EDGE_2G, ProfileCatalog.tag(values).preset)
        assertEquals(ThrottlePreset.CUSTOM, ProfileCatalog.tag(values.copy(jitterMs = 1)).preset)
    }

    @Test
    fun findAndNameOfUnknownSavedProfile() {
        assertNull(ProfileCatalog.find(ProfileRef.Saved("x"), listOf(metro)))
        assertEquals("Personalizado", ProfileCatalog.nameOf(ProfileRef.Saved("x"), listOf(metro)))
        assertEquals("Metro", ProfileCatalog.nameOf(ProfileRef.Saved("m"), listOf(metro)))
    }

    @Test
    fun namesMustBeNonEmptyShortAndUnique() {
        assertEquals(ProfileNameCheck.VALID, ProfileCatalog.checkName("  Metro en hora punta ", listOf(metro)))
        assertEquals(ProfileNameCheck.EMPTY, ProfileCatalog.checkName("   ", emptyList()))
        assertEquals(ProfileNameCheck.TOO_LONG, ProfileCatalog.checkName("x".repeat(25), emptyList()))
        assertEquals(ProfileNameCheck.VALID, ProfileCatalog.checkName("x".repeat(24), emptyList()))
        assertEquals(ProfileNameCheck.DUPLICATE, ProfileCatalog.checkName("3g lento", emptyList()))
        assertEquals(ProfileNameCheck.DUPLICATE, ProfileCatalog.checkName("METRO", listOf(metro)))
        assertEquals("Ya existe un perfil con ese nombre", ProfileNameCheck.DUPLICATE.message)
    }
}
