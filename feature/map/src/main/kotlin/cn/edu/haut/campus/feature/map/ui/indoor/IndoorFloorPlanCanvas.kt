package cn.edu.haut.campus.feature.map.ui.indoor

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisIndoorRoomCategory
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 独立建筑平面正交米制投影画布（无街景底图干扰、高对比度建筑制图风格、严格防重叠文字标注）。
 */
@Composable
internal fun IndoorFloorPlanCanvas(
    footprint: GisBuildingFootprint,
    floorPlan: GisIndoorFloorPlan?,
    selectedRoom: GisIndoorRoom?,
    nearestStairOrElevator: GisIndoorRoom?,
    highlightCategory: GisIndoorRoomCategory?,
    zoom: Float,
    panOffset: Offset,
    onTransform: (Float, Offset) -> Unit,
    onSelectRoom: (GisIndoorRoom) -> Unit
) {
    val isDarkPlan = XPeachTheme.isDark
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(panOffset)
    val currentFloorPlan by rememberUpdatedState(floorPlan)
    val currentOnTransform by rememberUpdatedState(onTransform)
    val currentOnSelectRoom by rememberUpdatedState(onSelectRoom)

    // 计算当前楼宇平面图的地理包围盒与中心点，保证单栋楼宇在 1.0x 下自动饱满居中显示
    val boundsInfo = remember(footprint, floorPlan) {
        val allPts = buildList {
            floorPlan?.rooms?.forEach { addAll(it.outerRing) }
            floorPlan?.outlineRings?.forEach { addAll(it) }
            addAll(footprint.outerRing)
        }
        if (allPts.isEmpty()) {
            IndoorBounds(
                midLat = footprint.centerLat,
                midLon = footprint.centerLon,
                cosLat = cos(Math.toRadians(footprint.centerLat)).coerceAtLeast(0.2),
                spanXMeters = 60.0,
                spanYMeters = 60.0
            )
        } else {
            val minLat = allPts.minOf { it.latitude }
            val maxLat = allPts.maxOf { it.latitude }
            val minLon = allPts.minOf { it.longitude }
            val maxLon = allPts.maxOf { it.longitude }
            val midLat = (minLat + maxLat) / 2.0
            val midLon = (minLon + maxLon) / 2.0
            val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
            val spanX = ((maxLon - minLon) * 111320.0 * cosLat).coerceAtLeast(22.0)
            val spanY = ((maxLat - minLat) * 110540.0).coerceAtLeast(22.0)
            IndoorBounds(
                midLat = midLat,
                midLon = midLon,
                cosLat = cosLat,
                spanXMeters = spanX,
                spanYMeters = spanY
            )
        }
    }

    val currentBounds by rememberUpdatedState(boundsInfo)
    var canvasWidthPx by remember { mutableFloatStateOf(1080f) }
    var canvasHeightPx by remember { mutableFloatStateOf(1920f) }

    fun wgsToPlanScreenDynamic(lat: Double, lon: Double): Offset {
        val b = currentBounds
        val baseScale = minOf(
            (canvasWidthPx * 0.82f) / b.spanXMeters.toFloat(),
            (canvasHeightPx * 0.52f) / b.spanYMeters.toFloat()
        ).coerceAtLeast(1.5f)
        val effectiveScale = baseScale * currentZoom
        val xm = ((lon - b.midLon) * 111320.0 * b.cosLat).toFloat()
        val ym = (-(lat - b.midLat) * 110540.0).toFloat()
        val cx = canvasWidthPx * 0.46f + currentPan.x
        val cy = canvasHeightPx * 0.48f + currentPan.y
        return Offset(cx + xm * effectiveScale, cy + ym * effectiveScale)
    }

    val boldSansTypeface = remember { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val roomLabelPaint = remember(isDarkPlan, boldSansTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = boldSansTypeface
            color = if (isDarkPlan) {
                android.graphics.Color.rgb(226, 232, 240)
            } else {
                android.graphics.Color.rgb(30, 41, 59)
            }
        }
    }
    val calloutTextPaint = remember(boldSansTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = boldSansTypeface
            color = android.graphics.Color.WHITE
        }
    }
    val calloutBgPaint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(234, 88, 12)
            style = Paint.Style.FILL
        }
    }
    val scaleBarTextPaint = remember(isDarkPlan, boldSansTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = boldSansTypeface
            color = if (isDarkPlan) {
                android.graphics.Color.rgb(148, 163, 184)
            } else {
                android.graphics.Color.rgb(71, 85, 105)
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val plan = currentFloorPlan ?: return@detectTapGestures
                    val hitRoom = plan.rooms.firstOrNull { room ->
                        val ring = room.outerRing.map { wgsToPlanScreenDynamic(it.latitude, it.longitude) }
                        isPointInsidePolygon(tapOffset, ring)
                    }
                    if (hitRoom != null) {
                        currentOnSelectRoom(hitRoom)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newZoom = (currentZoom * gestureZoom).coerceIn(0.75f, 6.0f)
                    val newPan = Offset(
                        x = (currentPan.x + pan.x).coerceIn(-canvasWidthPx * 1.6f, canvasWidthPx * 1.6f),
                        y = (currentPan.y + pan.y).coerceIn(-canvasHeightPx * 1.6f, canvasHeightPx * 1.6f)
                    )
                    currentOnTransform(newZoom, newPan)
                }
            }
    ) {
        canvasWidthPx = size.width
        canvasHeightPx = size.height

        val baseScale = minOf(
            (size.width * 0.82f) / boundsInfo.spanXMeters.toFloat(),
            (size.height * 0.52f) / boundsInfo.spanYMeters.toFloat()
        ).coerceAtLeast(1.5f)
        val effectiveScale = baseScale * zoom
        val originCenter = Offset(
            x = size.width * 0.46f + panOffset.x,
            y = size.height * 0.48f + panOffset.y
        )

        fun wgsToScreen(lat: Double, lon: Double): Offset {
            val xm = ((lon - boundsInfo.midLon) * 111320.0 * boundsInfo.cosLat).toFloat()
            val ym = (-(lat - boundsInfo.midLat) * 110540.0).toFloat()
            return Offset(
                x = originCenter.x + xm * effectiveScale,
                y = originCenter.y + ym * effectiveScale
            )
        }

        // 1. 绘制建筑工程制图深浅色自适应网格背景（无杂乱街景瓦片干扰）
        drawRect(color = if (isDarkPlan) Color(0xFF0C121B) else Color(0xFFF8FAFC))
        val gridLineColor = if (isDarkPlan) Color(0xFF182232) else Color(0xFFE2E8F0)
        val gridStepMeters = when {
            effectiveScale >= 18f -> 5f
            effectiveScale >= 9f -> 10f
            else -> 20f
        }
        val gridStepPx = (gridStepMeters * effectiveScale).coerceAtLeast(28f)
        val startX = (originCenter.x % gridStepPx + gridStepPx) % gridStepPx
        val startY = (originCenter.y % gridStepPx + gridStepPx) % gridStepPx
        var gx = startX
        while (gx < size.width) {
            drawLine(
                color = gridLineColor,
                start = Offset(gx, 0f),
                end = Offset(gx, size.height),
                strokeWidth = 1f
            )
            gx += gridStepPx
        }
        var gy = startY
        while (gy < size.height) {
            drawLine(
                color = gridLineColor,
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1f
            )
            gy += gridStepPx
        }

        // 2. 绘制建筑外轮廓结构底板与阴影
        val shellRings = if (!floorPlan?.outlineRings.isNullOrEmpty()) {
            floorPlan!!.outlineRings
        } else if (footprint.outerRing.size >= 3) {
            listOf(footprint.outerRing)
        } else {
            emptyList()
        }

        for (ring in shellRings) {
            val pts = ring.map { wgsToScreen(it.latitude, it.longitude) }
            if (pts.size < 3) continue
            val shadowPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x + 6f, p.y + 8f) else lineTo(p.x + 6f, p.y + 8f)
                }
                close()
            }
            val shellPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path = shadowPath, color = if (isDarkPlan) Color(0x55000000) else Color(0x180F172A))
            drawPath(path = shellPath, color = if (isDarkPlan) Color(0xFF151E2B) else Color(0xFFF1F5F9))
            drawPath(
                path = shellPath,
                color = if (isDarkPlan) Color(0xFF64748B) else Color(0xFF475569),
                style = Stroke(width = 4.5f, join = StrokeJoin.Round)
            )
        }

        // 3. 绘制当前楼层所有房间多边形（按教室/实验室/楼梯/卫生间着色，深浅色自适应高对比度）
        val rooms = floorPlan?.rooms.orEmpty()
        for (room in rooms) {
            val pts = room.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
            if (pts.size < 3) continue
            val isSelected = selectedRoom?.id == room.id
            val isDimmed = highlightCategory != null && room.category != highlightCategory && !isSelected
            val alphaMul = if (isDimmed) 0.28f else 1.0f

            val roomPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }

            val baseFill = if (isDarkPlan) {
                when {
                    isSelected -> Color(0xFFEA580C)
                    room.category == GisIndoorRoomCategory.CLASSROOM -> Color(0xFF16325B)
                    room.category == GisIndoorRoomCategory.LABORATORY -> Color(0xFF11382C)
                    room.category == GisIndoorRoomCategory.OFFICE -> Color(0xFF382813)
                    room.category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> Color(0xFF2B1C4E)
                    room.category == GisIndoorRoomCategory.RESTROOM -> Color(0xFF103344)
                    room.category == GisIndoorRoomCategory.CORRIDOR -> Color(0xFF121A26)
                    else -> Color(0xFF1D2736)
                }
            } else {
                when {
                    isSelected -> Color(0xFFF97316)
                    room.category == GisIndoorRoomCategory.CLASSROOM -> Color(0xFFDBEAFE)
                    room.category == GisIndoorRoomCategory.LABORATORY -> Color(0xFFD1FAE5)
                    room.category == GisIndoorRoomCategory.OFFICE -> Color(0xFFFEF3C7)
                    room.category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> Color(0xFFEDE9FE)
                    room.category == GisIndoorRoomCategory.RESTROOM -> Color(0xFFCFFAFE)
                    room.category == GisIndoorRoomCategory.CORRIDOR -> Color(0xFFF8FAFC)
                    else -> Color(0xFFE2E8F0)
                }
            }
            val baseBorder = if (isDarkPlan) {
                when {
                    isSelected -> Color(0xFFFB923C)
                    room.category == GisIndoorRoomCategory.CLASSROOM -> Color(0xFF60A5FA)
                    room.category == GisIndoorRoomCategory.LABORATORY -> Color(0xFF34D399)
                    room.category == GisIndoorRoomCategory.OFFICE -> Color(0xFFFBBF24)
                    room.category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> Color(0xFFA78BFA)
                    room.category == GisIndoorRoomCategory.RESTROOM -> Color(0xFF22D3EE)
                    else -> Color(0xFF475569)
                }
            } else {
                when {
                    isSelected -> Color(0xFFC2410C)
                    room.category == GisIndoorRoomCategory.CLASSROOM -> Color(0xFF3B82F6)
                    room.category == GisIndoorRoomCategory.LABORATORY -> Color(0xFF10B981)
                    room.category == GisIndoorRoomCategory.OFFICE -> Color(0xFFF59E0B)
                    room.category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> Color(0xFF7C3AED)
                    room.category == GisIndoorRoomCategory.RESTROOM -> Color(0xFF0891B2)
                    else -> Color(0xFF94A3B8)
                }
            }

            drawPath(
                path = roomPath,
                color = baseFill.copy(alpha = (if (isSelected) 0.92f else 0.90f) * alphaMul)
            )
            drawPath(
                path = roomPath,
                color = baseBorder.copy(alpha = alphaMul),
                style = Stroke(
                    width = if (isSelected) 4.5f else 2.0f,
                    join = StrokeJoin.Round
                )
            )
        }

        // 4. 若选中了目标房间，自动绘制从本层最近楼梯/电梯口到目标房间的室内步行引导虚线
        if (selectedRoom != null && nearestStairOrElevator != null && selectedRoom.id != nearestStairOrElevator.id) {
            val startPt = wgsToScreen(nearestStairOrElevator.centerLat, nearestStairOrElevator.centerLon)
            val endPt = wgsToScreen(selectedRoom.centerLat, selectedRoom.centerLon)
            drawLine(
                color = if (isDarkPlan) Color(0xFF0C121B) else Color.White,
                start = startPt,
                end = endPt,
                strokeWidth = 9f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = if (isDarkPlan) Color(0xFF38BDF8) else Color(0xFF0284C7),
                start = startPt,
                end = endPt,
                strokeWidth = 5f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            )
            val segLen = hypot((endPt.x - startPt.x).toDouble(), (endPt.y - startPt.y).toDouble()).toFloat()
            if (segLen > 45f) {
                val angle = atan2((endPt.y - startPt.y).toDouble(), (endPt.x - startPt.x).toDouble())
                val midX = (startPt.x + endPt.x) / 2f
                val midY = (startPt.y + endPt.y) / 2f
                val wing = 9f
                val arrowColor = if (isDarkPlan) Color(0xFF38BDF8) else Color(0xFF0284C7)
                drawLine(
                    color = arrowColor,
                    start = Offset(
                        midX - (wing * cos(angle - PI / 4)).toFloat(),
                        midY - (wing * sin(angle - PI / 4)).toFloat()
                    ),
                    end = Offset(midX, midY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = arrowColor,
                    start = Offset(
                        midX - (wing * cos(angle + PI / 4)).toFloat(),
                        midY - (wing * sin(angle + PI / 4)).toFloat()
                    ),
                    end = Offset(midX, midY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            drawCircle(color = Color.White, radius = 9f, center = startPt)
            drawCircle(color = Color(0xFF7C3AED), radius = 6f, center = startPt)
        }

        // 5. 严格防碰撞的房间门牌号标注（仅当文字能放入房间多边形内部且不与已有标签重叠时才绘制）
        val placedRects = mutableListOf<RectF>()
        roomLabelPaint.textSize = when {
            zoom >= 2.2f -> 12.sp.toPx()
            zoom >= 1.4f -> 10.5.sp.toPx()
            else -> 9.5.sp.toPx()
        }

        for (room in rooms) {
            if (room.name.isBlank() || room.id == selectedRoom?.id) continue
            val isDimmed = highlightCategory != null && room.category != highlightCategory
            if (isDimmed) continue

            val pts = room.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
            if (pts.size < 3) continue
            val minX = pts.minOf { it.x }
            val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }
            val maxY = pts.maxOf { it.y }
            val polyW = maxX - minX
            val polyH = maxY - minY

            val tw = roomLabelPaint.measureText(room.name)
            val th = roomLabelPaint.textSize
            // 如果房间在当前缩放级别下太窄放不下文字，则自动隐藏文字，放大后再显现，绝不糊成一团
            if (polyW < tw * 0.88f || polyH < th * 1.15f) continue

            val center = wgsToScreen(room.centerLat, room.centerLon)
            val candidateRect = RectF(
                center.x - tw / 2f - 4f,
                center.y - th / 2f - 3f,
                center.x + tw / 2f + 4f,
                center.y + th / 2f + 3f
            )
            if (placedRects.any { RectF.intersects(it, candidateRect) }) continue
            placedRects.add(candidateRect)

            drawContext.canvas.nativeCanvas.drawText(
                room.name,
                center.x - tw / 2f,
                center.y + th * 0.34f,
                roomLabelPaint
            )
        }

        // 6. 最高优先级：绘制当前选中房间的醒目悬浮气泡与定位锚点
        if (selectedRoom != null) {
            val center = wgsToScreen(selectedRoom.centerLat, selectedRoom.centerLon)
            drawCircle(
                color = Color(0x44F97316),
                radius = 28f,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 9f,
                center = center
            )
            drawCircle(
                color = Color(0xFFEA580C),
                radius = 6f,
                center = center
            )

            val calloutText = "★ ${selectedRoom.name} (${selectedRoom.floorLabel})"
            calloutTextPaint.textSize = 11.5.sp.toPx()
            val tw = calloutTextPaint.measureText(calloutText)
            val rectLeft = center.x - tw / 2f - 12f
            val rectTop = center.y - 44f
            val rectRight = center.x + tw / 2f + 12f
            val rectBottom = center.y - 14f
            drawContext.canvas.nativeCanvas.drawRoundRect(
                rectLeft,
                rectTop,
                rectRight,
                rectBottom,
                14f,
                14f,
                calloutBgPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                calloutText,
                center.x - tw / 2f,
                center.y - 24f,
                calloutTextPaint
            )
        }

        // 7. 左下角绘制真实米制比例尺与指北标识
        val scaleBarColor = if (isDarkPlan) Color(0xFF94A3B8) else Color(0xFF475569)
        scaleBarTextPaint.textSize = 10.sp.toPx()
        val barLeft = 20.dp.toPx()
        val barY = size.height - 196.dp.toPx()
        drawLine(
            color = scaleBarColor,
            start = Offset(barLeft, barY),
            end = Offset(barLeft + gridStepPx, barY),
            strokeWidth = 3f
        )
        drawLine(
            color = scaleBarColor,
            start = Offset(barLeft, barY - 6f),
            end = Offset(barLeft, barY + 6f),
            strokeWidth = 3f
        )
        drawLine(
            color = scaleBarColor,
            start = Offset(barLeft + gridStepPx, barY - 6f),
            end = Offset(barLeft + gridStepPx, barY + 6f),
            strokeWidth = 3f
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${gridStepMeters.roundToInt()}米 · 上北下南",
            barLeft,
            barY - 10f,
            scaleBarTextPaint
        )
    }
}
