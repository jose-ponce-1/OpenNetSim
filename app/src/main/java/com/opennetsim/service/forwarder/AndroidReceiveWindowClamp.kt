package com.opennetsim.service.forwarder

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.net.Socket

/**
 * [ReceiveWindowClamp] con la opción `TCP_WINDOW_CLAMP` de Linux sobre un duplicado del descriptor
 * del socket (comparte el socket del kernel). A diferencia de `SO_RCVBUF`, no desactiva el autoajuste
 * del búfer y se puede subir o bajar con la conexión establecida.
 */
object AndroidReceiveWindowClamp : ReceiveWindowClamp {
    /** `TCP_WINDOW_CLAMP` de `linux/tcp.h`; `OsConstants` no lo expone. */
    private const val TCP_WINDOW_CLAMP = 10

    override fun apply(socket: Socket, bytes: Int) {
        val descriptor = try {
            ParcelFileDescriptor.fromSocket(socket)
        } catch (e: IllegalStateException) {
            throw IOException("No se pudo duplicar el descriptor del socket", e)
        } ?: throw IOException("El socket no tiene descriptor")
        descriptor.use {
            try {
                Os.setsockoptInt(it.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_WINDOW_CLAMP, bytes)
            } catch (e: ErrnoException) {
                throw IOException("setsockopt(TCP_WINDOW_CLAMP) falló", e)
            }
        }
    }
}
