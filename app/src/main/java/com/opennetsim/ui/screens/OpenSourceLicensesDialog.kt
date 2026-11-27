package com.opennetsim.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opennetsim.R
import com.opennetsim.ui.LicenseGroup
import com.opennetsim.ui.OpenSourceLicenses
import com.opennetsim.ui.components.AppOutlinedButton
import java.io.IOException

/**
 * Vista modal desplazable con las dependencias de terceros incluidas y acceso sin conexión al texto
 * completo de su licencia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenSourceLicensesDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val artifacts = remember(context) { loadArtifacts(context) }
    val groups = remember(artifacts) { OpenSourceLicenses.group(artifacts.orEmpty()) }
    var selectedLicenseId by rememberSaveable { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.licenses_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                )
            }
        ) { padding ->
            LicensesBody(
                artifactCount = artifacts?.size,
                groups = groups,
                onOpenLicense = { licenseId -> selectedLicenseId = licenseId },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
            )
        }
    }

    selectedLicenseId?.let { licenseId ->
        LicenseTextDialog(
            licenseId = licenseId,
            text = remember(context, licenseId) { loadLicenseText(context, licenseId) },
            onDismiss = { selectedLicenseId = null }
        )
    }
}

@Composable
private fun LicensesBody(
    artifactCount: Int?,
    groups: List<LicenseGroup>,
    onOpenLicense: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (artifactCount == null) {
        Text(
            text = stringResource(R.string.licenses_load_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(24.dp)
        )
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "summary") {
            LicensesSummaryCard(
                artifactCount = artifactCount,
                groupCount = groups.size,
                onReadApache = { onOpenLicense(OpenSourceLicenses.APACHE_2_0_ID) }
            )
        }
        items(groups, key = { group -> group.id }) { group ->
            LicenseGroupCard(group = group, onOpenLicense = { onOpenLicense(group.licenseId) })
        }
    }
}

@Composable
private fun LicensesSummaryCard(
    artifactCount: Int,
    groupCount: Int,
    onReadApache: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.licenses_summary_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.licenses_summary_body, artifactCount, groupCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppOutlinedButton(onClick = onReadApache) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = ButtonDefaults.IconSpacing)
                        .size(ButtonDefaults.IconSize)
                )
                Text(stringResource(R.string.licenses_read_apache))
            }
        }
    }
}

@Composable
private fun LicenseGroupCard(
    group: LicenseGroup,
    onOpenLicense: () -> Unit
) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    val hasMultiple = group.artifacts.size > 1
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpenLicense)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = groupSubtitle(group),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LicensePill(licenseId = group.licenseId)
                if (hasMultiple) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.licenses_collapse else R.string.licenses_expand
                            )
                        )
                    }
                }
            }
            if (expanded && hasMultiple) {
                HorizontalDivider()
                GroupArtifactsList(group)
            }
        }
    }
}

@Composable
private fun groupSubtitle(group: LicenseGroup): String =
    if (group.artifacts.size == 1) {
        val single = group.artifacts.first()
        "${single.group}:${single.artifact} · ${single.version}"
    } else {
        val countText = pluralStringResource(R.plurals.licenses_modules, group.artifacts.size, group.artifacts.size)
        "$countText · ${group.versionsSummary}"
    }

@Composable
private fun GroupArtifactsList(group: LicenseGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        group.artifacts.forEach { artifact ->
            Column {
                Text(
                    text = artifact.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${artifact.group}:${artifact.artifact} · ${artifact.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LicensePill(licenseId: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = licenseId,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LicenseTextDialog(
    licenseId: String,
    text: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(licenseId) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = text ?: stringResource(R.string.licenses_text_unavailable, licenseId),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private fun loadArtifacts(context: Context) = try {
    context.assets.open(OpenSourceLicenses.LIBRARIES_ASSET_PATH).use(OpenSourceLicenses::parse)
} catch (_: IOException) {
    null
}

private fun loadLicenseText(context: Context, licenseId: String): String? {
    val path = OpenSourceLicenses.licenseAssetPath(licenseId) ?: return null
    return try {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    } catch (_: IOException) {
        null
    }
}
