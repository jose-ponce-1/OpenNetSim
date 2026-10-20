package com.opennetsim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.core.engine.BidirectionalThrottlingEngine
import com.opennetsim.core.engine.PacketResult
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.service.forwarder.IcmpResponder
import com.opennetsim.service.forwarder.TcpForwarder
import com.opennetsim.service.forwarder.UdpForwarder
import com.opennetsim.service.forwarder.VpnSocketProtector
import com.opennetsim.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket

/**
 * Servicio VPN para simulación de condiciones de red deficientes.
 * Intercepta tráfico local mediante una interfaz TUN virtual sin requerir root,
 * reenvía el tráfico web a la red física exterior a través de forwarders protegidos
 * (UdpForwarder, TcpForwarder e IcmpResponder) y aplica degradación bidireccional
 * simétrica (BidirectionalThrottlingEngine) en corrutinas (Dispatchers.IO).
 */
class ThrottleVpnService : VpnService(), VpnSocketProtector {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunOutputStream: FileOutputStream? = null
    private val tunWriteLock = Any()

    private var engine: BidirectionalThrottlingEngine? = null
    private var icmpResponder: IcmpResponder? = null
    private var udpForwarder: UdpForwarder? = null
    private var tcpForwarder: TcpForwarder? = null

    private val bufferPool = ByteBufferPool.defaultInstance

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var ioJob: Job? = null
    private var uplinkDrainJob: Job? = null
    private var downlinkDrainJob: Job? = null
    private var telemetryJob: Job? = null

    override fun protect(socket: Socket): Boolean = super.protect(socket)

