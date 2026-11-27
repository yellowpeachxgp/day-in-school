package cn.edu.haut.campus.feature.map.ui.map

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.designsystem.XPeachBrandLight
import cn.edu.haut.campus.core.designsystem.XPeachOk
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.CampusMapViewportMath
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisCampusVectorDataset
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisLanduseType
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.feature.map.tiles.CampusTileCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 一级校园室外地图画布（纯粹聚焦室外导航、实测建筑轮廓与防重叠地标标注；室内平面图已独立至二级页面）。
 */
@Composable
internal fun RealCampusTileMap(
    buildings: List<Building>,
    selectedBuilding: Building?,
    userLocation: UserGeoLocation?,
    deviceHeadingDegrees: Float?,
    navigationPlan: InAppNavigationPlan?,
    centerLatGcj: Double,
    centerLonGcj: Double,
    zoom: Float,
    styleMode: GisMapStyleMode = GisMapStyleMode.OFFICIAL_VECTOR_HYBRID,
    gisDataset: GisCampusVectorDataset? = null,
    activeBuildingFootprint: GisBuildingFootprint? = null,
    selectedIndoorRoom: GisIndoorRoom? = null,
    campusPolygonVertices: List<GeoPoint> = emptyList(),
    drawingPolygonVertices: List<GeoPoint> = emptyList(),
    isDrawingZoneMode: Boolean = false,
    onTouchActiveChange: (Boolean) -> Unit = {},
    onTapMapWgs84: ((Double, Double) -> Unit)? = null,
    onViewportChange: (Double, Double, Float) -> Unit,
    onSelectBuilding: (Building) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val scope = rememberCoroutineScope()
    val isDarkMap = XPeachTheme.isDark
    var tileLoadGeneration by remember { mutableIntStateOf(0) }
    var cooldownScheduleEpoch by remember { mutableIntStateOf(0) }
    var canvasWidthPx by remember { mutableFloatStateOf(1080f) }
    var canvasHeightPx by remember { mutableFloatStateOf(1920f) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (CampusTileCache.onForegroundResume()) {
                    tileLoadGeneration++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 有生命周期的最早冷却截止唤醒：静止视口下瓦片加载失败进入冷却后，到期自动唤醒重绘并重试（无逐帧风暴）
    LaunchedEffect(styleMode, tileLoadGeneration, cooldownScheduleEpoch) {
        if (CampusTileCache.awaitNextCooldownBatchExpiry { waitMs ->
                kotlinx.coroutines.delay(waitMs + 16L)
            }
        ) {
            tileLoadGeneration++
        }
    }

    val tileZ = CampusMapViewportMath.tileZoomFor(
        zoom = zoom,
        official25d = styleMode == GisMapStyleMode.OFFICIAL_25D_2026
    )
    val scaleFactor = 2.0.pow((zoom - tileZ).toDouble()).toFloat()
    val baseTileRenderPx = 256f * context.resources.displayMetrics.density * 0.90f * scaleFactor

    fun latLonToTileXY(lat: Double, lon: Double, z: Int): Pair<Double, Double> =
        CampusMapViewportMath.latLonToTileXY(lat, lon, z)

    fun tileXYToLatLon(tx: Double, ty: Double, z: Int): Pair<Double, Double> =
        CampusMapViewportMath.tileXYToLatLon(tx, ty, z)

    val (centerTileX, centerTileY) = remember(centerLatGcj, centerLonGcj, tileZ) {
        latLonToTileXY(centerLatGcj, centerLonGcj, tileZ)
    }

    // 使用 rememberUpdatedState 保持 pointerInput(Unit) 手势协程永不因坐标更新而中断重启
    val currentCenterLat by rememberUpdatedState(centerLatGcj)
    val currentCenterLon by rememberUpdatedState(centerLonGcj)
    val currentZoom by rememberUpdatedState(zoom)
    val currentTileZ by rememberUpdatedState(tileZ)
    val currentBaseTilePx by rememberUpdatedState(baseTileRenderPx)
    val currentBuildings by rememberUpdatedState(buildings)
    val currentGisDataset by rememberUpdatedState(gisDataset)
    val currentIsDrawingMode by rememberUpdatedState(isDrawingZoneMode)
    val currentOnTapMapWgs84 by rememberUpdatedState(onTapMapWgs84)
    val currentOnTouchActiveChange by rememberUpdatedState(onTouchActiveChange)
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val currentOnSelectBuilding by rememberUpdatedState(onSelectBuilding)

    fun gcjToScreenDynamic(latGcj: Double, lonGcj: Double): Offset {
        val (cTx, cTy) = latLonToTileXY(currentCenterLat, currentCenterLon, currentTileZ)
        val (tx, ty) = latLonToTileXY(latGcj, lonGcj, currentTileZ)
        val sx = (canvasWidthPx / 2f) + ((tx - cTx).toFloat() * currentBaseTilePx)
        val sy = (canvasHeightPx / 2f) + ((ty - cTy).toFloat() * currentBaseTilePx)
        return Offset(sx, sy)
    }

    fun wgsToScreenDynamic(latWgs: Double, lonWgs: Double): Offset {
        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(latWgs, lonWgs)
        return gcjToScreenDynamic(glat, glon)
    }

    val boldSansTypeface = remember { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    val labelBgPaint = remember(isDarkMap) {
        Paint().apply {
            isAntiAlias = true
            color = if (isDarkMap) {
                android.graphics.Color.argb(242, 30, 39, 52)
            } else {
                android.graphics.Color.argb(244, 255, 255, 255)
            }
            style = Paint.Style.FILL
        }
    }
    val targetBgPaint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(2, 132, 199)
            style = Paint.Style.FILL
        }
    }
    val labelBorderPaint = remember(isDarkMap) {
        Paint().apply {
            isAntiAlias = true
            color = if (isDarkMap) {
                android.graphics.Color.argb(180, 71, 85, 105)
            } else {
                android.graphics.Color.argb(120, 148, 163, 184)
            }
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
    }
    val labelTextPaint = remember(isDarkMap, boldSansTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = boldSansTypeface
            color = if (isDarkMap) {
                android.graphics.Color.rgb(241, 245, 249)
            } else {
                android.graphics.Color.rgb(17, 24, 39)
            }
        }
    }
    val targetTextPaint = remember(boldSansTypeface) {
        Paint().apply {
            isAntiAlias = true
            typeface = boldSansTypeface
            color = android.graphics.Color.WHITE
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val anyPressed = event.changes.any { it.pressed }
                        hostView.parent?.requestDisallowInterceptTouchEvent(anyPressed)
                        currentOnTouchActiveChange(anyPressed)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    if (currentIsDrawingMode && currentOnTapMapWgs84 != null) {
                        val (cTx, cTy) = latLonToTileXY(currentCenterLat, currentCenterLon, currentTileZ)
                        val tapTx = cTx + (tapOffset.x - canvasWidthPx / 2f) / currentBaseTilePx
                        val tapTy = cTy + (tapOffset.y - canvasHeightPx / 2f) / currentBaseTilePx
                        val (tapGcjLat, tapGcjLon) = tileXYToLatLon(tapTx, tapTy, currentTileZ)
                        val (wgsLat, wgsLon) = CampusMapNavigator.gcj02ToWgs84(tapGcjLat, tapGcjLon)
                        currentOnTapMapWgs84?.invoke(wgsLat, wgsLon)
                        return@detectTapGestures
                    }

                    // 1. 检测是否点击了官方 GIS 建筑轮廓多边形
                    val dataset = currentGisDataset
                    if (dataset != null) {
                        val hitFootprint = dataset.buildings.firstOrNull { fp ->
                            val screenRing = fp.outerRing.map { wgsToScreenDynamic(it.latitude, it.longitude) }
                            isScreenPointInRing(tapOffset, screenRing)
                        }
                        if (hitFootprint != null) {
                            val matchedB = currentBuildings.firstOrNull { it.id == hitFootprint.matchedBuildingId }
                                ?: currentBuildings.minByOrNull {
                                    CampusMapNavigator.calculateDistanceMeters(
                                        hitFootprint.centerLat,
                                        hitFootprint.centerLon,
                                        it.latitude,
                                        it.longitude
                                    )
                                }
                            if (matchedB != null) {
                                currentOnSelectBuilding(matchedB)
                                return@detectTapGestures
                            }
                        }
                    }

                    // 2. 检测楼宇地标圆点命中
                    val hit = currentBuildings.minByOrNull { b ->
                        val pt = wgsToScreenDynamic(b.latitude, b.longitude)
                        hypot((pt.x - tapOffset.x).toDouble(), (pt.y - tapOffset.y).toDouble())
                    }
                    if (hit != null) {
                        val pt = wgsToScreenDynamic(hit.latitude, hit.longitude)
                        if (hypot((pt.x - tapOffset.x).toDouble(), (pt.y - tapOffset.y).toDouble()) <= 96.0) {
                            currentOnSelectBuilding(hit)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pastTouchSlop = false
                    var panAccum = Offset.Zero
                    val touchSlop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val pressedCount = event.changes.count { it.pressed }
                            val gestureZoom = CampusMapViewportMath.sanitizeGestureZoom(event.calculateZoom())
                            val pan = event.calculatePan()
                            if (!pastTouchSlop) {
                                panAccum += pan
                                if (pressedCount >= 2 || panAccum.getDistance() > touchSlop) {
                                    pastTouchSlop = true
                                }
                            }
                            if (pastTouchSlop) {
                                val rawCentroid = event.calculateCentroid(useCurrent = false)
                                val centroid = if (rawCentroid != Offset.Unspecified) {
                                    rawCentroid
                                } else {
                                    Offset(canvasWidthPx / 2f, canvasHeightPx / 2f)
                                }
                                if (gestureZoom != 1f || pan != Offset.Zero) {
                                    val viewport = CampusMapViewportMath.viewportAfterGesture(
                                        centerLatGcj = currentCenterLat,
                                        centerLonGcj = currentCenterLon,
                                        currentZoom = currentZoom,
                                        tileZ = currentTileZ,
                                        baseTileRenderPx = currentBaseTilePx,
                                        canvasWidthPx = canvasWidthPx,
                                        canvasHeightPx = canvasHeightPx,
                                        centroidX = centroid.x,
                                        centroidY = centroid.y,
                                        panX = pan.x,
                                        panY = pan.y,
                                        rawGestureZoom = gestureZoom
                                    )
                                    currentOnViewportChange(
                                        viewport.centerLatGcj,
                                        viewport.centerLonGcj,
                                        viewport.zoom
                                    )
                                }
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val gen = tileLoadGeneration
        if (gen < 0) return@Canvas

        canvasWidthPx = size.width
        canvasHeightPx = size.height

        fun gcjToScreen(latGcj: Double, lonGcj: Double): Offset {
            val (tx, ty) = latLonToTileXY(latGcj, lonGcj, tileZ)
            val sx = (size.width / 2f) + ((tx - centerTileX).toFloat() * baseTileRenderPx)
            val sy = (size.height / 2f) + ((ty - centerTileY).toFloat() * baseTileRenderPx)
            return Offset(sx, sy)
        }

        fun wgsToScreen(latWgs: Double, lonWgs: Double): Offset {
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(latWgs, lonWgs)
            return gcjToScreen(glat, glon)
        }

        // 1. 地图底色
        drawRect(color = if (isDarkMap) Color(0xFF0F151E) else Color(0xFFF2EFE9))

        // 2. 绘制覆盖视口的周边街道/校园瓦片
        val halfTilesW = (size.width / 2f) / baseTileRenderPx + 1f
        val halfTilesH = (size.height / 2f) / baseTileRenderPx + 1f
        val minTx = floor(centerTileX - halfTilesW).toInt()
        val maxTx = ceil(centerTileX + halfTilesW).toInt()
        val minTy = floor(centerTileY - halfTilesH).toInt()
        val maxTy = ceil(centerTileY + halfTilesH).toInt()

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val leftPx = (size.width / 2f) + ((tx - centerTileX).toFloat() * baseTileRenderPx)
                val topPx = (size.height / 2f) + ((ty - centerTileY).toFloat() * baseTileRenderPx)
                val lookup = CampusTileCache.lookupForRender(styleMode, tileZ, tx, ty)
                val bmp = lookup.tile
                if (bmp != null) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(leftPx.roundToInt(), topPx.roundToInt()),
                        dstSize = IntSize(ceil(baseTileRenderPx).toInt(), ceil(baseTileRenderPx).toInt())
                    )
                }
                if (lookup.shouldTriggerTargetLoad) {
                    scope.launch {
                        CampusTileCache.loadTileIfNeeded(
                            cacheDir = context.cacheDir,
                            styleMode = styleMode,
                            z = tileZ,
                            x = tx,
                            y = ty,
                            onLoaded = { tileLoadGeneration++ },
                            onCooldownScheduled = { cooldownScheduleEpoch++ }
                        )
                    }
                }
            }
        }

        if (isDarkMap) {
            drawRect(color = Color(0x6E0B1018))
        }

        // 2.2 绘制河南工业大学官方 GIS 室外矢量底图（仅莲花街校区具备官方 92 栋矢量轮廓；嵩山路校区严禁用纯色底板遮蔽街景底图！）
        if (styleMode == GisMapStyleMode.OFFICIAL_VECTOR_HYBRID && gisDataset != null) {
            if (campusPolygonVertices.size >= 3 && campusPolygonVertices.first().latitude > 34.80) {
                val campusMaskPath = Path().apply {
                    campusPolygonVertices.forEachIndexed { idx, v ->
                        val p = wgsToScreen(v.latitude, v.longitude)
                        if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }
                // 纯净园区底板，彻底隔绝底层第三方街景瓦片的重影文字与重影道路
                drawPath(
                    path = campusMaskPath,
                    color = if (isDarkMap) Color(0xFF131B26) else Color(0xFFF5F6F8)
                )
            }

            // (a) 水系、绿地与运动场多边形
            for (land in gisDataset.landusePolygons) {
                if (zoom < 15.2f && land.type != GisLanduseType.WATER) continue
                val pts = land.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
                if (pts.size < 3) continue
                val minX = pts.minOf { it.x }
                val maxX = pts.maxOf { it.x }
                val minY = pts.minOf { it.y }
                val maxY = pts.maxOf { it.y }
                if (maxX < -40f || minX > size.width + 40f || maxY < -40f || minY > size.height + 40f) continue

                val path = Path().apply {
                    pts.forEachIndexed { idx, p ->
                        if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }
                when (land.type) {
                    GisLanduseType.WATER -> {
                        drawPath(path = path, color = if (isDarkMap) Color(0xFF0E2E47) else Color(0xFFBAE6FD))
                        drawPath(
                            path = path,
                            color = if (isDarkMap) Color(0xFF0284C7) else Color(0xFF38BDF8),
                            style = Stroke(width = 2f)
                        )
                    }
                    GisLanduseType.GREENERY -> {
                        drawPath(path = path, color = if (isDarkMap) Color(0xFF112920) else Color(0xFFDCFCE7))
                    }
                    GisLanduseType.SPORTS_GROUND -> {
                        drawPath(path = path, color = if (isDarkMap) Color(0xFF2D1F18) else Color(0xFFFFEDD5))
                        drawPath(
                            path = path,
                            color = if (isDarkMap) Color(0xFFD97706) else Color(0xFFFB923C),
                            style = Stroke(width = 1.6f)
                        )
                    }
                    GisLanduseType.PLAZA -> {
                        drawPath(path = path, color = if (isDarkMap) Color(0xFF192230) else Color(0xFFEFF2F6))
                    }
                }
            }

            // (b) 官方校园步行与行车道路网
            if (zoom >= 15.3f) {
                for (road in gisDataset.roadLines) {
                    val pts = road.points.map { wgsToScreen(it.latitude, it.longitude) }
                    if (pts.size < 2) continue
                    val path = Path().apply {
                        pts.forEachIndexed { idx, p ->
                            if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                        }
                    }
                    val strokeW = if (road.highwayType == "footway") 3.5f else 6.5f
                    drawPath(
                        path = path,
                        color = if (isDarkMap) Color(0xFF0B1017) else Color(0xFFCBD5E1),
                        style = Stroke(width = strokeW + 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = path,
                        color = if (isDarkMap) Color(0xFF283446) else Color.White,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // (c) 官方 92 栋实测建筑外轮廓多边形（带微立体阴影，不再把室内小房间画在室外楼体上）
            for (fp in gisDataset.buildings) {
                val pts = fp.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
                if (pts.size < 3) continue
                val minX = pts.minOf { it.x }
                val maxX = pts.maxOf { it.x }
                val minY = pts.minOf { it.y }
                val maxY = pts.maxOf { it.y }
                if (maxX < -60f || minX > size.width + 60f || maxY < -60f || minY > size.height + 60f) continue

                val isSelectedFp = (activeBuildingFootprint?.featureId == fp.featureId) ||
                    (selectedBuilding != null && fp.matchedBuildingId == selectedBuilding.id)

                val shadowPath = Path().apply {
                    pts.forEachIndexed { idx, p ->
                        if (idx == 0) moveTo(p.x + 2.5f, p.y + 3.5f) else lineTo(p.x + 2.5f, p.y + 3.5f)
                    }
                    close()
                }
                val polyPath = Path().apply {
                    pts.forEachIndexed { idx, p ->
                        if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path = shadowPath, color = if (isDarkMap) Color(0x45000000) else Color(0x1E0F172A))
                drawPath(
                    path = polyPath,
                    color = if (isSelectedFp) {
                        if (isDarkMap) Color(0xFF15385C) else Color(0xFFBAE6FD)
                    } else {
                        if (isDarkMap) Color(0xFF222D3D) else Color(0xFFE2E8F0)
                    }
                )
                drawPath(
                    path = polyPath,
                    color = if (isSelectedFp) {
                        if (isDarkMap) Color(0xFF38BDF8) else Color(0xFF0284C7)
                    } else {
                        if (isDarkMap) Color(0xFF475569) else Color(0xFF94A3B8)
                    },
                    style = Stroke(width = if (isSelectedFp) 3.8f else 1.6f, join = StrokeJoin.Round)
                )
            }
        } else if (activeBuildingFootprint != null && activeBuildingFootprint.outerRing.size >= 3) {
            // 在标准街景或 2.5D 模式下，仅高亮当前选中建筑轮廓，不叠加冗余矢量底图
            val pts = activeBuildingFootprint.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
            val polyPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path = polyPath, color = Color(0x4438BDF8))
            drawPath(
                path = polyPath,
                color = Color(0xFF0284C7),
                style = Stroke(width = 3.6f, join = StrokeJoin.Round)
            )
        }

        // 2.5 绘制校区电子区划（多边形边界围栏）及用户正在点选圈定的电子区划顶点
        val activePoly = if (isDrawingZoneMode && drawingPolygonVertices.isNotEmpty()) {
            drawingPolygonVertices
        } else {
            campusPolygonVertices
        }
        if (activePoly.size >= 2) {
            val polyPts = activePoly.map { v -> wgsToScreen(v.latitude, v.longitude) }
            val polyPath = Path().apply {
                polyPts.forEachIndexed { idx, offset ->
                    if (idx == 0) moveTo(offset.x, offset.y)
                    else lineTo(offset.x, offset.y)
                }
                if (polyPts.size >= 3) close()
            }
            if (polyPts.size >= 3 && isDrawingZoneMode) {
                drawPath(
                    path = polyPath,
                    color = Color(0x280EA5E9)
                )
            }
            drawPath(
                path = polyPath,
                color = if (isDrawingZoneMode) Color(0xFF0284C7) else Color(0x55053968),
                style = Stroke(
                    width = if (isDrawingZoneMode) 5f else 2.2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                )
            )
            if (isDrawingZoneMode) {
                polyPts.forEach { pt ->
                    drawCircle(color = Color.White, radius = 9f, center = pt)
                    drawCircle(color = Color(0xFF0284C7), radius = 6f, center = pt)
                }
            }
        } else if (isDrawingZoneMode && drawingPolygonVertices.size == 1) {
            val v = drawingPolygonVertices.first()
            val pt = wgsToScreen(v.latitude, v.longitude)
            drawCircle(color = Color.White, radius = 10f, center = pt)
            drawCircle(color = Color(0xFF0284C7), radius = 7f, center = pt)
        }

        // 3. 绘制校园路网动态规划路径（双层描边道路折线 + 沿途方向箭头 + 转向节点）
        if (navigationPlan != null && navigationPlan.routePoints.size >= 2) {
            val screenPts = navigationPlan.routePoints.map { pt ->
                wgsToScreen(pt.latitude, pt.longitude)
            }

            if (screenPts.size >= 2) {
                drawLine(
                    color = XPeachBrandLight.copy(alpha = 0.65f),
                    start = screenPts[0],
                    end = screenPts[1],
                    strokeWidth = 6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            }

            val routePath = Path().apply {
                screenPts.forEachIndexed { idx, offset ->
                    if (idx == 0) moveTo(offset.x, offset.y)
                    else lineTo(offset.x, offset.y)
                }
            }
            drawPath(
                path = routePath,
                color = Color(0xFF004B8D),
                style = Stroke(
                    width = 14f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = routePath,
                color = Color(0xFF0EA5E9),
                style = Stroke(
                    width = 9f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            for (i in 0 until screenPts.size - 1) {
                val p1 = screenPts[i]
                val p2 = screenPts[i + 1]
                val segLen = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()).toFloat()
                if (segLen >= 38f) {
                    val angle = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
                    val count = (segLen / 75f).toInt().coerceAtLeast(1)
                    for (k in 1..count) {
                        val ratio = k.toFloat() / (count + 1).toFloat()
                        val cx = p1.x + (p2.x - p1.x) * ratio
                        val cy = p1.y + (p2.y - p1.y) * ratio
                        val wing = 7.5f
                        val lx = cx - (wing * cos(angle - PI / 4)).toFloat()
                        val ly = cy - (wing * sin(angle - PI / 4)).toFloat()
                        val rx = cx - (wing * cos(angle + PI / 4)).toFloat()
                        val ry = cy - (wing * sin(angle + PI / 4)).toFloat()
                        drawLine(
                            color = Color.White,
                            start = Offset(lx, ly),
                            end = Offset(cx, cy),
                            strokeWidth = 2.8f,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(rx, ry),
                            end = Offset(cx, cy),
                            strokeWidth = 2.8f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            for (i in 1 until screenPts.size - 1) {
                drawCircle(
                    color = Color.White,
                    radius = 5.5f,
                    center = screenPts[i]
                )
                drawCircle(
                    color = Color(0xFF004B8D),
                    radius = 3.2f,
                    center = screenPts[i]
                )
            }
        }

        // 4. 绘制各校内楼宇锚点与严格防碰撞深浅色自适应标签（优先保证选中建筑完整显示，重叠标签自动退化为小圆点）
        val placedLabelRects = mutableListOf<RectF>()
        labelTextPaint.textSize = 11.sp.toPx()
        targetTextPaint.textSize = 11.5.sp.toPx()

        // 将当前选中的目标楼宇排在第一位优先占位，其余楼宇按屏幕碰撞检测决定是否展示文字胶囊
        val orderedBuildings = buildList {
            selectedBuilding?.let { target ->
                buildings.firstOrNull { it.id == target.id }?.let { add(it) }
            }
            buildings.forEach { b ->
                if (b.id != selectedBuilding?.id) add(b)
            }
        }

        for (b in orderedBuildings) {
            val pt = wgsToScreen(b.latitude, b.longitude)
            if (pt.x < -80f || pt.x > size.width + 80f || pt.y < -80f || pt.y > size.height + 80f) {
                continue
            }
            val isTarget = b.id == selectedBuilding?.id
            val roomSuffix = if (isTarget && selectedIndoorRoom != null) {
                " · ${selectedIndoorRoom.name}(${selectedIndoorRoom.floorLabel})"
            } else ""
            val text = if (isTarget) "终 · ${b.shortName}$roomSuffix" else b.shortName
            val paintToUse = if (isTarget) targetTextPaint else labelTextPaint
            val textW = paintToUse.measureText(text)
            val rectLeft = pt.x - textW / 2f - 10f
            val rectTop = pt.y - 46f
            val rectRight = pt.x + textW / 2f + 10f
            val rectBottom = pt.y - 16f

            val collisionBox = RectF(
                rectLeft - 8f,
                rectTop - 6f,
                rectRight + 8f,
                rectBottom + 18f
            )
            val canDrawLabel = isTarget || (zoom >= 15.8f && placedLabelRects.none { RectF.intersects(it, collisionBox) })

            if (isTarget) {
                drawCircle(
                    color = XPeachBrandLight.copy(alpha = 0.24f),
                    radius = 30f,
                    center = pt
                )
            }
            drawCircle(
                color = Color.White,
                radius = if (isTarget) 14f else if (canDrawLabel) 9.5f else 6.5f,
                center = pt
            )
            drawCircle(
                color = if (isTarget) XPeachBrandLight else Color(0xFF475569),
                radius = if (isTarget) 10f else if (canDrawLabel) 6.5f else 4.2f,
                center = pt
            )

            if (canDrawLabel) {
                placedLabelRects.add(collisionBox)
                if (isTarget) {
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        12f,
                        12f,
                        targetBgPaint
                    )
                } else {
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        10f,
                        10f,
                        labelBgPaint
                    )
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        10f,
                        10f,
                        labelBorderPaint
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    pt.x - textW / 2f,
                    pt.y - 24f,
                    paintToUse
                )
            }
        }

        // 5. 绘制用户当前位置蓝点 + 实时电子罗盘朝向扇形光束
        val originLatWgs = userLocation?.latitude ?: navigationPlan?.originLatitude
        val originLonWgs = userLocation?.longitude ?: navigationPlan?.originLongitude
        if (originLatWgs != null && originLonWgs != null) {
            val userPt = wgsToScreen(originLatWgs, originLonWgs)
            val headingDeg = deviceHeadingDegrees ?: navigationPlan?.bearingDegrees?.toFloat()

            if (headingDeg != null) {
                val canvasCenterAngle = headingDeg - 90f
                val coneRadius = 68f
                drawArc(
                    color = XPeachBrandLight.copy(alpha = 0.26f),
                    startAngle = canvasCenterAngle - 28f,
                    sweepAngle = 56f,
                    useCenter = true,
                    topLeft = Offset(userPt.x - coneRadius, userPt.y - coneRadius),
                    size = Size(coneRadius * 2f, coneRadius * 2f)
                )

                val rad = Math.toRadians(canvasCenterAngle.toDouble())
                val tip = Offset(
                    userPt.x + (cos(rad) * 24.0).toFloat(),
                    userPt.y + (sin(rad) * 24.0).toFloat()
                )
                val leftBase = Offset(
                    userPt.x + (cos(rad - 0.5) * 12.0).toFloat(),
                    userPt.y + (sin(rad - 0.5) * 12.0).toFloat()
                )
                val rightBase = Offset(
                    userPt.x + (cos(rad + 0.5) * 12.0).toFloat(),
                    userPt.y + (sin(rad + 0.5) * 12.0).toFloat()
                )
                val pointerPath = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(leftBase.x, leftBase.y)
                    lineTo(rightBase.x, rightBase.y)
                    close()
                }
                drawPath(path = pointerPath, color = XPeachBrandLight)
            }

            drawCircle(
                color = XPeachOk.copy(alpha = 0.22f),
                radius = 28f,
                center = userPt
            )
            drawCircle(
                color = Color.White,
                radius = 13f,
                center = userPt
            )
            drawCircle(
                color = if (userLocation != null) XPeachOk else XPeachBrandLight,
                radius = 9.5f,
                center = userPt
            )
        }
    }
}
