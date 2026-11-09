package cn.edu.haut.campus.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.model.Building
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.PriorityQueue
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
    val distanceToCampusCenterMeters: Int,
    val isInsideSongshanluCampus: Boolean = false,
    val distanceToSongshanluCenterMeters: Int = Int.MAX_VALUE
)

/**
 * 路网路径折线坐标点（WGS-84）
 */
data class RouteGeoPoint(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
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
 * 校内动态路网步行路线方案
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
    val routePoints: List<RouteGeoPoint>,
    val steps: List<InAppNavigationStep>
)

/**
 * 河南工业大学莲花街校区定位、电子罗盘朝向传感器、坐标系转换（WGS-84 <-> GCJ-02）与校园路网动态最短路径规划引擎。
 */
object CampusMapNavigator {

    // 河南工业大学莲花街校区真实中心点（WGS-84）
    const val CAMPUS_CENTER_LAT = 34.831200
    const val CAMPUS_CENTER_LNG = 113.544200
    private const val CAMPUS_GEOFENCE_RADIUS_METERS = 3000

    // 河南工业大学嵩山路校区真实中心点（WGS-84，对应郑州市中原区嵩山南路140号×伊河路西南象限院区）
    const val SONGSHANLU_CENTER_LAT = 34.74045
    const val SONGSHANLU_CENTER_LNG = 113.61895

    private const val EARTH_RADIUS_M = 6378245.0
    private const val EE = 0.00669342162296594323

    private data class CampusRoadNode(
        val id: String,
        val name: String,
        val lat: Double,
        val lng: Double
    )

    private data class CampusRoadEdge(
        val u: String,
        val v: String,
        val roadName: String
    )

    // 河南工业大学莲花街校区真实步行主干道与路口拓扑节点（WGS-84）
    private val CAMPUS_ROAD_NODES = listOf(
        // 南门与南环路沿线 (lat ≈ 34.8287 ~ 34.8293)
        CampusRoadNode("r_gate_south", "莲花街南门广场", 34.82872, 113.54532),
        CampusRoadNode("r_s_west", "实训南环路口", 34.82932, 113.54230),
        CampusRoadNode("r_s_t1_w", "1教西南路口", 34.82932, 113.54420),
        CampusRoadNode("r_s_axis", "南门中轴路口", 34.82932, 113.54532),
        CampusRoadNode("r_s_t4", "4教南路口", 34.82932, 113.54642),
        CampusRoadNode("r_s_t3", "3教南路口", 34.82932, 113.54785),

        // 教学区南中轴与连廊线 (lat ≈ 34.8300 ~ 34.8306)
        CampusRoadNode("r_t1_gate", "1号教学楼入口", 34.82975, 113.54500),
        CampusRoadNode("r_t4_gate", "4号教学楼入口", 34.83002, 113.54642),
        CampusRoadNode("r_t3_gate", "3号教学楼入口", 34.82971, 113.54785),
        CampusRoadNode("r_m_west_s", "西门南侧步道", 34.83060, 113.53685),
        CampusRoadNode("r_m_dorm_s", "生活区南路口", 34.83060, 113.53880),
        CampusRoadNode("r_m_cant_s", "四餐厅南路口", 34.83060, 113.54135),
        CampusRoadNode("r_m_eng_s", "实训中心南路口", 34.83060, 113.54230),
        CampusRoadNode("r_m_t1_n", "1教北侧林荫道", 34.83060, 113.54420),
        CampusRoadNode("r_m_axis", "教学区中轴十字", 34.83060, 113.54532),
        CampusRoadNode("r_m_t2_s", "32号楼南路口", 34.83060, 113.54642),
        CampusRoadNode("r_m_t3_n", "3教北路口", 34.83060, 113.54785),

        // 校区中轴景观道与图书馆/实训/西门沿线 (lat ≈ 34.8312 ~ 34.8322)
        CampusRoadNode("r_t2_gate", "32号楼（土建楼）入口", 34.83124, 113.54615),
        CampusRoadNode("r_eng_gate", "实训中心入口", 34.83123, 113.54230),
        CampusRoadNode("r_c_west_gate", "长椿路西门广场", 34.83185, 113.53675),
        CampusRoadNode("r_c_dorm_m", "公寓区中路路口", 34.83185, 113.53880),
        CampusRoadNode("r_c_cant_gate", "第四餐厅路口", 34.83185, 113.54135),
        CampusRoadNode("r_c_eng_n", "实训中心北路口", 34.83185, 113.54230),
        CampusRoadNode("r_c_lib_w", "图书馆西侧步道", 34.83185, 113.54420),
        CampusRoadNode("r_c_lib_s", "图书馆南广场", 34.83185, 113.54565),
        CampusRoadNode("r_c_t2_n", "32号楼北侧路口", 34.83185, 113.54642),
        CampusRoadNode("r_c_east", "东环中路路口", 34.83185, 113.54785),
        CampusRoadNode("r_lib_gate", "图书馆主入口", 34.83210, 113.54570),
        CampusRoadNode("r_cant_door", "第四学生餐厅入口", 34.83226, 113.54130),

        // 北环钟楼广场与学生公寓区沿线 (lat ≈ 34.8335 ~ 34.8347)
        CampusRoadNode("r_n_west", "西北环路口", 34.83358, 113.53685),
        CampusRoadNode("r_n_dorm_gate", "学生公寓区路口", 34.83358, 113.53880),
        CampusRoadNode("r_n_cant_n", "体育场南路口", 34.83358, 113.54140),
        CampusRoadNode("r_n_lake_w", "中心湖西岸步道", 34.83358, 113.54420),
        CampusRoadNode("r_n_lib_n", "图书馆北广场", 34.83358, 113.54570),
        CampusRoadNode("r_n_bell", "钟楼广场路口", 34.83358, 113.54680),
        CampusRoadNode("r_sp_dorm_n", "公寓北环路口", 34.83468, 113.53880),
        CampusRoadNode("r_sp_stadium", "西操场与体育馆入口", 34.83468, 113.54152),
        CampusRoadNode("r_sp_east", "体育场东路口", 34.83468, 113.54420)
    )

