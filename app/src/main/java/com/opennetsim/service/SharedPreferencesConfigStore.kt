package com.opennetsim.service

import android.content.Context
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.tunnel.ConfigStore
import com.opennetsim.tunnel.ThrottleConfigCodec

/**
 * [ConfigStore] sobre SharedPreferences (sin dependencias nuevas). Guarda la última configuración
 * aplicada al túnel para restablecerla tras un reinicio del servicio.
 */
class SharedPreferencesConfigStore(context: Context) : ConfigStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): ThrottleConfig? {
        val stored = preferences.all.mapValues { (_, value) -> value as? String }
        return ThrottleConfigCodec.decode(stored)
    }

    override fun save(config: ThrottleConfig) {
        val editor = preferences.edit()
        ThrottleConfigCodec.encode(config).forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "tunnel_config"
    }
}
