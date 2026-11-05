package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.model.Building
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 河南工业大学官方校园 GIS 地图图层模式（对接 https://gis.haut.edu.cn）。
 */
enum class GisMapStyleMode(
    val shortLabel: String,
    val subtitle: String
) {
    OFFICIAL_VECTOR_HYBRID("官方矢量底图", "学校官方 GIS 实测楼宇轮廓、莲湖水系与校园路网"),
    OFFICIAL_25D_2026("官方 2.5D", "学校官方 GIS 轴测手绘校园瓦片"),
    STREET_STANDARD("标准街景", "高德标准道路瓦片底图")
}

data class GisGeoPoint(
    val latitude: Double,
    val longitude: Double
)

enum class GisLanduseType {
    WATER,
    GREENERY,
    SPORTS_GROUND,
    PLAZA
}

data class GisLandusePolygon(
    val id: String,
    val type: GisLanduseType,
    val name: String?,
    val outerRing: List<GisGeoPoint>
)

data class GisRoadLine(
    val id: String,
    val highwayType: String,
    val name: String?,
    val points: List<GisGeoPoint>
)

data class GisBuildingFootprint(
    val featureId: String,
    val polygonId: String?,
    val name: String,
    val alias: String?,
    val minLevelIndex: Int,
    val maxLevelIndex: Int,
    val description: String?,
    val centerLat: Double,
    val centerLon: Double,
    val outerRing: List<GisGeoPoint>,
    val matchedBuildingId: String?
) {
    val hasIndoorFloors: Boolean
        get() = maxLevelIndex >= 1 || featureId in HautOfficialGisClient.INDOOR_ENABLED_FEATURE_IDS

    val floorOptions: List<Pair<Int, String>>
        get() {
            val top = maxLevelIndex.coerceIn(0, 19)
            val bottom = minLevelIndex.coerceIn(0, top)
            return (bottom..top).map { idx -> idx to "${idx + 1}F" }
        }
}

data class GisServicePoi(
    val id: String,
    val name: String,
    val categoryCode: String,
    val categoryName: String,
    val latitude: Double,
    val longitude: Double,
    val brief: String?
)

data class GisCampusVectorDataset(
    val landusePolygons: List<GisLandusePolygon>,
    val roadLines: List<GisRoadLine>,
    val buildings: List<GisBuildingFootprint>,
    val servicePois: List<GisServicePoi>,
    val fetchedAtMillis: Long,
    val fromNetwork: Boolean
)

enum class GisIndoorRoomCategory(val label: String) {
    CLASSROOM("教室"),
    LABORATORY("实验室"),
    OFFICE("办公/教研"),
    STAIRS_ELEVATOR("电梯/楼梯"),
    RESTROOM("卫生间"),
    CORRIDOR("门厅/走廊"),
    OTHER("房间")
}

data class GisIndoorRoom(
    val id: String,
    val name: String,
    val rawCategory: String,
    val category: GisIndoorRoomCategory,
    val levelIndex: Int,
    val floorLabel: String,
    val parentPolygonId: String?,
    val centerLat: Double,
    val centerLon: Double,
    val outerRing: List<GisGeoPoint>
)

data class GisIndoorFloorPlan(
    val buildingFeatureId: String,
    val levelIndex: Int,
    val floorLabel: String,
    val outlineRings: List<List<GisGeoPoint>>,
    val rooms: List<GisIndoorRoom>
)

data class GisSearchResultItem(
    val id: String,
    val name: String,
    val isIndoorRoom: Boolean,
    val categoryLabel: String,
    val levelIndex: Int?,
    val floorLabel: String?,
    val parentPolygonId: String?,
    val buildingFeatureId: String?,
    val buildingName: String?,
    val matchedBuildingId: String?,
    val latitude: Double,
    val longitude: Double,
    val subtitle: String
)

/**
 * 河南工业大学官方 GIS 系统 (`https://gis.haut.edu.cn`) 原生客户端。
 *
 * 支持能力：
 * 1. 莲花街校区官方室外矢量 GeoJSON（92 栋实测建筑多边形轮廓、莲湖/粮苑湖水系、绿地、运动场、校园道路网）。
 * 2. 教学楼/实验楼/图书馆逐层室内平面图与教室多边形 (`indoorSource/{featureId}?floorid={level}&unionAll=true`)。
 * 3. 官方教室级/实验室级/楼宇级全文检索 (`/cmgis-server/map/search/v1/keyword`)，支持如 `3101`、`5102`、`6201`、`31201` 直接定位到楼栋、楼层与室内坐标。
 * 4. 官方校园步行与室内外一体化路径规划 (`/cmgis-server/map/route/v3/find/0/0`)，离线时自动回退至本地 Dijkstra 校园路网。
 * 5. 官方 8 大类 55 处校园服务点位 (`/cmips-server/mapPoint/queryTypeToPoint`)。
 */
object HautOfficialGisClient {

    const val OFFICIAL_GIS_BASE_URL = "https://gis.haut.edu.cn"
    const val OFFICIAL_GIS_WEB_URL = "https://gis.haut.edu.cn/cmipsh5/#/"
    private const val CMGIS_SERVER = "$OFFICIAL_GIS_BASE_URL/cmgis-server"
    private const val CMIPS_SERVER = "$OFFICIAL_GIS_BASE_URL/cmips-server"

