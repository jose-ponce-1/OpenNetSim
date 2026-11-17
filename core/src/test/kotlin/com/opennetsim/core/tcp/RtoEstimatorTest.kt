package com.opennetsim.core.tcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtoEstimatorTest {

    @Test
    fun convergesToSimulatedRtt() {
        val estimator = RtoEstimator()
        repeat(50) { estimator.onRttSample(600L) }

        assertEquals(600.0, estimator.smoothedRttMs!!, 1.0)
        // Con varianza ~0, RTO ≈ SRTT (+ granularidad), dentro de los límites.
        assertTrue("RTO ${estimator.rtoMs}", estimator.rtoMs in 600L..620L)
    }

    @Test
    fun firstSampleFollowsRfc6298() {
        val estimator = RtoEstimator()
        estimator.onRttSample(100L)
        // SRTT = R, RTTVAR = R/2, RTO = SRTT + 4·RTTVAR = 300 ms
        assertEquals(300L, estimator.rtoMs)
    }

    @Test
    fun appliesMinimumAndBackoff() {
        val estimator = RtoEstimator()
        repeat(20) { estimator.onRttSample(5L) }
        assertEquals(RtoEstimator.DEFAULT_MIN_RTO_MS, estimator.rtoMs)

        estimator.onTimeout()
        assertEquals(400L, estimator.rtoMs)
        estimator.onTimeout()
        assertEquals(800L, estimator.rtoMs)
        estimator.resetBackoff()
        assertEquals(200L, estimator.rtoMs)
    }

    @Test
    fun karnIgnoresRetransmittedSegments() {
        val estimator = RtoEstimator()
        estimator.onAckOfSegment(sentAtMs = 0L, nowMs = 5_000L, wasRetransmitted = true)
        assertNull("Una muestra de un segmento retransmitido es ambigua", estimator.smoothedRttMs)
        assertEquals(RtoEstimator.DEFAULT_INITIAL_RTO_MS, estimator.rtoMs)
    }

    @Test
    fun retransmissionQueueDoesNotSampleRetransmittedSegments() {
        val estimator = RtoEstimator(initialRtoMs = 300L)
        val queue = TcpRetransmissionQueue(estimator)
        queue.add(OutboundSegment(seq = 1000L, payload = ByteArray(100), sentAtMs = 0L), nowMs = 0L)

        // Timeout: el segmento se retransmite en t=300 y se confirma en t=310
        val retransmitted = queue.onTimer(300L)
        assertEquals(1000L, retransmitted!!.seq)
        queue.onAck(1100L, nowMs = 310L)

        assertNull("Karn: no se debe muestrear el RTT de 10 ms", estimator.smoothedRttMs)
        assertTrue(queue.isEmpty)
        assertNull(queue.timerDeadlineMs)
    }

    @Test
    fun bdpCapForHighLatencyProfile() {
        val cap = TcpWindowPolicy.bdpCapBytes(downKbps = 10_000, latencyMs = 600, jitterMs = 0)
        assertTrue("Tope BDP $cap B", cap >= 1_500_000)
        assertEquals(TcpWindowPolicy.MIN_IN_FLIGHT_BYTES, TcpWindowPolicy.bdpCapBytes(128, 0, 0))
        assertEquals(TcpWindowPolicy.MAX_IN_FLIGHT_BYTES, TcpWindowPolicy.bdpCapBytes(1_000_000, 5_000, 0))
    }
}
