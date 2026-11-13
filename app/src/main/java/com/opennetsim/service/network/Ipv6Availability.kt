package com.opennetsim.service.network

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Decide si la red física ofrece conectividad IPv6 real: hace falta una ruta por defecto IPv6 y una
 * dirección IPv6 global (ni link-local, ni loopback, ni ULA `fc00::/7`, ni site-local).
 *
 * Si no la ofrece, el túnel no enruta IPv6: Android bloquea esa familia y las apps caen a IPv4 al
 * instante, en lugar de perderse en un agujero negro.
 */
object Ipv6Availability {
    private const val ULA_PREFIX_MASK = 0xFE
    private const val ULA_PREFIX = 0xFC

    /**
     * @param defaultRouteDestinations destinos de las rutas por defecto de la red (`0.0.0.0/0`, `::/0`).
     * @param linkAddresses direcciones asignadas a la red.
     */
    fun isRoutable(defaultRouteDestinations: List<InetAddress>, linkAddresses: List<InetAddress>): Boolean =
        defaultRouteDestinations.any { it is Inet6Address } &&
            linkAddresses.any { it is Inet6Address && isGlobal(it) }

    fun isGlobal(address: Inet6Address): Boolean =
        !address.isLinkLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isSiteLocalAddress &&
            !address.isAnyLocalAddress &&
            !address.isMulticastAddress &&
            (address.address[0].toInt() and ULA_PREFIX_MASK) != ULA_PREFIX
}
