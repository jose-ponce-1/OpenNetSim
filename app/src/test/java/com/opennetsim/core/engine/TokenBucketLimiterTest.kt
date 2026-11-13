package com.opennetsim.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketLimiterTest {

    private var simulatedTimeNanos: Long = 1_000_000_000L

    private fun createLimiter(
        bytesPerSec: Long = 1000L,
        burstCapacity: Long = 1000L,
        minBurstCapacity: Long = 0L
    ): TokenBucketLimiter {
        return TokenBucketLimiter(
            bytesPerSecond = bytesPerSec,
            burstCapacityBytes = burstCapacity,
            timeSourceNanos = { simulatedTimeNanos },
            minBurstCapacityBytes = minBurstCapacity
        )
    }

    @Test
    fun testInitialCapacityMatchesBurst() {
        val limiter = createLimiter(bytesPerSec = 500, burstCapacity = 1000)
        assertEquals(1000.0, limiter.currentTokens, 0.001)
    }

    @Test
    fun testMtuSafeBurstCapacityFloorAtLowBitrates() {
        // En configuración por defecto (ej. bitrate bajo de 20 kbps = 2500 B/s con ráfaga requerida menor a MTU)
        val lowRateLimiter = TokenBucketLimiter(
            bytesPerSecond = 2500L,
            burstCapacityBytes = 500L,
            timeSourceNanos = { simulatedTimeNanos }
        )
        // La capacidad de ráfaga debe elevarse al suelo seguro de MTU (1500 bytes)
        assertEquals(TokenBucketLimiter.MIN_BURST_CAPACITY_BYTES, lowRateLimiter.burstCapacityBytes)
        assertEquals(1500.0, lowRateLimiter.currentTokens, 0.001)

        // Puede consumir una trama MTU completa de 1500 bytes de inmediato
        assertTrue(lowRateLimiter.tryConsume(1500))
        assertEquals(0.0, lowRateLimiter.currentTokens, 0.001)

        // Tras agotarse, calcula el tiempo de espera necesario para recargar otros 1500 bytes
        // 1500 bytes / 2500 B/s = 0.6s = 600 ms
        val waitMs = lowRateLimiter.calculateWaitTimeMs(1500)
        assertEquals(600L, waitMs)
    }

    @Test
    fun testTryConsumeSuccessAndDepletion() {
        val limiter = createLimiter(bytesPerSec = 1000, burstCapacity = 1000)

        // Consumir 600 bytes
        assertTrue(limiter.tryConsume(600))
        assertEquals(400.0, limiter.currentTokens, 0.001)

        // Intentar consumir 500 bytes (supera los 400 restantes)
        assertFalse(limiter.tryConsume(500))
        assertEquals(400.0, limiter.currentTokens, 0.001)

        // Consumir 400 bytes restantes
        assertTrue(limiter.tryConsume(400))
        assertEquals(0.0, limiter.currentTokens, 0.001)

        // Consumir con bucket vacío
        assertFalse(limiter.tryConsume(1))
    }

    @Test
    fun testTokenRefillOverTime() {
        val limiter = createLimiter(bytesPerSec = 1000, burstCapacity = 1000)

        // Vaciar completamente
        assertTrue(limiter.tryConsume(1000))
        assertEquals(0.0, limiter.currentTokens, 0.001)

        // Avanzar el reloj 500 ms (0.5 segundos -> debe generar 500 tokens)
        simulatedTimeNanos += 500_000_000L
        assertEquals(500.0, limiter.currentTokens, 0.001)

        // Consumir 300
        assertTrue(limiter.tryConsume(300))
        assertEquals(200.0, limiter.currentTokens, 0.001)

        // Avanzar 2 segundos (2000 tokens potenciales, pero tope es burstCapacity = 1000)
        simulatedTimeNanos += 2_000_000_000L
        assertEquals(1000.0, limiter.currentTokens, 0.001)
    }

    @Test
    fun testWaitTimeCalculation() {
        val limiter = createLimiter(bytesPerSec = 1000, burstCapacity = 1000)

        // Vaciar el bucket
        assertTrue(limiter.tryConsume(1000))

        // Para transmitir 500 bytes a 1000 bytes/s, se requieren 0.5 segundos = 500 ms = 500_000_000 ns
        val waitNanos = limiter.calculateWaitTimeNanos(500)
        assertEquals(500_000_000L, waitNanos)

        val waitMs = limiter.calculateWaitTimeMs(500)
        assertEquals(500L, waitMs)

        // Si ya hay tokens suficientes, el tiempo de espera debe ser 0
        simulatedTimeNanos += 600_000_000L // 600 tokens disponibles
        assertEquals(0L, limiter.calculateWaitTimeNanos(500))
        assertEquals(0L, limiter.calculateWaitTimeMs(500))
    }

    @Test
    fun testOversizedPacketIsAdmittedWithDeficitAndDelaysNextPacket() {
        // 128 kbps = 16_000 B/s; ráfaga por defecto = max(1500, 16_000 / 20) = 1500 B
        val limiter = TokenBucketLimiter(
            bytesPerSecond = 16_000L,
            timeSourceNanos = { simulatedTimeNanos }
        )
        assertEquals(1500L, limiter.burstCapacityBytes)
        assertTrue(limiter.tryConsume(1500)) // vaciar el bucket

        // Un paquete de 3000 B (> ráfaga) solo necesita 1500 tokens para ser admitido: 93,75 ms
        assertFalse(limiter.tryConsume(3000))
        assertEquals(94L, limiter.calculateWaitTimeMs(3000))
        simulatedTimeNanos += 93_750_000L
        assertTrue("El paquete grande no debe bloquearse nunca", limiter.tryConsume(3000))
        assertEquals(-1500.0, limiter.currentTokens, 0.001)

        // El siguiente paquete paga la deuda: (1500 + 100) / 16_000 = 100 ms
        assertEquals(100L, limiter.calculateWaitTimeMs(100))

        // Paquete grande + siguiente ≈ 190 ms de serialización: (3000 + 100) / 16_000 s
        val totalNanos = 93_750_000L + limiter.calculateWaitTimeNanos(100)
        assertEquals(193_750_000.0, totalNanos.toDouble(), 1_000_000.0)
    }

    @Test
    fun testMeanRateMatchesConfiguredRateWithOversizedPackets() {
        val bytesPerSec = 16_000L
        val limiter = TokenBucketLimiter(
            bytesPerSecond = bytesPerSec,
            timeSourceNanos = { simulatedTimeNanos }
        )
        assertTrue(limiter.tryConsume(1500)) // partir del bucket vacío
        val startNanos = simulatedTimeNanos

        var sentBytes = 0L
        repeat(100) { index ->
            val size = if (index % 10 == 0) 3000 else 1000
            simulatedTimeNanos += limiter.calculateWaitTimeNanos(size)
            assertTrue(limiter.tryConsume(size))
            sentBytes += size
        }
        // El último paquete deja deuda: se cuenta el tiempo hasta pagarla.
        simulatedTimeNanos += limiter.calculateWaitTimeNanos(1)

        val elapsedSec = (simulatedTimeNanos - startNanos) / 1_000_000_000.0
        val measuredRate = sentBytes / elapsedSec
        assertEquals(bytesPerSec.toDouble(), measuredRate, bytesPerSec * 0.02)
    }

    @Test
    fun testDynamicRateUpdate() {
        val limiter = createLimiter(bytesPerSec = 1000, burstCapacity = 1000)

        // Actualizar a 2000 bytes/s y burst 2000
        limiter.updateRate(newBytesPerSecond = 2000, newBurstCapacityBytes = 2000)
        assertEquals(2000L, limiter.bytesPerSecond)
        assertEquals(2000L, limiter.burstCapacityBytes)

        // Vaciar
        assertTrue(limiter.tryConsume(1000))
        simulatedTimeNanos += 1_000_000_000L // 1 segundo a 2000 B/s = 2000 tokens añadidos
        assertEquals(2000.0, limiter.currentTokens, 0.001)
    }
}

