package com.opennetsim.service

import android.content.Context
import androidx.core.content.ContextCompat
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.TunnelState
import com.opennetsim.tunnel.ConfigStore
import com.opennetsim.tunnel.TunnelController
import kotlinx.coroutines.flow.StateFlow

/**
 * [TunnelController] que gobierna [ThrottleVpnService] mediante intents y lee su estado de
 * [TunnelStatusStore].
 */
class ServiceTunnelController(
    context: Context,
    private val configStore: ConfigStore = SharedPreferencesConfigStore(context)
) : TunnelController {
    private val appContext = context.applicationContext

    override val state: StateFlow<TunnelState> = TunnelStatusStore.state

    override val telemetry: StateFlow<NetworkTelemetry> = TunnelStatusStore.telemetry

    override fun lastAppliedConfig(): ThrottleConfig? = configStore.load()

    override fun start(config: ThrottleConfig) {
        ContextCompat.startForegroundService(appContext, TunnelIntents.start(appContext, config))
    }

    override fun stop() {
        appContext.startService(TunnelIntents.stop(appContext))
    }

    override fun updateConfig(config: ThrottleConfig) {
        appContext.startService(TunnelIntents.updateConfig(appContext, config))
    }
}
