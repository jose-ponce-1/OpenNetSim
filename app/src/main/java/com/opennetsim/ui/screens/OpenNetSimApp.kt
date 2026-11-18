package com.opennetsim.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opennetsim.ui.MainViewModel

/**
 * Raíz de la interfaz: el único composable que conoce el ViewModel. Muestra la bienvenida hasta que se
 * completa y, después, la app principal.
 */
@Composable
fun OpenNetSimApp(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        MainActions(
            profiles = ProfileActions(
                onSelect = viewModel::selectProfile,
                onSave = viewModel::saveCurrentAsProfile,
                onDelete = viewModel::deleteProfile
            ),
            parameters = ParameterActions(
                onTextChange = viewModel::onParameterTextChange,
                onFocusLost = viewModel::onParameterFocusLost,
                onSelectUnit = viewModel::selectBandwidthUnit,
                onReset = viewModel::resetToBase
            ),
            onToggle = viewModel::onToggleClicked,
            onMessageAction = viewModel::onMessageAction,
            onMessageDismiss = viewModel::clearMessage
        )
    }

    TunnelPermissionEffect(
        request = state.requestVpnPermission,
        onResult = viewModel::onVpnPermissionResult,
        onLaunch = viewModel::clearVpnPermissionRequest
    )

    Box(modifier = modifier.fillMaxSize()) {
        if (state.showOnboarding) {
            WelcomeScreen(onComplete = viewModel::completeOnboarding)
        } else {
            NotificationPermissionEffect()
            MainScaffold(state = state, actions = actions)
        }
    }
}

/**
 * Pide el permiso VPN al arrancar el túnel: el diálogo del sistema necesita una Activity; el resultado
 * lo decide el ViewModel.
 */
@Composable
private fun TunnelPermissionEffect(request: Boolean, onResult: (Boolean) -> Unit, onLaunch: () -> Unit) {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnLaunch by rememberUpdatedState(onLaunch)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        currentOnResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(request) {
        if (!request) return@LaunchedEffect
        val intent = VpnService.prepare(context)
        if (intent != null) {
            launcher.launch(intent)
            currentOnLaunch()
        } else {
            currentOnResult(true)
        }
    }
}

/** Permiso de notificaciones (Android 13+) para la notificación del túnel, ya fuera de la bienvenida. */
@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
