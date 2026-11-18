package com.opennetsim.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opennetsim.R

/** Cifras con anchura fija, para que los valores en vivo no bailen. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/** Logo oficial de OpenNetSim. */
@Composable
fun BrandLogo(
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Logotipo de OpenNetSim"
) {
    Image(
        painter = painterResource(R.drawable.ic_logo_opennetsim),
        contentDescription = contentDescription,
        modifier = modifier.size(size)
    )
}

/** Estado del túnel en la barra superior: «Activo», «Iniciando» o «En reposo». */
@Composable
fun StatusPill(
    isActive: Boolean,
    isStarting: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (isActive) colors.primaryContainer else colors.surfaceContainerHigh,
        label = "statusContainer"
    )
    val dot = if (isActive || isStarting) colors.primary else colors.outline
    val label = when {
        isActive -> "Activo"
        isStarting -> "Iniciando"
        else -> "En reposo"
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = if (isActive) colors.onPrimaryContainer else colors.onSurfaceVariant,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Icono dentro de un círculo de color, como en los elementos de lista de Material 3. */
@Composable
fun IconBadge(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
    }
}

/** Tarjeta pequeña con un dato: etiqueta, valor y una línea opcional bajo él. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    icon: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon?.invoke()
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.merge(TabularNumbers),
                    fontWeight = FontWeight.SemiBold
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Título de una sección de contenido. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}
