package com.opennetsim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opennetsim.R
import com.opennetsim.core.time.MonotonicClock
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TunnelState
import com.opennetsim.profiles.InMemoryProfileStore
import com.opennetsim.profiles.ProfileStore
import com.opennetsim.profiles.SavedProfile
import com.opennetsim.tunnel.TunnelController
import com.opennetsim.ui.preferences.InMemoryUiPreferences
import com.opennetsim.ui.preferences.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * ViewModel de la app. Solo conoce [TunnelController]; nunca el servicio Android.
 *
 * El servicio es la fuente de verdad de la configuración activa: al observar el túnel en marcha, la
 * UI adopta su configuración, y al túnel solo se envían los cambios hechos por el usuario.
 *
 * Los avisos son [UiText]: se resuelven al mostrarse, así que cambian de idioma con la app.
 *
 * @param clock el mismo reloj monotónico que usa el servicio para fijar el arranque del túnel.
 * @param scopeOverride ámbito para recoger los flujos; los tests pasan uno con reloj virtual.
 * @param naming cómo se identifican y nombran los perfiles que guarda el usuario.
 */
@OptIn(FlowPreview::class)
@Suppress("TooManyFunctions") // Un método por acción de la interfaz.
class MainViewModel(
    private val tunnel: TunnelController,
    private val profileStore: ProfileStore = InMemoryProfileStore(),
    private val preferences: UiPreferences = InMemoryUiPreferences(),
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    scopeOverride: CoroutineScope? = null,
    private val naming: ProfileNaming = ProfileNaming()
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private var previousTunnelState: TunnelState? = null
    private var ticker: Job? = null
    private var messageCounter = 0L

    /** Acción inversa del aviso actual («Deshacer»), ligada a su id. */
    private var pendingUndo: Pair<Long, () -> Unit>? = null

    /** Última configuración elegida por el usuario; null hasta que cambia un valor. */
    private val userConfig = MutableStateFlow<ThrottleConfig?>(null)

    /**
     * Configuración que debe enviarse al túnel activo: solo cambios del usuario, sin repetidos, con
     * debounce mientras escribe, y solo si difiere de la que ya aplica el servicio. Ni el valor inicial
     * ni la configuración adoptada del servicio se reenvían nunca.
     */
    val configToApply: Flow<ThrottleConfig> = userConfig
        .filterNotNull()
        .distinctUntilChanged()
        .debounce(CONFIG_DEBOUNCE_MS)
        .filter { config ->
            val state = tunnel.state.value
            state is TunnelState.Running && state.config != config
        }

    init {
        scope.launch { tunnel.state.collect { state -> onTunnelState(state) } }
        scope.launch {
            tunnel.telemetry.collect { telemetryData ->
                _uiState.update { it.copy(telemetry = telemetryData) }
            }
        }
        scope.launch { configToApply.collect { config -> tunnel.updateConfig(config) } }
    }

    // ------------------------------------------------------------------ Túnel

    private fun onTunnelState(state: TunnelState) {
        val previous = previousTunnelState
        previousTunnelState = state
        when (state) {
            is TunnelState.Running -> {
                if (previous !is TunnelState.Running) {
                    onEnteredRunning(state, announce = previous == TunnelState.Starting)
                }
                _uiState.update { it.copy(isActive = true, isStarting = false, ipv6Routed = state.ipv6Routed) }
            }
            TunnelState.Starting -> _uiState.update { it.copy(isStarting = true) }
            TunnelState.Stopped -> {
                onInactive()
                if (previous is TunnelState.Running) showMessage(UiText.res(R.string.message_stopped))
            }
            is TunnelState.Failed -> {
                onInactive()
                showMessage(UiText.res(R.string.message_tunnel_failed, state.reason))
            }
        }
    }

    private fun onInactive() {
        stopTicker()
        _uiState.update { it.copy(isActive = false, isStarting = false, activeSeconds = 0L) }
    }

    /**
     * Al entrar en `Running` (arranque, o un ViewModel nuevo con el túnel ya activo) los controles
     * adoptan la configuración del servicio y la gráfica empieza vacía. Las actualizaciones posteriores
     * no se adoptan: llegan con retraso respecto a lo que el usuario está escribiendo.
     */
    private fun onEnteredRunning(state: TunnelState.Running, announce: Boolean) {
        // Un cambio del usuario pendiente de enviar ya no aplica a este túnel.
        userConfig.value = null
        _uiState.update { current ->
            val selection = ProfileCatalog.match(state.config, current.savedProfiles)
            current.copy(
                config = state.config,
                selectedProfile = selection,
                base = ProfileBase(selection, state.config),
                drafts = ParameterInput.drafts(state.config, current.bandwidthUnit),
                history = emptyList(),
                activeSeconds = elapsedSeconds(state)
            )
        }
        if (announce) showMessage(UiText.res(R.string.message_tunnel_established, _uiState.value.selectedName))
        startTicker()
    }

    /** Cada segundo con el túnel activo: tiempo activo y una muestra de caudal para la gráfica. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(TICK_MS)
                val running = tunnel.state.value as? TunnelState.Running ?: continue
                _uiState.update { current ->
                    current.copy(
                        activeSeconds = elapsedSeconds(running),
                        history = ThroughputHistory.append(current.history, tunnel.telemetry.value)
                    )
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun elapsedSeconds(state: TunnelState.Running): Long =
        ((clock.nowMs() - state.startedAtMs) / MS_PER_SECOND).coerceAtLeast(0L)

    /** Interruptor principal: detiene el túnel o pide a la UI el permiso VPN para arrancarlo. */
    fun onToggleClicked() {
        val current = _uiState.value
        if (current.isActive || current.isStarting) {
            tunnel.stop()
        } else {
            _uiState.update { it.copy(requestVpnPermission = true) }
        }
    }

    /**
     * Resultado del permiso VPN pedido por la UI. Con permiso se arranca el túnel con la
     * configuración actual; el estado activo lo publica después el servicio.
     */
    fun onVpnPermissionResult(isGranted: Boolean) {
        _uiState.update { it.copy(requestVpnPermission = false) }
        if (isGranted) {
            tunnel.start(_uiState.value.config)
        } else {
            showMessage(UiText.res(R.string.message_vpn_permission_required))
        }
    }

    /** El diálogo del permiso ya se mostró: no volver a lanzarlo si la pantalla se recompone. */
    fun clearVpnPermissionRequest() {
        _uiState.update { it.copy(requestVpnPermission = false) }
    }

    // ------------------------------------------------------------------ Perfiles

    /** Aplica un perfil. «Personalizado» conserva los valores actuales. */
    fun selectProfile(ref: ProfileRef) {
        val current = _uiState.value
        val profile = ProfileCatalog.find(ref, current.savedProfiles) ?: return
        val config = when {
            ref == ProfileRef.CUSTOM -> current.config.copy(preset = ThrottlePreset.CUSTOM)
            ref is ProfileRef.Preset -> profile.config
            else -> ProfileCatalog.tag(profile.config)
        }
        _uiState.update {
            it.copy(
                config = config,
                selectedProfile = ref,
                base = ProfileBase(ref, config),
                drafts = ParameterInput.drafts(config, it.bandwidthUnit)
            )
        }
        userConfig.value = config
        if (current.isActive) showMessage(UiText.res(R.string.message_profile_applied, profile.name))
    }

    /** Guarda los valores actuales como un perfil nuevo. Devuelve false si el nombre no es válido. */
    fun saveCurrentAsProfile(name: String): Boolean {
        val current = _uiState.value
        if (ProfileCatalog.checkName(name, current.savedProfiles, naming.reservedNames) != ProfileNameCheck.VALID) {
            return false
        }
        val profile = SavedProfile(naming.newId(), name.trim(), current.config.copy(preset = ThrottlePreset.CUSTOM))
        val ref = ProfileRef.Saved(profile.id)
        updateProfiles(listOf(profile) + current.savedProfiles) {
            it.copy(selectedProfile = ref, base = ProfileBase(ref, it.config))
        }
        showMessage(UiText.res(R.string.message_profile_saved, UiText.Raw(profile.name)), UNDO) {
            updateProfiles(_uiState.value.savedProfiles.filterNot { it.id == profile.id }) {
                it.copy(
                    selectedProfile = if (it.selectedProfile == ref) current.selectedProfile else it.selectedProfile,
                    base = if (it.base.ref == ref) current.base else it.base
                )
            }
        }
        return true
    }

    /** Elimina un perfil guardado sin cambiar las condiciones aplicadas. */
    fun deleteProfile(id: String) {
        val current = _uiState.value
        val index = current.savedProfiles.indexOfFirst { it.id == id }
        if (index < 0) return
        val removed = current.savedProfiles[index]
        val ref = ProfileRef.Saved(id)
        val remaining = current.savedProfiles - removed
        updateProfiles(remaining) {
            // Los valores aplicados no cambian: solo deja de estar elegido el perfil eliminado.
            val selection = if (it.selectedProfile == ref) {
                ProfileCatalog.match(it.config, remaining)
            } else {
                it.selectedProfile
            }
            it.copy(
                selectedProfile = selection,
                base = if (it.base.ref == ref) ProfileBase(selection, it.config) else it.base
            )
        }
        showMessage(UiText.res(R.string.message_profile_deleted, UiText.Raw(removed.name)), UNDO) {
            val restored = _uiState.value.savedProfiles.toMutableList()
                .apply { add(index.coerceAtMost(size), removed) }
            updateProfiles(restored) {
                it.copy(
                    selectedProfile = if (current.selectedProfile == ref) ref else it.selectedProfile,
                    base = if (current.base.ref == ref) current.base else it.base
                )
            }
        }
    }

    private fun updateProfiles(profiles: List<SavedProfile>, adjust: (MainUiState) -> MainUiState) {
        _uiState.update { adjust(it.copy(savedProfiles = profiles)) }
        profileStore.save(profiles)
    }

    // ------------------------------------------------------------------ Parámetros

    /**
     * Cambio de la configuración: [transform] modifica solo sus parámetros. El perfil elegido pasa a
     * ser el que corresponde a los valores nuevos; el perfil base no cambia.
     */
    fun editConfig(transform: (ThrottleConfig) -> ThrottleConfig) = applyEdit(transform, typedDraft = null)

    /** Texto escrito en un campo: si es válido se aplica; si no, solo cambia el texto. */
    fun onParameterTextChange(field: ParameterField, text: String) {
        val current = _uiState.value
        val parsed = ParameterInput.parse(field, text, current.bandwidthUnit)
        if (parsed is ParameterParse.Valid && field.isEnabled(current.config)) {
            applyEdit({ field.write(it, parsed.value) }, typedDraft = field to text)
        } else {
            _uiState.update { it.copy(drafts = it.drafts + (field to text)) }
        }
    }

    /**
     * Al salir de un campo, un texto válido se reformatea (p. ej. «0200» → «200») y uno vacío pasa a «0»,
     * también donde 0 está fuera de rango (el campo sigue indicando el error).
     */
    fun onParameterFocusLost(field: ParameterField) {
        _uiState.update { current ->
            val text = current.drafts[field] ?: return@update current
            val unit = current.bandwidthUnit
            val formatted = when {
                text.isBlank() -> ParameterInput.format(field, 0.0, unit)
                ParameterInput.parse(field, text, unit) is ParameterParse.Valid ->
                    ParameterInput.format(field, field.read(current.config), unit)
                else -> return@update current
            }
            current.copy(drafts = current.drafts + (field to formatted))
        }
    }

    fun selectBandwidthUnit(unit: BandwidthUnit) {
        preferences.bandwidthUnit = unit
        _uiState.update { current ->
            val formatted = ParameterInput.drafts(current.config, unit).filterKeys { it.isBandwidth }
            current.copy(bandwidthUnit = unit, drafts = current.drafts + formatted)
        }
    }

    /** Vuelve a los valores del perfil base, con «Deshacer». */
    fun resetToBase() {
        val current = _uiState.value
        if (current.changedFields.isEmpty()) return
        val previous = current.config
        _uiState.update {
            it.copy(
                config = current.base.config,
                selectedProfile = current.base.ref,
                drafts = ParameterInput.drafts(current.base.config, it.bandwidthUnit)
            )
        }
        userConfig.value = current.base.config
        showMessage(UiText.res(R.string.message_values_restored, current.baseName), UNDO) { editConfig { previous } }
    }

    private fun applyEdit(transform: (ThrottleConfig) -> ThrottleConfig, typedDraft: Pair<ParameterField, String>?) {
        val updated = _uiState.updateAndGet { current ->
            val config = ProfileCatalog.tag(transform(current.config))
            current.copy(
                config = config,
                selectedProfile = ProfileCatalog.match(config, current.savedProfiles),
                drafts = if (typedDraft != null) {
                    current.drafts + typedDraft
                } else {
                    ParameterInput.drafts(config, current.bandwidthUnit)
                }
            )
        }
        userConfig.value = updated.config
    }

    // ------------------------------------------------------------------ Avisos y bienvenida

    private fun showMessage(text: UiText, actionLabel: UiText? = null, undo: (() -> Unit)? = null) {
        val message = UserMessage(id = ++messageCounter, text = text, actionLabel = actionLabel)
        pendingUndo = undo?.let { message.id to it }
        _uiState.update { it.copy(userMessage = message) }
    }

    /** El usuario pulsó la acción del aviso [id]; un aviso ya sustituido no hace nada. */
    fun onMessageAction(id: Long) {
        val undo = pendingUndo?.takeIf { it.first == id } ?: return
        pendingUndo = null
        undo.second()
    }

    /** El aviso [id] desapareció de la pantalla. */
    fun clearMessage(id: Long) {
        if (pendingUndo?.first == id) pendingUndo = null
        _uiState.update { if (it.userMessage?.id == id) it.copy(userMessage = null) else it }
    }

    fun completeOnboarding() {
        preferences.onboardingCompleted = true
        _uiState.update { it.copy(showOnboarding = false) }
    }

    private fun initialState(): MainUiState {
        val saved = profileStore.load()
        val unit = preferences.bandwidthUnit
        val config = tunnel.lastAppliedConfig() ?: MainUiState.DEFAULT_CONFIG
        val selection = ProfileCatalog.match(config, saved)
        return MainUiState(
            config = config,
            selectedProfile = selection,
            base = ProfileBase(selection, config),
            savedProfiles = saved,
            bandwidthUnit = unit,
            drafts = ParameterInput.drafts(config, unit),
            showOnboarding = !preferences.onboardingCompleted,
            reservedProfileNames = naming.reservedNames
        )
    }

    companion object {
        /** Espera tras el último cambio de un valor antes de aplicarlo al túnel. */
        const val CONFIG_DEBOUNCE_MS = 150L

        /** Periodo del tiempo activo y de las muestras de la gráfica. */
        const val TICK_MS = 1_000L

        private const val MS_PER_SECOND = 1_000L
        private val UNDO = UiText.res(R.string.action_undo)
    }
}
