package com.opennetsim.core.time

/**
 * Reloj monotónico: solo sirve para medir intervalos y programar eventos, nunca para mostrar la hora.
 *
 * A diferencia de `System.currentTimeMillis()`, no retrocede ni salta cuando el usuario o la red
 * cambian la hora del sistema. Todo el motor, los forwarders y el servicio deben compartir el mismo
 * reloj para que latencia, jitter, ancho de banda y timeouts sean coherentes entre sí.
 */
fun interface MonotonicClock {
    /** Nanosegundos desde un origen arbitrario y fijo. */
    fun nowNanos(): Long

    /** Milisegundos desde el mismo origen que [nowNanos]. */
    fun nowMs(): Long = nowNanos() / NANOS_PER_MS

    companion object {
        const val NANOS_PER_MS = 1_000_000L

        /** Reloj del sistema basado en `System.nanoTime()`. */
        val SYSTEM: MonotonicClock = MonotonicClock { System.nanoTime() }

        /** Adapta una fuente en milisegundos (p. ej. un reloj simulado en tests). */
        fun ofMillis(nowMs: () -> Long): MonotonicClock = MonotonicClock { nowMs() * NANOS_PER_MS }
    }
}
