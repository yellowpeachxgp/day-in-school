package cn.edu.haut.campus.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.model.Building
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.roundToInt

data class UserGeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 12f,
    val bearingDegrees: Float? = null,
    val provider: String = "FUSED",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val isSimulatedCampusAnchor: Boolean = false
)

data class BuildingNavigationPlan(
    val origin: UserGeoLocation,
    val destination: Building,
    val roomNumberHint: String? = null,
    val distanceMeters: Float,
    val bearingDegrees: Float,
    val compassDirection: String,
    val estimatedWalkMinutes: Int,
    val isUserInsideCampus: Boolean,
    val nearestBuildingToUser: Building? = null,
    val waypointBuilding: Building? = null,
    val turnByTurnSteps: List<String>
) {
    val formattedDistance: String
        get() = if (distanceMeters >= 1000f) {
            String.format("%.2f km", distanceMeters / 1000f)
        } else {
            "${distanceMeters.roundToInt()} m"
        }
}

object CampusMapNavigator {

    // 河南工业大学莲花街校区中心点（钟楼广场）与南大门基准坐标
    const val HAUT_CENTER_LAT = 34.8295
    const val HAUT_CENTER_LNG = 113.5495
    const val HAUT_SOUTH_GATE_LAT = 34.8266
    const val HAUT_SOUTH_GATE_LNG = 113.5495