    private const val CACHE_DIR_NAME = "haut_official_gis_v1"
    private const val GEOJSON_CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    /**
     * 官方含有丰富室内房间平面图的重点教学/实验/公共楼宇 Feature ID 集合。
     */
    val INDOOR_ENABLED_FEATURE_IDS = setOf(
        "12866", // 3号楼（文科楼）
        "12857", // 4号教学楼
        "12868", // 5号楼
        "12853", // 6号楼
        "12854", // 7号楼
        "12867", // 8号楼
        "12882", // 9号楼
        "12858", // 31号楼
        "13178", // 32号楼（土建楼）
        "12856", // 34号楼（实验实训中心）
        "13183", // 工程训练中心（35号楼）
        "12878", // 38号楼
        "12855", // 40号楼
        "12865", // 18号楼（艺术楼）
        "13066", // 24号楼（钟楼）
        "13065", // 25号楼（学生事务中心）
        "13185", // 图书馆
        "12883", // 办公楼
        "13182"  // 体育训练中心
    )

    internal data class StaticBuildingBinding(
        val featureId: String,
        val polygonId: String?,
        val officialName: String,
        val localBuildingId: String,
        val maxLevelIndex: Int
    )

    /**
     * 官方 GIS FeatureId / PolygonId 与本地 Building.id 的权威静态映射表，
     * 保证即使在矢量全量数据加载完成前，教室检索与室内图层切换也能毫秒级响应。
     */
    internal val STATIC_BUILDING_BINDINGS = listOf(
        StaticBuildingBinding("12858", "1067001026", "31号楼", "b_t1", 5),
        StaticBuildingBinding("13178", "1067001027", "32号楼（土建楼）", "b_t2", 5),
        StaticBuildingBinding("12866", "1067001022", "3号楼（文科楼）", "b_t3", 5),
        StaticBuildingBinding("12857", "1067001014", "4号教学楼", "b_t4", 4),
        StaticBuildingBinding("12868", "1067001020", "5号楼", "b_t5", 4),
        StaticBuildingBinding("12853", "1067001010", "6号楼", "b_t6", 4),
        StaticBuildingBinding("12854", "1067001011", "7号楼", "b_t7", 4),
        StaticBuildingBinding("12867", "1067001016", "8号楼", "b_t8", 5),
        StaticBuildingBinding("12882", "1067001225", "9号楼", "b_t9", 5),
        StaticBuildingBinding("12865", "1067001211", "18号楼（艺术楼）", "b_t18", 2),
        StaticBuildingBinding("13066", "1067002019", "24号楼（钟楼）", "b_bell", 2),
        StaticBuildingBinding("13065", "1067002018", "25号楼（学生事务中心）", "b_t25", 2),
        StaticBuildingBinding("12856", "1067001013", "34号楼（实验实训中心）", "b_t34", 4),
        StaticBuildingBinding("13183", "1067002050", "工程训练中心（35号楼）", "b_eng", 3),
        StaticBuildingBinding("13087", "1067002050", "工程训练中心", "b_eng", 3),
        StaticBuildingBinding("12878", "1067001224", "38号楼", "b_t38", 3),
        StaticBuildingBinding("12855", "1067001012", "40号楼", "b_t40", 3),
        StaticBuildingBinding("13185", "1067001034", "图书馆", "b_lib", 10),
        StaticBuildingBinding("12883", "1067001226", "办公楼", "b_admin", 6),
        StaticBuildingBinding("13182", "1067002021", "体育训练中心", "b_stadium", 3),
        StaticBuildingBinding("13068", "1067002024", "第一餐厅", "b_canteen1", 2),
        StaticBuildingBinding("12859", "1067001028", "小食堂", "b_canteen_small", 1),
        StaticBuildingBinding("13067", "1067002023", "菜鸟驿站", "b_express", 0),
        StaticBuildingBinding("12884", "1067001227", "校医院", "b_hospital", 4),
        StaticBuildingBinding("28", "1067002056", "南门", "b_gate_south", 0),
        StaticBuildingBinding("31", null, "西门", "b_gate_west", 0),
        StaticBuildingBinding("14698", null, "东门", "b_gate_east", 0)
    )

    private val bindingByFeatureId = STATIC_BUILDING_BINDINGS.associateBy { it.featureId }
    private val bindingByPolygonId = STATIC_BUILDING_BINDINGS.filter { it.polygonId != null }.associateBy { it.polygonId!! }
    private val bindingByLocalId = STATIC_BUILDING_BINDINGS.associateBy { it.localBuildingId }

    @Volatile
    private var memoryDataset: GisCampusVectorDataset? = null

    private val indoorFloorCache = ConcurrentHashMap<String, GisIndoorFloorPlan>()

    /**
     * 根据本地 Building 查询对应的官方 GIS FeatureId 与最大楼层索引。
     */
    fun resolveFeatureIdForBuilding(
        building: Building,
        dataset: GisCampusVectorDataset? = memoryDataset
    ): Pair<String, Int>? {
        dataset?.buildings?.firstOrNull { it.matchedBuildingId == building.id }?.let {
            return it.featureId to it.maxLevelIndex
        }
        bindingByLocalId[building.id]?.let {
            return it.featureId to it.maxLevelIndex
        }
        dataset?.buildings?.firstOrNull { fp ->
            building.name.contains(fp.name) || fp.name.contains(building.shortName)
        }?.let {
            return it.featureId to it.maxLevelIndex
        }
        return null
    }

