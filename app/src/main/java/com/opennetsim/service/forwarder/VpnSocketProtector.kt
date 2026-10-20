package com.opennetsim.service.forwarder

import java.net.DatagramSocket
import java.net.Socket

/**
 * Interfaz para proteger sockets contra el bucle de enrutamiento en VpnService.
 * Los sockets protegidos omiten el túnel VPN y se conectan directamente a través
 * del enlace físico subyacente (Wi-Fi / Datos móviles).
 */
interface VpnSocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean
}

