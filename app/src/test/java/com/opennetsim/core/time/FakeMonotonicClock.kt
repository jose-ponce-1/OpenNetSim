package com.opennetsim.core.time

import java.util.concurrent.atomic.AtomicLong

/** Reloj monotónico simulado para tests: solo avanza cuando el test lo pide. */
class FakeMonotonicClock(startMs: Long = 1_000_000L) : MonotonicClock {
    private val nowMsValue = AtomicLong(startMs)

    override fun nowNanos(): Long = nowMsValue.get() * MonotonicClock.NANOS_PER_MS

    override fun nowMs(): Long = nowMsValue.get()

    fun advanceMs(deltaMs: Long) {
        nowMsValue.addAndGet(deltaMs)
    }
}