    private val CAMPUS_ROAD_NODE_MAP = CAMPUS_ROAD_NODES.associateBy { it.id }

    private val CAMPUS_ROAD_EDGES = listOf(
        // 南门迎宾与南环横向道路
        CampusRoadEdge("r_gate_south", "r_s_axis", "南门迎宾大道"),
        CampusRoadEdge("r_s_west", "r_s_t1_w", "南环主干道"),
        CampusRoadEdge("r_s_t1_w", "r_s_axis", "南环主干道"),
        CampusRoadEdge("r_s_axis", "r_s_t4", "南环主干道"),
        CampusRoadEdge("r_s_t4", "r_s_t3", "南环主干道"),

        // 1/3/4教接入支路
        CampusRoadEdge("r_s_axis", "r_t1_gate", "1教南侧步道"),
        CampusRoadEdge("r_s_t1_w", "r_t1_gate", "1教西侧步道"),
        CampusRoadEdge("r_t1_gate", "r_m_axis", "中轴教学大道"),
        CampusRoadEdge("r_s_t4", "r_t4_gate", "4教西侧通道"),
        CampusRoadEdge("r_t4_gate", "r_m_t2_s", "4教北侧通道"),
        CampusRoadEdge("r_s_t3", "r_t3_gate", "3教南侧广场"),
        CampusRoadEdge("r_t3_gate", "r_m_t3_n", "3教东环步道"),

        // 教学区中横大道 (lat ≈ 34.8306)
        CampusRoadEdge("r_m_west_s", "r_m_dorm_s", "南生活区步道"),
        CampusRoadEdge("r_m_dorm_s", "r_m_cant_s", "南生活区步道"),
        CampusRoadEdge("r_m_cant_s", "r_m_eng_s", "实训南路"),
        CampusRoadEdge("r_m_eng_s", "r_m_t1_n", "教学区中路"),
        CampusRoadEdge("r_m_t1_n", "r_m_axis", "教学区中路"),
        CampusRoadEdge("r_m_axis", "r_m_t2_s", "教学区中路"),
        CampusRoadEdge("r_m_t2_s", "r_m_t3_n", "教学区中路"),

        // 32号楼与实训中心竖向连道
        CampusRoadEdge("r_s_west", "r_m_eng_s", "实训西路"),
        CampusRoadEdge("r_m_eng_s", "r_eng_gate", "实训西路"),
        CampusRoadEdge("r_eng_gate", "r_c_eng_n", "实训西路"),
        CampusRoadEdge("r_s_t1_w", "r_m_t1_n", "1教西侧林荫道"),
        CampusRoadEdge("r_m_t1_n", "r_c_lib_w", "图书馆西路"),
        CampusRoadEdge("r_m_axis", "r_c_lib_s", "校园中轴大道"),
        CampusRoadEdge("r_m_t2_s", "r_t2_gate", "32号楼步行道"),
        CampusRoadEdge("r_t2_gate", "r_c_t2_n", "32号楼步行道"),
        CampusRoadEdge("r_m_t3_n", "r_c_east", "东环路"),

        // 中心景观大道 (lat ≈ 34.83185)
        CampusRoadEdge("r_c_west_gate", "r_c_dorm_m", "西门景观大道"),
        CampusRoadEdge("r_c_dorm_m", "r_c_cant_gate", "生活区主干道"),
        CampusRoadEdge("r_c_cant_gate", "r_c_eng_n", "中心景观大道"),
        CampusRoadEdge("r_c_eng_n", "r_c_lib_w", "中心景观大道"),
        CampusRoadEdge("r_c_lib_w", "r_c_lib_s", "中心景观大道"),
        CampusRoadEdge("r_c_lib_s", "r_c_t2_n", "中心景观大道"),
        CampusRoadEdge("r_c_t2_n", "r_c_east", "中心景观大道"),
        CampusRoadEdge("r_c_lib_s", "r_lib_gate", "图书馆南广场步道"),
        CampusRoadEdge("r_c_cant_gate", "r_cant_door", "四餐厅通道"),

        // 西区与北环纵向连道
        CampusRoadEdge("r_m_west_s", "r_c_west_gate", "西环路"),
        CampusRoadEdge("r_c_west_gate", "r_n_west", "西环路"),
        CampusRoadEdge("r_m_dorm_s", "r_c_dorm_m", "公寓东路"),
        CampusRoadEdge("r_c_dorm_m", "r_n_dorm_gate", "公寓东路"),
        CampusRoadEdge("r_m_cant_s", "r_c_cant_gate", "餐厅东路"),
        CampusRoadEdge("r_cant_door", "r_n_cant_n", "餐厅北路"),
        CampusRoadEdge("r_c_lib_w", "r_n_lake_w", "中心湖西路"),
        CampusRoadEdge("r_lib_gate", "r_n_lib_n", "中轴文化广场"),
        CampusRoadEdge("r_c_t2_n", "r_n_bell", "钟楼东大道"),

        // 北环大道与体育场连道
        CampusRoadEdge("r_n_west", "r_n_dorm_gate", "北环生活路"),
        CampusRoadEdge("r_n_dorm_gate", "r_n_cant_n", "北环主干道"),
        CampusRoadEdge("r_n_cant_n", "r_n_lake_w", "北环主干道"),
        CampusRoadEdge("r_n_lake_w", "r_n_lib_n", "钟楼南广场"),
        CampusRoadEdge("r_n_lib_n", "r_n_bell", "钟楼广场大道"),
        CampusRoadEdge("r_n_dorm_gate", "r_sp_dorm_n", "公寓北路"),
        CampusRoadEdge("r_n_cant_n", "r_sp_stadium", "体育馆西路"),
        CampusRoadEdge("r_n_lake_w", "r_sp_east", "体育馆东路"),
        CampusRoadEdge("r_sp_dorm_n", "r_sp_stadium", "北环体育场路"),
        CampusRoadEdge("r_sp_stadium", "r_sp_east", "北环体育场路")
    )