    /**
     * 从教室编号（如 "3101", "5304", "31205", "6号楼6402"）推断 0-based 楼层索引（0 = 1F, 1 = 2F...）。
     */
    fun inferFloorIndexFromRoomQuery(rawQuery: String?, maxLevelIndex: Int = 5): Int? {
        if (rawQuery.isNullOrBlank()) return null
        val fiveDigits = Regex("(?<!\\d)(31|32|34|35|38|40)([1-9])\\d{2}(?!\\d)").find(rawQuery)
        if (fiveDigits != null) {
            val floorNum = fiveDigits.groupValues[2].toIntOrNull() ?: return null
            return (floorNum - 1).coerceIn(0, maxLevelIndex)
        }
        val fourDigits = Regex("(?<!\\d)[1-9]([1-9])\\d{2}(?!\\d)").find(rawQuery)
        if (fourDigits != null) {
            val floorNum = fourDigits.groupValues[1].toIntOrNull() ?: return null
            return (floorNum - 1).coerceIn(0, maxLevelIndex)
        }
        val explicitFloor = Regex("([1-9]|1[0-2])\\s*[层楼F]", RegexOption.IGNORE_CASE).find(rawQuery)
        if (explicitFloor != null) {
            val floorNum = explicitFloor.groupValues[1].toIntOrNull() ?: return null
            return (floorNum - 1).coerceIn(0, maxLevelIndex)
        }
        return null
    }

    /**
     * 提取纯房间号（如 "莲花街 3号楼3104" -> "3104"）。
     */
    fun extractCleanRoomCode(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        Regex("(?<!\\d)(\\d{4,5})(?!\\d)").find(rawQuery)?.let { return it.groupValues[1] }
        Regex("([A-Za-z]?\\d{3,5})").find(rawQuery)?.let { return it.groupValues[1].uppercase() }
        return null
    }

