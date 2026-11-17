package com.opennetsim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TunnelState
import com.opennetsim.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
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
 * Estado observable de la pantalla principal de OpenNetSim.
 *
 * [isActive] refleja el estado real del túnel publicado por el servicio, nunca una suposición.
 */
data class MainUiState(
    val isActive: Boolean = false,
    val isStarting: Boolean = false,
    val ipv6Routed: Boolean = false,
    val config: ThrottleConfig = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G),
    val selectedPreset: ThrottlePreset = ThrottlePreset.SLOW_3G,
    val userMessage: String? = null,
    val requestVpnPermission: Boolean = false,
    val telemetry: NetworkTelemetry = NetworkTelemetry()
)

/**
 * ViewModel de la pantalla principal. Solo conoce [TunnelController]; nunca el servicio Android.
 *
 * El servicio es la fuente de verdad de la configuración activa: al observar el túnel en marcha, la
 * UI adopta su configuración, y al túnel solo se envían los cambios hechos por el usuario.
 *
 * @param scopeOverride ámbito para recoger los flujos; los tests pasan uno con reloj virtual.
 */
@OptIn(FlowPreview::class)
@Suppress("TooManyFunctions") // Un método por acción de la pantalla.
class MainViewModel(
    private val tunnel: TunnelController,
    scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState(tunnel.lastAppliedConfig()))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private var previousTunnelState: TunnelState? = null

    /** Última configuración elegida por el usuario; null hasta que toca un control. */
    private val userConfig = MutableStateFlow<ThrottleConfig?>(null)

    /**
     * Configuración que debe enviarse al túnel activo: solo cambios del usuario, sin repetidos, con
     * debounce mientras se arrastra un slider, y solo si difiere de la que ya aplica el servicio.
     * Ni el valor inicial ni la configuración adoptada del servicio se reenvían nunca.
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

    private fun onTunnelState(state: TunnelState) {
        val previous = previousTunnelState
        previousTunnelState = state
        _uiState.update { current ->
            when (state) {
                is TunnelState.Running -> runningState(
                    current,
                    state,
                    enteredRunning = previous !is TunnelState.Running
                )
                TunnelState.Starting -> current.copy(isStarting = true)
                TunnelState.Stopped -> current.copy(
                    isActive = false,
                    isStarting = false,
                    userMessage = if (previous is TunnelState.Running) "Throttler detenido." else current.userMessage
                )
                is TunnelState.Failed -> current.copy(
                    isActive = false,
                    isStarting = false,
                    userMessage = "No se pudo activar el túnel: ${state.reason}"
                )
            }
        }
    }

    /**
     * Al entrar en `Running` (arranque, o un ViewModel nuevo con el túnel ya activo) los controles
     * adoptan la configuración del servicio. Las actualizaciones posteriores no se adoptan: llegan
     * con retraso respecto a lo que el usuario está arrastrando y harían saltar los sliders.
     */
    private fun runningState(current: MainUiState, state: TunnelState.Running, enteredRunning: Boolean): MainUiState {
        val base = current.copy(isActive = true, isStarting = false, ipv6Routed = state.ipv6Routed)
        if (!enteredRunning) return base
        // Un cambio del usuario pendiente de enviar ya no aplica a este túnel.
        userConfig.value = null
        return base.copy(
            config = state.config,
            selectedPreset = state.config.preset,
            userMessage = "Throttler activado (${state.config.preset.displayName})."
        )
    }

    fun selectPreset(preset: ThrottlePreset) {
        val newConfig = if (preset == ThrottlePreset.CUSTOM) {
            _uiState.value.config.copy(preset = ThrottlePreset.CUSTOM)
        } else {
            ThrottlePreset.fromPreset(preset)
        }
        _uiState.update { it.copy(config = newConfig, selectedPreset = preset) }
        userConfig.value = newConfig
    }

    fun updateDownBandwidth(kbps: Int) {
        updateParameter { it.copy(downKbps = kbps) }
    }

    fun updateUpBandwidth(kbps: Int) {
        updateParameter { it.copy(upKbps = kbps) }
    }

    fun updateLatency(latencyMs: Int) {
        updateParameter { it.copy(latencyMs = latencyMs) }
    }

    fun updateJitter(jitterMs: Int) {
        updateParameter { it.copy(jitterMs = jitterMs) }
    }

    fun updatePacketLoss(packetLossPercent: Float) {
        updateParameter { it.copy(packetLossPercent = packetLossPercent) }
    }

    private fun updateParameter(transform: (ThrottleConfig) -> ThrottleConfig) {
        val updated = _uiState.updateAndGet { current ->
            val modified = transform(current.config)
            val matchedPreset = ThrottlePreset.entries.firstOrNull { modified.matchesPreset(it) }
                ?: ThrottlePreset.CUSTOM
            current.copy(config = modified.copy(preset = matchedPreset), selectedPreset = matchedPreset)
        }
        userConfig.value = updated.config
    }

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
     * configuración de los controles; el estado activo lo publica después el servicio.
     */
    fun onVpnPermissionResult(isGranted: Boolean) {
        _uiState.update { current ->
            current.copy(
                requestVpnPermission = false,
                userMessage = if (isGranted) {
                    current.userMessage
                } else {
                    "Se requiere permiso de VPN para interceptar y simular el tráfico."
                }
            )
        }
        if (isGranted) tunnel.start(_uiState.value.config)
    }

    /** El diálogo del permiso ya se mostró: no volver a lanzarlo si la pantalla se recompone. */
    fun clearVpnPermissionRequest() {
        _uiState.update { it.copy(requestVpnPermission = false) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    companion object {
        /** Espera tras el último cambio de un control antes de aplicarlo al túnel. */
        const val CONFIG_DEBOUNCE_MS = 150L

        private fun initialState(lastConfig: ThrottleConfig?): MainUiState =
            if (lastConfig == null) {
                MainUiState()
            } else {
                MainUiState(config = lastConfig, selectedPreset = lastConfig.preset)
            }
    }
}
