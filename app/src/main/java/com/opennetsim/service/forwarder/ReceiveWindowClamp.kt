package com.opennetsim.service.forwarder

import java.io.IOException
import java.net.Socket

/**
 * Limita la ventana de recepción que un socket TCP exterior anuncia al servidor, es decir, cuántos
 * datos puede tener el servidor en vuelo hacia el túnel. Se puede cambiar con la conexión abierta.
 *
 * En Android lo implementa [AndroidReceiveWindowClamp] con `TCP_WINDOW_CLAMP`; en los tests JVM, un
 * doble que registra las llamadas.
 */
fun interface ReceiveWindowClamp {
    /** Aplica el tope de [bytes] a [socket], o lanza [IOException] si el sistema no lo permite. */
    @Throws(IOException::class)
    fun apply(socket: Socket, bytes: Int)

    companion object {
        /** Sin tope: el kernel autoajusta la ventana (comportamiento por defecto en la JVM). */
        val NONE = ReceiveWindowClamp { _, _ -> }
    }
}
