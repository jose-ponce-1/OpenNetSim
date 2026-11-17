package com.opennetsim.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.core.time.MonotonicClock
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TunnelState
import com.opennetsim.service.diag.AndroidLogSink
import com.opennetsim.service.diag.DiagLevel
import com.opennetsim.service.diag.NetDiagnostics
import com.opennetsim.service.diag.error
import com.opennetsim.service.diag.warn
import com.opennetsim.service.forwarder.AndroidIcmpEchoTransport
import com.opennetsim.service.forwarder.ForwarderEnvironment
import com.opennetsim.service.forwarder.IcmpEchoTransport
import com.opennetsim.service.forwarder.VpnSocketProtector
import com.opennetsim.service.network.UnderlyingNetworkMonitor
import com.opennetsim.service.pipeline.ParcelFileDescriptorTunDevice
import com.opennetsim.service.pipeline.TunnelPipeline
import com.opennetsim.tunnel.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.FileDescriptor
import java.net.DatagramSocket
import java.net.Socket

/**
 * Servicio VPN de OpenNetSim: gestiona el ciclo de vida del túnel y nada más.
 *
 * - Interpreta las órdenes ([TunnelIntents]) y los reinicios del sistema (`START_STICKY`, VPN siempre
 *   activa, revocación del permiso).
 * - Establece la interfaz TUN según la red física y la entrega a un [TunnelPipeline], que hace todo
 *   el reenvío y la degradación.
 * - Publica el estado real en [TunnelStatusStore] y persiste la configuración aplicada
 *   ([ConfigStore]) para que un reinicio nunca cambie en silencio las condiciones de red.
 *
 * Todos los métodos de ciclo de vida se ejecutan en el hilo principal.
 */
@Suppress("TooManyFunctions") // Un método por evento del ciclo de vida del VpnService.
class ThrottleVpnService : VpnService(), VpnSocketProtector {

    private val diagnostics: NetDiagnostics = AndroidLogSink.rateLimited()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clock = MonotonicClock.SYSTEM

    private lateinit var configStore: ConfigStore
    private lateinit var notifications: TunnelNotifications

    private var pipeline: TunnelPipeline? = null
    private var activeConfig: ThrottleConfig? = null

    /** True si el pipeline actual enruta IPv6 (la red física tenía IPv6 al establecerlo). */
    private var ipv6Routed = false
    private var lastEstablishFailure: String? = null

    private val networkMonitor by lazy {
        UnderlyingNetworkMonitor(this, diagnostics) { network, hasIpv6 ->
            mainHandler.post { onUnderlyingNetworkChanged(network, hasIpv6) }
        }
    }

    override fun protect(socket: Socket): Boolean = super.protect(socket)

    override fun protect(socket: DatagramSocket): Boolean = super.protect(socket)

