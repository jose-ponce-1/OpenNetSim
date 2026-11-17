package com.opennetsim.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JitterProcessTest {

    private val random = Random(42)
    private val jitter = JitterProcess { min, max -> random.nextLong(min, max + 1) }

    @Test
    fun offsetStaysWithinAmplitude() {
        jitter.reset(50L)
        var now = 0L
        repeat(20_000) {
            now += random.nextLong(0, 5)
            val offset = jitter.offsetAt(now)
            assertTrue("Desplazamiento $offset fuera de ±50", offset in -50L..50L)
        }
    }

    @Test
    fun departuresNeverGoBackwardsSoJitterNeverReorders() {
        jitter.reset(80L)
        var now = 0L
        var lastDeparture = Long.MIN_VALUE
        repeat(50_000) {
            now += random.nextLong(0, 3)
            val departure = now + jitter.offsetAt(now)
            assertTrue("Salida $departure antes que la anterior $lastDeparture", departure >= lastDeparture)
            lastDeparture = departure
        }
    }

    @Test
    fun packetsOneSecondApartSeeIndependentValuesAcrossTheWholeRange() {
        jitter.reset(40L)
        val offsets = (0 until 500).map { second -> jitter.offsetAt(second * 1_000L) }
        assertTrue("Valores bajos", offsets.min() < -25L)
        assertTrue("Valores altos", offsets.max() > 25L)
        assertEquals("Media centrada en 0", 0.0, offsets.average(), 5.0)
    }

    @Test
    fun zeroAmplitudeMeansNoJitter() {
        jitter.reset(0L)
        assertEquals(0L, jitter.offsetAt(123L))
    }
}
