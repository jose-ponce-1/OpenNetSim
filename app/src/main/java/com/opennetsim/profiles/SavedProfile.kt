package com.opennetsim.profiles

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.tunnel.ThrottleConfigCodec

/**
 * Perfil de conexión guardado por el usuario con un nombre propio. [config] siempre lleva el perfil
 * CUSTOM: un perfil guardado no es ninguno de los predefinidos.
 *
 * @param id identificador estable, sin `,` ni `.` (los usa la codificación).
 */
data class SavedProfile(val id: String, val name: String, val config: ThrottleConfig)

/** Persistencia de los perfiles guardados, del más reciente al más antiguo. */
interface ProfileStore {
    fun load(): List<SavedProfile>

    fun save(profiles: List<SavedProfile>)
}

/** Implementación en memoria (tests y valor por defecto sin almacenamiento). */
class InMemoryProfileStore(private var stored: List<SavedProfile> = emptyList()) : ProfileStore {
    override fun load(): List<SavedProfile> = stored

    override fun save(profiles: List<SavedProfile>) {
        stored = profiles
    }
}

/**
 * Codificación de los perfiles guardados como pares clave/valor de texto, independiente de Android.
 * [KEY_ORDER] guarda los ids en orden; cada perfil guarda su nombre y su configuración (con
 * [ThrottleConfigCodec]) bajo el prefijo `<id>.`. Un perfil incompleto o corrupto se descarta sin
 * perder los demás.
 */
object SavedProfilesCodec {
    const val KEY_ORDER = "order"
    private const val KEY_NAME = "name"
    private const val ID_SEPARATOR = ","
    private const val PREFIX_SEPARATOR = "."

    fun encode(profiles: List<SavedProfile>): Map<String, String> {
        val values = mutableMapOf(KEY_ORDER to profiles.joinToString(ID_SEPARATOR) { it.id })
        profiles.forEach { profile ->
            val prefix = profile.id + PREFIX_SEPARATOR
            values[prefix + KEY_NAME] = profile.name
            ThrottleConfigCodec.encode(profile.config).forEach { (key, value) -> values[prefix + key] = value }
        }
        return values
    }

    fun decode(values: Map<String, String?>): List<SavedProfile> {
        val ids = values[KEY_ORDER]
            ?.split(ID_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: return emptyList()
        return ids.mapNotNull { id -> decodeProfile(id, values) }
    }

    private fun decodeProfile(id: String, values: Map<String, String?>): SavedProfile? {
        val prefix = id + PREFIX_SEPARATOR
        val name = values[prefix + KEY_NAME]?.takeIf { it.isNotBlank() }
        val configValues = values
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
        val config = ThrottleConfigCodec.decode(configValues)
        return if (name != null && config != null) {
            SavedProfile(id, name, config.copy(preset = ThrottlePreset.CUSTOM))
        } else {
            null
        }
    }
}
