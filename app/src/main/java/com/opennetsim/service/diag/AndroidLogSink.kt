package com.opennetsim.service.diag

import android.util.Log

/**
 * Escribe las entradas de diagnóstico en Logcat con la etiqueta común [TAG].
 */
object AndroidLogSink : DiagnosticsSink {
    const val TAG = "OpenNetSim"

    override fun write(level: DiagLevel, key: String, message: String, error: Throwable?) {
        val line = "[$key] $message"
        when (level) {
            DiagLevel.DEBUG -> Log.d(TAG, line, error)
            DiagLevel.INFO -> Log.i(TAG, line, error)
            DiagLevel.WARN -> Log.w(TAG, line, error)
            DiagLevel.ERROR -> Log.e(TAG, line, error)
        }
    }

    /** Diagnóstico de producción: Logcat con limitación de frecuencia por clave. */
    fun rateLimited(): NetDiagnostics = RateLimitedDiagnostics(this)
}
