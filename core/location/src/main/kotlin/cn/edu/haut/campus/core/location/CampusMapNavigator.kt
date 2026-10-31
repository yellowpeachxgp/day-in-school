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
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.model.Building
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 实时设备端定位快照（WGS-84）
 */
data class UserGeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val bearingDegrees: Float?,
    val provider: String,
    val timestampMillis: Long,
    val isInsideLianhuajieCampus: Boolean,
    val distanceToCampusCenterMeters: Int
)

/**
 * 校内步行路线单步指引
 */
data class InAppNavigationStep(
    val stepIndex: Int,
    val title: String,
    val detail: String,
    val distanceMeters: Int
)

/**
 * 校内步行路线方案
 */
data class InAppNavigationPlan(
    val originLabel: String,
    val originLatitude: Double,
    val originLongitude: Double,
    val originIsLiveGps: Boolean,
    val destinationBuilding: Building,
    val rawClassroom: String?,
    val straightDistanceMeters: Int,
    val estimatedWalkDistanceMeters: Int,
    val estimatedWalkMinutes: Int,
    val bearingDegrees: Int,
    val compassDirectionLabel: String,
    val waypointBuilding: Building?,
    val steps: List<InAppNavigationStep>
)

/**
 * 河南工业大学莲花街校区定位、坐标系转换（WGS-84 <-> GCJ-02）与步行导航引擎。
 */
object CampusMapNavigator {

    // 河南工业大学莲花街校区真实中心点（WGS-84）
    const val CAMPUS_CENTER_LAT = 34.831200
    const val CAMPUS_CENTER_LNG = 113.544200
    private const val CAMPUS_GEOFENCE_RADIUS_METERS = 1600

