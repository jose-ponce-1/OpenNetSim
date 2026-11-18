package com.opennetsim.ui

import androidx.annotation.StringRes
import com.opennetsim.R
import com.opennetsim.model.ThrottlePreset

/** Nombre y descripción traducidos de cada perfil predefinido. */
object PresetTexts {
    @StringRes
    fun nameRes(preset: ThrottlePreset): Int = when (preset) {
        ThrottlePreset.CUSTOM -> R.string.preset_custom_name
        ThrottlePreset.SLOW_3G -> R.string.preset_slow_3g_name
        ThrottlePreset.EDGE_2G -> R.string.preset_edge_name
        ThrottlePreset.UNSTABLE_WIFI -> R.string.preset_unstable_wifi_name
        ThrottlePreset.HIGH_LATENCY -> R.string.preset_high_latency_name
    }

    @StringRes
    fun descriptionRes(preset: ThrottlePreset): Int = when (preset) {
        ThrottlePreset.CUSTOM -> R.string.preset_custom_description
        ThrottlePreset.SLOW_3G -> R.string.preset_slow_3g_description
        ThrottlePreset.EDGE_2G -> R.string.preset_edge_description
        ThrottlePreset.UNSTABLE_WIFI -> R.string.preset_unstable_wifi_description
        ThrottlePreset.HIGH_LATENCY -> R.string.preset_high_latency_description
    }

    fun name(preset: ThrottlePreset): UiText = UiText.res(nameRes(preset))

    fun description(preset: ThrottlePreset): UiText = UiText.res(descriptionRes(preset))
}
