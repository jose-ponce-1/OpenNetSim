package com.opennetsim.ui

import com.opennetsim.core.time.FakeMonotonicClock
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TunnelState
import com.opennetsim.profiles.InMemoryProfileStore
import com.opennetsim.profiles.SavedProfile
import com.opennetsim.tunnel.TunnelController
import com.opennetsim.ui.preferences.InMemoryUiPreferences
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

    /** Como el servicio: aplica la configuración al túnel activo y la publica. */
    override fun updateConfig(config: ThrottleConfig) {
        updates += config
        (state.value as? TunnelState.Running)?.let { state.value = it.copy(config = config) }
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
    private val slow3g = ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G)
    private val clock = FakeMonotonicClock()
    private val profileStore = InMemoryProfileStore()
    private val preferences = InMemoryUiPreferences(onboardingCompleted = true)
    private var nextId = 0

    /** ViewModel cuyo ámbito usa el reloj virtual del test: el debounce no espera tiempo real. */
    private fun TestScope.viewModel(tunnel: FakeTunnelController): MainViewModel =
        MainViewModel(
            tunnel,
            profileStore = profileStore,
            preferences = preferences,
            clock = clock,
            scopeOverride = backgroundScope,
            newProfileId = { "p${nextId++}" }
        ).also { runCurrent() }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun running(config: ThrottleConfig, startedAtMs: Long = clock.nowMs(), ipv6: Boolean = false) =
        TunnelState.Running(config, ipv6Routed = ipv6, startedAtMs = startedAtMs)

    /** Arranque completo pedido por el usuario: Starting y después Running. */
    private fun FakeTunnelController.startRunning(config: ThrottleConfig) {
        state.value = TunnelState.Starting
        state.value = running(config)
    }

    private fun TestScope.settle() = advanceTimeBy(MainViewModel.CONFIG_DEBOUNCE_MS * 3)

    // ------------------------------------------------------------------ Estado del túnel

    @Test
    fun failedTunnelTurnsSwitchOffAndShowsReason() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = TunnelState.Starting
        assertTrue(vm.uiState.value.isStarting)

        tunnel.state.value = running(vm.uiState.value.config)
        assertTrue(vm.uiState.value.isActive)

        tunnel.state.value = TunnelState.Failed("otra VPN lo bloquea")
        val state = vm.uiState.value
        assertFalse("El interruptor vuelve a apagado", state.isActive)
        assertFalse(state.isStarting)
        assertTrue(state.userMessage!!.text.contains("otra VPN lo bloquea"))
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
        tunnel.state.value = running(ThrottleConfig(), ipv6 = true)
        assertTrue(vm.uiState.value.ipv6Routed)
    }

    @Test
    fun startAndStopAreAnnouncedButReopeningIsNot() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.startRunning(slow3g)
        assertEquals("Túnel VPN establecido · «3G Lento»", vm.uiState.value.userMessage?.text)

        tunnel.state.value = TunnelState.Stopped
        assertEquals("Simulación detenida. Tráfico sin alterar.", vm.uiState.value.userMessage?.text)

        val reopened = viewModel(FakeTunnelController(initialState = running(slow3g)))
        assertNull("Reabrir la app con el túnel activo no avisa de nada", reopened.uiState.value.userMessage)
    }

    @Test
    fun continuousTypingIsDebouncedAndEndsWithFinalValue() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = running(vm.uiState.value.config)

        // 2 s escribiendo: un cambio cada 20 ms
        repeat(100) { step ->
            vm.onParameterTextChange(ParameterField.LATENCY_MS, (100 + step).toString())
            advanceTimeBy(20L)
        }
        settle()

        assertTrue("Actualizaciones: ${tunnel.updates.size}", tunnel.updates.size in 1..10)
        assertEquals("La última es el valor final escrito", 199, tunnel.updates.last().latencyMs)
    }

    @Test
    fun configMatchingActiveTunnelIsNotResent() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = running(vm.uiState.value.config)
        settle()

        assertTrue("Una recreación de la actividad no reenvía la configuración activa", tunnel.updates.isEmpty())
    }

    @Test
    fun reopeningAppWithCustomTunnelAdoptsServiceConfigAndSendsNothing() = runVmTest {
        // El túnel sigue activo con un perfil personalizado y la actividad se recrea desde cero.
        val tunnel = FakeTunnelController(initialState = running(customConfig))
        val vm = viewModel(tunnel)
        settle()

        assertEquals("Los controles muestran la configuración del túnel", customConfig, vm.uiState.value.config)
        assertEquals("2000", vm.uiState.value.drafts[ParameterField.DOWN_KBPS])
        assertEquals(ProfileRef.CUSTOM, vm.uiState.value.selectedProfile)
        assertTrue("El servicio no recibe ninguna actualización", tunnel.updates.isEmpty())
    }

    @Test
    fun lateServiceUpdatesDoNotOverwriteValueBeingTyped() = runVmTest {
        val tunnel = FakeTunnelController(initialState = running(customConfig))
        val vm = viewModel(tunnel)

        vm.onParameterTextChange(ParameterField.LATENCY_MS, "350")
        // El servicio confirma un valor intermedio mientras el usuario sigue escribiendo.
        tunnel.state.value = running(customConfig.copy(latencyMs = 320))
        assertEquals(350, vm.uiState.value.config.latencyMs)
        assertEquals("350", vm.uiState.value.drafts[ParameterField.LATENCY_MS])

        settle()
        assertEquals(350, tunnel.updates.last().latencyMs)
    }

    @Test
    fun stoppedTunnelShowsLastPersistedConfig() = runVmTest {
        val vm = viewModel(FakeTunnelController(persisted = customConfig))
        assertEquals(customConfig, vm.uiState.value.config)
        assertEquals(ProfileRef.CUSTOM, vm.uiState.value.selectedProfile)
    }

    @Test
    fun toggleRequestsPermissionThenStartsWithCurrentConfig() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        vm.selectProfile(ProfileRef.Preset(ThrottlePreset.EDGE_2G))

        vm.onToggleClicked()
        assertTrue(vm.uiState.value.requestVpnPermission)

        vm.onVpnPermissionResult(isGranted = true)
        assertFalse(vm.uiState.value.requestVpnPermission)
        assertEquals(listOf(ThrottlePreset.fromPreset(ThrottlePreset.EDGE_2G)), tunnel.started)
        settle()
        assertTrue("Sin túnel activo no se envían actualizaciones", tunnel.updates.isEmpty())
    }

    @Test
    fun deniedPermissionDoesNotStartTunnel() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        vm.onToggleClicked()
        vm.onVpnPermissionResult(isGranted = false)

        assertTrue(tunnel.started.isEmpty())
        assertTrue(vm.uiState.value.userMessage!!.text.contains("permiso"))
    }

    @Test
    fun toggleWhileActiveStopsTunnel() = runVmTest {
        val tunnel = FakeTunnelController(initialState = running(customConfig))
        val vm = viewModel(tunnel)
        vm.onToggleClicked()

        assertEquals(1, tunnel.stops)
        assertNull(tunnel.started.firstOrNull())
    }

    // ------------------------------------------------------------------ Tiempo activo y gráfica

    @Test
    fun activeTimeComesFromServiceStartAndSurvivesReopening() = runVmTest {
        // El túnel arrancó hace 5 minutos y la actividad se recrea.
        val startedAt = clock.nowMs()
        clock.advanceMs(300_000)
        val tunnel = FakeTunnelController(initialState = running(slow3g, startedAtMs = startedAt))
        val vm = viewModel(tunnel)
        assertEquals(300L, vm.uiState.value.activeSeconds)

        clock.advanceMs(1_000)
        advanceTimeBy(MainViewModel.TICK_MS + 1)
        assertEquals("Sigue contando", 301L, vm.uiState.value.activeSeconds)

        // Otro perfil aplicado por el servicio conserva el instante de arranque.
        tunnel.state.value = running(customConfig, startedAtMs = startedAt)
        clock.advanceMs(1_000)
        advanceTimeBy(MainViewModel.TICK_MS)
        assertEquals(302L, vm.uiState.value.activeSeconds)

        tunnel.state.value = TunnelState.Stopped
        assertEquals(0L, vm.uiState.value.activeSeconds)
    }

    @Test
    fun historySamplesOncePerSecondAndRestartsEmpty() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.startRunning(slow3g)
        // Sin tráfico el StateFlow de telemetría no emite valores nuevos, pero la gráfica avanza igual.
        tunnel.telemetry.value = NetworkTelemetry(isConnected = true, downBytesPerSecond = 40_000)
        advanceTimeBy(MainViewModel.TICK_MS * 90 + 1)

        val history = vm.uiState.value.history
        assertEquals(ThroughputHistory.CAPACITY, history.size)
        assertTrue(history.all { it.downBytesPerSecond == 40_000L })

        tunnel.state.value = TunnelState.Stopped
        advanceTimeBy(MainViewModel.TICK_MS * 5)
        assertEquals("Detenido no se muestrea", ThroughputHistory.CAPACITY, vm.uiState.value.history.size)

        tunnel.startRunning(slow3g)
        assertTrue("Un arranque nuevo empieza vacío", vm.uiState.value.history.isEmpty())
    }

    // ------------------------------------------------------------------ Perfiles

    @Test
    fun pickingAProfileWhileActiveAppliesItWithoutRestart() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.startRunning(slow3g)

        vm.selectProfile(ProfileRef.Preset(ThrottlePreset.HIGH_LATENCY))
        settle()

        assertEquals(ThrottlePreset.fromPreset(ThrottlePreset.HIGH_LATENCY), tunnel.updates.single())
        assertEquals(0, tunnel.stops)
        assertEquals("Perfil «Alta Latencia» aplicado sin reiniciar el túnel", vm.uiState.value.userMessage?.text)
    }

    @Test
    fun customKeepsCurrentValues() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.selectProfile(ProfileRef.CUSTOM)
        val state = vm.uiState.value
        assertEquals(slow3g.copy(preset = ThrottlePreset.CUSTOM), state.config)
        assertEquals(ProfileRef.CUSTOM, state.selectedProfile)
        assertTrue(state.changedFields.isEmpty())
    }

    @Test
    fun editedValuesThatMatchAgainSelectThePreset() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "250")
        assertEquals(ProfileRef.CUSTOM, vm.uiState.value.selectedProfile)
        assertEquals(ThrottlePreset.CUSTOM, vm.uiState.value.config.preset)

        vm.onParameterTextChange(ParameterField.LATENCY_MS, "200")
        assertEquals(ProfileRef.Preset(ThrottlePreset.SLOW_3G), vm.uiState.value.selectedProfile)
        assertEquals(ThrottlePreset.SLOW_3G, vm.uiState.value.config.preset)
    }

    @Test
    fun savingAProfilePutsItFirstSelectsItAndPersistsIt() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "350")

        assertTrue(vm.saveCurrentAsProfile("  Metro en hora punta "))
        val state = vm.uiState.value
        val saved = state.savedProfiles.single()
        assertEquals("Metro en hora punta", saved.name)
        assertEquals(350, saved.config.latencyMs)
        assertEquals(ProfileRef.Saved(saved.id), state.selectedProfile)
        assertEquals("Metro en hora punta", state.profiles.first().name)
        assertEquals(listOf(saved), profileStore.load())
        assertTrue("Tras guardar no hay cambios respecto al perfil base", state.changedFields.isEmpty())

        // Sobrevive a cerrar y abrir la app.
        val reopened = viewModel(FakeTunnelController(persisted = saved.config))
        assertEquals(ProfileRef.Saved(saved.id), reopened.uiState.value.selectedProfile)
    }

    @Test
    fun duplicateNameIsRejected() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        assertFalse(vm.saveCurrentAsProfile("3g lento"))
        assertTrue(vm.uiState.value.savedProfiles.isEmpty())
    }

    @Test
    fun undoingASaveRestoresThePreviousSelection() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.saveCurrentAsProfile("Metro")
        val message = vm.uiState.value.userMessage!!
        assertEquals("Deshacer", message.actionLabel)

        vm.onMessageAction(message.id)
        assertTrue(vm.uiState.value.savedProfiles.isEmpty())
        assertTrue(profileStore.load().isEmpty())
        assertEquals(ProfileRef.Preset(ThrottlePreset.SLOW_3G), vm.uiState.value.selectedProfile)
    }

    @Test
    fun deletingTheSelectedProfileNeverChangesTheNetwork() = runVmTest {
        val metro = SavedProfile("m", "Metro", customConfig)
        profileStore.save(listOf(metro))
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.startRunning(customConfig)
        assertEquals(ProfileRef.Saved("m"), vm.uiState.value.selectedProfile)

        vm.deleteProfile("m")
        settle()
        assertTrue("Las condiciones de red no cambian", tunnel.updates.isEmpty())
        assertEquals(customConfig, vm.uiState.value.config)
        assertEquals(ProfileRef.CUSTOM, vm.uiState.value.selectedProfile)
        assertTrue(profileStore.load().isEmpty())

        vm.onMessageAction(vm.uiState.value.userMessage!!.id)
        assertEquals(listOf(metro), vm.uiState.value.savedProfiles)
        assertEquals(ProfileRef.Saved("m"), vm.uiState.value.selectedProfile)
        assertEquals(listOf(metro), profileStore.load())
    }

    @Test
    fun undoingADeleteRestoresThePosition() = runVmTest {
        val profiles = listOf(
            SavedProfile("a", "A", customConfig.copy(latencyMs = 1)),
            SavedProfile("b", "B", customConfig.copy(latencyMs = 2)),
            SavedProfile("c", "C", customConfig.copy(latencyMs = 3))
        )
        profileStore.save(profiles)
        val vm = viewModel(FakeTunnelController())
        vm.deleteProfile("b")
        assertEquals(listOf("a", "c"), vm.uiState.value.savedProfiles.map { it.id })

        vm.onMessageAction(vm.uiState.value.userMessage!!.id)
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.savedProfiles.map { it.id })
        vm.deleteProfile("zzz")
        assertEquals(3, vm.uiState.value.savedProfiles.size)
    }

    @Test
    fun actionOfAReplacedMessageDoesNothing() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.saveCurrentAsProfile("Metro")
        val saveMessage = vm.uiState.value.userMessage!!
        vm.onToggleClicked()
        vm.onVpnPermissionResult(isGranted = false)

        vm.onMessageAction(saveMessage.id)
        assertEquals("El perfil sigue guardado", 1, vm.uiState.value.savedProfiles.size)

        val current = vm.uiState.value.userMessage!!
        vm.clearMessage(saveMessage.id)
        assertEquals("Solo se borra el aviso indicado", current, vm.uiState.value.userMessage)
        vm.clearMessage(current.id)
        assertNull(vm.uiState.value.userMessage)
    }

    // ------------------------------------------------------------------ Parámetros

    @Test
    fun invalidTextKeepsTheLastValidValue() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = running(slow3g)

        vm.onParameterTextChange(ParameterField.LATENCY_MS, "5000")
        vm.onParameterTextChange(ParameterField.UP_KBPS, "")
        settle()

        val state = vm.uiState.value
        assertEquals(200, state.config.latencyMs)
        assertEquals(128, state.config.upKbps)
        assertEquals("5000", state.drafts[ParameterField.LATENCY_MS])
        assertTrue("Un valor no válido nunca llega al túnel", tunnel.updates.isEmpty())

        vm.onParameterFocusLost(ParameterField.LATENCY_MS)
        assertEquals(
            "El texto no válido se conserva al salir",
            "5000",
            vm.uiState.value.drafts[ParameterField.LATENCY_MS]
        )
    }

    @Test
    fun clearingAFieldAppliesZeroAndShowsItWhenLeaving() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = running(slow3g)

        vm.onParameterTextChange(ParameterField.LATENCY_MS, "")
        settle()

        assertEquals(0, vm.uiState.value.config.latencyMs)
        assertEquals(0, tunnel.updates.last().latencyMs)
        assertEquals("Mientras se edita, el campo sigue vacío", "", vm.uiState.value.drafts[ParameterField.LATENCY_MS])

        vm.onParameterFocusLost(ParameterField.LATENCY_MS)
        assertEquals("0", vm.uiState.value.drafts[ParameterField.LATENCY_MS])
    }

    @Test
    fun clearingAFieldWhereZeroIsOutOfRangeKeepsTheLastValidValue() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = running(slow3g)

        vm.onParameterTextChange(ParameterField.UP_KBPS, "")
        settle()
        vm.onParameterFocusLost(ParameterField.UP_KBPS)

        assertEquals(128, vm.uiState.value.config.upKbps)
        assertTrue("Un valor no válido nunca llega al túnel", tunnel.updates.isEmpty())
        assertEquals(
            "Al salir, el campo muestra 0 con su error de rango",
            "0",
            vm.uiState.value.drafts[ParameterField.UP_KBPS]
        )
    }

    @Test
    fun validTextIsReformattedWhenLeavingTheField() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "0200")
        assertEquals("0200", vm.uiState.value.drafts[ParameterField.LATENCY_MS])
        vm.onParameterFocusLost(ParameterField.LATENCY_MS)
        assertEquals("200", vm.uiState.value.drafts[ParameterField.LATENCY_MS])
    }

    @Test
    fun reorderCannotBeEditedWithoutLatency() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "0")
        vm.onParameterTextChange(ParameterField.REORDER_PERCENT, "10")
        assertEquals(0f, vm.uiState.value.config.reorderPercent, 0f)
    }

    @Test
    fun changingTheUnitConvertsTextAndIsRemembered() = runVmTest {
        val vm = viewModel(FakeTunnelController(persisted = customConfig.copy(downKbps = 1500)))
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "abc")

        vm.selectBandwidthUnit(BandwidthUnit.MBPS)
        val state = vm.uiState.value
        assertEquals("1.5", state.drafts[ParameterField.DOWN_KBPS])
        assertEquals(1500, state.config.downKbps)
        assertEquals("Los demás campos no cambian", "abc", state.drafts[ParameterField.LATENCY_MS])
        assertEquals(BandwidthUnit.MBPS, preferences.bandwidthUnit)

        vm.onParameterTextChange(ParameterField.DOWN_KBPS, "12")
        assertEquals(12_000, vm.uiState.value.config.downKbps)
        assertEquals(BandwidthUnit.MBPS, viewModel(FakeTunnelController()).uiState.value.bandwidthUnit)
    }

    @Test
    fun resetGoesBackToTheBaseProfileAndCanBeUndone() = runVmTest {
        val tunnel = FakeTunnelController()
        val vm = viewModel(tunnel)
        tunnel.state.value = running(slow3g)
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "250")
        vm.onParameterTextChange(ParameterField.JITTER_MS, "10")
        settle()

        var state = vm.uiState.value
        assertEquals(listOf(ParameterField.LATENCY_MS, ParameterField.JITTER_MS), state.changedFields)
        assertEquals(
            BaseProfileHeader("Personalizado", "Basado en 3G Lento · 2 cambios", canReset = true),
            state.baseHeader
        )

        vm.resetToBase()
        settle()
        state = vm.uiState.value
        assertEquals(slow3g, state.config)
        assertEquals(BaseProfileHeader("3G Lento", "Perfil predefinido", canReset = false), state.baseHeader)
        assertEquals("200", state.drafts[ParameterField.LATENCY_MS])
        assertEquals(ProfileRef.Preset(ThrottlePreset.SLOW_3G), state.selectedProfile)
        assertEquals("Valores de «3G Lento» restablecidos", state.userMessage?.text)
        assertEquals(slow3g, tunnel.updates.last())

        vm.onMessageAction(state.userMessage!!.id)
        settle()
        assertEquals(2, vm.uiState.value.changedFields.size)
        assertEquals(250, tunnel.updates.last().latencyMs)

        vm.resetToBase()
        vm.resetToBase()
        assertEquals("Sin cambios no hay nada que restablecer", slow3g, vm.uiState.value.config)
    }

    @Test
    fun headerForManualAndSavedBases() = runVmTest {
        val vm = viewModel(FakeTunnelController())
        vm.selectProfile(ProfileRef.CUSTOM)
        vm.onParameterTextChange(ParameterField.LATENCY_MS, "250")
        assertEquals(BaseProfileHeader("Personalizado", "Ajuste manual", canReset = false), vm.uiState.value.baseHeader)

        vm.saveCurrentAsProfile("Metro")
        assertEquals(BaseProfileHeader("Metro", "Perfil guardado", canReset = false), vm.uiState.value.baseHeader)
        assertEquals("Perfil propio guardado en este dispositivo.", vm.uiState.value.selectedDescription)
        vm.onParameterTextChange(ParameterField.JITTER_MS, "5")
        assertEquals("Basado en Metro · 1 cambio", vm.uiState.value.baseHeader.subtitle)
    }

    // ------------------------------------------------------------------ Bienvenida

    @Test
    fun onboardingIsShownUntilCompleted() = runVmTest {
        preferences.onboardingCompleted = false
        val vm = viewModel(FakeTunnelController())
        assertTrue(vm.uiState.value.showOnboarding)

        vm.completeOnboarding()
        assertFalse(vm.uiState.value.showOnboarding)
        assertTrue(preferences.onboardingCompleted)
        assertFalse(viewModel(FakeTunnelController()).uiState.value.showOnboarding)
    }
}
