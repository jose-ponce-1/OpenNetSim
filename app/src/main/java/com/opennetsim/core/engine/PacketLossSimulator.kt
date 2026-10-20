package com.opennetsim.core.engine

/**
 * Simulador de descarte estocástico de paquetes de red.
 *
 * @param initialLossProbability Probabilidad de pérdida en rango [0.0, 1.0] (ej. 0.05 para 5%).
 * @param randomGenerator Proveedor inyectable de números pseudoaleatorios uniformes en [0.0, 1.0).
 */
class PacketLossSimulator(
    initialLossProbability: Double = 0.0,
    private val randomGenerator: () -> Double = { kotlin.random.Random.nextDouble() }
) {
    var lossProbability: Double = clamp(initialLossProbability)
        private set

    /**
     * Evalúa si el paquete entrante debe ser descartado probabilísticamente.
     * @return true si el paquete debe ser descartado; false si debe preservarse.
     */
    @Synchronized
    fun shouldDrop(): Boolean {
        if (lossProbability <= 0.0) return false
        if (lossProbability >= 1.0) return true
        return randomGenerator() < lossProbability
    }

    /**
     * Actualiza la probabilidad de pérdida (0.0 a 1.0).
     */
    @Synchronized
    fun updateLossProbability(probability: Double) {
        this.lossProbability = clamp(probability)
    }

    /**
     * Actualiza la probabilidad de pérdida desde un porcentaje (0.0% a 100.0%).
     */
    @Synchronized
    fun updateLossPercent(percent: Float) {
        updateLossProbability(percent.toDouble() / 100.0)
    }

    private fun clamp(value: Double): Double {
        return value.coerceIn(0.0, 1.0)
    }
}