    // 各楼宇对应的标准路网入口节点映射
    private val BUILDING_ENTRANCE_NODE = mapOf(
        "b_gate_south" to "r_gate_south",
        "b_t1" to "r_t1_gate",
        "b_t2" to "r_t2_gate",
        "b_t3" to "r_t3_gate",
        "b_t4" to "r_t4_gate",
        "b_lib" to "r_lib_gate",
        "b_bell" to "r_n_bell",
        "b_eng" to "r_eng_gate",
        "b_canteen1" to "r_cant_door",
        "b_dorm_ju" to "r_n_dorm_gate",
        "b_express" to "r_c_west_gate",
        "b_stadium" to "r_sp_stadium"
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

    /**
     * 将国内高德瓦片使用的 GCJ-02 坐标反算为标准 WGS-84 坐标（供电子区划地图点选圈定校区使用）。
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        if (outOfChina(gcjLat, gcjLon)) return gcjLat to gcjLon
        val (gLat, gLon) = wgs84ToGcj02(gcjLat, gcjLon)
        return (gcjLat * 2.0 - gLat) to (gcjLon * 2.0 - gLon)
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

    /**
     * 实时监听设备朝向方位角（0°~360°，0°=正北，90°=正东）。
     * 采用 TYPE_ROTATION_VECTOR 主传感器，自动降级兼容加速度计+磁力计，并内置圆周低通滤波消除抖动。
     */
    fun observeDeviceHeadingDegrees(context: Context): Flow<Float?> = callbackFlow {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (rotationSensor == null && (accelSensor == null || magSensor == null)) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var hasGravity = false
        var hasMag = false
        var smoothedHeading: Float? = null

        fun getScreenRotation(): Int {
            return runCatching {
                @Suppress("DEPRECATION")
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }.getOrDefault(Surface.ROTATION_0)
        }

        fun emitMatrixHeading() {
            val rot = getScreenRotation()
            val (axisX, axisY) = when (rot) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
            SensorManager.getOrientation(remappedMatrix, orientationAngles)
            val rawDeg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()

            val prev = smoothedHeading
            val next = if (prev == null) {
                rawDeg
            } else {
                var diff = rawDeg - prev
                if (diff > 180f) diff -= 360f
                if (diff < -180f) diff += 360f
                ((prev + diff * 0.22f) + 360f) % 360f
            }
            if (smoothedHeading == null || abs(next - (smoothedHeading ?: 0f)) >= 0.8f) {
                smoothedHeading = next
                trySend(next)
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        emitMatrixHeading()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, 3)
                        hasGravity = true
                        if (hasMag && SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                            emitMatrixHeading()
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                        hasMag = true
                        if (hasGravity && SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                            emitMatrixHeading()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.distinct()

        var latestFix: Location? = null
        var dynamicWalkingBearing: Float? = null

        @Synchronized
        fun dispatchLocation(incoming: Location, forceEmit: Boolean = false) {
            val prev = latestFix
            if (prev != null && !forceEmit) {
                // 避免旧的缓存坐标覆盖更新的实时坐标
                if (incoming.time > 0L && prev.time > 0L && incoming.time < prev.time - 3_000L) {
                    return
                }
                // 若最近 5 秒内已有高精度 GPS 定位，忽略精度过差(>120m)的粗略基站跳变
                val ageSincePrevMs = System.currentTimeMillis() - prev.time
                val prevAcc = if (prev.hasAccuracy()) prev.accuracy else 30f
                val newAcc = if (incoming.hasAccuracy()) incoming.accuracy else 30f
                if (ageSincePrevMs in 0..5_000L && prevAcc <= 22f && newAcc > 120f) {
                    return
                }
                val movedMeters = calculateDistanceMeters(
                    prev.latitude,
                    prev.longitude,
                    incoming.latitude,
                    incoming.longitude
                )
                if (movedMeters >= 2) {
                    dynamicWalkingBearing = calculateBearingDegrees(
                        prev.latitude,
                        prev.longitude,
                        incoming.latitude,
                        incoming.longitude
                    ).toFloat()
                }
                val sameCoords = abs(prev.latitude - incoming.latitude) < 1e-7 &&
                    abs(prev.longitude - incoming.longitude) < 1e-7 &&
                    abs(prevAcc - newAcc) < 0.5f
                if (sameCoords) {
                    return
                }
            }
            latestFix = Location(incoming)
            trySend(
                incoming.toUserGeoLocation(
                    fallbackBearing = dynamicWalkingBearing,
                    emittedAtMs = System.currentTimeMillis()
                )
            )
        }

        // 1. 立即读取所有可用 Provider 中最新的缓存位置作为初始快照
        candidateProviders
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?.let { dispatchLocation(it, forceEmit = true) }

        // 2. 为每个 Provider 创建独立的 LocationListener（防止共用同一 Listener 导致底层注册互相覆盖）
        val registeredListeners = mutableListOf<LocationListener>()
        for (provider in candidateProviders) {
            val providerListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    dispatchLocation(location)
                }

                @Deprecated("Deprecated in Android API")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }

            val registered = runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    1_000L,
                    0f,
                    providerListener,
                    Looper.getMainLooper()
                )
                true
            }.getOrDefault(false)

            if (registered) {
                registeredListeners.add(providerListener)
            }
        }

        // 3. 持续主动拉取协程：每 2 秒主动请求一次最新实时坐标，解决国产 ROM 对被动 LocationListener 节流不回调的问题
        val activeProviders = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.distinct()
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val activePollJob = launch {
            while (isActive) {
                for (provider in activeProviders) {
                    val enabled = runCatching { locationManager.isProviderEnabled(provider) }
                        .getOrDefault(provider == LocationManager.FUSED_PROVIDER)
                    if (!enabled) continue

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            locationManager.getCurrentLocation(
                                provider,
                                null,
                                mainExecutor
                            ) { freshLoc ->
                                if (freshLoc != null) {
                                    dispatchLocation(freshLoc)
                                }
                            }
                        }
                    } else {
                        runCatching {
                            @Suppress("DEPRECATION")
                            locationManager.requestSingleUpdate(
                                provider,
                                object : LocationListener {
                                    override fun onLocationChanged(location: Location) {
                                        dispatchLocation(location)
                                    }
                                    @Deprecated("Deprecated in Android API")
                                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
                                    override fun onProviderEnabled(p: String) = Unit
                                    override fun onProviderDisabled(p: String) = Unit
                                },
                                Looper.getMainLooper()
                            )
                        }
                    }
                }

                // 同步检查系统底层最新缓存坐标是否已刷新
                candidateProviders
                    .mapNotNull { provider ->
                        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                    }
                    .maxByOrNull { it.time }
                    ?.let { dispatchLocation(it) }

                delay(2_000L)
            }
        }

        awaitClose {
            activePollJob.cancel()
            for (listener in registeredListeners) {
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }

    private fun Location.toUserGeoLocation(
        fallbackBearing: Float? = null,
        emittedAtMs: Long = time
    ): UserGeoLocation {
        val distToCampus = calculateDistanceMeters(
            latitude,
            longitude,
            CAMPUS_CENTER_LAT,
            CAMPUS_CENTER_LNG
        )
        val distToSongshanlu = calculateDistanceMeters(
            latitude,
            longitude,
            SONGSHANLU_CENTER_LAT,
            SONGSHANLU_CENTER_LNG
        )
        return UserGeoLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else 15f,
            bearingDegrees = if (hasBearing()) bearing else fallbackBearing,
            provider = provider ?: "fused",
            timestampMillis = maxOf(time, emittedAtMs),
            isInsideLianhuajieCampus = distToCampus <= CAMPUS_GEOFENCE_RADIUS_METERS,
            distanceToCampusCenterMeters = distToCampus,
            isInsideSongshanluCampus = cn.edu.haut.campus.core.model.CustomCampusZone.isPointInPolygon(
                latitude,
                longitude,
                cn.edu.haut.campus.core.domain.ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
            ),
            distanceToSongshanluCenterMeters = distToSongshanlu
        )
    }

    /**
     * 校区步行路线计算（同时支持莲花街校区精细路网、嵩山路校区及自定义电子区划校区步行导航）。
     */
    fun buildInAppNavigationPlan(
        userLocation: UserGeoLocation?,
        fallbackOriginBuilding: Building?,
        destinationBuilding: Building,
        allBuildings: List<Building>,
        rawClassroom: String? = null
    ): InAppNavigationPlan {
        val isSongshanluTarget = destinationBuilding.campusId.contains("songshan", ignoreCase = true)
        val isLianhuajieTarget = destinationBuilding.campusId.isBlank() ||
            destinationBuilding.campusId.contains("lianhuajie", ignoreCase = true)
        val useLiveGps = userLocation != null && when {
            isSongshanluTarget -> userLocation.isInsideSongshanluCampus
            isLianhuajieTarget -> userLocation.isInsideLianhuajieCampus
            else -> calculateDistanceMeters(
                userLocation.latitude,
                userLocation.longitude,
                destinationBuilding.latitude,
                destinationBuilding.longitude
            ) <= 3000
        }
        val defaultOriginLat = if (isSongshanluTarget) SONGSHANLU_CENTER_LAT else CAMPUS_CENTER_LAT
        val defaultOriginLng = if (isSongshanluTarget) SONGSHANLU_CENTER_LNG else CAMPUS_CENTER_LNG
        val originLat = when {
            useLiveGps -> userLocation!!.latitude
            fallbackOriginBuilding != null -> fallbackOriginBuilding.latitude
            !isLianhuajieTarget && !isSongshanluTarget -> allBuildings.firstOrNull()?.latitude ?: destinationBuilding.latitude
            else -> defaultOriginLat
        }
        val originLng = when {
            useLiveGps -> userLocation!!.longitude
            fallbackOriginBuilding != null -> fallbackOriginBuilding.longitude
            !isLianhuajieTarget && !isSongshanluTarget -> allBuildings.firstOrNull()?.longitude ?: destinationBuilding.longitude
            else -> defaultOriginLng
        }
        val originLabel = when {
            useLiveGps -> {
                val nearest = findNearestBuilding(originLat, originLng, allBuildings)
                if (nearest != null && nearest.second <= 120) {
                    "我的位置（${nearest.first.shortName}附近）"
                } else {
                    "我的位置"
                }
            }
            fallbackOriginBuilding != null -> fallbackOriginBuilding.name
            isSongshanluTarget -> "嵩山路校区东门"
            !isLianhuajieTarget -> allBuildings.firstOrNull()?.name ?: "校区中心"
            else -> "莲花街南门"
        }

        val straightMeters = calculateDistanceMeters(
            originLat,
            originLng,
            destinationBuilding.latitude,
            destinationBuilding.longitude
        )

        val bearing = calculateBearingDegrees(
            originLat,
            originLng,
            destinationBuilding.latitude,
            destinationBuilding.longitude
        )
        val compass = bearingToCompassLabel(bearing)

        val (routePoints, roadDistanceMeters, roadSteps) = if (isLianhuajieTarget) {
            computeRoadNetworkRoute(
                originLat = originLat,
                originLng = originLng,
                originLabel = originLabel,
                destinationBuilding = destinationBuilding,
                rawClassroom = rawClassroom
            )
        } else {
            val midLat = (originLat + destinationBuilding.latitude) / 2.0
            val midLng = destinationBuilding.longitude
            val pts = listOf(
                RouteGeoPoint(originLat, originLng, originLabel),
                RouteGeoPoint(midLat, midLng, "校区主干道"),
                RouteGeoPoint(destinationBuilding.latitude, destinationBuilding.longitude, destinationBuilding.name)
            )
            val estMeters = (straightMeters * 1.18).roundToInt().coerceAtLeast(25)
            val steps = listOf(
                InAppNavigationStep(
                    stepIndex = 1,
                    title = "从 $originLabel 出发，沿校区主干道向$compass",
                    detail = "步行约 $estMeters 米前往 ${destinationBuilding.name}",
                    distanceMeters = estMeters
                ),
                InAppNavigationStep(
                    stepIndex = 2,
                    title = "到达 ${destinationBuilding.name}",
                    detail = if (!rawClassroom.isNullOrBlank()) {
                        "由 ${destinationBuilding.entrances.firstOrNull() ?: "正门"} 进入前往 $rawClassroom"
                    } else {
                        destinationBuilding.description
                    },
                    distanceMeters = 15
                )
            )
            Triple(pts, estMeters, steps)
        }

        val walkMeters = roadDistanceMeters.coerceAtLeast(straightMeters)
        val walkMinutes = (walkMeters / 72.0).roundToInt().coerceAtLeast(1)

        val midPoint = routePoints.getOrNull(routePoints.size / 2)
        val waypoint = if (midPoint != null && straightMeters >= 150) {
            allBuildings
                .filter { it.id != destinationBuilding.id && it.id != fallbackOriginBuilding?.id }
                .minByOrNull { calculateDistanceMeters(midPoint.latitude, midPoint.longitude, it.latitude, it.longitude) }
        } else null

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
            routePoints = routePoints,
            steps = roadSteps
        )
    }

    private fun computeRoadNetworkRoute(
        originLat: Double,
        originLng: Double,
        originLabel: String,
        destinationBuilding: Building,
        rawClassroom: String?
    ): Triple<List<RouteGeoPoint>, Int, List<InAppNavigationStep>> {
        // 1. 将起点吸附到最近的校园道路边或路口节点
        var bestEdge: CampusRoadEdge? = null
        var bestProjLat = originLat
        var bestProjLng = originLng
        var bestDistToEdge = Double.MAX_VALUE

        for (edge in CAMPUS_ROAD_EDGES) {
            val uNode = CAMPUS_ROAD_NODE_MAP[edge.u] ?: continue
            val vNode = CAMPUS_ROAD_NODE_MAP[edge.v] ?: continue
            val (pLat, pLng) = projectPointOntoSegment(
                originLat,
                originLng,
                uNode.lat,
                uNode.lng,
                vNode.lat,
                vNode.lng
            )
            val d = calculateDistanceMeters(originLat, originLng, pLat, pLng).toDouble()
            if (d < bestDistToEdge) {
                bestDistToEdge = d
                bestEdge = edge
                bestProjLat = pLat
                bestProjLng = pLng
            }
        }

        // 2. 确定目标建筑对应的路网终点节点
        val targetNodeId = BUILDING_ENTRANCE_NODE[destinationBuilding.id]
            ?: CAMPUS_ROAD_NODES.minByOrNull {
                calculateDistanceMeters(destinationBuilding.latitude, destinationBuilding.longitude, it.lat, it.lng)
            }?.id
            ?: "r_c_lib_s"

        // 3. 构建动态邻接表（加入虚拟起点 v_start）
        val virtualStartId = "v_start"
        val virtualStartNode = CampusRoadNode(virtualStartId, originLabel, bestProjLat, bestProjLng)
        val allNodes = CAMPUS_ROAD_NODE_MAP.toMutableMap().apply {
            put(virtualStartId, virtualStartNode)
        }

        data class AdjEdge(val to: String, val weightMeters: Int, val roadName: String)
        val adj = mutableMapOf<String, MutableList<AdjEdge>>()

        fun addBidirectional(a: String, b: String, roadName: String) {
            val na = allNodes[a] ?: return
            val nb = allNodes[b] ?: return
            val w = calculateDistanceMeters(na.lat, na.lng, nb.lat, nb.lng).coerceAtLeast(1)
            adj.getOrPut(a) { mutableListOf() }.add(AdjEdge(b, w, roadName))
            adj.getOrPut(b) { mutableListOf() }.add(AdjEdge(a, w, roadName))
        }

        for (edge in CAMPUS_ROAD_EDGES) {
            addBidirectional(edge.u, edge.v, edge.roadName)
        }

        if (bestEdge != null) {
            addBidirectional(virtualStartId, bestEdge.u, bestEdge.roadName)
            addBidirectional(virtualStartId, bestEdge.v, bestEdge.roadName)
        } else {
            val nearestNode = CAMPUS_ROAD_NODES.minByOrNull {
                calculateDistanceMeters(originLat, originLng, it.lat, it.lng)
            } ?: CAMPUS_ROAD_NODES.first()
            addBidirectional(virtualStartId, nearestNode.id, "校园步道")
        }

        // 4. 执行 Dijkstra 最短路径搜索
        val dist = mutableMapOf<String, Int>().withDefault { Int.MAX_VALUE / 2 }
        val prevNode = mutableMapOf<String, String>()
        val prevRoad = mutableMapOf<String, String>()
        val pq = PriorityQueue<Pair<String, Int>>(compareBy { it.second })

        dist[virtualStartId] = 0
        pq.add(virtualStartId to 0)

        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll()
            if (d > dist.getValue(u)) continue
            if (u == targetNodeId) break
            for (edge in adj[u].orEmpty()) {
                val nd = d + edge.weightMeters
                if (nd < dist.getValue(edge.to)) {
                    dist[edge.to] = nd
                    prevNode[edge.to] = u
                    prevRoad[edge.to] = edge.roadName
                    pq.add(edge.to to nd)
                }
            }
        }

        // 5. 回溯节点序列
        val nodeIds = mutableListOf<String>()
        var curr: String? = targetNodeId
        while (curr != null) {
            nodeIds.add(curr)
            if (curr == virtualStartId) break
            curr = prevNode[curr]
        }
        nodeIds.reverse()

        // 组装完整坐标序列：真实起点 -> 道路接入点 -> 沿途路口 -> 目标建筑正门
        val rawPoints = mutableListOf<RouteGeoPoint>()
        rawPoints.add(RouteGeoPoint(originLat, originLng, originLabel))
        for (nid in nodeIds) {
            val node = allNodes[nid] ?: continue
            val last = rawPoints.last()
            if (calculateDistanceMeters(last.latitude, last.longitude, node.lat, node.lng) >= 4) {
                rawPoints.add(RouteGeoPoint(node.lat, node.lng, node.name))
            }
        }
        val lastPt = rawPoints.last()
        if (calculateDistanceMeters(
                lastPt.latitude,
                lastPt.longitude,
                destinationBuilding.latitude,
                destinationBuilding.longitude
            ) >= 4
        ) {
            rawPoints.add(
                RouteGeoPoint(
                    destinationBuilding.latitude,
                    destinationBuilding.longitude,
                    destinationBuilding.name
                )
            )
        }

        // 6. 计算真实路网总距离并按道路名称聚合生成分段转向导航指令
        var totalMeters = 0
        for (i in 0 until rawPoints.size - 1) {
            totalMeters += calculateDistanceMeters(
                rawPoints[i].latitude,
                rawPoints[i].longitude,
                rawPoints[i + 1].latitude,
                rawPoints[i + 1].longitude
            )
        }

        val steps = mutableListOf<InAppNavigationStep>()
        if (nodeIds.size >= 2) {
            data class MergedRoadLeg(
                val roadName: String,
                val startNode: CampusRoadNode,
                var endNode: CampusRoadNode,
                var distanceMeters: Int
            )

            val legs = mutableListOf<MergedRoadLeg>()
            for (i in 0 until nodeIds.size - 1) {
                val uId = nodeIds[i]
                val vId = nodeIds[i + 1]
                val uNode = allNodes[uId] ?: continue
                val vNode = allNodes[vId] ?: continue
                val rName = prevRoad[vId] ?: "校园步道"
                val segLen = calculateDistanceMeters(uNode.lat, uNode.lng, vNode.lat, vNode.lng)
                if (segLen < 3) continue
                val lastLeg = legs.lastOrNull()
                if (lastLeg != null && lastLeg.roadName == rName) {
                    lastLeg.endNode = vNode
                    lastLeg.distanceMeters += segLen
                } else {
                    legs.add(MergedRoadLeg(rName, uNode, vNode, segLen))
                }
            }

            legs.forEachIndexed { idx, leg ->
                val bearingDeg = calculateBearingDegrees(
                    leg.startNode.lat,
                    leg.startNode.lng,
                    leg.endNode.lat,
                    leg.endNode.lng
                )
                val dirLabel = bearingToCompassLabel(bearingDeg)
                val turnAction = if (idx == 0) {
                    "从 $originLabel 出发，沿 ${leg.roadName} 向$dirLabel"
                } else {
                    val prevLeg = legs[idx - 1]
                    val prevBearing = calculateBearingDegrees(
                        prevLeg.startNode.lat,
                        prevLeg.startNode.lng,
                        prevLeg.endNode.lat,
                        prevLeg.endNode.lng
                    )
                    val turnText = computeTurnInstruction(prevBearing, bearingDeg)
                    "在 ${leg.startNode.name} $turnText，进入 ${leg.roadName}"
                }
                steps.add(
                    InAppNavigationStep(
                        stepIndex = steps.size + 1,
                        title = turnAction,
                        detail = "直行约 ${leg.distanceMeters} 米至 ${leg.endNode.name}",
                        distanceMeters = leg.distanceMeters
                    )
                )
            }
        }

        steps.add(
            InAppNavigationStep(
                stepIndex = steps.size + 1,
                title = "到达 ${destinationBuilding.name}",
                detail = if (!rawClassroom.isNullOrBlank()) {
                    "由 ${destinationBuilding.entrances.firstOrNull() ?: "正门"} 进入前往 $rawClassroom"
                } else {
                    destinationBuilding.description ?: "到达目标楼宇入口"
                },
                distanceMeters = calculateDistanceMeters(
                    rawPoints.getOrElse(rawPoints.size - 2) { rawPoints.first() }.latitude,
                    rawPoints.getOrElse(rawPoints.size - 2) { rawPoints.first() }.longitude,
                    destinationBuilding.latitude,
                    destinationBuilding.longitude
                ).coerceAtLeast(10)
            )
        )

        return Triple(rawPoints, totalMeters.coerceAtLeast(20), steps)
    }

    private fun projectPointOntoSegment(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double
    ): Pair<Double, Double> {
        val dx = bLng - aLng
        val dy = bLat - aLat
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return aLat to aLng
        val t = (((pLng - aLng) * dx + (pLat - aLat) * dy) / lenSq).coerceIn(0.0, 1.0)
        return (aLat + t * dy) to (aLng + t * dx)
    }

    private fun computeTurnInstruction(prevBearing: Int, nextBearing: Int): String {
        var delta = (nextBearing - prevBearing) % 360
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return when {
            abs(delta) < 25 -> "继续直行"
            delta in 25..65 -> "向右前方转"
            delta > 65 -> "右转"
            delta in -65..-25 -> "向左前方转"
            else -> "左转"
        }
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
        val campusPrefix = when {
            building.campusId.contains("songshan", ignoreCase = true) -> "河南工业大学嵩山路校区"
            building.campusId.contains("lianhuajie", ignoreCase = true) -> "河南工业大学莲花街校区"
            building.campusId.startsWith("custom_", ignoreCase = true) -> "自定义校区"
            else -> "校区导航"
        }
        val label = Uri.encode("$campusPrefix-${building.name}")

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
        runCatching {
            context.startActivity(webIntent)
        }
    }

    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Int {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).roundToInt()
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
        val theta = atan2(y, x)
        return ((Math.toDegrees(theta).roundToInt() + 360) % 360)
    }

    fun bearingToCompassLabel(bearingDegrees: Int): String {
        val dirs = arrayOf("正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北")
        val idx = (((bearingDegrees + 22.5) % 360) / 45.0).toInt().coerceIn(0, 7)
        return dirs[idx]
    }
}
