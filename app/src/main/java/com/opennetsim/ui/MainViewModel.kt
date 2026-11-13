package com.opennetsim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TunnelState
import com.opennetsim.service.ThrottleVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * @param tunnelState estado real del túnel (por defecto, el del servicio).
 * @param scopeOverride ámbito para recoger los flujos; los tests pasan uno sin `Dispatchers.Main`.
 */
@OptIn(FlowPreview::class)
class MainViewModel(
    private val tunnelState: StateFlow<TunnelState> = ThrottleVpnService.tunnelState,
    telemetry: StateFlow<NetworkTelemetry> = ThrottleVpnService.telemetry,
    scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private var previousTunnelState: TunnelState? = null

    /**
     * Configuración que debe enviarse al túnel activo: sin repetidos, con debounce mientras se arrastra
     * un slider, y solo si el túnel está en marcha con una configuración distinta (así una recreación
     * de la actividad no reenvía nada).
     */
    val configToApply: Flow<ThrottleConfig> = _uiState
        .map { it.config }
        .distinctUntilChanged()
        .debounce(CONFIG_DEBOUNCE_MS)
        .filter { config ->
            val state = tunnelState.value
            state is TunnelState.Running && state.config != config
        }

    init {
        scope.launch {
            tunnelState.collect { state -> onTunnelState(state) }
        }
        scope.launch {
            telemetry.collect { telemetryData ->
                _uiState.update { it.copy(telemetry = telemetryData) }
            }
        }
    }

    private fun onTunnelState(state: TunnelState) {
        val previous = previousTunnelState
        previousTunnelState = state
        _uiState.update { current ->
            when (state) {
                is TunnelState.Running -> current.copy(
                    isActive = true,
                    isStarting = false,
                    ipv6Routed = state.ipv6Routed,
                    userMessage = if (previous is TunnelState.Running) {
                        current.userMessage
                    } else {
                        "Throttler activado (${current.selectedPreset.displayName})."
                    }
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

    fun selectPreset(preset: ThrottlePreset) {
        val newConfig = if (preset == ThrottlePreset.CUSTOM) {
            _uiState.value.config.copy(preset = ThrottlePreset.CUSTOM)
        } else {
            ThrottlePreset.fromPreset(preset)
        }
        _uiState.update { current ->
            current.copy(
                config = newConfig,
                selectedPreset = preset
            )
        }
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
        _uiState.update { current ->
            val modified = transform(current.config)
            val matchedPreset = ThrottlePreset.entries.firstOrNull { modified.matchesPreset(it) }
                ?: ThrottlePreset.CUSTOM

            current.copy(
                config = modified.copy(preset = matchedPreset),
                selectedPreset = matchedPreset
            )
        }
    }

    /**
     * Procesar resultado del cuadro de diálogo del sistema VpnService. El estado activo lo publica el
     * servicio; aquí solo se informa de una denegación.
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
    }

    fun clearVpnPermissionRequest() {
        _uiState.update { it.copy(requestVpnPermission = false) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    companion object {
        /** Espera tras el último cambio de un control antes de aplicarlo al túnel. */
        const val CONFIG_DEBOUNCE_MS = 150L
    }
}
