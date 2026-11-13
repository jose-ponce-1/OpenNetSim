package com.opennetsim.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.opennetsim.core.buffer.ByteBufferPool
import com.opennetsim.core.engine.BidirectionalThrottlingEngine
import com.opennetsim.core.engine.DelayedPacket
import com.opennetsim.core.engine.PacketResult
import com.opennetsim.core.engine.TrafficClass
import com.opennetsim.core.packet.IpPacket
import com.opennetsim.core.packet.Ipv6Packet
import com.opennetsim.core.packet.PacketMeta
import com.opennetsim.model.NetworkTelemetry
import com.opennetsim.model.TelemetrySampler
import com.opennetsim.model.ThrottleConfig
import com.opennetsim.model.ThrottlePreset
import com.opennetsim.model.TrafficCounters
import com.opennetsim.model.TunnelState
import com.opennetsim.service.diag.AndroidLogSink
import com.opennetsim.service.diag.DiagLevel
import com.opennetsim.service.diag.NetDiagnostics
import com.opennetsim.service.diag.error
import com.opennetsim.service.diag.warn
import com.opennetsim.service.forwarder.ForwarderEnvironment
import com.opennetsim.service.forwarder.IcmpResponder
import com.opennetsim.service.forwarder.TcpForwarder
import com.opennetsim.service.forwarder.UdpForwarder
import com.opennetsim.service.forwarder.VpnSocketProtector
import com.opennetsim.service.network.UnderlyingNetworkMonitor
import com.opennetsim.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    private val diagnostics: NetDiagnostics = AndroidLogSink.rateLimited()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Generación del pipeline activo: evita que un fallo de un pipeline viejo detenga uno nuevo. */
    @Volatile
    private var tunnelGeneration: Int = 0

    /** True si el pipeline actual enruta IPv6 (la red física tenía IPv6 al establecerlo). */
    private var ipv6Routed = false
    private var lastEstablishFailure: String? = null

    private val networkMonitor by lazy {
        UnderlyingNetworkMonitor(this, diagnostics) { network, hasIpv6 ->
            mainHandler.post { onUnderlyingNetworkChanged(network, hasIpv6) }
        }
    }

    private val serviceJob = SupervisorJob()
    private val coroutineFailureHandler = CoroutineExceptionHandler { _, throwable ->
        failTunnel(tunnelGeneration, "service.coroutine", "Excepción no capturada en una corrutina del túnel", throwable)
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob + coroutineFailureHandler)

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
                // Parada pedida por el usuario: limpia también un fallo anterior.
                _tunnelState.value = TunnelState.Stopped
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
        _tunnelState.value = TunnelState.Starting

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

        networkMonitor.start()
        if (!startPipeline(config)) {
            stopThrottling(failureReason = lastEstablishFailure ?: "No se pudo establecer el túnel VPN")
        }
    }

    /**
     * Establece el TUN y arranca motor, forwarders y bucles. IPv6 solo se enruta si la red física
     * tiene conectividad IPv6. Devuelve false si el sistema rechaza establecer el TUN.
     */
    @Suppress("LongMethod")
    private fun startPipeline(config: ThrottleConfig): Boolean {
        // 2. Establecer la interfaz TUN virtual (IPv4 y, si la red física lo permite, IPv6)
        val network = networkMonitor.currentNetwork()
        val routeIpv6 = networkMonitor.hasIpv6(network)
        val pfd = establishTunInterface(routeIpv6, network) ?: return false
        ipv6Routed = routeIpv6
        val generation = ++tunnelGeneration
        vpnInterface = pfd
        tunOutputStream = FileOutputStream(pfd.fileDescriptor)

        // 3. Inicializar el motor bidireccional con la configuración activa
        val newEngine = BidirectionalThrottlingEngine.fromConfig(config)
        engine = newEngine
        val uplinkWake = Channel<Unit>(Channel.CONFLATED)
        val downlinkWake = Channel<Unit>(Channel.CONFLATED)
        val toDownlink: (ByteArray) -> Unit = { packetBytes ->
            handleInboundPacket(packetBytes, newEngine, downlinkWake)
        }

        // 4. Inicializar respondedor ICMP y forwarders hacia la red exterior
        val newIcmpResponder = IcmpResponder(onInboundPacket = toDownlink)
        icmpResponder = newIcmpResponder

        val forwarderEnvironment = ForwarderEnvironment(
            socketProtector = this,
            coroutineScope = serviceScope,
            bufferPool = bufferPool,
            diagnostics = diagnostics
        )

        val newUdpForwarder = UdpForwarder(environment = forwarderEnvironment, onInboundPacket = toDownlink)
        udpForwarder = newUdpForwarder

        val newTcpForwarder = TcpForwarder(
            environment = forwarderEnvironment,
            onInboundPacket = toDownlink,
            canReadDownlink = { newEngine.downlinkHasCapacity },
            onControlPacket = toDownlink
        )
        newTcpForwarder.updateDegradation(config.downKbps, config.latencyMs, config.jitterMs)
        tcpForwarder = newTcpForwarder

        _isServiceRunning.value = true
        _activeConfig.value = config
        _tunnelState.value = TunnelState.Running(config, ipv6Routed = routeIpv6)

        // 5. Iniciar bucles de I/O en corrutinas y telemetría
        startIoProcessing(
            TunnelPipeline(
                generation = generation,
                tunDescriptor = pfd,
                engine = newEngine,
                udpForwarder = newUdpForwarder,
                tcpForwarder = newTcpForwarder,
                icmpResponder = newIcmpResponder,
                uplinkWake = uplinkWake,
                downlinkWake = downlinkWake
            )
        )
        startTelemetryLoop(newEngine, newTcpForwarder)
        return true
    }

    /**
     * La red física cambió (hilo principal). Si cambia su disponibilidad de IPv6, se reconstruye el
     * túnel entero para enrutar o dejar de enrutar IPv6; las conexiones en curso se reinician.
     */
    private fun onUnderlyingNetworkChanged(network: Network?, hasIpv6: Boolean) {
        if (!_isServiceRunning.value) return
        setUnderlyingNetworks(network?.let { arrayOf(it) })
        if (hasIpv6 == ipv6Routed) return

        diagnostics.log(
            DiagLevel.INFO,
            "net.change",
            if (hasIpv6) "La red física ahora tiene IPv6: se enruta por el túnel" else "La red física perdió IPv6: se bloquea"
        )
        val config = _activeConfig.value
        tearDownPipeline()
        if (!startPipeline(config)) {
            stopThrottling(failureReason = lastEstablishFailure ?: "No se pudo reconstruir el túnel tras un cambio de red")
        }
    }

    /** Componentes de un pipeline activo del túnel. */
    private class TunnelPipeline(
        val generation: Int,
        val tunDescriptor: ParcelFileDescriptor,
        val engine: BidirectionalThrottlingEngine,
        val udpForwarder: UdpForwarder,
        val tcpForwarder: TcpForwarder,
        val icmpResponder: IcmpResponder,
        /** Despierta a uplinkDrainJob cuando el lector del TUN encola un paquete. */
        val uplinkWake: Channel<Unit>,
        /** Despierta a downlinkDrainJob cuando un forwarder encola un paquete. */
        val downlinkWake: Channel<Unit>
    )

    /**
     * Registra un fallo no recuperable del túnel y lo detiene desde el hilo principal, salvo que el
     * fallo pertenezca a un pipeline ya sustituido.
     */
    private fun failTunnel(generation: Int, key: String, message: String, error: Throwable?) {
        diagnostics.error(key, message, error)
        mainHandler.post {
            if (generation == tunnelGeneration && _isServiceRunning.value) {
                stopThrottling(failureReason = message)
            }
        }
    }

    /**
     * Ejecuta [block] para un único paquete: un error inesperado descarta ese paquete y se registra,
     * sin detener el bucle que lo invoca. La cancelación de la corrutina se propaga.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun guardPacket(key: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.error(key, "Paquete descartado por un error inesperado", e)
        }
    }

    /**
     * Encola un paquete de bajada (exterior → TUN). Nunca se escribe aquí: lo escribe solo
     * downlinkDrainJob, en el orden del motor, para no reordenar paquetes de un mismo flujo.
     */
    private fun handleInboundPacket(
        packetBytes: ByteArray,
        activeEngine: BidirectionalThrottlingEngine,
        downlinkWake: Channel<Unit>
    ) {
        val meta = PacketMeta.parse(packetBytes)
        val result = activeEngine.downlinkEngine.processIncoming(
            packet = packetBytes,
            trafficClass = classify(meta),
            flowId = meta?.flowId ?: DelayedPacket.NO_FLOW,
            allowImmediateRelease = false
        )
        if (result is PacketResult.Enqueued) downlinkWake.trySend(Unit)
    }

    /**
     * Interactivo: segmentos TCP sin datos (ACK/SYN/FIN/RST), ICMP y DNS. El motor mantiene el orden
     * por flujo, así que un FIN nunca adelanta a datos de su propia conexión.
     */
    private fun classify(meta: PacketMeta?): TrafficClass = when {
        meta == null -> TrafficClass.BULK
        meta.isIcmp || meta.isTcpWithoutPayload || meta.isDns -> TrafficClass.INTERACTIVE
        else -> TrafficClass.BULK
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            val stream = tunOutputStream ?: return
            try {
                stream.write(packet)
            } catch (e: IOException) {
                if (packet.size > TUN_MTU) {
                    // Excepción a la regla de fallo fatal (design D12): un paquete entero mayor que el
                    // MTU que el kernel rechaza solo se descarta; para la app equivale a una pérdida.
                    diagnostics.warn("tun.write.oversize", "El TUN rechazó un paquete de ${packet.size} B; se descarta", e)
                } else {
                    failTunnel(tunnelGeneration, "tun.write", "Error escribiendo en el descriptor del TUN", e)
                }
            }
        }
    }

    private fun startTelemetryLoop(activeEngine: BidirectionalThrottlingEngine, activeTcpForwarder: TcpForwarder) {
        telemetryJob = serviceScope.launch {
            val sampler = TelemetrySampler(startMs = System.currentTimeMillis())
            _telemetry.value = NetworkTelemetry(isConnected = true)

            while (isActive) {
                delay(TELEMETRY_INTERVAL_MS)
                val down = activeEngine.downlinkEngine.getMetrics()
                val up = activeEngine.uplinkEngine.getMetrics()
                val counters = TrafficCounters(
                    downReleasedBytes = down.totalReleasedBytes,
                    upReleasedBytes = up.totalReleasedBytes,
                    retransmittedBytes = activeTcpForwarder.retransmittedBytes(),
                    droppedPackets = down.totalDroppedPackets + up.totalDroppedPackets
                )
                _telemetry.value = sampler.sample(counters, System.currentTimeMillis())
            }
        }
    }

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
                // Red virtual IPv4
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
            if (routeIpv6) {
                builder.addAddress("fd00::2", 64)
                    .addRoute("::", 0)
                    .addDnsServer("2001:4860:4860::8888")
                    .addDnsServer("2606:4700:4700::1111")
            }
            builder.setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Por defecto una VPN es "medida" y las apps reducen calidad o difieren descargas.
                builder.setMetered(false)
            }

            // Excluir la propia app del túnel para evitar recursión local
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                diagnostics.warn("tun.establish", "No se pudo excluir la app del túnel; se confía en protect()", e)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                recordEstablishFailure("El sistema rechazó el túnel: falta el permiso VPN u otra VPN lo bloquea", null)
            }
            pfd
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

    private fun startIoProcessing(pipeline: TunnelPipeline) {
        val generation = pipeline.generation
        val activeEngine = pipeline.engine
        val inputStream = FileInputStream(pipeline.tunDescriptor.fileDescriptor)

        // Bucle de lectura de paquetes salientes desde la interfaz TUN con buffer reciclado.
        // Nunca espera por contrapresión ni despacha: solo encola en el motor (o descarta).
        ioJob = serviceScope.launch {
            val buffer = bufferPool.acquire()
            val rawArray = buffer.array()
            try {
                while (isActive) {
                    val bytesRead = try {
                        inputStream.read(rawArray)
                    } catch (e: IOException) {
                        if (isActive) failTunnel(generation, "tun.read", "Error leyendo el descriptor del TUN", e)
                        break
                    }
                    if (bytesRead < 0) {
                        if (isActive) failTunnel(generation, "tun.read", "Fin de flujo inesperado en el TUN", null)
                        break
                    }
                    if (bytesRead == 0) continue

                    guardPacket("uplink.read") {
                        enqueueUplinkPacket(rawArray.copyOf(bytesRead), pipeline)
                    }
                }
            } finally {
                bufferPool.release(buffer)
            }
        }

        // Bucle de drenaje de paquetes salientes (Uplink): único punto de despacho a los forwarders,
        // de modo que cada forwarder recibe los paquetes en el orden del motor.
        uplinkDrainJob = serviceScope.launch {
            while (isActive) {
                var readyPackets: List<ByteArray> = emptyList()
                guardPacket("uplink.drain") { readyPackets = activeEngine.drainUplinkReady() }
                for (packet in readyPackets) {
                    guardPacket("uplink.dispatch") { dispatchOutboundPacket(packet, pipeline) }
                }
                awaitNextEvent(activeEngine.timeUntilNextUplinkEventMs(), readyPackets.isNotEmpty(), pipeline.uplinkWake)
            }
        }

        // Bucle de drenaje de paquetes entrantes (Downlink): único escritor de datos hacia el TUN.
        downlinkDrainJob = serviceScope.launch {
            var hadCapacity = activeEngine.downlinkHasCapacity
            while (isActive) {
                var readyPackets: List<ByteArray> = emptyList()
                guardPacket("downlink.drain") { readyPackets = activeEngine.drainDownlinkReady() }
                for (packet in readyPackets) {
                    guardPacket("downlink.write") { writeToTun(packet) }
                }
                // Señal de capacidad: reanudar lecturas TCP pausadas en cuanto la bajada tiene hueco.
                val hasCapacity = activeEngine.downlinkHasCapacity
                if (hasCapacity && !hadCapacity) pipeline.tcpForwarder.resumeReads()
                hadCapacity = hasCapacity
                awaitNextEvent(activeEngine.timeUntilNextDownlinkEventMs(), readyPackets.isNotEmpty(), pipeline.downlinkWake)
            }
        }
    }

    private fun enqueueUplinkPacket(packetData: ByteArray, pipeline: TunnelPipeline) {
        val meta = PacketMeta.parse(packetData)
        val result = pipeline.engine.uplinkEngine.processIncoming(
            packet = packetData,
            trafficClass = classify(meta),
            flowId = meta?.flowId ?: DelayedPacket.NO_FLOW,
            allowImmediateRelease = false
        )
        if (result is PacketResult.Enqueued) pipeline.uplinkWake.trySend(Unit)
    }

    /**
     * Espera hasta el próximo evento del motor o hasta que se encole un paquete nuevo ([wake]).
     */
    private suspend fun awaitNextEvent(nextEventMs: Long?, releasedPackets: Boolean, wake: Channel<Unit>) {
        val waitMs = when {
            nextEventMs == null -> IDLE_WAIT_MS
            nextEventMs <= 0L -> if (releasedPackets) 0L else 1L
            else -> nextEventMs.coerceIn(1L, IDLE_WAIT_MS)
        }
        if (waitMs == 0L) {
            yield()
        } else {
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    private fun dispatchOutboundPacket(packetData: ByteArray, pipeline: TunnelPipeline) {
        val udpForwarder = pipeline.udpForwarder
        val tcpForwarder = pipeline.tcpForwarder
        val icmpResponder = pipeline.icmpResponder
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
        tcpForwarder?.updateDegradation(config.downKbps, config.latencyMs, config.jitterMs)
        _activeConfig.value = config
        if (_tunnelState.value is TunnelState.Running) {
            _tunnelState.value = TunnelState.Running(config, ipv6Routed)
        }

        // Actualizar notificación visible con el nuevo perfil
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(config))
    }

    /**
     * Detiene el túnel y el servicio. Con [failureReason] publica [TunnelState.Failed]; sin él publica
     * [TunnelState.Stopped], salvo que ya hubiera un fallo (p. ej. onDestroy tras un fallo).
     */
    private fun stopThrottling(failureReason: String? = null) {
        tearDownPipeline()
        networkMonitor.stop()

        _isServiceRunning.value = false
        _telemetry.value = NetworkTelemetry(isConnected = false)
        _tunnelState.value = when {
            failureReason != null -> TunnelState.Failed(failureReason)
            _tunnelState.value is TunnelState.Failed -> _tunnelState.value
            else -> TunnelState.Stopped
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Libera el pipeline actual (bucles, forwarders, descriptor del TUN y motor). */
    private fun tearDownPipeline() {
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
                diagnostics.log(DiagLevel.DEBUG, "tun.close", "Error al cerrar el flujo de salida del TUN", e)
            }
            tunOutputStream = null

            try {
                vpnInterface?.close()
            } catch (e: IOException) {
                diagnostics.log(DiagLevel.DEBUG, "tun.close", "Error al cerrar el descriptor del TUN", e)
            }
            vpnInterface = null
        }

        engine?.clear()
        engine = null
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
        private const val IDLE_WAIT_MS = 250L
        private const val TELEMETRY_INTERVAL_MS = 1_000L

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Stopped)

        /** Estado real del túnel: la UI lo usa en lugar de suposiciones optimistas. */
        val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

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