    override fun protect(socket: DatagramSocket): Boolean = super.protect(socket)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopThrottling()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                val config = extractConfigFromIntent(intent)
                updateActiveConfig(config)
                return START_STICKY
            }
            ACTION_START -> {
                val config = extractConfigFromIntent(intent)
                startThrottling(config)
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    private fun startThrottling(config: ThrottleConfig) {
        if (_isServiceRunning.value) {
            updateActiveConfig(config)
            return
        }

        // 1. Iniciar Foreground Service con notificación persistente
        val notification = buildNotification(config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Establecer la interfaz TUN virtual (Dual Stack IPv4 + IPv6)
        val pfd = establishTunInterface()
        if (pfd == null) {
            stopThrottling()
            return
        }
        vpnInterface = pfd
        tunOutputStream = FileOutputStream(pfd.fileDescriptor)

        // 3. Inicializar el motor bidireccional con la configuración activa
        val newEngine = BidirectionalThrottlingEngine.fromConfig(config)
        engine = newEngine

        // 4. Inicializar respondedor ICMP y forwarders hacia la red exterior
        val newIcmpResponder = IcmpResponder(
            onInboundPacket = { packetBytes ->
                handleInboundPacket(packetBytes, newEngine)
            }
        )
        icmpResponder = newIcmpResponder

        val newUdpForwarder = UdpForwarder(
            socketProtector = this,
            coroutineScope = serviceScope,
            onInboundPacket = { packetBytes ->
                handleInboundPacket(packetBytes, newEngine)
            },
            bufferPool = bufferPool
        )
        udpForwarder = newUdpForwarder

        val newTcpForwarder = TcpForwarder(
            socketProtector = this,
            coroutineScope = serviceScope,
            onInboundPacket = { packetBytes ->
                handleInboundPacket(packetBytes, newEngine)
            },
            bufferPool = bufferPool,
            canReadDownlink = { !newEngine.downlinkEngine.isBackpressured },
            onControlPacket = { packetBytes ->
                if (newEngine.downlinkEngine.hasActiveImpairments) {
                    handleInboundPacket(packetBytes, newEngine)
                } else {
                    writeToTun(packetBytes)
                }
            }
        )
        tcpForwarder = newTcpForwarder

        _isServiceRunning.value = true
        _activeConfig.value = config

        // 5. Iniciar bucles de I/O en corrutinas y telemetría
        startIoProcessing(pfd, newEngine, newUdpForwarder, newTcpForwarder, newIcmpResponder)
        startTelemetryLoop(newEngine)
    }

    private fun handleInboundPacket(packetBytes: ByteArray, activeEngine: BidirectionalThrottlingEngine) {
        when (val result = activeEngine.processDownlink(packetBytes)) {
            is PacketResult.Released -> {
                writeToTun(result.packet)
            }
            is PacketResult.Dropped -> {
                // Descartado por el simulador de pérdida en downlink
            }
            is PacketResult.Enqueued -> {
                // Retenido en la cola para entrega posterior con latencia en downlink
            }
        }
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                tunOutputStream?.write(packet)
            } catch (e: IOException) {
                // Interface cerrada o error de descriptor
            }
        }
    }

    private fun startTelemetryLoop(activeEngine: BidirectionalThrottlingEngine) {
        telemetryJob = serviceScope.launch {
            var lastTransferredBytes = 0L
            var lastTimestampMs = System.currentTimeMillis()

            _telemetry.value = NetworkTelemetry(
                bytesPerSecond = 0L,
                totalTransferredBytes = 0L,
                totalDroppedPackets = 0L,
                isConnected = true
            )

            while (isActive) {
                delay(1000L)
                val metrics = activeEngine.getCombinedMetrics()
                val now = System.currentTimeMillis()
                val elapsedMs = kotlin.math.max(1L, now - lastTimestampMs)

                val deltaBytes = kotlin.math.max(0L, metrics.totalReleasedBytes - lastTransferredBytes)
                val bytesPerSec = (deltaBytes * 1000L) / elapsedMs

                lastTransferredBytes = metrics.totalReleasedBytes
                lastTimestampMs = now

                _telemetry.value = NetworkTelemetry(
                    bytesPerSecond = bytesPerSec,
                    totalTransferredBytes = metrics.totalReleasedBytes,
                    totalDroppedPackets = metrics.totalDroppedPackets,
                    isConnected = true
                )
            }
        }
    }

    private fun establishTunInterface(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("OpenNetSim")
                .setMtu(TUN_MTU)
                // Red virtual IPv4
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                // Red virtual IPv6
                .addAddress("fd00::2", 64)
                .addRoute("::", 0)
                .addDnsServer("2001:4860:4860::8888")
                .addDnsServer("2606:4700:4700::1111")

            // Excluir la propia app del túnel para evitar recursión local
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                // Si falla la exclusión, continuar
            }

            builder.establish()
        } catch (e: Exception) {
            null
        }
    }

    private fun startIoProcessing(
        pfd: ParcelFileDescriptor,
        activeEngine: BidirectionalThrottlingEngine,
        activeUdpForwarder: UdpForwarder,
        activeTcpForwarder: TcpForwarder,
        activeIcmpResponder: IcmpResponder
    ) {
        val inputStream = FileInputStream(pfd.fileDescriptor)

        // Bucle de lectura de paquetes salientes desde la interfaz TUN con buffer reciclado
        ioJob = serviceScope.launch {
            val buffer = bufferPool.acquire()
            val rawArray = buffer.array()

            try {
                while (isActive) {
                    val bytesRead = inputStream.read(rawArray)
                    if (bytesRead > 0) {
                        val packetData = rawArray.copyOf(bytesRead)

                        // Procesar degradación en Uplink
                        when (val result = activeEngine.processUplink(packetData)) {
                            is PacketResult.Released -> {
                                dispatchOutboundPacket(
                                    packetData = result.packet,
                                    udpForwarder = activeUdpForwarder,
                                    tcpForwarder = activeTcpForwarder,
                                    icmpResponder = activeIcmpResponder
                                )
                            }
                            is PacketResult.Dropped -> {
                                // Descartado por pérdida en uplink
                            }
                            is PacketResult.Enqueued -> {
                                // Retenido en la cola de retardo de uplink
                            }
                        }
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } catch (e: IOException) {
                // Fin del flujo o interfaz TUN cerrada
            } finally {
                bufferPool.release(buffer)
            }
        }

        // Bucle de drenaje de paquetes salientes (Uplink) tras expirar su retardo
        uplinkDrainJob = serviceScope.launch {
            try {
                while (isActive) {
                    val readyPackets = activeEngine.drainUplinkReady()
                    if (readyPackets.isNotEmpty()) {
                        for (packet in readyPackets) {
                            dispatchOutboundPacket(
                                packetData = packet,
                                udpForwarder = activeUdpForwarder,
                                tcpForwarder = activeTcpForwarder,
                                icmpResponder = activeIcmpResponder
                            )
                        }
                    }

                    val nextEventMs = activeEngine.timeUntilNextUplinkEventMs()
                    when {
                        nextEventMs == null -> delay(2L)
                        nextEventMs <= 0L -> {
                            if (readyPackets.isNotEmpty()) {
                                yield()
                            } else {
                                delay(1L)
                            }
                        }
                        else -> delay(nextEventMs.coerceIn(1L, 10L))
                    }
                }
            } catch (e: Exception) {
                // Cancelación de corrutina
            }
        }

        // Bucle de drenaje de paquetes entrantes (Downlink) tras expirar su retardo
        downlinkDrainJob = serviceScope.launch {
            try {
                while (isActive) {
                    val readyPackets = activeEngine.drainDownlinkReady()
                    if (readyPackets.isNotEmpty()) {
                        for (packet in readyPackets) {
                            writeToTun(packet)
                        }
                    }

                    val nextEventMs = activeEngine.timeUntilNextDownlinkEventMs()
                    when {
                        nextEventMs == null -> delay(2L)
                        nextEventMs <= 0L -> {
                            if (readyPackets.isNotEmpty()) {
                                yield()
                            } else {
                                delay(1L)
                            }
                        }
                        else -> delay(nextEventMs.coerceIn(1L, 10L))
                    }
                }
            } catch (e: Exception) {
                // Cancelación de corrutina
            }
        }
    }

    private fun dispatchOutboundPacket(
        packetData: ByteArray,
        udpForwarder: UdpForwarder,
        tcpForwarder: TcpForwarder,
        icmpResponder: IcmpResponder
    ) {
        if (packetData.isEmpty()) return
        val version = (packetData[0].toInt() and 0xF0) ushr 4

        if (version == 4) {
            val ipPacket = IpPacket.parse(packetData) ?: return
            when (ipPacket.protocol) {
                IpPacket.PROTOCOL_UDP -> udpForwarder.processOutbound(ipPacket)
                IpPacket.PROTOCOL_TCP -> tcpForwarder.processOutbound(ipPacket)
                IpPacket.PROTOCOL_ICMP -> icmpResponder.processOutboundIpv4(ipPacket)
            }
        } else if (version == 6) {
            val ipv6Packet = Ipv6Packet.parse(packetData) ?: return
            when (ipv6Packet.nextHeader) {
                Ipv6Packet.PROTOCOL_UDP -> udpForwarder.processOutboundIpv6(ipv6Packet)
                Ipv6Packet.PROTOCOL_TCP -> tcpForwarder.processOutboundIpv6(ipv6Packet)
                Ipv6Packet.PROTOCOL_ICMPV6 -> icmpResponder.processOutboundIpv6(ipv6Packet)
            }
        }
    }

    private fun updateActiveConfig(config: ThrottleConfig) {
        engine?.updateConfig(config)
        _activeConfig.value = config

        // Actualizar notificación visible con el nuevo perfil
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(config))
    }

    private fun stopThrottling() {
        ioJob?.cancel()
        uplinkDrainJob?.cancel()
        downlinkDrainJob?.cancel()
        telemetryJob?.cancel()
        ioJob = null
        uplinkDrainJob = null
        downlinkDrainJob = null
        telemetryJob = null

        udpForwarder?.close()
        tcpForwarder?.close()
        udpForwarder = null
        tcpForwarder = null
        icmpResponder = null

        synchronized(tunWriteLock) {
            try {
                tunOutputStream?.close()
            } catch (e: IOException) {
                // Ignorar error al cerrar descriptor de salida
            }
            tunOutputStream = null

            try {
                vpnInterface?.close()
            } catch (e: IOException) {
                // Ignorar error al cerrar descriptor TUN
            }
            vpnInterface = null
        }

        engine?.clear()
        engine = null

        _isServiceRunning.value = false
        _telemetry.value = NetworkTelemetry(isConnected = false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopThrottling()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenNetSim VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente de control del motor de throttling"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(config: ThrottleConfig): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = createStopIntent(this)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = "${config.preset.displayName} • ↓${config.formattedDownBandwidth()} ↑${config.formattedUpBandwidth()} • +${config.latencyMs}ms"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenNetSim Activo")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun extractConfigFromIntent(intent: Intent?): ThrottleConfig {
        if (intent == null) return _activeConfig.value

        val downKbps = intent.getIntExtra(EXTRA_DOWN_KBPS, _activeConfig.value.downKbps)
        val upKbps = intent.getIntExtra(EXTRA_UP_KBPS, _activeConfig.value.upKbps)
        val latencyMs = intent.getIntExtra(EXTRA_LATENCY_MS, _activeConfig.value.latencyMs)
        val jitterMs = intent.getIntExtra(EXTRA_JITTER_MS, _activeConfig.value.jitterMs)
        val packetLossPercent = intent.getFloatExtra(EXTRA_LOSS_PERCENT, _activeConfig.value.packetLossPercent)
        val presetName = intent.getStringExtra(EXTRA_PRESET_NAME)

        val preset = presetName?.let {
            runCatching { ThrottlePreset.valueOf(it) }.getOrNull()
        } ?: _activeConfig.value.preset

        return ThrottleConfig(
            downKbps = downKbps,
            upKbps = upKbps,
            latencyMs = latencyMs,
            jitterMs = jitterMs,
            packetLossPercent = packetLossPercent,
            preset = preset
        )
    }

    companion object {
        const val ACTION_START = "com.opennetsim.action.START"
        const val ACTION_STOP = "com.opennetsim.action.STOP"
        const val ACTION_UPDATE_CONFIG = "com.opennetsim.action.UPDATE_CONFIG"

        private const val EXTRA_DOWN_KBPS = "extra_down_kbps"
        private const val EXTRA_UP_KBPS = "extra_up_kbps"
        private const val EXTRA_LATENCY_MS = "extra_latency_ms"
        private const val EXTRA_JITTER_MS = "extra_jitter_ms"
        private const val EXTRA_LOSS_PERCENT = "extra_loss_percent"
        private const val EXTRA_PRESET_NAME = "extra_preset_name"

        private const val CHANNEL_ID = "open_net_sim_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TUN_MTU = 1500

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _activeConfig = MutableStateFlow(ThrottlePreset.fromPreset(ThrottlePreset.SLOW_3G))
        val activeConfig: StateFlow<ThrottleConfig> = _activeConfig.asStateFlow()

        private val _telemetry = MutableStateFlow(NetworkTelemetry())
        val telemetry: StateFlow<NetworkTelemetry> = _telemetry.asStateFlow()

        fun createStartIntent(context: Context, config: ThrottleConfig): Intent {
            return Intent(context, ThrottleVpnService::class.java).apply {
                action = ACTION_START
                putConfigExtras(this, config)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, ThrottleVpnService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun createUpdateConfigIntent(context: Context, config: ThrottleConfig): Intent {
            return Intent(context, ThrottleVpnService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putConfigExtras(this, config)
            }
        }

        private fun putConfigExtras(intent: Intent, config: ThrottleConfig) {
            intent.putExtra(EXTRA_DOWN_KBPS, config.downKbps)
            intent.putExtra(EXTRA_UP_KBPS, config.upKbps)
            intent.putExtra(EXTRA_LATENCY_MS, config.latencyMs)
            intent.putExtra(EXTRA_JITTER_MS, config.jitterMs)
            intent.putExtra(EXTRA_LOSS_PERCENT, config.packetLossPercent)
            intent.putExtra(EXTRA_PRESET_NAME, config.preset.name)
        }
    }
}

