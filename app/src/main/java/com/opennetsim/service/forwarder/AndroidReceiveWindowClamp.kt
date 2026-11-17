package com.opennetsim.service.forwarder

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.net.Socket

/**
 * [ReceiveWindowClamp] con dos opciones de socket de Linux, ambas con el socket ya conectado (la
 * escala de ventana ya se negoció en el SYN y no queda limitada) y ajustables en caliente:
 * - `SO_RCVBUF`: lo que acota los datos que el kernel acepta sin que el túnel los lea. Linux reserva
 *   el doble del valor pedido, así que se pide la mitad del tope. Desactiva el autoajuste del búfer.
 * - `TCP_WINDOW_CLAMP`: solo acota la ventana anunciada por delante de lo ya recibido, no la cola sin
 *   leer (medido en un kernel 4.14: con tope de 625 KB y búfer de 4 MB, un socket acumuló 6,9 MB). Se
 *   fija al mismo valor para que, sin autoajuste, la ventana pueda crecer hasta el tope y no se quede
 *   en la calculada al establecer la conexión. Se aplica sobre un duplicado del descriptor, que
 *   comparte el socket del kernel.
 */
object AndroidReceiveWindowClamp : ReceiveWindowClamp {
    /** `TCP_WINDOW_CLAMP` de `linux/tcp.h`; `OsConstants` no lo expone. */
    private const val TCP_WINDOW_CLAMP = 10

    /** Linux guarda `2 × SO_RCVBUF` como búfer real (`sock_setsockopt`). */
    private const val KERNEL_RCVBUF_FACTOR = 2

    override fun apply(socket: Socket, bytes: Int) {
        socket.receiveBufferSize = bytes / KERNEL_RCVBUF_FACTOR
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
