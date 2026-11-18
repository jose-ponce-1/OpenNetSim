package com.opennetsim.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opennetsim.R
import com.opennetsim.ui.components.BrandLogo
import com.opennetsim.ui.components.IconBadge

/**
 * Bienvenida del primer arranque. Pide el permiso VPN con el diálogo del sistema sin arrancar el túnel;
 * [onComplete] la cierra para siempre («Empezar» o «Ahora no»).
 */
@Composable
fun WelcomeScreen(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(VpnService.prepare(context) == null) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        granted = result.resultCode == Activity.RESULT_OK
        denied = !granted
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 40.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                WelcomeIntro()
                WelcomeFeatures()
            }
            WelcomeActions(
                granted = granted,
                denied = denied,
                onRequestPermission = {
                    val intent = VpnService.prepare(context)
                    if (intent == null) granted = true else launcher.launch(intent)
                },
                onComplete = onComplete
            )
        }
    }
}

@Composable
private fun WelcomeIntro() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { BrandLogo(size = 72.dp) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WelcomeFeatures() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FeatureRow(Icons.Outlined.SwapVert, stringResource(R.string.welcome_feature_bandwidth))
        FeatureRow(Icons.Outlined.Timer, stringResource(R.string.welcome_feature_latency))
        FeatureRow(Icons.Outlined.DisabledByDefault, stringResource(R.string.welcome_feature_loss))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconBadge(
            icon = icon,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WelcomeActions(
    granted: Boolean,
    denied: Boolean,
    onRequestPermission: () -> Unit,
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(if (denied) R.string.welcome_vpn_denied else R.string.welcome_vpn_note),
            style = MaterialTheme.typography.bodySmall,
            color = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        val buttonModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
        if (granted) {
            Button(onClick = onComplete, modifier = buttonModifier) {
                Text(stringResource(R.string.welcome_get_started))
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = ButtonDefaults.IconSpacing)
                        .size(ButtonDefaults.IconSize)
                )
            }
        } else {
            Button(onClick = onRequestPermission, modifier = buttonModifier) {
                Icon(
                    Icons.Outlined.VpnKey,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize)
                )
                Text(
                    stringResource(R.string.welcome_allow_vpn),
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing)
                )
            }
            TextButton(onClick = onComplete, modifier = buttonModifier) {
                Text(stringResource(R.string.welcome_not_now))
            }
        }
    }
}
