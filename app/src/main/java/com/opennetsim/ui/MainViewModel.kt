package com.opennetsim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.service.ThrottleVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado observable de la pantalla principal de OpenNetSim.
 */
data class MainUiState(
    val isActive: Boolean = false,
    val config: ThrottleConfig = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G),
    val selectedPreset: ThrottlePreset = ThrottlePreset.SLOW_3G,
    val userMessage: String? = null,
    val requestVpnPermission: Boolean = false,
    val telemetry: NetworkTelemetry = NetworkTelemetry()
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ThrottleVpnService.isServiceRunning.collect { running ->
                _uiState.update { it.copy(isActive = running) }
            }
        }
        viewModelScope.launch {
            ThrottleVpnService.telemetry.collect { telemetryData ->
                _uiState.update { it.copy(telemetry = telemetryData) }
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
     * Invocado cuando el usuario interactúa con el botón de conmutación.
     */
    fun onToggleThrottling(hasVpnPermission: Boolean) {
        val currentlyActive = _uiState.value.isActive
        if (currentlyActive) {
            // Desactivar simulación
            _uiState.update {
                it.copy(
                    isActive = false,
                    userMessage = "Throttler detenido."
                )
            }
        } else {
            // Activar simulación
            if (hasVpnPermission) {
                _uiState.update {
                    it.copy(
                        isActive = true,
                        userMessage = "Throttler activado (${it.selectedPreset.displayName})."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(requestVpnPermission = true)
                }
            }
        }
    }

    /**
     * Procesar resultado del cuadro de diálogo del sistema VpnService.
     */
    fun onVpnPermissionResult(isGranted: Boolean) {
        _uiState.update { current ->
            current.copy(
                requestVpnPermission = false,
                isActive = isGranted,
                userMessage = if (isGranted) {
                    "Permiso VPN concedido. Throttler activado."
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
}

