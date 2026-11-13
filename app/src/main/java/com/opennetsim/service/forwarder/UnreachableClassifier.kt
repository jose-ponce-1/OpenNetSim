package com.opennetsim.service.forwarder

import com.opennetsim.core.packet.UnreachableKind
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException

/**
 * Traduce errores de socket a motivos de ICMP de destino inalcanzable. Devuelve null para errores
 * que no indican inalcanzabilidad (la app los verá como pérdida).
 */
object UnreachableClassifier {
    fun classify(error: IOException): UnreachableKind? {
        val message = error.message.orEmpty()
        return when {
            error is PortUnreachableException -> UnreachableKind.PORT
            error is NoRouteToHostException -> UnreachableKind.HOST
            message.contains("host", ignoreCase = true) && message.contains("unreachable", ignoreCase = true) ->
                UnreachableKind.HOST
            message.contains("unreachable", ignoreCase = true) -> UnreachableKind.NETWORK
            message.contains("connection refused", ignoreCase = true) -> UnreachableKind.PORT
            else -> null
        }
    }
}
