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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.opennetsim.service.ServiceTunnelController
import com.opennetsim.ui.theme.OpenNetSimTheme

class MainActivity : ComponentActivity() {

    // Raíz de composición: el único punto de la UI que conoce la implementación del túnel.
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer { MainViewModel(ServiceTunnelController(applicationContext)) }
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // El diálogo del sistema para el permiso VPN necesita una Activity; el resto lo decide el ViewModel.
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.requestVpnPermission) {
        if (uiState.requestVpnPermission) {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
                viewModel.clearVpnPermissionRequest()
            } else {
                viewModel.onVpnPermissionResult(true)
            }
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
        onToggleClick = viewModel::onToggleClicked,
        onDismissSnackbar = viewModel::clearMessage
    )
}
