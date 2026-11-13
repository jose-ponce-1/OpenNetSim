package com.opennetsim.ui

import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tunnelState = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    private val viewModel = MainViewModel(tunnelState, MutableStateFlow(NetworkTelemetry()), scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun failedTunnelTurnsSwitchOffAndShowsReason() {
        tunnelState.value = TunnelState.Starting
        assertTrue(viewModel.uiState.value.isStarting)

        tunnelState.value = TunnelState.Running(viewModel.uiState.value.config, ipv6Routed = false)
        assertTrue(viewModel.uiState.value.isActive)

        tunnelState.value = TunnelState.Failed("otra VPN lo bloquea")
        val state = viewModel.uiState.value
        assertFalse("El interruptor vuelve a apagado", state.isActive)
        assertFalse(state.isStarting)
        assertTrue(state.userMessage!!.contains("otra VPN lo bloquea"))
    }

    @Test
    fun establishFailureNeverShowsTunnelAsActive() {
        tunnelState.value = TunnelState.Starting
        tunnelState.value = TunnelState.Failed("El sistema rechazó el túnel")
        assertFalse(viewModel.uiState.value.isActive)
    }

    @Test
    fun runningStateExposesIpv6Routing() {
        tunnelState.value = TunnelState.Running(ThrottleConfig(), ipv6Routed = true)
        assertTrue(viewModel.uiState.value.ipv6Routed)
    }

    @Test
    fun continuousSliderDragIsDebouncedAndEndsWithFinalValue() = runBlocking {
        tunnelState.value = TunnelState.Running(viewModel.uiState.value.config, ipv6Routed = false)
        val emissions = mutableListOf<ThrottleConfig>()
        val collector = launch { viewModel.configToApply.collect { emissions += it } }

        // 2 s de arrastre continuo: un cambio cada 20 ms
        repeat(100) { step ->
            viewModel.updateLatency(100 + step)
            delay(20L)
        }
        delay(MainViewModel.CONFIG_DEBOUNCE_MS * 3)
        collector.cancel()

        assertTrue("Emisiones: ${emissions.size}", emissions.size in 1..10)
        assertEquals("La última es el valor final del slider", 199, emissions.last().latencyMs)
    }

    @Test
    fun configMatchingActiveTunnelIsNotResent() = runBlocking {
        tunnelState.value = TunnelState.Running(viewModel.uiState.value.config, ipv6Routed = false)
        val emissions = mutableListOf<ThrottleConfig>()
        val collector = launch { viewModel.configToApply.collect { emissions += it } }

        delay(MainViewModel.CONFIG_DEBOUNCE_MS * 3)
        collector.cancel()

        assertTrue("Una recreación de la actividad no reenvía la configuración activa", emissions.isEmpty())
    }
}
