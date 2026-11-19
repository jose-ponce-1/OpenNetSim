package com.opennetsim.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.opennetsim.R
import com.opennetsim.ui.SupportLinks
import com.opennetsim.ui.components.AppOutlinedButton
import com.opennetsim.ui.components.BrandLogo

/**
 * Información de la app: versión, descripción, código abierto con enlace a GitHub, apoyo económico con
 * enlace a Ko-fi (no si se instaló desde Google Play, ver [SupportLinks.donationLinkAllowed]) y firma.
 * Desplazable: en pantallas de 360 × 640 dp no cabe entero.
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    val showDonations = remember(context) { SupportLinks.donationLinkAllowed(installerPackage(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { BrandLogo(size = 48.dp, contentDescription = null) },
        title = { Text(stringResource(R.string.brand_name)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (version != null) {
                        Text(
                            stringResource(R.string.about_version, version),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(stringResource(R.string.about_description))
                }
                AboutSection(
                    title = stringResource(R.string.about_open_source_title),
                    text = stringResource(R.string.about_open_source_text)
                ) {
                    AppOutlinedButton(onClick = { openLink(context, SupportLinks.SOURCE_CODE_URL) }) {
                        ButtonIcon(Icons.Outlined.Code)
                        Text(stringResource(R.string.about_view_source))
                    }
                }
                if (showDonations) {
                    AboutSection(
                        title = stringResource(R.string.about_support_title),
                        text = stringResource(R.string.about_support_text)
                    ) {
                        FilledTonalButton(onClick = { openLink(context, SupportLinks.KO_FI_URL) }) {
                            ButtonIcon(Icons.Outlined.Coffee)
                            Text(stringResource(R.string.about_support_button))
                        }
                    }
                }
                MadeWithLove()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun AboutSection(title: String, text: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(text)
        content()
    }
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.padding(end = ButtonDefaults.IconSpacing).size(ButtonDefaults.IconSize)
    )
}

/** Firma del diálogo «Acerca de». TalkBack la lee como una sola frase. */
@Composable
private fun MadeWithLove() {
    val style = MaterialTheme.typography.bodySmall
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.about_made_with), style = style)
        Icon(
            Icons.Filled.Favorite,
            contentDescription = stringResource(R.string.about_love),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(stringResource(R.string.about_made_by), style = style)
    }
}

/** Paquete de la tienda que instaló la app; null si se instaló a mano (p. ej. el APK de GitHub). */
private fun installerPackage(context: Context): String? = try {
    val packageManager = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(context.packageName)
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun versionName(context: Context): String? = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName
} catch (_: PackageManager.NameNotFoundException) {
    null
}
