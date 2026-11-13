package com.opennetsim.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.opennetsim.service.diag.NetDiagnostics
import com.opennetsim.service.diag.warn

/**
 * Observa la red física por defecto de la app (que está excluida del túnel, así que su red por
 * defecto es la subyacente) y avisa cuando cambia su disponibilidad de IPv6.
 *
 * @param onChange se invoca desde un hilo de ConnectivityManager con la red actual (o null) y si
 * tiene conectividad IPv6.
 */
class UnderlyingNetworkMonitor(
    context: Context,
    private val diagnostics: NetDiagnostics,
    private val onChange: (Network?, Boolean) -> Unit
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onChange(network, hasIpv6(network))
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            onChange(network, evaluate(linkProperties))
        }

        override fun onLost(network: Network) {
            onChange(null, false)
        }
    }

    /** Red física por defecto actual, o null si no hay ninguna. */
    fun currentNetwork(): Network? = connectivityManager?.activeNetwork

    /** True si [network] tiene ruta por defecto IPv6 y una dirección IPv6 global. */
    fun hasIpv6(network: Network?): Boolean {
        val properties = network?.let { connectivityManager?.getLinkProperties(it) } ?: return false
        return evaluate(properties)
    }

    private fun evaluate(properties: LinkProperties): Boolean = Ipv6Availability.isRoutable(
        defaultRouteDestinations = properties.routes.filter { it.isDefaultRoute }.map { it.destination.address },
        linkAddresses = properties.linkAddresses.map { it.address }
    )

    fun start() {
        if (registered) return
        try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: SecurityException) {
            diagnostics.warn("net.monitor", "Sin permiso para observar la red física", e)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            diagnostics.warn("net.monitor", "El callback de red ya no estaba registrado", e)
        }
    }
}
