package com.opennetsim.service.diag

import java.util.concurrent.ConcurrentHashMap

/**
 * Nivel de severidad de una entrada de diagnóstico.
 */
enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Registro de diagnóstico del túnel (fallos de conexión, sockets, bucles y TUN).
 *
 * [key] identifica una categoría fija de evento (p. ej. `tcp.connect`), nunca un valor variable como
 * una dirección o un puerto: la limitación de frecuencia se aplica por clave.
 * Los mensajes nunca deben incluir datos del payload.
 */
interface NetDiagnostics {
    fun log(level: DiagLevel, key: String, message: String, error: Throwable? = null)

    companion object {
        /** Implementación que descarta todas las entradas (tests JVM). */
        val NONE: NetDiagnostics = object : NetDiagnostics {
            override fun log(level: DiagLevel, key: String, message: String, error: Throwable?) = Unit
        }
    }
}

fun NetDiagnostics.warn(key: String, message: String, error: Throwable? = null) =
    log(DiagLevel.WARN, key, message, error)

fun NetDiagnostics.error(key: String, message: String, error: Throwable? = null) =
    log(DiagLevel.ERROR, key, message, error)

/**
 * Destino final de las entradas ya filtradas por [RateLimitedDiagnostics].
 */
fun interface DiagnosticsSink {
    fun write(level: DiagLevel, key: String, message: String, error: Throwable?)
}

/**
 * Limita cada clave a [maxEntriesPerWindow] entradas por ventana de [windowMs] milisegundos.
 * Las entradas que exceden el límite se cuentan, y la primera entrada emitida en la ventana
 * siguiente indica cuántas se suprimieron.
 */
class RateLimitedDiagnostics(
    private val sink: DiagnosticsSink,
    private val maxEntriesPerWindow: Int = DEFAULT_MAX_ENTRIES_PER_WINDOW,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) : NetDiagnostics {

    private class KeyState(var windowStartMs: Long) {
        var emittedInWindow: Int = 0
        var suppressed: Long = 0L
    }

    private val states = ConcurrentHashMap<String, KeyState>()

    override fun log(level: DiagLevel, key: String, message: String, error: Throwable?) {
        val now = clockMs()
        val state = states.computeIfAbsent(key) { KeyState(now) }

        val suppressedBefore: Long
        synchronized(state) {
            if (now - state.windowStartMs >= windowMs) {
                state.windowStartMs = now
                state.emittedInWindow = 0
            }
            if (state.emittedInWindow >= maxEntriesPerWindow) {
                state.suppressed++
                return
            }
            state.emittedInWindow++
            suppressedBefore = state.suppressed
            state.suppressed = 0L
        }

        val finalMessage = if (suppressedBefore > 0L) {
            "$message [+$suppressedBefore suprimidas]"
        } else {
            message
        }
        sink.write(level, key, finalMessage, error)
    }

    /** Entradas suprimidas pendientes de notificar para [key]. */
    fun pendingSuppressed(key: String): Long = states[key]?.let { synchronized(it) { it.suppressed } } ?: 0L

    companion object {
        const val DEFAULT_MAX_ENTRIES_PER_WINDOW = 5
        const val DEFAULT_WINDOW_MS = 1000L
    }
}
