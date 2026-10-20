package com.opennetsim.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opennetsim.service.ThrottleVpnService
import com.opennetsim.ui.theme.OpenNetSimTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenNetSimTheme {
                MainAppContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: MainViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Launcher para permisos de notificación en Android 13+ (API 33+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Launcher para interceptar el resultado de la solicitud del sistema para VpnService
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val isGranted = result.resultCode == Activity.RESULT_OK
        viewModel.onVpnPermissionResult(isGranted)
        if (isGranted) {
            val startIntent = ThrottleVpnService.createStartIntent(context, uiState.config)
            ContextCompat.startForegroundService(context, startIntent)
        }
    }

    // Efecto reactivo si el ViewModel solicita explícitamente el permiso VPN
    LaunchedEffect(uiState.requestVpnPermission) {
        if (uiState.requestVpnPermission) {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
            } else {
                viewModel.onVpnPermissionResult(true)
                val startIntent = ThrottleVpnService.createStartIntent(context, uiState.config)
                ContextCompat.startForegroundService(context, startIntent)
            }
            viewModel.clearVpnPermissionRequest()
        }
    }

    // Sincronizar cambios de configuración en caliente con el servicio activo
    LaunchedEffect(uiState.config) {
        if (uiState.isActive) {
            val updateIntent = ThrottleVpnService.createUpdateConfigIntent(context, uiState.config)
            context.startService(updateIntent)
        }
    }

    ThrottleDashboardScreen(
        uiState = uiState,
        onPresetSelected = viewModel::selectPreset,
        onDownBandwidthChanged = viewModel::updateDownBandwidth,
        onUpBandwidthChanged = viewModel::updateUpBandwidth,
        onLatencyChanged = viewModel::updateLatency,
        onJitterChanged = viewModel::updateJitter,
        onPacketLossChanged = viewModel::updatePacketLoss,
        onToggleClick = {
            if (uiState.isActive) {
                // Detener el servicio VPN
                val stopIntent = ThrottleVpnService.createStopIntent(context)
                context.startService(stopIntent)
                viewModel.onToggleThrottling(hasVpnPermission = true)
            } else {
                // Comprobar si se requiere solicitar permisos de VpnService
                val vpnIntent = VpnService.prepare(context)
                if (vpnIntent != null) {
                    vpnLauncher.launch(vpnIntent)
                } else {
                    val startIntent = ThrottleVpnService.createStartIntent(context, uiState.config)
                    ContextCompat.startForegroundService(context, startIntent)
                    viewModel.onToggleThrottling(hasVpnPermission = true)
                }
            }
        },
        onDismissSnackbar = viewModel::clearMessage
    )
}

