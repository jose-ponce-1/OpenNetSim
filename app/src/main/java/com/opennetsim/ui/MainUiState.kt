package com.opennetsim.ui

import com.opennetsim.R
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.profiles.SavedProfile

/**
 * Aviso para el usuario. [id] distingue dos avisos con el mismo texto; [actionLabel] es la acción
 * opcional (p. ej. «Deshacer»). Los textos se resuelven al mostrarse, en el idioma activo.
 */
data class UserMessage(val id: Long, val text: UiText, val actionLabel: UiText? = null)

/** Título, subtítulo y si se ofrece «Restablecer» en la cabecera de Parámetros. */
data class BaseProfileHeader(val title: UiText, val subtitle: UiText, val canReset: Boolean)

/**
 * Estado observable de la app.
 *
 * [isActive] refleja el estado real del túnel publicado por el servicio, nunca una suposición.
 * [selectedProfile] corresponde a los valores de [config]; [base] es el perfil del que parten.
 * [drafts] son los textos de los campos de Parámetros, que pueden no ser válidos.
 * [reservedProfileNames] son los nombres de los perfiles predefinidos en todos los idiomas.
 */
data class MainUiState(
    val isActive: Boolean = false,
    val isStarting: Boolean = false,
    val ipv6Routed: Boolean = false,
    val config: ThrottleConfig = DEFAULT_CONFIG,
    val selectedProfile: ProfileRef = ProfileRef.Preset(DEFAULT_CONFIG.preset),
    val base: ProfileBase = ProfileBase(ProfileRef.Preset(DEFAULT_CONFIG.preset), DEFAULT_CONFIG),
    val savedProfiles: List<SavedProfile> = emptyList(),
    val bandwidthUnit: BandwidthUnit = BandwidthUnit.KBPS,
    val drafts: Map<ParameterField, String> = ParameterInput.drafts(DEFAULT_CONFIG, BandwidthUnit.KBPS),
    val showOnboarding: Boolean = false,
    val userMessage: UserMessage? = null,
    val requestVpnPermission: Boolean = false,
    val telemetry: NetworkTelemetry = NetworkTelemetry(),
    val history: List<ThroughputSample> = emptyList(),
    val activeSeconds: Long = 0L,
    val reservedProfileNames: Set<String> = emptySet()
) {
    val profiles: List<ConnectionProfile> get() = ProfileCatalog.all(savedProfiles)

    val selectedName: UiText get() = ProfileCatalog.nameOf(selectedProfile, savedProfiles)

    val selectedDescription: UiText
        get() = ProfileCatalog.find(selectedProfile, savedProfiles)?.description
            ?: PresetTexts.description(ThrottlePreset.CUSTOM)

    val baseName: UiText get() = ProfileCatalog.nameOf(base.ref, savedProfiles)

    /** Campos cuyo valor difiere del perfil base. */
    val changedFields: List<ParameterField>
        get() = ParameterField.entries.filter { it.differs(config, base.config) }

    /** Cabecera de Parámetros: de qué perfil parten los valores y si se puede volver a él. */
    val baseHeader: BaseProfileHeader
        get() {
            val changes = changedFields.size
            return when {
                base.ref == ProfileRef.CUSTOM ->
                    BaseProfileHeader(baseName, UiText.res(R.string.profile_manual), canReset = false)
                changes > 0 -> BaseProfileHeader(
                    PresetTexts.name(ThrottlePreset.CUSTOM),
                    UiText.Plural(R.plurals.profile_based_on, changes, listOf(baseName, changes)),
                    canReset = true
                )
                base.ref is ProfileRef.Saved ->
                    BaseProfileHeader(baseName, UiText.res(R.string.profile_saved), canReset = false)
                else -> BaseProfileHeader(baseName, UiText.res(R.string.profile_builtin), canReset = false)
            }
        }

    companion object {
        val DEFAULT_CONFIG: ThrottleConfig = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G)
    }
}
