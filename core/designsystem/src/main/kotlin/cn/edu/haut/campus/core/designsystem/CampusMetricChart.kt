/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 折线趋势图数据点模型。
 * @param segmentBreakBefore 若为 `true`，表示该点与前一点之间存在缺测日或未记录购电，折线在此处自动断开。
 */
data class CampusMetricPoint(
    val label: String,
    val value: Double,
    val unit: String = "kWh",
    val subtitle: String = "",
    val segmentBreakBefore: Boolean = false
)

@Composable
fun CampusMetricChart(
    points: List<CampusMetricPoint>,
    modifier: Modifier = Modifier,
    emptyText: String = "暂无每日趋势数据"
) {
    val validPoints = remember(points) {
        points.filter { it.value.isFinite() && it.value >= 0.0 }
    }
    if (validPoints.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp)
        )
        return
    }

    var selectedIndex by remember(validPoints.size) {
        mutableIntStateOf(validPoints.lastIndex)
    }
    val safeSelectedIndex = selectedIndex.coerceIn(0, validPoints.lastIndex)
    val selectedPoint = validPoints[safeSelectedIndex]

    val minVal = validPoints.minOf { it.value }
    val maxVal = validPoints.maxOf { it.value }
    val avgVal = validPoints.map { it.value }.average()
    val unitText = selectedPoint.unit.ifBlank { "kWh" }

    val lineColor = XPeachTheme.readablePrimary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(
        alpha = if (XPeachTheme.isDark) 0.65f else 0.45f
    )
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val highlightColor = XPeachTheme.readableAccent(MaterialTheme.colorScheme.tertiary)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedPoint.subtitle.ifBlank {
                    "${selectedPoint.label} · ${"%.2f".format(selectedPoint.value)} $unitText"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Text(
                text = "均值 ${"%.2f".format(avgVal)} · 峰值 ${"%.2f".format(maxVal)} $unitText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .pointerInput(validPoints) {
                    detectTapGestures { tapOffset ->
                        val padH = 6.dp.toPx()
                        val usableWidth = (size.width - padH * 2f).coerceAtLeast(1f)
                        val count = validPoints.size
                        val bestIdx = if (count <= 1) {
                            0
                        } else {
                            (0 until count).minByOrNull { idx ->
                                val x = padH + usableWidth * (idx.toFloat() / (count - 1).toFloat())
                                abs(tapOffset.x - x)
                            } ?: 0
                        }
                        selectedIndex = bestIdx
                    }
                }
        ) {
            val padHorizontal = 6.dp.toPx()
            val padVertical = 16.dp.toPx()
            val chartW = (size.width - padHorizontal * 2f).coerceAtLeast(1f)
            val chartH = (size.height - padVertical * 2f).coerceAtLeast(1f)

            // 3 条水平参考网格线
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            for (level in 0..2) {
                val y = padVertical + chartH * (level / 2f)
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
            }

            val span = (maxVal - minVal).takeIf { it > 1e-6 } ?: 1.0
            val coordinates = validPoints.mapIndexed { idx, pt ->
                val x = if (validPoints.size == 1) {
                    size.width / 2f
                } else {
                    padHorizontal + chartW * (idx.toFloat() / (validPoints.size - 1).toFloat())
                }
                val normalized = if (maxVal - minVal <= 1e-6) {
                    0.5f
                } else {
                    ((pt.value - minVal) / span).toFloat().coerceIn(0f, 1f)
                }
                val y = padVertical + chartH * (1f - normalized)
                Offset(x, y)
            }

            // 分段绘制折线：遇到 segmentBreakBefore == true 时断开 Path，不画跨缺测日连线
            val path = Path()
            var hasActiveSegment = false
            coordinates.forEachIndexed { idx, offset ->
                val breakSegment = idx == 0 || validPoints[idx].segmentBreakBefore
                if (breakSegment || !hasActiveSegment) {
                    path.moveTo(offset.x, offset.y)
                    hasActiveSegment = true
                } else {
                    path.lineTo(offset.x, offset.y)
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 绘制每个采样点圆点与当前选中高亮
            coordinates.forEachIndexed { idx, center ->
                val isSelected = idx == safeSelectedIndex
                if (isSelected) {
                    drawLine(
                        color = highlightColor.copy(alpha = 0.35f),
                        start = Offset(center.x, padVertical),
                        end = Offset(center.x, size.height - padVertical),
                        strokeWidth = 1.2.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }
                val outerRadius = if (isSelected) 5.5.dp.toPx() else 3.8.dp.toPx()
                drawCircle(
                    color = if (isSelected) highlightColor else lineColor,
                    radius = outerRadius,
                    center = center
                )
                drawCircle(
                    color = surfaceColor,
                    radius = outerRadius * 0.48f,
                    center = center
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = validPoints.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (validPoints.any { it.segmentBreakBefore }) {
                Text(
                    text = "注：未记录或充值当日折线断开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            if (validPoints.size > 1) {
                Text(
                    text = validPoints.last().label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