    /**
     * 加载莲花街校区官方室外矢量数据集（含建筑轮廓、水系、绿地、路网与 55 个校园服务 POI）。
     */
    suspend fun loadCampusDataset(
        cacheDir: File,
        forceRefresh: Boolean = false
    ): GisCampusVectorDataset? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            memoryDataset?.let { return@withContext it }
        }

        val dir = File(cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val geojsonFile = File(dir, "campus_1.geojson")
        val poiFile = File(dir, "service_pois_1.json")

        val now = System.currentTimeMillis()
        var fromNetwork = false

        val geojsonText = if (!forceRefresh &&
            geojsonFile.exists() &&
            geojsonFile.length() > 1024L &&
            now - geojsonFile.lastModified() < GEOJSON_CACHE_TTL_MS
        ) {
            runCatching { geojsonFile.readText() }.getOrNull()
        } else {
            val fetched = httpGetText("$CMGIS_SERVER/map/v2/data/geojson/1.geojson?include_indoor=false", timeoutMs = 8_000)
            if (!fetched.isNullOrBlank() && fetched.contains("\"FeatureCollection\"")) {
                fromNetwork = true
                runCatching { geojsonFile.writeText(fetched) }
                fetched
            } else {
                runCatching { if (geojsonFile.exists()) geojsonFile.readText() else null }.getOrNull()
            }
        } ?: return@withContext null

        val pois = loadOrFetchServicePois(poiFile, forceRefresh)
        val parsed = runCatching {
            parseOutdoorGeoJson(
                geojsonText = geojsonText,
                servicePois = pois,
                fetchedAtMillis = if (fromNetwork) now else geojsonFile.lastModified(),
                fromNetwork = fromNetwork
            )
        }.getOrNull()

        if (parsed != null) {
            memoryDataset = parsed
        }
        parsed
    }

    /**
     * 加载指定楼宇、指定楼层的官方室内平面图 (`indoorSource/{buildingFeatureId}?floorid={levelIndex}&unionAll=true`)。
     */
    suspend fun loadIndoorFloorPlan(
        cacheDir: File,
        buildingFeatureId: String,
        levelIndex: Int
    ): GisIndoorFloorPlan? = withContext(Dispatchers.IO) {
        val cacheKey = "${buildingFeatureId}_${levelIndex}"
        indoorFloorCache[cacheKey]?.let { return@withContext it }

        val dir = File(cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val floorFile = File(dir, "indoor_${cacheKey}.json")
        val now = System.currentTimeMillis()

        val rawJson = if (floorFile.exists() &&
            floorFile.length() > 64L &&
            now - floorFile.lastModified() < GEOJSON_CACHE_TTL_MS
        ) {
            runCatching { floorFile.readText() }.getOrNull()
        } else {
            val url = "$CMGIS_SERVER/map/v1/mapdata/indoorSource/$buildingFeatureId?floorid=$levelIndex&unionAll=true"
            val fetched = httpGetText(url, timeoutMs = 6_000)
            if (!fetched.isNullOrBlank() && fetched.contains("\"FeatureCollection\"")) {
                runCatching { floorFile.writeText(fetched) }
                fetched
            } else {
                runCatching { if (floorFile.exists()) floorFile.readText() else null }.getOrNull()
            }
        } ?: return@withContext null

        val plan = runCatching {
            parseIndoorFloorPlanJson(buildingFeatureId, levelIndex, rawJson)
        }.getOrNull()

        if (plan != null) {
            indoorFloorCache[cacheKey] = plan
        }
        plan
    }

    /**
     * 调用学校官方 GIS 关键词检索接口 (`/cmgis-server/map/search/v1/keyword`)，
     * 同时融合本地楼宇与校园服务点位，支持精确搜教室（如 `3101`、`5102`、`31201`）与搜楼宇/服务。
     */
    suspend fun searchOfficialGis(
        query: String,
        dataset: GisCampusVectorDataset? = memoryDataset,
        allBuildings: List<Building> = emptyList()
    ): List<GisSearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val results = mutableListOf<GisSearchResultItem>()
        val seenKeys = HashSet<String>()

        // 1. 请求官方 GIS 实时检索接口（支持教室/实验室/房间号与楼宇名）
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val searchUrl = "$CMGIS_SERVER/map/search/v1/keyword?page=0&pageSize=24&text=$encoded&tag_name=name&zone_id=1&location=113.5448,34.8304"
        val responseText = httpGetText(searchUrl, timeoutMs = 4_500)
        if (!responseText.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(responseText)
                val list = root.optJSONArray("list") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name").trim()
                    if (name.isBlank()) continue
                    val coordArr = item.optJSONArray("coordinate")
                    val lon = coordArr?.optDouble(0) ?: continue
                    val lat = coordArr.optDouble(1)
                    if (lat.isNaN() || lon.isNaN()) continue

                    val tags = item.optJSONObject("tags")
                    val polygonCat = tags?.optString("polygon_category").orEmpty()
                    val roomCatRaw = tags?.optString("room_category").orEmpty()
                    val parentPol = tags?.optString("parent_pol")?.takeIf { it.isNotBlank() }
                    val levelIndex = tags?.optString("level")?.toIntOrNull()
                    val floorLabel = levelIndex?.let { "${it + 1}F" }
                    val isRoom = polygonCat == "room" || roomCatRaw.isNotBlank() || parentPol != null

                    val parentFootprint = if (parentPol != null) {
                        dataset?.buildings?.firstOrNull { it.polygonId == parentPol }
                    } else {
                        dataset?.buildings?.firstOrNull { it.featureId == id }
                    }
                    val staticBind = if (parentPol != null) {
                        bindingByPolygonId[parentPol]
                    } else {
                        bindingByFeatureId[id]
                    }

                    val buildingFeatureId = parentFootprint?.featureId ?: staticBind?.featureId ?: if (!isRoom) id else null
                    val buildingName = parentFootprint?.name ?: staticBind?.officialName ?: if (!isRoom) name else null
                    val matchedBuildingId = parentFootprint?.matchedBuildingId
                        ?: staticBind?.localBuildingId
                        ?: matchOfficialNameToLocalBuildingId(buildingName ?: name, allBuildings)

                    val categoryLabel = when {
                        isRoom -> classifyRoomCategory(roomCatRaw, name).label
                        else -> "官方楼宇"
                    }
                    val subtitle = buildString {
                        if (isRoom) {
                            if (!buildingName.isNullOrBlank()) {
                                append(buildingName)
                                append(" · ")
                            }
                            if (floorLabel != null) {
                                append(floorLabel)
                                append(" · ")
                            }
                            append(categoryLabel)
                        } else {
                            append("莲花街校区 · ")
                            val alias = tags?.optString("another_name")?.takeIf { it.isNotBlank() }
                            if (alias != null) {
                                append(alias)
                                append(" · ")
                            }
                            append(categoryLabel)
                        }
                    }

                    val dedupKey = "${name}_${buildingFeatureId.orEmpty()}_${levelIndex ?: -1}"
                    if (seenKeys.add(dedupKey)) {
                        results.add(
                            GisSearchResultItem(
                                id = id,
                                name = name,
                                isIndoorRoom = isRoom,
                                categoryLabel = categoryLabel,
                                levelIndex = levelIndex,
                                floorLabel = floorLabel,
                                parentPolygonId = parentPol,
                                buildingFeatureId = buildingFeatureId,
                                buildingName = buildingName,
                                matchedBuildingId = matchedBuildingId,
                                latitude = lat,
                                longitude = lon,
                                subtitle = subtitle
                            )
                        )
                    }
                }
            }
        }

        // 2. 补充官方校园服务点位与本地建筑匹配结果
        dataset?.servicePois?.filter {
            it.name.contains(trimmed, ignoreCase = true) ||
                it.categoryName.contains(trimmed, ignoreCase = true) ||
                (it.brief?.contains(trimmed, ignoreCase = true) == true)
        }?.take(8)?.forEach { poi ->
            val key = "poi_${poi.name}"
            if (seenKeys.add(key)) {
                results.add(
                    GisSearchResultItem(
                        id = poi.id,
                        name = poi.name,
                        isIndoorRoom = false,
                        categoryLabel = poi.categoryName,
                        levelIndex = null,
                        floorLabel = null,
                        parentPolygonId = null,
                        buildingFeatureId = null,
                        buildingName = poi.name,
                        matchedBuildingId = matchOfficialNameToLocalBuildingId(poi.name, allBuildings),
                        latitude = poi.latitude,
                        longitude = poi.longitude,
                        subtitle = "校园服务 · ${poi.categoryName}"
                    )
                )
            }
        }

        results
    }

    /**
     * 调用学校官方 GIS 路径规划 v3 接口 (`/cmgis-server/map/route/v3/find/0/0`)，
     * 支持室外道路与室内楼层连通导航；若离线或跨校区则自动回退至本地路网规划。
     */
    suspend fun buildOfficialOrFallbackNavigationPlan(
        userLocation: UserGeoLocation?,
        fallbackOriginBuilding: Building?,
        destinationBuilding: Building,
        allBuildings: List<Building>,
        rawClassroom: String? = null,
        targetFloorIndex: Int? = null,
        targetRoomCenter: GisGeoPoint? = null
    ): Pair<InAppNavigationPlan, Boolean> = withContext(Dispatchers.IO) {
        val localPlan = CampusMapNavigator.buildInAppNavigationPlan(
            userLocation = userLocation,
            fallbackOriginBuilding = fallbackOriginBuilding,
            destinationBuilding = destinationBuilding,
            allBuildings = allBuildings,
            rawClassroom = rawClassroom
        )

        val isLianhuajieTarget = destinationBuilding.campusId.isBlank() ||
            destinationBuilding.campusId.contains("lianhuajie", ignoreCase = true)
        if (!isLianhuajieTarget) {
            return@withContext localPlan to false
        }

        val startLat = localPlan.originLatitude
        val startLon = localPlan.originLongitude
        val endLat = targetRoomCenter?.latitude ?: destinationBuilding.latitude
        val endLon = targetRoomCenter?.longitude ?: destinationBuilding.longitude

        val startParam = "$startLon,$startLat"
        val endParam = if (targetFloorIndex != null && targetFloorIndex > 0) {
            "$endLon,$endLat,$targetFloorIndex"
        } else {
            "$endLon,$endLat"
        }

        val url = "$CMGIS_SERVER/map/route/v3/find/0/0?start=$startParam&end=$endParam&mapId=1"
        val responseText = httpGetText(url, timeoutMs = 4_500)
        if (responseText.isNullOrBlank()) {
            return@withContext localPlan to false
        }

        val officialPlan = runCatching {
            parseOfficialRouteResponse(
                responseText = responseText,
                basePlan = localPlan,
                destinationBuilding = destinationBuilding,
                rawClassroom = rawClassroom,
                targetFloorIndex = targetFloorIndex,
                targetRoomCenter = targetRoomCenter
            )
        }.getOrNull()

        if (officialPlan != null && officialPlan.routePoints.size >= 2) {
            officialPlan to true
        } else {
            localPlan to false
        }
    }

    internal fun parseOutdoorGeoJson(
        geojsonText: String,
        servicePois: List<GisServicePoi>,
        fetchedAtMillis: Long,
        fromNetwork: Boolean
    ): GisCampusVectorDataset {
        val root = JSONObject(geojsonText)
        val features = root.optJSONArray("features") ?: JSONArray()

        val landuseList = mutableListOf<GisLandusePolygon>()
        val roadList = mutableListOf<GisRoadLine>()
        val buildingList = mutableListOf<GisBuildingFootprint>()

        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val fid = feat.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: "f_$i"
            val props = feat.optJSONObject("properties") ?: JSONObject()
            val geom = feat.optJSONObject("geometry") ?: continue
            val geomType = geom.optString("type")
            val coords = geom.optJSONArray("coordinates") ?: continue

            val newId = props.optString("newid")
            val highway = props.optString("highway")
            val name = props.optString("name").trim().takeIf { it.isNotBlank() }
            val alias = props.optString("another_name").trim().takeIf { it.isNotBlank() }

            if (geomType == "LineString" && highway.isNotBlank()) {
                val pts = parseGeoPointArray(coords)
                if (pts.size >= 2) {
                    roadList.add(
                        GisRoadLine(
                            id = fid,
                            highwayType = highway,
                            name = name,
                            points = pts
                        )
                    )
                }
                continue
            }

            if (geomType == "Polygon" || geomType == "MultiPolygon") {
                val ring = extractPrimaryOuterRing(geomType, coords)
                if (ring.size < 3) continue

                when (newId) {
                    "22" -> {
                        landuseList.add(GisLandusePolygon(fid, GisLanduseType.WATER, name, ring))
                    }
                    "23" -> {
                        landuseList.add(GisLandusePolygon(fid, GisLanduseType.GREENERY, name, ring))
                    }
                    "24" -> {
                        landuseList.add(GisLandusePolygon(fid, GisLanduseType.SPORTS_GROUND, name, ring))
                    }
                    "25" -> {
                        if (name != null) {
                            val (cLat, cLon) = computeRingCentroid(ring)
                            val polygonId = props.optString("polygonid").trim().takeIf { it.isNotBlank() }
                                ?: bindingByFeatureId[fid]?.polygonId
                            val maxLvl = props.optString("building:levels").toIntOrNull()
                                ?: bindingByFeatureId[fid]?.maxLevelIndex
                                ?: 0
                            val minLvl = props.optString("building:min_level").toIntOrNull() ?: 0
                            val rawContent = props.optString("content").takeIf { it.isNotBlank() }
                            val cleanDesc = cleanHtmlContent(rawContent)
                            val matchedId = bindingByFeatureId[fid]?.localBuildingId
                                ?: polygonId?.let { bindingByPolygonId[it]?.localBuildingId }
                                ?: matchOfficialNameToLocalIdStatic(name)

                            buildingList.add(
                                GisBuildingFootprint(
                                    featureId = fid,
                                    polygonId = polygonId,
                                    name = name,
                                    alias = alias,
                                    minLevelIndex = minLvl.coerceAtLeast(0),
                                    maxLevelIndex = maxLvl.coerceAtLeast(0),
                                    description = cleanDesc,
                                    centerLat = cLat,
                                    centerLon = cLon,
                                    outerRing = ring,
                                    matchedBuildingId = matchedId
                                )
                            )
                        }
                    }
                }
            }
        }

        return GisCampusVectorDataset(
            landusePolygons = landuseList,
            roadLines = roadList,
            buildings = buildingList,
            servicePois = servicePois,
            fetchedAtMillis = fetchedAtMillis,
            fromNetwork = fromNetwork
        )
    }

    internal fun parseIndoorFloorPlanJson(
        buildingFeatureId: String,
        levelIndex: Int,
        rawJson: String
    ): GisIndoorFloorPlan {
        val root = JSONObject(rawJson)
        val features = root.optJSONArray("features") ?: JSONArray()
        val floorLabel = "${levelIndex + 1}F"
        val outlines = mutableListOf<List<GisGeoPoint>>()
        val rooms = mutableListOf<GisIndoorRoom>()

        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val fid = feat.opt("id")?.toString() ?: "r_$i"
            val props = feat.optJSONObject("properties") ?: JSONObject()
            val geom = feat.optJSONObject("geometry") ?: continue
            val geomType = geom.optString("type")
            val coords = geom.optJSONArray("coordinates") ?: continue
            if (geomType != "Polygon" && geomType != "MultiPolygon") continue

            val ring = extractPrimaryOuterRing(geomType, coords)
            if (ring.size < 3) continue

            val polyCat = props.optString("polygon_category")
            val roomCatRaw = props.optString("room_category")
            val name = props.optString("name").trim()
            val parentPol = props.optString("parent_pol").trim().takeIf { it.isNotBlank() }

            if (polyCat == "floor" || (roomCatRaw.isBlank() && name.isBlank() && i == 0)) {
                outlines.add(ring)
                continue
            }

            val (cLat, cLon) = computeRingCentroid(ring)
            val category = classifyRoomCategory(roomCatRaw, name)
            val displayName = when {
                name.isNotBlank() -> name
                category == GisIndoorRoomCategory.RESTROOM -> "卫生间"
                category == GisIndoorRoomCategory.STAIRS_ELEVATOR && roomCatRaw == "elevator" -> "电梯"
                category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> "楼梯"
                else -> ""
            }

            rooms.add(
                GisIndoorRoom(
                    id = fid,
                    name = displayName,
                    rawCategory = roomCatRaw,
                    category = category,
                    levelIndex = levelIndex,
                    floorLabel = floorLabel,
                    parentPolygonId = parentPol,
                    centerLat = cLat,
                    centerLon = cLon,
                    outerRing = ring
                )
            )
        }

        return GisIndoorFloorPlan(
            buildingFeatureId = buildingFeatureId,
            levelIndex = levelIndex,
            floorLabel = floorLabel,
            outlineRings = outlines,
            rooms = rooms
        )
    }

    private fun parseOfficialRouteResponse(
        responseText: String,
        basePlan: InAppNavigationPlan,
        destinationBuilding: Building,
        rawClassroom: String?,
        targetFloorIndex: Int?,
        targetRoomCenter: GisGeoPoint?
    ): InAppNavigationPlan? {
        val root = JSONObject(responseText)
        if (root.optInt("status", -1) != 0) return null
        val dataArr = root.optJSONArray("data") ?: return null
        if (dataArr.length() == 0) return null

        val routePoints = mutableListOf<RouteGeoPoint>()
        val steps = mutableListOf<InAppNavigationStep>()
        var totalMeters = 0

        routePoints.add(
            RouteGeoPoint(
                latitude = basePlan.originLatitude,
                longitude = basePlan.originLongitude,
                label = basePlan.originLabel
            )
        )

        for (i in 0 until dataArr.length()) {
            val seg = dataArr.optJSONObject(i) ?: continue
            val routeName = seg.optString("routeName").trim().ifBlank { "校园步道" }
            val distance = seg.optDouble("distance", 0.0).roundToInt().coerceAtLeast(1)
            val indoor = seg.optBoolean("indoor", false)
            val startFloor = seg.optString("startFloor").toIntOrNull()
            val endFloor = seg.optString("endFloor").toIntOrNull()
            val turnType = seg.optInt("turnType", 1)

            val geom = seg.optJSONObject("geometry")
            val geomType = geom?.optString("type").orEmpty()
            val coords = geom?.optJSONArray("coordinates")
            if (coords != null) {
                val segPts = when (geomType) {
                    "LineString" -> parseGeoPointArray(coords)
                    "MultiLineString" -> {
                        val list = mutableListOf<GisGeoPoint>()
                        for (k in 0 until coords.length()) {
                            val sub = coords.optJSONArray(k) ?: continue
                            list.addAll(parseGeoPointArray(sub))
                        }
                        list
                    }
                    else -> emptyList()
                }
                for (pt in segPts) {
                    val prev = routePoints.lastOrNull()
                    if (prev == null ||
                        CampusMapNavigator.calculateDistanceMeters(
                            prev.latitude,
                            prev.longitude,
                            pt.latitude,
                            pt.longitude
                        ) >= 2
                    ) {
                        routePoints.add(RouteGeoPoint(pt.latitude, pt.longitude, routeName))
                    }
                }
            }

            totalMeters += distance
            val turnLabel = when (turnType) {
                2 -> "左转"
                3 -> "右转"
                4 -> "向左前方"
                5 -> "向右前方"
                else -> "直行"
            }

            val stepTitle = if (indoor) {
                val floorHint = if (startFloor != null && endFloor != null && startFloor != endFloor) {
                    "由 ${startFloor + 1}F 前往 ${endFloor + 1}F"
                } else if (endFloor != null) {
                    "在 ${endFloor + 1}F 室内通行"
                } else {
                    "室内连廊通行"
                }
                "室内引导 · $floorHint"
            } else if (steps.isEmpty()) {
                "从 ${basePlan.originLabel} 出发，沿 $routeName 行进"
            } else {
                "$turnLabel 进入 $routeName"
            }

            val prevStep = steps.lastOrNull()
            if (prevStep != null && prevStep.title == stepTitle && !indoor) {
                val mergedDist = prevStep.distanceMeters + distance
                steps[steps.lastIndex] = prevStep.copy(
                    detail = "沿 $routeName 继续步行约 $mergedDist 米",
                    distanceMeters = mergedDist
                )
            } else {
                steps.add(
                    InAppNavigationStep(
                        stepIndex = steps.size + 1,
                        title = stepTitle,
                        detail = if (indoor) {
                            "沿楼内走廊/楼梯通行约 $distance 米"
                        } else {
                            "沿 $routeName 步行约 $distance 米"
                        },
                        distanceMeters = distance
                    )
                )
            }
        }

        val finalLat = targetRoomCenter?.latitude ?: destinationBuilding.latitude
        val finalLon = targetRoomCenter?.longitude ?: destinationBuilding.longitude
        val lastPt = routePoints.lastOrNull()
        if (lastPt == null ||
            CampusMapNavigator.calculateDistanceMeters(lastPt.latitude, lastPt.longitude, finalLat, finalLon) >= 3
        ) {
            routePoints.add(RouteGeoPoint(finalLat, finalLon, destinationBuilding.name))
        }

        val floorSuffix = targetFloorIndex?.let { " (${it + 1}F)" }.orEmpty()
        steps.add(
            InAppNavigationStep(
                stepIndex = steps.size + 1,
                title = "到达 ${destinationBuilding.name}$floorSuffix",
                detail = if (!rawClassroom.isNullOrBlank()) {
                    "目标教室/房间：$rawClassroom$floorSuffix"
                } else {
                    destinationBuilding.description
                },
                distanceMeters = 10
            )
        )

        val walkMeters = totalMeters.coerceAtLeast(basePlan.straightDistanceMeters).coerceAtLeast(20)
        val walkMinutes = (walkMeters / 72.0).roundToInt().coerceAtLeast(1)

        return basePlan.copy(
            estimatedWalkDistanceMeters = walkMeters,
            estimatedWalkMinutes = walkMinutes,
            routePoints = routePoints,
            steps = steps
        )
    }

    private fun loadOrFetchServicePois(poiFile: File, forceRefresh: Boolean): List<GisServicePoi> {
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            poiFile.exists() &&
            poiFile.length() > 64L &&
            now - poiFile.lastModified() < GEOJSON_CACHE_TTL_MS
        ) {
            val cached = runCatching { parseCachedServicePois(poiFile.readText()) }.getOrNull()
            if (!cached.isNullOrEmpty()) return cached
        }

        val parentCodes = listOf(
            "100" to "购物服务",
            "101" to "餐饮服务",
            "103" to "交通出行",
            "104" to "文体娱乐",
            "105" to "金融服务",
            "106" to "生活服务",
            "108" to "医疗服务",
            "109" to "校园景点"
        )
        val fetchedPois = mutableListOf<GisServicePoi>()
        for ((code, catName) in parentCodes) {
            val url = "$CMIPS_SERVER/mapPoint/queryTypeToPoint?campusCode=1&parentTypeCode=$code&typeCode=$code&isVector=true&lon=&lat=&distance="
            val text = httpGetText(url, timeoutMs = 4_000) ?: continue
            runCatching {
                val root = JSONObject(text)
                val data = root.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val obj = data.optJSONObject(i) ?: continue
                    val name = obj.optString("pointName").trim()
                    val lon = obj.optString("longitude").toDoubleOrNull() ?: continue
                    val lat = obj.optString("latitude").toDoubleOrNull() ?: continue
                    val id = obj.optString("pointCode").ifBlank { "${code}_$i" }
                    val brief = cleanHtmlContent(obj.optString("pointBrief"))
                    if (name.isNotBlank()) {
                        fetchedPois.add(
                            GisServicePoi(
                                id = id,
                                name = name,
                                categoryCode = code,
                                categoryName = catName,
                                latitude = lat,
                                longitude = lon,
                                brief = brief
                            )
                        )
                    }
                }
            }
        }

        if (fetchedPois.isNotEmpty()) {
            runCatching {
                val arr = JSONArray()
                fetchedPois.forEach { p ->
                    arr.put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("categoryCode", p.categoryCode)
                            put("categoryName", p.categoryName)
                            put("latitude", p.latitude)
                            put("longitude", p.longitude)
                            put("brief", p.brief ?: "")
                        }
                    )
                }
                poiFile.writeText(arr.toString())
            }
            return fetchedPois
        }

        return runCatching {
            if (poiFile.exists()) parseCachedServicePois(poiFile.readText()) else emptyList()
        }.getOrDefault(emptyList())
    }

    private fun parseCachedServicePois(jsonText: String): List<GisServicePoi> {
        val arr = JSONArray(jsonText)
        val list = mutableListOf<GisServicePoi>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                GisServicePoi(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    categoryCode = obj.optString("categoryCode"),
                    categoryName = obj.optString("categoryName"),
                    latitude = obj.optDouble("latitude"),
                    longitude = obj.optDouble("longitude"),
                    brief = obj.optString("brief").takeIf { it.isNotBlank() }
                )
            )
        }
        return list
    }

    internal fun classifyRoomCategory(rawCategory: String, name: String): GisIndoorRoomCategory {
        val lower = rawCategory.lowercase()
        return when {
            lower == "classroom" || name.contains("教室") || name.contains("阶梯") -> GisIndoorRoomCategory.CLASSROOM
            lower == "laboratory" || name.contains("实验") || name.contains("机房") || name.contains("实训") -> GisIndoorRoomCategory.LABORATORY
            lower == "office" || name.contains("办公") || name.contains("教研") || name.contains("会议") -> GisIndoorRoomCategory.OFFICE
            lower == "stairs" || lower == "elevator" || name.contains("电梯") || name.contains("楼梯") -> GisIndoorRoomCategory.STAIRS_ELEVATOR
            lower == "toilet" || name.contains("卫生间") || name.contains("洗手间") || name.contains("厕") -> GisIndoorRoomCategory.RESTROOM
            lower == "corridor" || name.contains("走廊") || name.contains("门厅") -> GisIndoorRoomCategory.CORRIDOR
            name.matches(Regex("\\d{3,5}[A-Za-z]?")) -> GisIndoorRoomCategory.CLASSROOM
            else -> GisIndoorRoomCategory.OTHER
        }
    }

    private fun matchOfficialNameToLocalIdStatic(officialName: String): String? {
        return when {
            officialName.contains("31号楼") || officialName.contains("1号教学楼") -> "b_t1"
            officialName.contains("32号楼") || officialName.contains("土建楼") -> "b_t2"
            officialName.contains("3号楼") || officialName.contains("文科楼") -> "b_t3"
            officialName.contains("4号教学楼") || officialName.contains("4号楼") -> "b_t4"
            officialName.contains("5号楼") -> "b_t5"
            officialName.contains("6号楼") -> "b_t6"
            officialName.contains("7号楼") -> "b_t7"
            officialName.contains("8号楼") -> "b_t8"
            officialName.contains("9号楼") -> "b_t9"
            officialName.contains("18号楼") || officialName.contains("艺术楼") -> "b_t18"
            officialName.contains("24号楼") || officialName.contains("钟楼") -> "b_bell"
            officialName.contains("25号楼") || officialName.contains("学生事务中心") -> "b_t25"
            officialName.contains("34号楼") || officialName.contains("实验实训中心") -> "b_t34"
            officialName.contains("35号楼") || officialName.contains("工程训练中心") -> "b_eng"
            officialName.contains("38号楼") -> "b_t38"
            officialName.contains("40号楼") -> "b_t40"
            officialName.contains("图书馆") -> "b_lib"
            officialName.contains("办公楼") -> "b_admin"
            officialName.contains("体育训练中心") || officialName.contains("体育场") -> "b_stadium"
            officialName.contains("第一餐厅") -> "b_canteen1"
            officialName.contains("小食堂") -> "b_canteen_small"
            officialName.contains("菜鸟驿站") -> "b_express"
            officialName.contains("校医院") -> "b_hospital"
            officialName.contains("学生公寓A区") || officialName.contains("学生公寓B区") -> "b_dorm_ab"
            officialName.contains("学生公寓C区") || officialName.contains("学生公寓D区") -> "b_dorm_ju"
            officialName.contains("学生公寓E区") || officialName.contains("学生公寓F区") -> "b_dorm_ef"
            officialName.contains("南门") -> "b_gate_south"
            officialName.contains("西门") -> "b_gate_west"
            officialName.contains("东门") -> "b_gate_east"
            else -> null
        }
    }

    private fun matchOfficialNameToLocalBuildingId(
        officialName: String,
        allBuildings: List<Building>
    ): String? {
        matchOfficialNameToLocalIdStatic(officialName)?.let { staticId ->
            if (allBuildings.isEmpty() || allBuildings.any { it.id == staticId }) {
                return staticId
            }
        }
        return allBuildings.firstOrNull { b ->
            b.name.contains(officialName, ignoreCase = true) ||
                officialName.contains(b.shortName, ignoreCase = true)
        }?.id
    }

    private fun extractPrimaryOuterRing(geomType: String, coords: JSONArray): List<GisGeoPoint> {
        return when (geomType) {
            "Polygon" -> {
                val outer = coords.optJSONArray(0) ?: return emptyList()
                parseGeoPointArray(outer)
            }
            "MultiPolygon" -> {
                val firstPoly = coords.optJSONArray(0) ?: return emptyList()
                val outer = firstPoly.optJSONArray(0) ?: return emptyList()
                parseGeoPointArray(outer)
            }
            else -> emptyList()
        }
    }

    private fun parseGeoPointArray(arr: JSONArray): List<GisGeoPoint> {
        val list = ArrayList<GisGeoPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val pt = arr.optJSONArray(i) ?: continue
            val lon = pt.optDouble(0)
            val lat = pt.optDouble(1)
            if (!lat.isNaN() && !lon.isNaN() && abs(lat) <= 90.0 && abs(lon) <= 180.0) {
                list.add(GisGeoPoint(latitude = lat, longitude = lon))
            }
        }
        return list
    }

    private fun computeRingCentroid(ring: List<GisGeoPoint>): Pair<Double, Double> {
        if (ring.isEmpty()) return CampusMapNavigator.CAMPUS_CENTER_LAT to CampusMapNavigator.CAMPUS_CENTER_LNG
        var sumLat = 0.0
        var sumLon = 0.0
        for (pt in ring) {
            sumLat += pt.latitude
            sumLon += pt.longitude
        }
        return (sumLat / ring.size) to (sumLon / ring.size)
    }

    private fun cleanHtmlContent(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val stripped = raw
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped.takeIf { it.isNotBlank() }
    }

    private fun httpGetText(urlString: String, timeoutMs: Int): String? {
        val conn = (URL(urlString).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "HAUT-DayInSchool-Android/0.15 (Official-GIS-Client)")
            conn.setRequestProperty("Referer", OFFICIAL_GIS_WEB_URL)
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
