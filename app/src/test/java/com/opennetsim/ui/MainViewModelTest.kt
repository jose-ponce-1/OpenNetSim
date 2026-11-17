package com.opennetsim.ui

import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TunnelState
import com.opennetsim.tunnel.TunnelController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlador falso: el test publica el estado y observa las órdenes enviadas. */
private class FakeTunnelController(
    initialState: TunnelState = TunnelState.Stopped,
    private val persisted: ThrottleConfig? = null
) : TunnelController {
    override val state = MutableStateFlow(initialState)
    override val telemetry = MutableStateFlow(NetworkTelemetry())
    val started = mutableListOf<ThrottleConfig>()
    val updates = mutableListOf<ThrottleConfig>()
    var stops = 0

    override fun lastAppliedConfig(): ThrottleConfig? = persisted

    override fun start(config: ThrottleConfig) {
        started += config
    }

    override fun stop() {
        stops++
    }

    override fun updateConfig(config: ThrottleConfig) {
        updates += config
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val customConfig = ThrottleConfig(
        downKbps = 2000,
        upKbps = 400,
        latencyMs = 300,
        jitterMs = 10,
        upLossPercent = 1f,
        downLossPercent = 1f,
        preset = ThrottlePreset.CUSTOM
    )

    /** ViewModel cuyo ámbito usa el reloj virtual del test: el debounce no espera tiempo real. */
    private fun TestScope.viewModel(tunnel: FakeTunnelController): MainViewModel =
        MainViewModel(tunnel, scopeOverride = backgroundScope)
            .also { runCurrent() }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    @Test
    fun failedTunnelTurnsSwitchOffAndShowsReason() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = TunnelState.Starting
        assertTrue(vm.uiState.value.isStarting)

        tunnel.state.value = TunnelState.Running(vm.uiState.value.config, ipv6Routed = false)
        assertTrue(vm.uiState.value.isActive)

        tunnel.state.value = TunnelState.Failed("otra VPN lo bloquea")
        val state = vm.uiState.value
        assertFalse("El interruptor vuelve a apagado", state.isActive)
        assertFalse(state.isStarting)
        assertTrue(state.userMessage!!.contains("otra VPN lo bloquea"))
    }

    @Test
    fun establishFailureNeverShowsTunnelAsActive() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = TunnelState.Starting
        tunnel.state.value = TunnelState.Failed("El sistema rechazó el túnel")
        assertFalse(vm.uiState.value.isActive)
    }

    @Test
    fun runningStateExposesIpv6Routing() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = TunnelState.Running(ThrottleConfig(), ipv6Routed = true)
        assertTrue(vm.uiState.value.ipv6Routed)
    }

    @Test
    fun continuousSliderDragIsDebouncedAndEndsWithFinalValue() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = TunnelState.Running(vm.uiState.value.config, ipv6Routed = false)

        // 2 s de arrastre continuo: un cambio cada 20 ms
        repeat(100) { step ->
            vm.editConfig { it.copy(latencyMs = 100 + step) }
            advanceTimeBy(20L)
        }
        advanceTimeBy(MainViewModel.CONFIG_DEBOUNCE_MS * 3)

        assertTrue("Actualizaciones: ${tunnel.updates.size}", tunnel.updates.size in 1..10)
        assertEquals("La última es el valor final del slider", 199, tunnel.updates.last().latencyMs)
    }

    @Test
    fun configMatchingActiveTunnelIsNotResent() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = TunnelState.Running(vm.uiState.value.config, ipv6Routed = false)
        advanceTimeBy(MainViewModel.CONFIG_DEBOUNCE_MS * 3)

        assertTrue("Una recreación de la actividad no reenvía la configuración activa", tunnel.updates.isEmpty())
    }

    @Test
    fun reopeningAppWithCustomTunnelAdoptsServiceConfigAndSendsNothing() = runVmTest {
        // El túnel sigue activo con un perfil personalizado y la actividad se recrea desde cero.
        val tunnel = FakeTunnelController(initialState = TunnelState.Running(customConfig, ipv6Routed = false))
        val vm = viewModel(tunnel)
        advanceTimeBy(MainViewModel.CONFIG_DEBOUNCE_MS * 3)

        assertEquals("Los controles muestran la configuración del túnel", customConfig, vm.uiState.value.config)
        assertEquals(ThrottlePreset.CUSTOM, vm.uiState.value.selectedPreset)
        assertTrue("El servicio no recibe ninguna actualización", tunnel.updates.isEmpty())
    }

    @Test
    fun lateServiceUpdatesDoNotOverwriteSliderBeingDragged() = runVmTest {
        val tunnel = FakeTunnelController(initialState = TunnelState.Running(customConfig, ipv6Routed = false))
        val vm = viewModel(tunnel)

        vm.editConfig { it.copy(latencyMs = 350) }
        // El servicio confirma un valor intermedio mientras el usuario sigue arrastrando.
        tunnel.state.value = TunnelState.Running(customConfig.copy(latencyMs = 320), ipv6Routed = false)
        assertEquals(350, vm.uiState.value.config.latencyMs)

        advanceTimeBy(MainViewModel.CONFIG_DEBOUNCE_MS * 3)
        assertEquals(350, tunnel.updates.last().latencyMs)
    }

    @Test
    fun stoppedTunnelShowsLastPersistedConfig() = runVmTest {
        val vm = viewModel(FakeTunnelController(persisted = customConfig))
        assertEquals(customConfig, vm.uiState.value.config)
        assertEquals(ThrottlePreset.CUSTOM, vm.uiState.value.selectedPreset)
    }

    @Test
    fun toggleRequestsPermissionThenStartsWithCurrentControls() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        vm.selectPreset(ThrottlePreset.EDGE_2G)

        vm.onToggleClicked()
        assertTrue(vm.uiState.value.requestVpnPermission)

        vm.onVpnPermissionResult(isGranted = true)
        assertFalse(vm.uiState.value.requestVpnPermission)
        assertEquals(listOf(ThrottlePreset.fromPreset(ThrottlePreset.EDGE_2G)), tunnel.started)
        assertTrue("Sin túnel activo no se envían actualizaciones", tunnel.updates.isEmpty())
    }

    @Test
    fun deniedPermissionDoesNotStartTunnel() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        vm.onToggleClicked()
        vm.onVpnPermissionResult(isGranted = false)

        assertTrue(tunnel.started.isEmpty())
        assertTrue(vm.uiState.value.userMessage!!.contains("permiso"))
    }

    @Test
    fun toggleWhileActiveStopsTunnel() = runVmTest {
        val tunnel = FakeTunnelController(initialState = TunnelState.Running(customConfig, ipv6Routed = false))
        val vm = viewModel(tunnel)
        vm.onToggleClicked()

        assertEquals(1, tunnel.stops)
        assertNull(tunnel.started.firstOrNull())
    }
}
