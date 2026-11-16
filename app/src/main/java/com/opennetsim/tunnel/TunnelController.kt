package com.opennetsim.tunnel

import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.TunnelState
import kotlinx.coroutines.flow.StateFlow

/**
 * Contrato entre la UI y el túnel: la UI solo conoce esta interfaz, nunca el servicio Android que la
 * implementa. Así el ViewModel se prueba en la JVM con un controlador falso.
 *
 * El permiso VPN lo pide la UI antes de llamar a [start] (necesita una Activity).
 */
interface TunnelController {
    /** Estado real del túnel publicado por el servicio. */
    val state: StateFlow<TunnelState>

    /** Telemetría en tiempo real del túnel activo. */
    val telemetry: StateFlow<NetworkTelemetry>

    /** Última configuración aplicada al túnel (persistida entre ejecuciones), o null si no hay. */
    fun lastAppliedConfig(): ThrottleConfig?

    fun start(config: ThrottleConfig)

    fun stop()

    /** Aplica [config] al túnel activo sin reiniciarlo. */
    fun updateConfig(config: ThrottleConfig)
}
