package com.opennetsim.ui

import com.opennetsim.R
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

    /** Lo que calcula MainActivity a partir de los recursos en inglés y en español. */
    private val reserved = setOf(
        "Custom", "Personalizado", "Slow 3G", "3G Lento", "2G / EDGE",
        "Flaky Wi-Fi", "Wi-Fi Inestable", "High Latency", "Alta Latencia"
    )

    @Test
    fun savedProfilesComeFirstThenPresets() {
        val names = ProfileCatalog.all(listOf(metro)).map { it.name }
        assertEquals(
            listOf(
                UiText.Raw("Metro"),
                UiText.res(R.string.preset_custom_name),
                UiText.res(R.string.preset_slow_3g_name),
                UiText.res(R.string.preset_edge_name),
                UiText.res(R.string.preset_unstable_wifi_name),
                UiText.res(R.string.preset_high_latency_name)
            ),
            names
        )
        assertEquals(
            UiText.res(R.string.profile_saved_description),
            ProfileCatalog.all(listOf(metro)).first().description
        )
        assertEquals(
            UiText.res(R.string.preset_slow_3g_description),
            ProfileCatalog.find(ProfileRef.Preset(ThrottlePreset.SLOW_3G), emptyList())?.description
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
        assertEquals(
            UiText.res(R.string.preset_custom_name),
            ProfileCatalog.nameOf(ProfileRef.Saved("x"), listOf(metro))
        )
        assertEquals(UiText.Raw("Metro"), ProfileCatalog.nameOf(ProfileRef.Saved("m"), listOf(metro)))
    }

    @Test
    fun namesMustBeNonEmptyShortAndUnique() {
        assertEquals(
            ProfileNameCheck.VALID,
            ProfileCatalog.checkName("  Metro en hora punta ", listOf(metro), reserved)
        )
        assertEquals(ProfileNameCheck.EMPTY, ProfileCatalog.checkName("   ", emptyList(), reserved))
        assertEquals(ProfileNameCheck.TOO_LONG, ProfileCatalog.checkName("x".repeat(25), emptyList(), reserved))
        assertEquals(ProfileNameCheck.VALID, ProfileCatalog.checkName("x".repeat(24), emptyList(), reserved))
        assertEquals(ProfileNameCheck.DUPLICATE, ProfileCatalog.checkName("METRO", listOf(metro), reserved))
        assertEquals(UiText.res(R.string.profile_name_error_duplicate), ProfileNameCheck.DUPLICATE.message)
        assertEquals("Máximo 24 caracteres", ProfileNameCheck.TOO_LONG.message.es())
        assertEquals("Up to 24 characters", ProfileNameCheck.TOO_LONG.message.en())
    }

    @Test
    fun presetNamesAreReservedInEveryLanguage() {
        assertEquals(ProfileNameCheck.DUPLICATE, ProfileCatalog.checkName("3g lento", emptyList(), reserved))
        assertEquals(ProfileNameCheck.DUPLICATE, ProfileCatalog.checkName("slow 3g", emptyList(), reserved))
        assertEquals(ProfileNameCheck.DUPLICATE, ProfileCatalog.checkName(" FLAKY WI-FI ", emptyList(), reserved))
        assertEquals(ProfileNameCheck.VALID, ProfileCatalog.checkName("Slow 4G", emptyList(), reserved))
    }
}
