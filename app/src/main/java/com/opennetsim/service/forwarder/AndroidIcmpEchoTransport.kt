package com.opennetsim.service.forwarder

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.opennetsim.service.diag.DiagLevel
import com.opennetsim.service.diag.NetDiagnostics
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [IcmpEchoTransport] con los sockets de ping sin privilegios de Linux
 * (`socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)` y su equivalente IPv6), que Android permite a las
 * apps. La espera usa `poll()` sobre los sockets y un pipe propio para [wakeup].
 *
 * @param protect excluye el socket del túnel (`VpnService.protect`).
 */
class AndroidIcmpEchoTransport(
    private val protect: (FileDescriptor) -> Boolean,
    private val diagnostics: NetDiagnostics
) : IcmpEchoTransport {

    private class PingSocket(val fd: FileDescriptor) : IcmpEchoSocket {
        override fun send(message: ByteArray) {
            try {
                Os.write(fd, message, 0, message.size)
            } catch (e: ErrnoException) {
                throw e.toIOException()
            }
        }

        override fun receive(buffer: ByteArray): Int = try {
            Os.read(fd, buffer, 0, buffer.size)
        } catch (e: ErrnoException) {
            throw e.toIOException()
        }

        override fun close() {
            try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                throw e.toIOException()
            }
        }
    }

    private val wakeupPipe: Array<FileDescriptor> = Os.pipe()
    private val wakeupPending = AtomicBoolean(false)
    private val drainBuffer = ByteArray(DRAIN_BUFFER_BYTES)

    @Volatile
    private var closed = false

    override fun open(destination: InetAddress): IcmpEchoSocket {
        val isIpv6 = destination is Inet6Address
        val fd = try {
            Os.socket(
                if (isIpv6) OsConstants.AF_INET6 else OsConstants.AF_INET,
                OsConstants.SOCK_DGRAM,
                if (isIpv6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP
            )
        } catch (e: ErrnoException) {
            throw e.toIOException()
        }
        val socket = PingSocket(fd)
        try {
            if (!protect(fd)) throw IOException("protect() rechazó el socket de ping")
            Os.connect(fd, destination, 0)
        } catch (e: ErrnoException) {
            closeQuietly(socket)
            throw e.toIOException()
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
        return socket
    }

    override fun awaitReadable(sockets: Collection<IcmpEchoSocket>, timeoutMs: Long): List<IcmpEchoSocket> {
        if (closed) return emptyList()
        val pingSockets = sockets.filterIsInstance<PingSocket>()
        val pollFds = Array(pingSockets.size + 1) { index ->
            StructPollfd().apply {
                fd = if (index == 0) wakeupPipe[0] else pingSockets[index - 1].fd
                events = OsConstants.POLLIN.toShort()
            }
        }
        return if (poll(pollFds, timeoutMs)) {
            if (pollFds[0].revents.toInt() != 0) drainWakeups()
            pingSockets.filterIndexed { index, _ -> pollFds[index + 1].revents.toInt() != 0 }
        } else {
            emptyList()
        }
    }

    /** `poll()` sobre [pollFds]; false si se interrumpió (EINTR o un descriptor cerrado). */
    private fun poll(pollFds: Array<StructPollfd>, timeoutMs: Long): Boolean = try {
        Os.poll(pollFds, timeoutMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        true
    } catch (e: ErrnoException) {
        diagnostics.log(DiagLevel.DEBUG, "icmp.poll", "poll() interrumpido: ${e.message}", e)
        false
    }

    override fun wakeup() {
        if (closed || !wakeupPending.compareAndSet(false, true)) return
        try {
            Os.write(wakeupPipe[1], WAKEUP_BYTE, 0, 1)
        } catch (e: ErrnoException) {
            diagnostics.log(DiagLevel.DEBUG, "icmp.wakeup", "No se pudo despertar el bucle ICMP", e)
        }
    }

    private fun drainWakeups() {
        // Rearmar antes de vaciar: un wakeup posterior vuelve a escribir en el pipe.
        wakeupPending.set(false)
        try {
            Os.read(wakeupPipe[0], drainBuffer, 0, drainBuffer.size)
        } catch (e: ErrnoException) {
            diagnostics.log(DiagLevel.DEBUG, "icmp.wakeup", "No se pudo vaciar el pipe ICMP", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        wakeupPipe.forEach { fd ->
            try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                diagnostics.log(DiagLevel.DEBUG, "icmp.close", "Error al cerrar el pipe ICMP", e)
            }
        }
    }

    private fun closeQuietly(socket: IcmpEchoSocket) {
        try {
            socket.close()
        } catch (e: IOException) {
            diagnostics.log(DiagLevel.DEBUG, "icmp.close", "Error al cerrar un socket de ping", e)
        }
    }

    private companion object {
        val WAKEUP_BYTE = byteArrayOf(1)
        const val DRAIN_BUFFER_BYTES = 64
    }
}

/** `ErrnoException.rethrowAsIOException()` solo existe desde API 30; la app soporta API 26. */
private fun ErrnoException.toIOException(): IOException = IOException(message, this)
