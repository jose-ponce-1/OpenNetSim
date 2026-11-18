package com.opennetsim.profiles

import android.content.Context
import androidx.core.content.edit

/** [ProfileStore] sobre SharedPreferences, en un archivo propio separado de la configuración del túnel. */
class SharedPreferencesProfileStore(context: Context) : ProfileStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): List<SavedProfile> {
        val stored = preferences.all.mapValues { (_, value) -> value as? String }
        return SavedProfilesCodec.decode(stored)
    }

    override fun save(profiles: List<SavedProfile>) {
        preferences.edit {
            clear()
            SavedProfilesCodec.encode(profiles).forEach { (key, value) -> putString(key, value) }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "saved_profiles"
    }
}