    override fun onCreate() {
        super.onCreate()
        configStore = SharedPreferencesConfigStore(this)
        notifications = TunnelNotifications(this).also { it.createChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (val command = TunnelIntents.parse(intent)) {
            is TunnelCommand.Start -> {
                startTunnel(command.config)
                START_STICKY
            }
            is TunnelCommand.UpdateConfig -> {
                if (pipeline != null) applyConfig(command.config) else stopSelfIfIdle()
                START_STICKY
            }
            TunnelCommand.Stop -> {
                stopTunnel()
                // Parada pedida por el usuario: limpia también un fallo anterior.
                TunnelStatusStore.publish(TunnelState.Stopped)
                START_NOT_STICKY
            }
            is TunnelCommand.Restore -> {
                restoreTunnel(command.alwaysOn)
                START_STICKY
            }
            TunnelCommand.Unknown -> {
                diagnostics.warn("service.command", "Orden desconocida ignorada: ${intent?.action}")
                stopSelfIfIdle()
                START_NOT_STICKY
            }
        }

    /**
     * El sistema revocó la VPN (otra app VPN tomó el control o el usuario la desactivó): parada
     * ordenada con un motivo explícito, no un error de E/S.
     */
    override fun onRevoke() {
        diagnostics.log(DiagLevel.WARN, "tun.revoke", "El sistema revocó el permiso VPN")
        stopTunnel(failureReason = REVOKED_REASON)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Arranque y restauración

    /**
     * Reinicio no pedido por la UI: reanuda con la configuración persistida. Sin permiso VPN o sin
     * configuración (salvo VPN siempre activa, que usa el perfil por defecto) no se establece nada.
     */
    private fun restoreTunnel(alwaysOn: Boolean) {
        if (pipeline != null) return
        val config = configStore.load() ?: if (alwaysOn) ThrottlePreset.fromPreset(DEFAULT_PRESET) else null
        val permissionMissing = prepare(this) != null
        if (config == null || permissionMissing) {
            diagnostics.log(
                DiagLevel.INFO,
                "tun.restore",
                if (permissionMissing) RESTORE_NO_PERMISSION else RESTORE_NO_CONFIG
            )
            TunnelStatusStore.publish(TunnelState.Stopped)
            stopSelf()
            return
        }
        startTunnel(config)
    }

    private fun startTunnel(config: ThrottleConfig) {
        if (pipeline != null) {
            applyConfig(config)
            return
        }
        TunnelStatusStore.publish(TunnelState.Starting)
        startForegroundCompat(config)
        networkMonitor.start()
        if (!startPipeline(config)) {
            stopTunnel(failureReason = lastEstablishFailure ?: "No se pudo establecer el túnel VPN")
        }
    }

    private fun startForegroundCompat(config: ThrottleConfig) {
        val notification = notifications.build(config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TunnelNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(TunnelNotifications.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Establece el TUN y arranca un pipeline nuevo. IPv6 solo se enruta si la red física tiene
     * conectividad IPv6. Devuelve false si el sistema rechaza establecer el TUN.
     */
    private fun startPipeline(config: ThrottleConfig): Boolean {
        val network = networkMonitor.currentNetwork()
        val routeIpv6 = networkMonitor.hasIpv6(network)
        val descriptor = establishTunInterface(routeIpv6, network) ?: return false

        val listener = PipelineListener()
        val newPipeline = TunnelPipeline(
            tun = ParcelFileDescriptorTunDevice(descriptor, diagnostics),
            initialConfig = config,
            environment = ForwarderEnvironment(
                socketProtector = this,
                coroutineScope = serviceScope,
                bufferPool = ByteBufferPool.defaultInstance,
                diagnostics = diagnostics,
                clock = clock
            ),
            listener = listener,
            mtu = TUN_MTU,
            icmpTransport = createIcmpTransport()
        )
        listener.owner = newPipeline
        pipeline = newPipeline
        ipv6Routed = routeIpv6
        rememberConfig(config)
        TunnelStatusStore.publish(TunnelState.Running(config, ipv6Routed = routeIpv6))
        newPipeline.start()
        return true
    }

    /** Sockets de ping del sistema para reenviar ICMP real; sin ellos el ping queda sin respuesta. */
    private fun createIcmpTransport(): IcmpEchoTransport = try {
        AndroidIcmpEchoTransport(protect = ::protectDescriptor, diagnostics = diagnostics)
    } catch (e: ErrnoException) {
        diagnostics.warn("icmp.transport", "Sin sockets de ping: el ping no tendrá respuesta", e)
        IcmpEchoTransport.UNAVAILABLE
    }

    /** `protect()` para un socket abierto con `android.system.Os` (solo hay `protect(int)`). */
    private fun protectDescriptor(descriptor: FileDescriptor): Boolean =
        ParcelFileDescriptor.dup(descriptor).use { protect(it.fd) }

    /** Recibe los avisos de un pipeline y los ignora si ese pipeline ya fue sustituido. */
    private inner class PipelineListener : TunnelPipeline.Listener {
        @Volatile
        var owner: TunnelPipeline? = null

        override fun onFatalError(message: String) {
            mainHandler.post {
                if (owner != null && owner === pipeline) {
                    // Si la lectura falla porque otra VPN tomó el control, se informa como revocación.
                    stopTunnel(
                        failureReason = if (prepare(this@ThrottleVpnService) != null) REVOKED_REASON else message
                    )
                }
            }
        }

        override fun onTelemetry(telemetry: NetworkTelemetry) {
            if (owner != null && owner === pipeline) TunnelStatusStore.publish(telemetry)
        }
    }

    // ------------------------------------------------------------------ Configuración y red

    private fun applyConfig(config: ThrottleConfig) {
        val current = pipeline ?: return
        current.updateConfig(config)
        rememberConfig(config)
        TunnelStatusStore.publish(TunnelState.Running(config, ipv6Routed))
        notifications.show(config)
    }

    /** Toda configuración aplicada pasa por aquí: es la que se restablece tras un reinicio. */
    private fun rememberConfig(config: ThrottleConfig) {
        activeConfig = config
        configStore.save(config)
    }

    /**
     * La red física cambió. Si cambia su disponibilidad de IPv6, se reconstruye el túnel entero para
     * enrutar o dejar de enrutar IPv6; las conexiones en curso se reinician.
     */
    private fun onUnderlyingNetworkChanged(network: Network?, hasIpv6: Boolean) {
        val config = activeConfig
        if (pipeline == null || config == null) return
        setUnderlyingNetworks(network?.let { arrayOf(it) })
        if (hasIpv6 == ipv6Routed) return

        diagnostics.log(
            DiagLevel.INFO,
            "net.change",
            if (hasIpv6) NET_GAINED_IPV6 else NET_LOST_IPV6
        )
        tearDownPipeline()
        if (!startPipeline(config)) {
            stopTunnel(failureReason = lastEstablishFailure ?: "No se pudo reconstruir el túnel tras un cambio de red")
        }
    }

    // ------------------------------------------------------------------ Interfaz TUN

    /**
     * Establece la interfaz TUN. Sin [routeIpv6] no se añade dirección, ruta ni DNS IPv6: Android
     * bloquea entonces el tráfico IPv6 y las apps usan IPv4 sin esperar a timeouts.
     */
    private fun establishTunInterface(routeIpv6: Boolean, underlyingNetwork: Network?): ParcelFileDescriptor? {
        lastEstablishFailure = null
        return try {
            val builder = Builder()
                .setSession("OpenNetSim")
                .setMtu(TUN_MTU)
                .addAddress("10.0.0.2", IPV4_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
            if (routeIpv6) {
                builder.addAddress("fd00::2", IPV6_PREFIX)
                    .addRoute("::", 0)
                    .addDnsServer("2001:4860:4860::8888")
                    .addDnsServer("2606:4700:4700::1111")
            }
            builder.setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Por defecto una VPN es "medida" y las apps reducen calidad o difieren descargas.
                builder.setMetered(false)
            }
            try {
                // Excluir la propia app del túnel para evitar recursión local.
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                diagnostics.warn("tun.establish", "No se pudo excluir la app del túnel; se confía en protect()", e)
            }
            builder.establish()
                ?: recordEstablishFailure(ESTABLISH_REJECTED, null)
        } catch (e: IllegalArgumentException) {
            recordEstablishFailure("Parámetros del túnel rechazados", e)
        } catch (e: IllegalStateException) {
            recordEstablishFailure("Estado no válido al establecer el túnel", e)
        } catch (e: SecurityException) {
            recordEstablishFailure("Sin permiso para establecer el túnel", e)
        }
    }

    private fun recordEstablishFailure(reason: String, error: Throwable?): ParcelFileDescriptor? {
        lastEstablishFailure = reason
        diagnostics.error("tun.establish", reason, error)
        return null
    }

    // ------------------------------------------------------------------ Parada

    /**
     * Detiene el túnel y el servicio. Con [failureReason] publica [TunnelState.Failed]; sin él publica
     * [TunnelState.Stopped], salvo que ya hubiera un fallo (p. ej. onDestroy tras un fallo).
     */
    private fun stopTunnel(failureReason: String? = null) {
        tearDownPipeline()
        networkMonitor.stop()
        activeConfig = null

        TunnelStatusStore.publish(NetworkTelemetry(isConnected = false))
        val current = TunnelStatusStore.state.value
        TunnelStatusStore.publish(
            when {
                failureReason != null -> TunnelState.Failed(failureReason)
                current is TunnelState.Failed -> current
                else -> TunnelState.Stopped
            }
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun tearDownPipeline() {
        pipeline?.close()
        pipeline = null
    }

    /** Una orden que no tiene efecto sin túnel no debe dejar el servicio vivo. */
    private fun stopSelfIfIdle() {
        if (pipeline == null) stopSelf()
    }

    private companion object {
        const val TUN_MTU = TunnelPipeline.DEFAULT_MTU
        const val IPV4_PREFIX = 24
        const val IPV6_PREFIX = 64
        val DEFAULT_PRESET = ThrottlePreset.SLOW_3G
        const val REVOKED_REASON = "Permiso VPN revocado: otra app VPN tomó el control o se desactivó en los ajustes"
        const val RESTORE_NO_PERMISSION = "Reinicio sin permiso VPN: no se establece el túnel"
        const val RESTORE_NO_CONFIG = "Reinicio sin configuración guardada"
        const val NET_GAINED_IPV6 = "La red física ahora tiene IPv6: se enruta por el túnel"
        const val NET_LOST_IPV6 = "La red física perdió IPv6: se bloquea"
        const val ESTABLISH_REJECTED = "El sistema rechazó el túnel: falta el permiso VPN u otra VPN lo bloquea"
    }
}
