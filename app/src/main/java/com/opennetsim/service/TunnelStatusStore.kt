package com.opennetsim.service

import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado del túnel compartido en el proceso entre [ThrottleVpnService] (único escritor) y
 * [ServiceTunnelController] (lectura para la UI). Fuera del paquete `service` nadie lo ve.
 */
internal object TunnelStatusStore {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    private val mutableTelemetry = MutableStateFlow(NetworkTelemetry())
    val telemetry: StateFlow<NetworkTelemetry> = mutableTelemetry.asStateFlow()

    fun publish(state: TunnelState) {
        mutableState.value = state
    }

    fun publish(telemetry: NetworkTelemetry) {
        mutableTelemetry.value = telemetry
    }
}
