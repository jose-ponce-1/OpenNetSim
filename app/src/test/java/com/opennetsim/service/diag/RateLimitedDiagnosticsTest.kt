package com.opennetsim.service.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitedDiagnosticsTest {

    private class CapturingSink : DiagnosticsSink {
        val entries = mutableListOf<Pair<String, String>>()
        override fun write(level: DiagLevel, key: String, message: String, error: Throwable?) {
            entries.add(key to message)
        }
    }

    @Test
    fun suppressesRepeatedEntriesBeyondLimitWithinWindow() {
        var now = 0L
        val sink = CapturingSink()
        val diagnostics = RateLimitedDiagnostics(sink, maxEntriesPerWindow = 3, windowMs = 1000L, clockMs = { now })

        repeat(500) {
            diagnostics.log(DiagLevel.WARN, "tcp.connect", "fallo $it")
            now += 1 // 500 entradas en 500 ms
        }

        assertEquals(3, sink.entries.size)
        assertEquals(497L, diagnostics.pendingSuppressed("tcp.connect"))
    }

    @Test
    fun reportsSuppressedCountOnNextWindow() {
        var now = 0L
        val sink = CapturingSink()
        val diagnostics = RateLimitedDiagnostics(sink, maxEntriesPerWindow = 2, windowMs = 1000L, clockMs = { now })

        repeat(10) { diagnostics.log(DiagLevel.WARN, "udp.connect", "fallo") }
        now = 1500L
        diagnostics.log(DiagLevel.WARN, "udp.connect", "fallo nuevo")

        assertEquals(3, sink.entries.size)
        assertTrue(sink.entries.last().second.endsWith("[+8 suprimidas]"))
        assertEquals(0L, diagnostics.pendingSuppressed("udp.connect"))
    }

    @Test
    fun limitsEachKeyIndependently() {
        val sink = CapturingSink()
        val diagnostics = RateLimitedDiagnostics(sink, maxEntriesPerWindow = 1, windowMs = 1000L, clockMs = { 0L })

        diagnostics.log(DiagLevel.ERROR, "tun.write", "a")
        diagnostics.log(DiagLevel.ERROR, "tun.write", "b")
        diagnostics.log(DiagLevel.ERROR, "tcp.read", "c")

        assertEquals(listOf("tun.write" to "a", "tcp.read" to "c"), sink.entries)
    }
}
