package com.opennetsim.ui

import com.opennetsim.R
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

/** Perfil tal y como lo muestra la interfaz: el nombre de un perfil guardado es [UiText.Raw]. */
data class ConnectionProfile(
    val ref: ProfileRef,
    val name: UiText,
    val description: UiText,
    val config: ThrottleConfig
) {
    val isSaved: Boolean get() = ref is ProfileRef.Saved
}

/** Perfil del que parten los valores actuales y sus valores en el momento de elegirlo. */
data class ProfileBase(val ref: ProfileRef, val config: ThrottleConfig)

/** Validación del nombre de un perfil nuevo. */
enum class ProfileNameCheck(val message: UiText?) {
    VALID(null),
    EMPTY(UiText.res(R.string.profile_name_error_empty)),
    TOO_LONG(UiText.plural(R.plurals.profile_name_error_too_long, ProfileCatalog.MAX_NAME_LENGTH)),
    DUPLICATE(UiText.res(R.string.profile_name_error_duplicate))
}

/** Catálogo de perfiles: orden, búsqueda, reconocimiento por valores y nombres válidos. */
object ProfileCatalog {
    const val MAX_NAME_LENGTH = 24
    private val SAVED_DESCRIPTION = UiText.res(R.string.profile_saved_description)

    /** Perfiles guardados (del más reciente al más antiguo) seguidos de los predefinidos. */
    fun all(saved: List<SavedProfile>): List<ConnectionProfile> =
        saved.map { it.toConnectionProfile() } + ThrottlePreset.entries.map { it.toConnectionProfile() }

    fun find(ref: ProfileRef, saved: List<SavedProfile>): ConnectionProfile? = when (ref) {
        is ProfileRef.Preset -> ref.preset.toConnectionProfile()
        is ProfileRef.Saved -> saved.firstOrNull { it.id == ref.id }?.toConnectionProfile()
    }

    fun nameOf(ref: ProfileRef, saved: List<SavedProfile>): UiText =
        find(ref, saved)?.name ?: PresetTexts.name(ThrottlePreset.CUSTOM)

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

    /**
     * Nombre de un perfil nuevo. [reservedNames] son los nombres de los perfiles predefinidos en todos los
     * idiomas de la app: un perfil guardado no puede llamarse como uno predefinido en ninguno de ellos.
     */
    fun checkName(name: String, saved: List<SavedProfile>, reservedNames: Set<String>): ProfileNameCheck {
        val trimmed = name.trim()
        val taken = saved.map { it.name } + reservedNames
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
        ConnectionProfile(ProfileRef.Saved(id), UiText.Raw(name), SAVED_DESCRIPTION, config)

    private fun ThrottlePreset.toConnectionProfile() =
        ConnectionProfile(
            ProfileRef.Preset(this),
            PresetTexts.name(this),
            PresetTexts.description(this),
            ThrottlePreset.fromPreset(this)
        )
}
