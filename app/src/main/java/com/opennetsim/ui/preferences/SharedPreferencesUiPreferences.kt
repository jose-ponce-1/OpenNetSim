package com.opennetsim.ui.preferences

import android.content.Context
import androidx.core.content.edit
import com.opennetsim.ui.BandwidthUnit

/** [UiPreferences] sobre SharedPreferences. */
class SharedPreferencesUiPreferences(context: Context) : UiPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var onboardingCompleted: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) {
            preferences.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }
        }

    override var bandwidthUnit: BandwidthUnit
        get() = preferences.getString(KEY_BANDWIDTH_UNIT, null)
            ?.let { name -> BandwidthUnit.entries.firstOrNull { it.name == name } }
            ?: BandwidthUnit.KBPS
        set(value) {
            preferences.edit { putString(KEY_BANDWIDTH_UNIT, value.name) }
        }

    private companion object {
        const val PREFERENCES_NAME = "ui_preferences"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_BANDWIDTH_UNIT = "bandwidth_unit"
    }
}
