package com.opennetsim.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketLossSimulatorTest {

    @Test
    fun testZeroProbabilityNeverDrops() {
        val simulator = PacketLossSimulator(initialLossProbability = 0.0)

        for (i in 0 until 1000) {
            assertFalse(simulator.shouldDrop())
        }
    }

    @Test
    fun testOneHundredPercentProbabilityAlwaysDrops() {
        val simulator = PacketLossSimulator(initialLossProbability = 1.0)

        for (i in 0 until 1000) {
            assertTrue(simulator.shouldDrop())
        }
    }

    @Test
    fun testStatisticalDistribution() {
        val targetProbability = 0.25 // 25% de pérdida
        val simulator = PacketLossSimulator(initialLossProbability = targetProbability)

        val totalTrials = 10_000
        var droppedCount = 0

        for (i in 0 until totalTrials) {
            if (simulator.shouldDrop()) {
                droppedCount++
            }
        }

        val observedRatio = droppedCount.toDouble() / totalTrials
        // Tolerancia del 2.5% sobre 10.000 iteraciones
        assertEquals(targetProbability, observedRatio, 0.025)
    }

    @Test
    fun testClampingAndPercentUpdate() {
        val simulator = PacketLossSimulator(initialLossProbability = -0.5)
        assertEquals(0.0, simulator.lossProbability, 0.0001)

        simulator.updateLossProbability(1.8)
        assertEquals(1.0, simulator.lossProbability, 0.0001)

        simulator.updateLossPercent(12.5f)
        assertEquals(0.125, simulator.lossProbability, 0.0001)
    }

    @Test
    fun testDeterministicSequenceWithMockGenerator() {
        val values = listOf(0.10, 0.30, 0.05, 0.80)
        var index = 0
        val mockGenerator = { values[index++] }

        val simulator = PacketLossSimulator(
            initialLossProbability = 0.20,
            randomGenerator = mockGenerator
        )

        // 0.10 < 0.20 -> drop
        assertTrue(simulator.shouldDrop())
        // 0.30 >= 0.20 -> keep
        assertFalse(simulator.shouldDrop())
        // 0.05 < 0.20 -> drop
        assertTrue(simulator.shouldDrop())
        // 0.80 >= 0.20 -> keep
        assertFalse(simulator.shouldDrop())
    }
}

