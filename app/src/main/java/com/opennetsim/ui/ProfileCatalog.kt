package com.opennetsim.ui

import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.profiles.SavedProfile

/** Referencia a un perfil de conexión: predefinido o guardado por el usuario. */
sealed interface ProfileRef {
    data class Preset(val preset: ThrottlePreset) : ProfileRef

    data class Saved(val id: String) : ProfileRef

    companion object {
        val CUSTOM: ProfileRef = Preset(ThrottlePreset.CUSTOM)
    }
}

/** Perfil tal y como lo muestra la interfaz. */
data class ConnectionProfile(
    val ref: ProfileRef,
    val name: String,
    val description: String,
    val config: ThrottleConfig
) {
    val isSaved: Boolean get() = ref is ProfileRef.Saved
}

/** Perfil del que parten los valores actuales y sus valores en el momento de elegirlo. */
data class ProfileBase(val ref: ProfileRef, val config: ThrottleConfig)

/** Validación del nombre de un perfil nuevo. */
enum class ProfileNameCheck(val message: String?) {
    VALID(null),
    EMPTY("Escribe un nombre"),
    TOO_LONG("Máximo ${ProfileCatalog.MAX_NAME_LENGTH} caracteres"),
    DUPLICATE("Ya existe un perfil con ese nombre")
}

/** Catálogo de perfiles: orden, búsqueda, reconocimiento por valores y nombres válidos. */
object ProfileCatalog {
    const val MAX_NAME_LENGTH = 24
    private const val SAVED_DESCRIPTION = "Perfil propio guardado en este dispositivo."

    /** Perfiles guardados (del más reciente al más antiguo) seguidos de los predefinidos. */
    fun all(saved: List<SavedProfile>): List<ConnectionProfile> =
        saved.map { it.toConnectionProfile() } + ThrottlePreset.entries.map { it.toConnectionProfile() }

    fun find(ref: ProfileRef, saved: List<SavedProfile>): ConnectionProfile? = when (ref) {
        is ProfileRef.Preset -> ref.preset.toConnectionProfile()
        is ProfileRef.Saved -> saved.firstOrNull { it.id == ref.id }?.toConnectionProfile()
    }

    fun nameOf(ref: ProfileRef, saved: List<SavedProfile>): String =
        find(ref, saved)?.name ?: ThrottlePreset.CUSTOM.displayName

    /**
     * Perfil que corresponde a [config]: un predefinido si coincide exactamente, si no un guardado con
     * los mismos valores, y si no Personalizado.
     */
    fun match(config: ThrottleConfig, saved: List<SavedProfile>): ProfileRef {
        val preset = matchingPreset(config)
        if (preset != ThrottlePreset.CUSTOM) return ProfileRef.Preset(preset)
        val savedMatch = saved.firstOrNull { sameValues(it.config, config) }
        return savedMatch?.let { ProfileRef.Saved(it.id) } ?: ProfileRef.CUSTOM
    }

    /** [config] con el perfil predefinido que le corresponde, o CUSTOM. */
    fun tag(config: ThrottleConfig): ThrottleConfig = config.copy(preset = matchingPreset(config))

    fun sameValues(a: ThrottleConfig, b: ThrottleConfig): Boolean =
        a.copy(preset = ThrottlePreset.CUSTOM) == b.copy(preset = ThrottlePreset.CUSTOM)

    fun checkName(name: String, saved: List<SavedProfile>): ProfileNameCheck {
        val trimmed = name.trim()
        val taken = saved.map { it.name } + ThrottlePreset.entries.map { it.displayName }
        return when {
            trimmed.isEmpty() -> ProfileNameCheck.EMPTY
            trimmed.length > MAX_NAME_LENGTH -> ProfileNameCheck.TOO_LONG
            taken.any { it.equals(trimmed, ignoreCase = true) } -> ProfileNameCheck.DUPLICATE
            else -> ProfileNameCheck.VALID
        }
    }

    private fun matchingPreset(config: ThrottleConfig): ThrottlePreset =
        ThrottlePreset.entries.firstOrNull { config.matchesPreset(it) } ?: ThrottlePreset.CUSTOM

    private fun SavedProfile.toConnectionProfile() =
        ConnectionProfile(ProfileRef.Saved(id), name, SAVED_DESCRIPTION, config)

    private fun ThrottlePreset.toConnectionProfile() =
        ConnectionProfile(ProfileRef.Preset(this), displayName, description, ThrottlePreset.fromPreset(this))
}