    val REQUIRED_LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * 实时监听设备端 GPS / Fused / Network 定位坐标流（需已授予 ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION）。
     */
    @SuppressLint("MissingPermission")
    fun observeUserLocation(context: Context): Flow<UserGeoLocation?> = callbackFlow {
        if (!hasLocationPermission(context)) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val candidateProviders = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.distinct()

        // 1. 先尝试立即发射最近缓存坐标
        val bestLastKnown = candidateProviders
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (bestLastKnown != null) {
            trySend(bestLastKnown.toUserGeoLocation())
        }

        val listener = LocationListener { location ->
            trySend(location.toUserGeoLocation())
        }

        // 2. 注册持续位置更新（每 2 秒或移动 2 米刷新一次）
        for (provider in candidateProviders) {
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        2000L,
                        2f,
                        listener,
                        Looper.getMainLooper()
                    )
                    // Android 11+ (API 30+) 原生即时单次拉取最新坐标
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor
                    ) { freshLoc ->
                        if (freshLoc != null) {
                            trySend(freshLoc.toUserGeoLocation())
                        }
                    }
                }
            }
        }

        awaitClose {
            runCatching { locationManager.removeUpdates(listener) }
        }
    }

    fun simulatedSouthGateAnchor(): UserGeoLocation = UserGeoLocation(
        latitude = HAUT_SOUTH_GATE_LAT,
        longitude = HAUT_SOUTH_GATE_LNG,
        accuracyMeters = 5f,
        provider = "CAMPUS_ANCHOR",
        isSimulatedCampusAnchor = true
    )

    /**
     * 计算 App 内实时步行路线、距离、方位角与分步引导指令。
     */
    fun buildInAppNavigationPlan(
        origin: UserGeoLocation,
        destination: Building,
        allBuildings: List<Building>,
        roomNumberHint: String? = null
    ): BuildingNavigationPlan {
        val distResults = FloatArray(2)
        Location.distanceBetween(
            origin.latitude,
            origin.longitude,
            destination.latitude,
            destination.longitude,
            distResults
        )
        val distanceMeters = distResults[0].coerceAtLeast(1f)
        val rawBearing = distResults[1]
        val bearingDegrees = ((rawBearing % 360f) + 360f) % 360f
        val compass = bearingToCompassLabel(bearingDegrees)

        // 判断用户当前是否在莲花街校区 2.2 公里范围内
        val centerCheck = FloatArray(1)
        Location.distanceBetween(
            origin.latitude,
            origin.longitude,
            HAUT_CENTER_LAT,
            HAUT_CENTER_LNG,
            centerCheck
        )
        val isUserInsideCampus = centerCheck[0] <= 2200f

        // 计算离用户当前位置最近的校内建筑地标
        val nearestBuilding = allBuildings
            .filter { it.id != destination.id }
            .minByOrNull { b ->
                val r = FloatArray(1)
                Location.distanceBetween(origin.latitude, origin.longitude, b.latitude, b.longitude, r)
                r[0]
            }

        // 计算途经地标（若距离 > 120 米，寻找位于起点与终点连线中段附近的校内地标）
        val midLat = (origin.latitude + destination.latitude) / 2.0
        val midLng = (origin.longitude + destination.longitude) / 2.0
        val waypoint = if (distanceMeters > 120f && isUserInsideCampus) {
            allBuildings
                .filter { it.id != destination.id && it.id != nearestBuilding?.id }
                .minByOrNull { b ->
                    val r = FloatArray(1)
                    Location.distanceBetween(midLat, midLng, b.latitude, b.longitude, r)
                    r[0]
                }
        } else null

        val walkMinutes = (distanceMeters / 75f).roundToInt().coerceAtLeast(1)

        val steps = buildList {
            if (origin.isSimulatedCampusAnchor) {
                add("起点：莲花街校区南大门基准点（当前使用校内起点预览）")
            } else if (isUserInsideCampus) {
                val nearText = nearestBuilding?.let { "，靠近「${it.shortName}」" } ?: ""
                add("起点：你的实时定位 (${String.format("%.5f, %.5f", origin.latitude, origin.longitude)} · 精度 ±${origin.accuracyMeters.roundToInt()}m$nearText)")
            } else {
                add("起点：你的当前设备位置（距莲花街校区约 ${String.format("%.1f km", centerCheck[0] / 1000f)}）")
            }

            if (waypoint != null) {
                add("沿校园主干道朝【$compass (${bearingDegrees.roundToInt()}°)】方向前行，途经「${waypoint.name}」")
            } else {
                add("朝【$compass (${bearingDegrees.roundToInt()}°)】方向直行约 ${if (distanceMeters >= 1000f) String.format("%.2f km", distanceMeters / 1000f) else "${distanceMeters.roundToInt()} 米"}（步行约 $walkMinutes 分钟）")
            }

            val entranceText = destination.entrances.firstOrNull() ?: "主入口"
            add("抵达目的地【${destination.name}】，推荐由「$entranceText」进入")

            if (!roomNumberHint.isNullOrBlank()) {
                val floorHint = roomNumberHint.firstOrNull { it.isDigit() }
                if (floorHint != null) {
                    add("进入楼宇后前往 ${floorHint} 层 → $roomNumberHint 教室")
                } else {
                    add("进入楼宇后根据指示牌前往 $roomNumberHint 教室")
                }
            }
        }

        return BuildingNavigationPlan(
            origin = origin,
            destination = destination,
            roomNumberHint = roomNumberHint,
            distanceMeters = distanceMeters,
            bearingDegrees = bearingDegrees,
            compassDirection = compass,
            estimatedWalkMinutes = walkMinutes,
            isUserInsideCampus = isUserInsideCampus,
            nearestBuildingToUser = nearestBuilding,
            waypointBuilding = waypoint,
            turnByTurnSteps = steps
        )
    }

    fun distanceBetweenMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val res = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, res)
        return res[0]
    }

    private fun bearingToCompassLabel(deg: Float): String = when {
        deg >= 337.5f || deg < 22.5f -> "正北"
        deg < 67.5f -> "东北"
        deg < 112.5f -> "正东"
        deg < 157.5f -> "东南"
        deg < 202.5f -> "正南"
        deg < 247.5f -> "西南"
        deg < 292.5f -> "正西"
        else -> "西北"
    }

    private fun Location.toUserGeoLocation(): UserGeoLocation = UserGeoLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else 15f,
        bearingDegrees = if (hasBearing()) bearing else null,
        provider = provider ?: "GPS",
        updatedAtMillis = time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        isSimulatedCampusAnchor = false
    )

    /**
     * 备选：调起外部地图步行导航（高德/系统地图）。
     */
    fun launchWalkingNavigation(context: Context, building: Building) {
        val label = Uri.encode("河南工业大学莲花街校区 ${building.name}")
        val geoUri = Uri.parse(
            "geo:${building.latitude},${building.longitude}?q=${building.latitude},${building.longitude}($label)"
        )
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val packageManager = context.packageManager
        if (geoIntent.resolveActivity(packageManager) != null) {
            context.startActivity(geoIntent)
            return
        }

        val webUri = Uri.parse(
            "https://uri.amap.com/marker?position=${building.longitude},${building.latitude}&name=$label&callnative=1"
        )
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            Toast.makeText(
                context,
                "目标建筑：${building.name} (${building.latitude}, ${building.longitude})",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
