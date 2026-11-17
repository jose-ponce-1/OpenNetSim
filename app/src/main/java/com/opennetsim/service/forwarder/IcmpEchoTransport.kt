package com.opennetsim.service.forwarder

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress

/**
 * Socket de ping del sistema conectado a un destino (`SOCK_DGRAM` + `IPPROTO_ICMP`/`IPPROTO_ICMPV6`,
 * sin privilegios). El kernel asigna el identificador ICMP y calcula el checksum.
 */
interface IcmpEchoSocket : Closeable {
    /** Envía un mensaje ICMP Echo Request completo (cabecera de 8 bytes + datos). */
    @Throws(IOException::class)
    fun send(message: ByteArray)

    /** Lee el mensaje ICMP recibido (sin cabecera IP) en [buffer] y devuelve su longitud. */
    @Throws(IOException::class)
    fun receive(buffer: ByteArray): Int
}

/**
 * Acceso a los sockets de ping del sistema. En Android lo implementa [AndroidIcmpEchoTransport]; en
 * los tests JVM, un transporte en memoria.
 *
 * Todas las llamadas, salvo [wakeup], se hacen desde un único hilo (el de [IcmpForwarder]).
 */
interface IcmpEchoTransport {
    /** Abre un socket de ping hacia [destination], o lanza [IOException] si el sistema no lo permite. */
    @Throws(IOException::class)
    fun open(destination: InetAddress): IcmpEchoSocket

    /**
     * Espera hasta [timeoutMs] a que algún socket de [sockets] tenga datos, o hasta [wakeup]. Devuelve
     * los sockets legibles (vacío si venció el plazo o se despertó).
     */
    fun awaitReadable(sockets: Collection<IcmpEchoSocket>, timeoutMs: Long): List<IcmpEchoSocket>

    /** Despierta una espera en curso de [awaitReadable]; se puede llamar desde cualquier hilo. */
    fun wakeup()

    /** Libera los recursos del transporte. */
    fun close()

    companion object {
        /** Transporte sin sockets de ping: todo echo request se descarta, como en una red sin ICMP. */
        val UNAVAILABLE: IcmpEchoTransport = object : IcmpEchoTransport {
            private val lock = Object()

            override fun open(destination: InetAddress): IcmpEchoSocket =
                throw IOException("Sockets de ping no disponibles")

            override fun awaitReadable(sockets: Collection<IcmpEchoSocket>, timeoutMs: Long): List<IcmpEchoSocket> {
                synchronized(lock) { lock.wait(timeoutMs.coerceAtLeast(1L)) }
                return emptyList()
            }

            override fun wakeup() {
                synchronized(lock) { lock.notifyAll() }
            }

            override fun close() = wakeup()
        }
    }
}