    private const val EARTH_RADIUS_M = 6378245.0
    private const val EE = 0.00669342162296594323

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
     * 将 WGS-84 坐标转换为国内高德瓦片使用的 GCJ-02 坐标。
     */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((EARTH_RADIUS_M * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (EARTH_RADIUS_M / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.distinct()

        val lastKnown = candidateProviders
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (lastKnown != null) {
            trySend(lastKnown.toUserGeoLocation())
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toUserGeoLocation())
            }

            @Deprecated("Deprecated in Android API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val registeredProviders = mutableListOf<String>()
        for (provider in candidateProviders) {
            val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            if (enabled) {
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        3_000L,
                        2f,
                        listener,
                        Looper.getMainLooper()
                    )
                    registeredProviders.add(provider)
                }
            }
        }

        awaitClose {
            if (registeredProviders.isNotEmpty()) {
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    private fun Location.toUserGeoLocation(): UserGeoLocation {
        val distToCampus = calculateDistanceMeters(
            latitude,
            longitude,
            CAMPUS_CENTER_LAT,
            CAMPUS_CENTER_LNG
        )
        return UserGeoLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else 15f,
            bearingDegrees = if (hasBearing()) bearing else null,
            provider = provider ?: "fused",
            timestampMillis = time,
            isInsideLianhuajieCampus = distToCampus <= CAMPUS_GEOFENCE_RADIUS_METERS,
            distanceToCampusCenterMeters = distToCampus
        )
    }

    fun buildInAppNavigationPlan(
        userLocation: UserGeoLocation?,
        fallbackOriginBuilding: Building?,
        destinationBuilding: Building,
        allBuildings: List<Building>,
        rawClassroom: String? = null
    ): InAppNavigationPlan {
        val useLiveGps = userLocation != null && userLocation.isInsideLianhuajieCampus
        val originLat = when {
            useLiveGps -> userLocation!!.latitude
            fallbackOriginBuilding != null -> fallbackOriginBuilding.latitude
            else -> CAMPUS_CENTER_LAT
        }
        val originLng = when {
            useLiveGps -> userLocation!!.longitude
            fallbackOriginBuilding != null -> fallbackOriginBuilding.longitude
            else -> CAMPUS_CENTER_LNG
        }
        val originLabel = when {
            useLiveGps -> {
                val nearest = findNearestBuilding(originLat, originLng, allBuildings)
                if (nearest != null && nearest.second <= 120) {
                    "当前位置（近${nearest.first.name}）"
                } else {
                    "当前位置"
                }
            }
            fallbackOriginBuilding != null -> fallbackOriginBuilding.name
            else -> "莲花街南门"
        }

        val straightMeters = calculateDistanceMeters(
            originLat,
            originLng,
            destinationBuilding.latitude,
            destinationBuilding.longitude
        )
        val walkMeters = (straightMeters * 1.22).roundToInt().coerceAtLeast(straightMeters)
        val walkMinutes = (walkMeters / 72.0).roundToInt().coerceAtLeast(1)
        val bearing = calculateBearingDegrees(
            originLat,
            originLng,
            destinationBuilding.latitude,
            destinationBuilding.longitude
        )
        val compass = bearingToCompassLabel(bearing)

        val midLat = (originLat + destinationBuilding.latitude) / 2.0
        val midLng = (originLng + destinationBuilding.longitude) / 2.0
        val waypoint = if (straightMeters >= 180) {
            allBuildings
                .filter { it.id != destinationBuilding.id && it.id != fallbackOriginBuilding?.id }
                .minByOrNull { calculateDistanceMeters(midLat, midLng, it.latitude, it.longitude) }
        } else null

        val steps = buildList {
            add(
                InAppNavigationStep(
                    stepIndex = 1,
                    title = "从 $originLabel 出发",
                    detail = "朝 $compass (${bearing}°) 方向进入校园步道",
                    distanceMeters = (walkMeters * 0.35).roundToInt().coerceAtLeast(15)
                )
            )
            if (waypoint != null) {
                add(
                    InAppNavigationStep(
                        stepIndex = 2,
                        title = "途经 ${waypoint.name}",
                        detail = "沿主干道继续向 $compass 直行",
                        distanceMeters = (walkMeters * 0.45).roundToInt().coerceAtLeast(25)
                    )
                )
            }
            add(
                InAppNavigationStep(
                    stepIndex = size + 1,
                    title = "到达 ${destinationBuilding.name}",
                    detail = if (!rawClassroom.isNullOrBlank()) {
                        "进入楼宇前往 $rawClassroom"
                    } else {
                        destinationBuilding.description ?: "到达目标楼宇正门"
                    },
                    distanceMeters = (walkMeters * 0.20).roundToInt().coerceAtLeast(10)
                )
            )
        }

        return InAppNavigationPlan(
            originLabel = originLabel,
            originLatitude = originLat,
            originLongitude = originLng,
            originIsLiveGps = useLiveGps,
            destinationBuilding = destinationBuilding,
            rawClassroom = rawClassroom,
            straightDistanceMeters = straightMeters,
            estimatedWalkDistanceMeters = walkMeters,
            estimatedWalkMinutes = walkMinutes,
            bearingDegrees = bearing,
            compassDirectionLabel = compass,
            waypointBuilding = waypoint,
            steps = steps
        )
    }

    fun findNearestBuilding(
        latitude: Double,
        longitude: Double,
        buildings: List<Building>
    ): Pair<Building, Int>? {
        if (buildings.isEmpty()) return null
        val nearest = buildings.minByOrNull {
            calculateDistanceMeters(latitude, longitude, it.latitude, it.longitude)
        } ?: return null
        val dist = calculateDistanceMeters(latitude, longitude, nearest.latitude, nearest.longitude)
        return nearest to dist
    }

    fun openWalkingNavigation(context: Context, building: Building) {
        val (gcjLat, gcjLng) = wgs84ToGcj02(building.latitude, building.longitude)
        val label = Uri.encode("河南工业大学莲花街校区-${building.name}")

        val geoUri = Uri.parse("geo:$gcjLat,$gcjLng?q=$gcjLat,$gcjLng($label)")
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val packageManager = context.packageManager
        if (geoIntent.resolveActivity(packageManager) != null) {
            context.startActivity(geoIntent)
            return
        }

        val webFallbackUri = Uri.parse(
            "https://uri.amap.com/marker?position=$gcjLng,$gcjLat&name=$label&callnative=1"
        )
        val webIntent = Intent(Intent.ACTION_VIEW, webFallbackUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(webIntent) }
    }

    fun estimateWalkingMinutes(from: Building, to: Building): Int {
        val distanceMeters = calculateDistanceMeters(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude
        )
        return ((distanceMeters * 1.22) / 72.0).roundToInt().coerceAtLeast(1)
    }

    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Int {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadiusMeters * c).roundToInt()
    }

    fun calculateBearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Int {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = Math.toDegrees(atan2(y, x))
        return ((theta + 360.0) % 360.0).roundToInt() % 360
    }

    fun bearingToCompassLabel(degrees: Int): String {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            in 0..22 -> "正北"
            in 23..67 -> "东北"
            in 68..112 -> "正东"
            in 113..157 -> "东南"
            in 158..202 -> "正南"
            in 203..247 -> "西南"
            in 248..292 -> "正西"
            in 293..337 -> "西北"
            else -> "正北"
        }
    }
}
