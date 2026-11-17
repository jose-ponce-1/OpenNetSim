package com.opennetsim.service.forwarder

import java.io.IOException
import java.net.Socket

/**
 * Limita cuántos datos puede tener un socket TCP exterior pendientes hacia el túnel: lo que el kernel
 * acepta sin que el túnel lo lea más lo que el servidor tiene en vuelo. El tope se aplica con el socket
 * conectado y se puede cambiar con la conexión abierta.
 *
 * En Android lo implementa [AndroidReceiveWindowClamp]; en los tests JVM, un doble que registra las
 * llamadas.
 */
fun interface ReceiveWindowClamp {
    /** Aplica el tope de [bytes] a [socket] conectado, o lanza [IOException] si no es posible. */
    @Throws(IOException::class)
    fun apply(socket: Socket, bytes: Int)

    companion object {
        /** Sin tope: el kernel autoajusta el búfer (comportamiento por defecto en la JVM). */
        val NONE = ReceiveWindowClamp { _, _ -> }
    }
}
