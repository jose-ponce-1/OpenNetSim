package com.opennetsim.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opennetsim.R
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.UserMessage
import com.opennetsim.ui.components.BrandLogo
import com.opennetsim.ui.components.StatusPill
import com.opennetsim.ui.components.asString

/**
 * App principal: barra superior y botón flotante según la sección, barra de navegación inferior y
 * avisos. Atrás desde Parámetros o Telemetría vuelve a Simular.
 */
@Composable
fun MainScaffold(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.SIMULATE) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(state.userMessage, snackbarHostState, actions)
    BackHandler(enabled = destination != AppDestination.SIMULATE) { destination = AppDestination.SIMULATE }

    Box(modifier = modifier) {
        Scaffold(
            topBar = { AppTopBar(destination, state, onAbout = { showAbout = true }) },
            bottomBar = { AppNavigationBar(destination, onSelect = { destination = it }) },
            floatingActionButton = {
                if (destination == AppDestination.TELEMETRY) TunnelFab(state, actions.onToggle)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            val content = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
            when (destination) {
                AppDestination.SIMULATE -> SimulateScreen(
                    state = state,
                    actions = actions.profiles,
                    onToggle = actions.onToggle,
                    onNavigate = { destination = it },
                    modifier = content
                )
                AppDestination.PARAMETERS -> ParametersScreen(
                    state = state,
                    actions = actions.parameters,
                    onChangeProfile = { destination = AppDestination.SIMULATE },
                    modifier = content.imePadding()
                )
                AppDestination.TELEMETRY -> TelemetryScreen(state = state, modifier = content)
            }
        }
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    }
}

/** Muestra cada aviso una vez; la acción («Deshacer») y la desaparición se comunican por su id. */
@Composable
private fun MessageEffect(message: UserMessage?, host: SnackbarHostState, actions: MainActions) {
    val currentActions by rememberUpdatedState(actions)
    // Se resuelven aquí, en el idioma activo: al cambiar de idioma la actividad se recrea y el aviso
    // vuelve a mostrarse ya traducido.
    val text = message?.text?.asString().orEmpty()
    val actionLabel = message?.actionLabel?.asString()
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(
            message = text,
            actionLabel = actionLabel,
            duration = if (message.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) currentActions.onMessageAction(message.id)
        currentActions.onMessageDismiss(message.id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(destination: AppDestination, state: MainUiState, onAbout: () -> Unit) {
    TopAppBar(
        title = {
            if (destination == AppDestination.SIMULATE) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BrandLogo(size = 32.dp, contentDescription = null)
                    Text(stringResource(R.string.brand_name))
                }
            } else {
                Text(stringResource(destination.labelRes))
            }
        },
        actions = {
            StatusPill(state.isActive, state.isStarting, modifier = Modifier.padding(end = 4.dp))
            if (destination == AppDestination.SIMULATE) {
                IconButton(onClick = onAbout) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about_content_description))
                }
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
        }
    )
}

@Composable
private fun AppNavigationBar(destination: AppDestination, onSelect: (AppDestination) -> Unit) {
    // Colores del prototipo: Material 3 1.4+ pasó la etiqueta elegida de `onSurface` a `secondary`.
    val colors = MaterialTheme.colorScheme
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = colors.onSecondaryContainer,
        selectedTextColor = colors.onSurface,
        indicatorColor = colors.secondaryContainer,
        unselectedIconColor = colors.onSurfaceVariant,
        unselectedTextColor = colors.onSurfaceVariant
    )
    NavigationBar {
        AppDestination.entries.forEach { item ->
            NavigationBarItem(
                selected = item == destination,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.labelRes)) },
                colors = itemColors
            )
        }
    }
}

/** Detiene el túnel o lo inicia (pidiendo el permiso VPN si hace falta). */
@Composable
private fun TunnelFab(state: MainUiState, onToggle: () -> Unit) {
    val running = state.isActive || state.isStarting
    val colors = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        onClick = onToggle,
        icon = { Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null) },
        text = { Text(stringResource(if (running) R.string.action_stop else R.string.action_start)) },
        containerColor = if (running) colors.tertiaryContainer else colors.primaryContainer,
        contentColor = if (running) colors.onTertiaryContainer else colors.onPrimaryContainer
    )
}
