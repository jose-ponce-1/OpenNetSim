package com.opennetsim.core.tcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpWindowPolicyTest {

    @Test
    fun receiveWindowCoversConfiguredRateDuringLatencyAndQueueWindow() {
        // 10 Mbps = 1.250.000 B/s; 0 ms de latencia + 500 ms de cola → 625.000 B.
        assertEquals(625_000, TcpWindowPolicy.receiveWindowClampBytes(downKbps = 10_000, latencyMs = 0, jitterMs = 0))
        // 10 Mbps con 100 ms de latencia y 20 de jitter → 620 ms → 775.000 B.
        assertEquals(
            775_000,
            TcpWindowPolicy.receiveWindowClampBytes(downKbps = 10_000, latencyMs = 100, jitterMs = 20)
        )
    }

    @Test
    fun receiveWindowDoesNotLimitThroughputWithRealPathRtt() {
        // Requisito "Recepción del socket exterior": 5 Mbps con el servidor a 80 ms de RTT necesitan
        // al menos 5 Mbps × 80 ms = 50.000 B de ventana.
        val window = TcpWindowPolicy.receiveWindowClampBytes(downKbps = 5_000, latencyMs = 0, jitterMs = 0)
        assertTrue("ventana $window B", window >= 5_000 * 1000 / 8 * 80 / 1000)
    }

    @Test
    fun receiveWindowIsBoundedBetweenMinimumAndMaximum() {
        assertEquals(
            TcpWindowPolicy.MIN_RECEIVE_WINDOW_BYTES,
            TcpWindowPolicy.receiveWindowClampBytes(downKbps = 64, latencyMs = 0, jitterMs = 0)
        )
        assertEquals(
            TcpWindowPolicy.MAX_RECEIVE_WINDOW_BYTES,
            TcpWindowPolicy.receiveWindowClampBytes(downKbps = 1_000_000, latencyMs = 2_000, jitterMs = 0)
        )
    }
}
