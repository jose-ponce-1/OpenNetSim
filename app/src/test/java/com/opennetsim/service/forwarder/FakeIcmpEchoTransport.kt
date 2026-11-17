package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.IcmpPacket
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transporte de ping en memoria que imita al kernel: reescribe el identificador ICMP con el suyo y
 * responde a cada Echo Request salvo que el destino esté en [unreachable].
 */
class FakeIcmpEchoTransport(private val kernelIdentifier: Int = KERNEL_IDENTIFIER) : IcmpEchoTransport {
    val unreachable: MutableSet<InetAddress> = ConcurrentHashMap.newKeySet()
    val sent = CopyOnWriteArrayList<Pair<InetAddress, IcmpPacket>>()
    val openedSockets = AtomicInteger(0)
    val closedSockets = AtomicInteger(0)

    @Volatile
    var failOpen = false

    @Volatile
    var closed = false
        private set

    private val lock = Object()
    private var woken = false

    inner class Socket(private val destination: InetAddress) : IcmpEchoSocket {
        val inbox = ConcurrentLinkedQueue<ByteArray>()

        override fun send(message: ByteArray) {
            val request = IcmpPacket.parse(message) ?: throw IOException("Mensaje ICMP no válido")
            sent += destination to request
            if (destination in unreachable) return
            val replyType = if (destination is Inet6Address) {
                IcmpPacket.TYPE_ECHO_REPLY_V6
            } else {
                IcmpPacket.TYPE_ECHO_REPLY_V4
            }
            val reply = IcmpPacket(
                type = replyType,
                code = 0,
                identifier = kernelIdentifier,
                sequenceNumber = request.sequenceNumber,
                payload = request.payload
            )
            inbox.add(reply.toByteArray(isIpv6 = false))
            wakeup()
        }

        override fun receive(buffer: ByteArray): Int {
            val message = inbox.poll() ?: throw IOException("Sin datos")
            message.copyInto(buffer)
            return message.size
        }

        override fun close() {
            closedSockets.incrementAndGet()
        }
    }

    override fun open(destination: InetAddress): IcmpEchoSocket {
        if (failOpen) throw IOException("Sockets de ping no disponibles")
        openedSockets.incrementAndGet()
        return Socket(destination)
    }

    override fun awaitReadable(sockets: Collection<IcmpEchoSocket>, timeoutMs: Long): List<IcmpEchoSocket> {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
        synchronized(lock) {
            while (true) {
                val ready = sockets.filter { (it as Socket).inbox.isNotEmpty() }
                if (ready.isNotEmpty() || woken) {
                    woken = false
                    return ready
                }
                val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MS
                if (remainingMs <= 0) return emptyList()
                lock.wait(remainingMs)
            }
        }
    }

    override fun wakeup() {
        synchronized(lock) {
            woken = true
            lock.notifyAll()
        }
    }

    override fun close() {
        closed = true
        wakeup()
    }

    companion object {
        const val KERNEL_IDENTIFIER = 4242
        private const val NANOS_PER_MS = 1_000_000L
    }
}
