package com.opennetsim.ui.screens

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.opennetsim.ui.ConnectionProfile
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.ProfileRef
import com.opennetsim.ui.components.SectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val NUDGE_DELAY_MS = 900L
private val START_FADE = 40.dp
private val END_FADE = 56.dp

/**
 * Perfiles en chips desplazables, con degradados en los bordes que tienen más chips, una indicación de
 * cuántos quedan fuera y un pequeño desplazamiento de muestra la primera vez.
 */
@Composable
fun ProfileChips(
    state: MainUiState,
    onSelect: (ProfileRef) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles = state.profiles
    val listState = rememberLazyListState()
    var userScrolled by rememberSaveable { mutableStateOf(false) }
    val hidden by remember(listState) { derivedStateOf { hiddenCount(listState) } }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled = true }
    }
    NudgeOnce(listState)
    ScrollSelectedIntoView(listState, state.selectedProfile, profiles.indexOfFirst { it.ref == state.selectedProfile })

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("Perfiles de conexión", modifier = Modifier.weight(1f))
            TextButton(onClick = onShowAll) {
                Text("Ver todos (${profiles.size})")
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fadingEdges(listState, MaterialTheme.colorScheme.surface)
        ) {
            items(profiles, key = { it.ref.toString() }) { profile ->
                ProfileChip(profile, selected = profile.ref == state.selectedProfile, onSelect = onSelect)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = state.selectedDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!userScrolled && hidden > 0) SwipeHint(hidden)
        }
    }
}

@Composable
private fun ProfileChip(profile: ConnectionProfile, selected: Boolean, onSelect: (ProfileRef) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onSelect(profile.ref) },
        label = { Text(profile.name) },
        leadingIcon = when {
            selected -> {
                {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            }
            profile.isSaved -> {
                {
                    Icon(
                        Icons.Outlined.BookmarkBorder,
                        contentDescription = "Perfil guardado",
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                }
            }
            else -> null
        }
    )
}

/** Degradado hacia el fondo en los bordes por los que aún quedan chips. */
private fun Modifier.fadingEdges(listState: LazyListState, color: Color): Modifier = drawWithContent {
    drawContent()
    val transparent = color.copy(alpha = 0f)
    if (listState.canScrollBackward) {
        val width = START_FADE.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(color, transparent), startX = 0f, endX = width),
            size = Size(width, size.height)
        )
    }
    if (listState.canScrollForward) {
        val width = END_FADE.toPx()
        drawRect(
            Brush.horizontalGradient(listOf(transparent, color), startX = size.width - width, endX = size.width),
            topLeft = Offset(size.width - width, 0f),
            size = Size(width, size.height)
        )
    }
}

@Composable
private fun SwipeHint(hidden: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            Icons.Outlined.SwipeLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = if (hidden == 1) "Desliza para ver 1 perfil más" else "Desliza para ver $hidden perfiles más",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** La primera vez, desliza un poco los chips y los devuelve para mostrar que hay más. */
@Composable
private fun NudgeOnce(listState: LazyListState) {
    var nudged by rememberSaveable { mutableStateOf(false) }
    val distance = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(listState) {
        if (nudged) return@LaunchedEffect
        delay(NUDGE_DELAY_MS)
        if (listState.canScrollForward && !listState.canScrollBackward) {
            listState.animateScrollBy(distance)
            listState.animateScrollBy(-distance)
        }
        nudged = true
    }
}

/** Si el perfil elegido (p. ej. desde la lista completa) queda fuera de la vista, se desplaza hasta él. */
@Composable
private fun ScrollSelectedIntoView(listState: LazyListState, selected: ProfileRef, selectedIndex: Int) {
    // También con el mismo índice: un perfil recién guardado ocupa el 0 que tenía el elegido antes.
    LaunchedEffect(selected, selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        // Espera a que la fila tenga tamaño: antes de la primera medida no hay nada visible.
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(selectedIndex)
    }
}

/** Chips que no se ven enteros. */
private fun hiddenCount(listState: LazyListState): Int {
    val info = listState.layoutInfo
    if (info.totalItemsCount == 0) return 0
    val fullyVisible = info.visibleItemsInfo.count {
        it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
    }
    return info.totalItemsCount - fullyVisible
}
