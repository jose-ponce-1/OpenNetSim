package com.opennetsim.core.engine

import com.opennetsim.model.ThrottleConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottlingEngineTest {

    private var simulatedTimeMs: Long = 10_000L

    private fun createEngine(
        bytesPerSec: Long = 10_000L,
        baseLatencyMs: Long = 0L,
        jitterMs: Long = 0L,
        lossProbability: Double = 0.0
    ): ThrottlingEngine {
        val limiter = TokenBucketLimiter(
            bytesPerSecond = bytesPerSec,
            burstCapacityBytes = bytesPerSec,
            timeSourceNanos = { simulatedTimeMs * 1_000_000L }
        )
        val queue = LatencyQueue(
            baseLatencyMs = baseLatencyMs,
            jitterMs = jitterMs,
            timeSourceMs = { simulatedTimeMs },
            jitterRandom = { _, _ -> 0L }
        )
        val lossSim = PacketLossSimulator(initialLossProbability = lossProbability)

        return ThrottlingEngine(
            bandwidthLimiter = limiter,
            latencyQueue = queue,
            packetLossSimulator = lossSim,
            timeSourceMs = { simulatedTimeMs }
        )
    }

    @Test
    fun testOversizedPacketDoesNotBlockQueueAt128Kbps() {
        // 128 kbps = 16_000 B/s con la ráfaga por defecto (1500 B, inferior al paquete grande)
        val engine = ThrottlingEngine(
            bandwidthLimiter = TokenBucketLimiter(
                bytesPerSecond = 16_000L,
                timeSourceNanos = { simulatedTimeMs * 1_000_000L }
            ),
            latencyQueue = LatencyQueue(timeSourceMs = { simulatedTimeMs }),
            packetLossSimulator = PacketLossSimulator(),
            timeSourceMs = { simulatedTimeMs }
        )
        val startMs = simulatedTimeMs

        // Vaciar la ráfaga inicial
        assertTrue(engine.processIncoming(ByteArray(1500), simulatedTimeMs) is PacketResult.Released)

        val big = ByteArray(3000) { 9 }
        val followers = List(3) { index -> ByteArray(500) { index.toByte() } }
        assertTrue(engine.processIncoming(big, simulatedTimeMs) is PacketResult.Enqueued)
        followers.forEach { assertTrue(engine.processIncoming(it, simulatedTimeMs) is PacketResult.Enqueued) }

        val drained = mutableListOf<ByteArray>()
        var iterations = 0
        while (drained.size < 4 && iterations < 100) {
            iterations++
            drained += engine.drainReady(simulatedTimeMs)
            val next = engine.timeUntilNextEventMs(simulatedTimeMs) ?: break
            simulatedTimeMs += maxOf(1L, next)
        }

        assertEquals("Todos los paquetes deben salir", 4, drained.size)
        assertArrayEquals(big, drained[0])
        followers.forEachIndexed { index, packet -> assertArrayEquals(packet, drained[index + 1]) }

        // (3000 + 3 × 500) B a 16_000 B/s ≈ 281 ms tras vaciar la ráfaga inicial
        val elapsedMs = simulatedTimeMs - startMs
        assertTrue("Tiempo de salida ($elapsedMs ms) proporcional al tamaño", elapsedMs in 250L..320L)
    }

    @Test
    fun testImmediateReleaseWhenNoDegradation() {
        val engine = createEngine(bytesPerSec = 50_000L, baseLatencyMs = 0L, lossProbability = 0.0)
        val packet = byteArrayOf(1, 2, 3, 4, 5)

        val result = engine.processIncoming(packet, currentTimeMs = simulatedTimeMs)

        assertTrue(result is PacketResult.Released)
        assertArrayEquals(packet, (result as PacketResult.Released).packet)

        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalReceivedPackets)
        assertEquals(1L, metrics.totalReleasedPackets)
        assertEquals(0L, metrics.totalDroppedPackets)
        assertEquals(5L, metrics.totalReceivedBytes)
        assertEquals(5L, metrics.totalReleasedBytes)
    }

    @Test
    fun testPacketDropWhenLossProbabilityIsOne() {
        val engine = createEngine(lossProbability = 1.0)
        val packet = byteArrayOf(9, 9, 9)

        val result = engine.processIncoming(packet, currentTimeMs = simulatedTimeMs)

        assertTrue(result is PacketResult.Dropped)
        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalReceivedPackets)
        assertEquals(0L, metrics.totalReleasedPackets)
        assertEquals(1L, metrics.totalDroppedPackets)
        assertEquals(3L, metrics.totalDroppedBytes)
    }

    @Test
    fun testLatencyEnqueuingAndDrain() {
        val engine = createEngine(
            bytesPerSec = 100_000L,
            baseLatencyMs = 150L,
            jitterMs = 0L,
            lossProbability = 0.0
        )
        val packet = byteArrayOf(10, 20, 30)

        // Encolar a T=10_000 con latencia 150 ms -> scheduled = 10_150
        val result = engine.processIncoming(packet, currentTimeMs = simulatedTimeMs)
        assertTrue(result is PacketResult.Enqueued)
        val enqueued = result as PacketResult.Enqueued
        assertEquals(10_150L, enqueued.scheduledReleaseTimeMs)
        assertEquals(150L, enqueued.waitMs)

        // A T=10_100, no debe drenarse
        simulatedTimeMs = 10_100L
        val drainedEarly = engine.drainReady(currentTimeMs = simulatedTimeMs)
        assertTrue(drainedEarly.isEmpty())

        // A T=10_150, se drena correctamente
        simulatedTimeMs = 10_150L
        val drainedOnTime = engine.drainReady(currentTimeMs = simulatedTimeMs)
        assertEquals(1, drainedOnTime.size)
        assertArrayEquals(packet, drainedOnTime[0])

        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalReceivedPackets)
        assertEquals(1L, metrics.totalReleasedPackets)
    }

    @Test
    fun testUpdateConfigDynamically() {
        val engine = createEngine(bytesPerSec = 50_000L, baseLatencyMs = 0L)

        val newConfig = ThrottleConfig(
            downKbps = 384, // 384 kbps = 48_000 bytes/sec
            upKbps = 128,
            latencyMs = 250,
            jitterMs = 50,
            packetLossPercent = 10.0f
        )

        engine.updateConfig(newConfig)

        assertEquals(48_000L, engine.bandwidthLimiter.bytesPerSecond)
        assertEquals(250L, engine.latencyQueue.baseLatencyMs)
        assertEquals(50L, engine.latencyQueue.jitterMs)
        assertEquals(0.10, engine.packetLossSimulator.lossProbability, 0.001)
    }

    @Test
    fun testStrictFifoOrderWhenRateThrottledWithoutLatency() {
        // 10_000 bytes/sec = 10 bytes/ms
        val engine = createEngine(
            bytesPerSec = 10_000L,
            baseLatencyMs = 0L,
            jitterMs = 0L,
            lossProbability = 0.0
        )

        val p1 = byteArrayOf(1, 1)
        val p2 = byteArrayOf(2, 2)
        val p3 = byteArrayOf(3, 3)

        // Consumir el burst inicial (10_000 bytes)
        val burstEater = ByteArray(10_000)
        assertTrue(engine.processIncoming(burstEater, currentTimeMs = simulatedTimeMs) is PacketResult.Released)

        // Ahora el bucket está vacío. Llega p1 a T=10_000
        val res1 = engine.processIncoming(p1, currentTimeMs = simulatedTimeMs)
        assertTrue(res1 is PacketResult.Enqueued)
        val enq1 = res1 as PacketResult.Enqueued

        // Llega p2 a T=10_001
        val res2 = engine.processIncoming(p2, currentTimeMs = simulatedTimeMs + 1)
        assertTrue(res2 is PacketResult.Enqueued)
        val enq2 = res2 as PacketResult.Enqueued

        // Llega p3 a T=10_002
        val res3 = engine.processIncoming(p3, currentTimeMs = simulatedTimeMs + 2)
        assertTrue(res3 is PacketResult.Enqueued)
        val enq3 = res3 as PacketResult.Enqueued

        // Los tiempos deben ser estrictamente crecientes para preservar FIFO (sin inversión de prioridades)
        assertTrue("enq1 (${enq1.scheduledReleaseTimeMs}) debe ser <= enq2 (${enq2.scheduledReleaseTimeMs})",
            enq1.scheduledReleaseTimeMs <= enq2.scheduledReleaseTimeMs)
        assertTrue("enq2 (${enq2.scheduledReleaseTimeMs}) debe ser < enq3 (${enq3.scheduledReleaseTimeMs})",
            enq2.scheduledReleaseTimeMs < enq3.scheduledReleaseTimeMs)

        // Avanzar el tiempo más allá del último y drenar
        simulatedTimeMs = enq3.scheduledReleaseTimeMs + 100L
        val drained = engine.drainReady(currentTimeMs = simulatedTimeMs)

        assertEquals(3, drained.size)
        assertArrayEquals("p1 debe ser el primero", p1, drained[0])
        assertArrayEquals("p2 debe ser el segundo", p2, drained[1])
        assertArrayEquals("p3 debe ser el tercero", p3, drained[2])
    }

    @Test
    fun testTailDropWhenBulkLaneReachesByteCapacity() {
        val limiter = TokenBucketLimiter(
            bytesPerSecond = 10_000L,
            burstCapacityBytes = 1500L,
            timeSourceNanos = { simulatedTimeMs * 1_000_000L }
        )
        val queue = LatencyQueue(baseLatencyMs = 200L, timeSourceMs = { simulatedTimeMs })
        val engine = ThrottlingEngine(
            bandwidthLimiter = limiter,
            latencyQueue = queue,
            packetLossSimulator = PacketLossSimulator(0.0),
            timeSourceMs = { simulatedTimeMs }
        )
        // max(2 × 1500, 10_000 B/s × (500 + 200) ms) = 7_000 B
        assertEquals(7_000L, engine.bulkCapacityBytes)

        repeat(4) { assertTrue(engine.processIncoming(ByteArray(1500), simulatedTimeMs) is PacketResult.Enqueued) }
        assertEquals(6_000L, engine.queuedBulkBytes)

        val dropResult = engine.processIncoming(ByteArray(1500), simulatedTimeMs)
        assertTrue(dropResult is PacketResult.Dropped)

        val metrics = engine.getMetrics()
        assertEquals(1L, metrics.totalDroppedPackets)
        assertEquals(1500L, metrics.totalDroppedBytes)
        assertEquals(4, engine.queuedPackets)
    }

    // ------------------------------------------------------------------ Carriles y orden por flujo

    private fun engineAt(bytesPerSec: Long, latencyMs: Long, lossProbability: Double = 0.0): ThrottlingEngine =
        ThrottlingEngine(
            bandwidthLimiter = TokenBucketLimiter(
                bytesPerSecond = bytesPerSec,
                timeSourceNanos = { simulatedTimeMs * 1_000_000L }
            ),
            latencyQueue = LatencyQueue(baseLatencyMs = latencyMs, timeSourceMs = { simulatedTimeMs }),
            packetLossSimulator = PacketLossSimulator(lossProbability),
            timeSourceMs = { simulatedTimeMs }
        )

    /** Drena simulando el reloj hasta vaciar ambos carriles; devuelve (paquete, instante de salida). */
    private fun drainAll(engine: ThrottlingEngine, maxIterations: Int = 10_000): List<Pair<ByteArray, Long>> {
        val out = mutableListOf<Pair<ByteArray, Long>>()
        var iterations = 0
        while (iterations++ < maxIterations) {
            engine.drainReady(simulatedTimeMs).forEach { out += it to simulatedTimeMs }
            val next = engine.timeUntilNextEventMs(simulatedTimeMs) ?: break
            simulatedTimeMs += maxOf(1L, next)
        }
        return out
    }

    @Test
    fun testFinStaysBehindRateLimitedDataOfItsFlow() {
        val engine = engineAt(bytesPerSec = 16_000L, latencyMs = 0L)
        val flow = 42L
        val data = List(5) { index -> ByteArray(1400) { index.toByte() } }
        val fin = ByteArray(40) { 0x7F }

        // Como en el servicio: todo se encola y sale por el drenaje (sin liberación inmediata).
        data.forEach {
            engine.processIncoming(it, simulatedTimeMs, TrafficClass.BULK, flow, allowImmediateRelease = false)
        }
        val finResult =
            engine.processIncoming(fin, simulatedTimeMs, TrafficClass.INTERACTIVE, flow, allowImmediateRelease = false)
        assertTrue("El FIN espera detrás de los datos de su flujo", finResult is PacketResult.Enqueued)

        val released = drainAll(engine).map { it.first }
        assertEquals(6, released.size)
        data.forEachIndexed { index, packet -> assertArrayEquals(packet, released[index]) }
        assertArrayEquals("El FIN sale después del último segmento", fin, released.last())
    }

    @Test
    fun testAckOfAnotherFlowOvertakesQueuedBulkData() {
        val engine = engineAt(bytesPerSec = 16_000L, latencyMs = 50L)
        val bulkFlow = 1L
        val ackFlow = 2L
        repeat(10) { engine.processIncoming(ByteArray(1400), simulatedTimeMs, TrafficClass.BULK, bulkFlow) }
        val ack = ByteArray(40) { 0x11 }
        val ackEnqueuedAt = simulatedTimeMs
        engine.processIncoming(ack, simulatedTimeMs, TrafficClass.INTERACTIVE, ackFlow)

        val released = drainAll(engine)
        val ackIndex = released.indexOfFirst { it.first.contentEquals(ack) }
        assertTrue("El ACK de otro flujo adelanta a los datos encolados (índice $ackIndex)", ackIndex in 0 until 3)
        val ackDelay = released[ackIndex].second - ackEnqueuedAt
        assertTrue("Con la latencia aplicada ($ackDelay ms)", ackDelay >= 50L)
    }

    @Test
    fun testQueueingDelayIsBoundedAtLowRateWithParallelDownloads() {
        val latencyMs = 100L
        val engine = engineAt(bytesPerSec = 16_000L, latencyMs = latencyMs) // 128 kbps
        val maxDelayMs = ThrottlingEngine.DEFAULT_QUEUE_WINDOW_MS + latencyMs

        var accepted = 0
        var dropped = 0
        repeat(200) { index ->
            val flow = (index % 6).toLong() // Seis descargas en paralelo
            when (val result = engine.processIncoming(ByteArray(1400), simulatedTimeMs, TrafficClass.BULK, flow)) {
                is PacketResult.Enqueued -> {
                    accepted++
                    assertTrue("Retardo estimado ${result.waitMs} ms > $maxDelayMs ms", result.waitMs <= maxDelayMs)
                }
                is PacketResult.Dropped -> dropped++
                is PacketResult.Released -> accepted++
            }
        }
        assertTrue("La cola se llena y descarta", dropped > 0)

        // Los paquetes aceptados salen, como muy tarde, en la ventana más la latencia.
        val startMs = simulatedTimeMs
        val released = drainAll(engine)
        assertEquals(accepted, released.size)
        val lastDepartureDelay = released.last().second - startMs
        assertTrue("Último paquete a $lastDepartureDelay ms", lastDepartureDelay <= maxDelayMs + 10L)
    }

    @Test
    fun testDnsResponseIsNotQueuedBehindBulkTransfer() {
        val latencyMs = 100L
        val engine = engineAt(bytesPerSec = 16_000L, latencyMs = latencyMs)
        repeat(15) { engine.processIncoming(ByteArray(1400), simulatedTimeMs, TrafficClass.BULK, 7L) }

        val dns = ByteArray(120) { 0x35 }
        val dnsEnqueuedAt = simulatedTimeMs
        engine.processIncoming(dns, simulatedTimeMs, TrafficClass.INTERACTIVE, 99L)

        val released = drainAll(engine)
        val dnsDeparture = released.first { it.first.contentEquals(dns) }.second
        // Latencia + su propia serialización (y como mucho un MTU en curso), no 15 × 1400 B de cola.
        val bound = latencyMs + (2 * 1500L * 1000L) / 16_000L
        val dnsDelay = dnsDeparture - dnsEnqueuedAt
        assertTrue("DNS entregado a $dnsDelay ms (límite $bound)", dnsDelay <= bound)
    }

    @Test
    fun testLossAppliesEquallyToControlAndDataPackets() {
        val engine = engineAt(bytesPerSec = 100_000_000L, latencyMs = 0L, lossProbability = 0.05)
        val total = 20_000
        repeat(total) { index ->
            val trafficClass = if (index % 2 == 0) TrafficClass.INTERACTIVE else TrafficClass.BULK
            engine.processIncoming(ByteArray(40), simulatedTimeMs, trafficClass, (index % 10).toLong())
            if (index % 100 == 0) {
                simulatedTimeMs += 1L
                engine.drainReady(simulatedTimeMs)
            }
        }
        val dropRate = engine.getMetrics().totalDroppedPackets.toDouble() / total
        assertEquals("Tasa de descarte medida", 0.05, dropRate, 0.01)
    }
}

