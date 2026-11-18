package com.opennetsim.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opennetsim.R
import com.opennetsim.ui.MainUiState
import com.opennetsim.ui.ThroughputChart
import com.opennetsim.ui.ThroughputHistory
import com.opennetsim.ui.TrafficDirection
import com.opennetsim.ui.components.TabularNumbers
import com.opennetsim.ui.components.asString

private const val AREA_ALPHA = 0.14f
private const val GRID_DIVISIONS = 3

/** Caudal actual y gráfica de los últimos 60 s frente al límite configurado. */
@Composable
fun ThroughputCard(state: MainUiState, direction: TrafficDirection, modifier: Modifier = Modifier) {
    val headline = ThroughputHistory.headline(state.telemetry, state.config, direction, state.isActive)
    val label = headline.label.asString()
    val capLabel = headline.capLabel.asString()
    val chart = remember(state.history, state.config, direction) {
        ThroughputHistory.chart(state.history, state.config, direction)
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = headline.value, style = MaterialTheme.typography.displaySmall.merge(TabularNumbers))
                }
                Text(
                    text = capLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            ChartCanvas(
                chart = chart,
                description = stringResource(R.string.chart_description, label, headline.value, capLabel),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )
            AxisLabels()
            Legend(direction)
        }
    }
}

@Composable
private fun ChartCanvas(chart: ThroughputChart, description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val tertiary = colors.tertiary
    val grid = colors.outlineVariant
    val baseline = colors.outline
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val stroke = 1.dp.toPx()
        for (division in 0 until GRID_DIVISIONS) {
            val y = size.height * division / GRID_DIVISIONS
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = stroke)
        val capY = size.height * (1f - chart.capFraction.coerceIn(0f, 1f))
        val dash = 4.dp.toPx()
        drawLine(
            tertiary,
            Offset(0f, capY),
            Offset(size.width, capY),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
        )
        chart.down?.let { drawSeries(it, chart.capacity, primary, fillArea = true) }
        chart.up?.let { drawSeries(it, chart.capacity, tertiary, fillArea = false) }
    }
}

/** Serie alineada a la derecha: la muestra más reciente siempre queda en el borde derecho. */
private fun DrawScope.drawSeries(points: List<Float>, capacity: Int, color: Color, fillArea: Boolean) {
    if (points.isEmpty()) return
    val step = size.width / (capacity - 1)
    val startX = size.width - (points.size - 1) * step
    fun y(fraction: Float) = size.height * (1f - fraction.coerceIn(0f, 1f))
    if (points.size == 1) {
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width, y(points.single())))
        return
    }
    val line = Path().apply {
        points.forEachIndexed { index, fraction ->
            val x = startX + index * step
            if (index == 0) moveTo(x, y(fraction)) else lineTo(x, y(fraction))
        }
    }
    if (fillArea) {
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(startX, size.height)
            close()
        }
        drawPath(area, color.copy(alpha = AREA_ALPHA))
    }
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun AxisLabels() {
    Row(modifier = Modifier.padding(horizontal = 2.dp)) {
        val style = MaterialTheme.typography.labelSmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(stringResource(R.string.axis_minus_60), style = style, color = color, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.axis_minus_30),
            style = style,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.axis_now),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Legend(direction: TrafficDirection) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (direction != TrafficDirection.UP) {
            LegendItem(stringResource(R.string.direction_download), colors.primary, dashed = false)
        }
        if (direction != TrafficDirection.DOWN) {
            LegendItem(stringResource(R.string.direction_upload), colors.tertiary, dashed = false)
        }
        LegendItem(stringResource(R.string.legend_limit), colors.tertiary, dashed = true)
    }
}

@Composable
private fun LegendItem(label: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 4.dp)) {
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = if (dashed) 2.dp.toPx() else size.height,
                cap = if (dashed) StrokeCap.Butt else StrokeCap.Round,
                pathEffect = effect
            )
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
