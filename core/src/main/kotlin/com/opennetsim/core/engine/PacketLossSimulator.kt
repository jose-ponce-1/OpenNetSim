package com.opennetsim.core.engine

/**
 * Simulador de descarte de paquetes de una dirección.
 *
 * - Con [burstLength] = 1 cada paquete se pierde de forma independiente (Bernoulli).
 * - Con [burstLength] ≥ 2 se usa el modelo de Gilbert (dos estados): en el estado "malo" se pierde
 *   todo y en el "bueno" nada. Las transiciones se eligen para que la tasa media de pérdida siga
 *   siendo [lossProbability] y la longitud media de cada ráfaga sea [burstLength] paquetes:
 *   `P(malo → bueno) = 1 / L` y `P(bueno → malo) = p / (L · (1 − p))`.
 *
 * @param initialLossProbability Probabilidad de pérdida en rango [0.0, 1.0] (ej. 0.05 para 5%).
 * @param randomGenerator Proveedor inyectable de números pseudoaleatorios uniformes en [0.0, 1.0).
 * @param initialBurstLength Longitud media de las ráfagas de pérdida, en paquetes.
 */
class PacketLossSimulator(
    initialLossProbability: Double = 0.0,
    private val randomGenerator: () -> Double = { kotlin.random.Random.nextDouble() },
    initialBurstLength: Int = 1
) {
    var lossProbability: Double = clamp(initialLossProbability)
        private set

    var burstLength: Int = initialBurstLength.coerceAtLeast(1)
        private set

    /** Estado del modelo de Gilbert: true mientras dura una ráfaga de pérdida. */
    private var inBurst = false

    /**
     * Evalúa si el paquete entrante debe ser descartado.
     * @return true si el paquete debe ser descartado; false si debe preservarse.
     */
    @Synchronized
    fun shouldDrop(): Boolean = when {
        lossProbability <= 0.0 -> false
        lossProbability >= 1.0 -> true
        burstLength <= 1 -> randomGenerator() < lossProbability
        else -> nextGilbertState()
    }

    private fun nextGilbertState(): Boolean {
        val leaveBurst = 1.0 / burstLength
        val enterBurst = (lossProbability * leaveBurst / (1.0 - lossProbability)).coerceAtMost(1.0)
        inBurst = if (inBurst) randomGenerator() >= leaveBurst else randomGenerator() < enterBurst
        return inBurst
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
        updateLossProbability(percent.toDouble() / PERCENT)
    }

    /** Cambia la longitud media de las ráfagas (1 = pérdidas independientes). */
    @Synchronized
    fun updateBurstLength(packets: Int) {
        burstLength = packets.coerceAtLeast(1)
        if (burstLength == 1) inBurst = false
    }

    private fun clamp(value: Double): Double {
        return value.coerceIn(0.0, 1.0)
    }

    private companion object {
        const val PERCENT = 100.0
    }
}
