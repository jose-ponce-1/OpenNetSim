package com.opennetsim.model

/**
 * Estado real del túnel VPN tal y como lo publica el servicio.
 */
sealed interface TunnelState {
    /** Túnel detenido (estado inicial o tras una parada pedida por el usuario). */
    data object Stopped : TunnelState

    /** Arranque pedido: estableciendo la interfaz y el pipeline. */
    data object Starting : TunnelState

    /**
     * Túnel establecido y reenviando tráfico.
     * @param ipv6Routed true si IPv6 pasa por el túnel (la red física tiene conectividad IPv6).
     */
    data class Running(val config: ThrottleConfig, val ipv6Routed: Boolean) : TunnelState

    /** El túnel no pudo establecerse o un fallo no recuperable lo detuvo. */
    data class Failed(val reason: String) : TunnelState

    val isActive: Boolean get() = this is Running
}
