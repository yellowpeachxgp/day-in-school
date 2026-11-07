package cn.edu.haut.campus.core.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class AttemptType(val label: String) {
    NORMAL("正常修读"),
    MAKEUP("补考"),
    RETAKE("重修"),
    EXEMPT("免修/免考"),
    DEFERRED("缓考"),
    PASS_FAIL("通过制"),
    FIVE_LEVEL("五级制")
}

enum class CourseCategory(val label: String) {
    COMPULSORY("必修"),
    ELECTIVE("专业选修"),
    PUBLIC_ELECTIVE("通识选修"),
    PRACTICE("实践环节")
}

data class GradeAttempt(
    val id: String,
    val termId: String,
    val academicYear: String,
    val termNumber: Int,
    val courseKey: String,
    val name: String,
    val category: CourseCategory = CourseCategory.COMPULSORY,
    val credits: BigDecimal,
    val rawGrade: String,
    val numericScore: BigDecimal? = null,
    val gradePoint: BigDecimal? = null,
    val attemptType: AttemptType = AttemptType.NORMAL,
    val passed: Boolean = true,
    val isCourseReplacement: Boolean = false,
    val source: CourseSource = CourseSource.OFFICIAL_JW,
    val syncedAt: Instant
)

enum class GpaSourceType(val badgeLabel: String) {
    OFFICIAL_JW("来自教务官方汇总"),
    LOCAL_WEIGHTED_FROM_JW_POINTS("本地加权计算（教务课程绩点）"),
    POLICY_FORMULA_ESTIMATE("按已验证学校规则换算"),
    INSUFFICIENT_RULE("不足以可靠计算")
}

enum class RetakeInclusionStrategy {
    /** 取最高有效修读记录（重修/补考覆盖原记录） */
    HIGHEST_ATTEMPT,
    /** 取最新一次有效修读记录 */
    LATEST_ATTEMPT,
    /** 仅首次正考 */
    FIRST_ATTEMPT_ONLY
}

data class GpaPolicy(
    val id: String,
    val source: String,
    val appliesTo: String,
    val policyVersion: String,
    val retakeStrategy: RetakeInclusionStrategy = RetakeInclusionStrategy.HIGHEST_ATTEMPT,
    val includeZeroCreditCourses: Boolean = false,
    val includePassFailCoursesInGpa: Boolean = false,
    val allowRawScoreFallbackFormula: Boolean = false,
    val scoreToPointRules: List<ScoreBandRule> = emptyList()
)

data class ScoreBandRule(
    val minScoreInclusive: BigDecimal,
    val maxScoreInclusive: BigDecimal,
    val gradePoint: BigDecimal
)

data class OfficialGpaSnapshot(
    val cumulativeGpa: BigDecimal?,
    val termGpas: Map<String, BigDecimal> = emptyMap(),
    val academicYearGpas: Map<String, BigDecimal> = emptyMap(),
    val fetchedAt: Instant,
    val fieldDefinitionNote: String
)

data class TermGpaTrendPoint(
    val termId: String,
    val termLabel: String,
    val gpa: BigDecimal?,
    val weightedAverageScore: BigDecimal?,
    val earnedCredits: BigDecimal,
    val sourceType: GpaSourceType
)

data class GradeDistribution(
    val excellentCount: Int, // >= 90 or GPA >= 3.7 / 优秀
    val goodCount: Int,      // 80-89 / 良好
    val mediumCount: Int,    // 70-79 / 中等
    val passCount: Int,      // 60-69 / 及格 / 通过
    val failOrOtherCount: Int // < 60 / 未通过 / 缓考
)

data class CourseGradeAuditItem(
    val courseKey: String,
    val courseName: String,
    val selectedAttempt: GradeAttempt?,
    val allAttempts: List<GradeAttempt>,
    val includedInGpaDenominator: Boolean,
    val exclusionOrChangeReason: String
)

data class GpaReport(
    val cumulativeGpa: BigDecimal?,
    val currentTermGpa: BigDecimal?,
    val currentAcademicYearGpa: BigDecimal?,
    val totalEarnedCredits: BigDecimal,
    val gpaIncludedCredits: BigDecimal,
    val primarySourceType: GpaSourceType,
    val sourceExplanation: String,
    val termTrends: List<TermGpaTrendPoint>,
    val distribution: GradeDistribution,
    val auditItems: List<CourseGradeAuditItem>,
    val diagnosticNotes: List<String>
)

enum class CampusCategory(val label: String) {
    TEACHING_BUILDING("教学楼"),
    LIBRARY("图书馆"),
    CANTEEN("食堂"),
    GATE("校门"),
    DORMITORY("宿舍区"),
    EXPRESS("快递/生活"),
    LAB("实验楼/学院楼"),
    SPORTS("体育场馆")
}

data class Building(
    val id: String,
    val campusId: String = "lianhuajie",
    val name: String,
    val shortName: String,
    val latitude: Double,
    val longitude: Double,
    val category: CampusCategory,
    val entrances: List<String>,
    val description: String,
    val verifiedAt: LocalDate
)

data class BuildingAlias(
    val id: String,
    val buildingId: String,
    val rawNameOrPattern: String,
    val confidence: Float,
    val verified: Boolean
)

enum class BuildingMatchStatus {
    EXACT_VERIFIED,
    FUZZY_NEEDS_CONFIRMATION,
    UNRESOLVED
}

data class ResolvedClassroomLocation(
    val rawRoom: String,
    val status: BuildingMatchStatus,
    val building: Building? = null,
    val roomNumberHint: String? = null,
    val confidence: Float = 0f,
    val matchedAlias: String? = null,
    val candidateBuildings: List<Building> = emptyList()
)

/**
 * WGS-84 地理坐标顶点，用于校区电子区划（电子围栏多边形）与自定义建筑坐标。
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

/**
 * 用户在电子地图上通过电子区划（多边形顶点）自定义圈定的校区，或从外部导入的校区适配包配置。
 */
data class CustomCampusZone(
    val campusId: String,
    val campusName: String,
    val schoolName: String = "自定义校区",
    val centerLatitude: Double,
    val centerLongitude: Double,
    val polygonVertices: List<GeoPoint> = emptyList(),
    val buildings: List<Building> = emptyList(),
    val geofenceRadiusMeters: Int = 2000
) {
    /**
     * 使用射线法（Ray-Casting）判断 WGS-84 坐标点是否落在该校区的电子区划多边形内；
     * 若多边形顶点数不足 3 个，则回退至中心点圆形围栏判定。
     */
    fun containsWgs84Point(lat: Double, lng: Double): Boolean {
        if (polygonVertices.size >= 3) {
            return isPointInPolygon(lat, lng, polygonVertices)
        }
        val dLat = Math.toRadians(lat - centerLatitude)
        val dLng = Math.toRadians(lng - centerLongitude)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(centerLatitude)) * kotlin.math.cos(Math.toRadians(lat)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val distMeters = 6371000.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return distMeters <= geofenceRadiusMeters
    }

    companion object {
        fun isPointInPolygon(lat: Double, lng: Double, polygon: List<GeoPoint>): Boolean {
            if (polygon.size < 3) return false
            var inside = false
            var j = polygon.lastIndex
            for (i in polygon.indices) {
                val yi = polygon[i].latitude
                val xi = polygon[i].longitude
                val yj = polygon[j].latitude
                val xj = polygon[j].longitude
                val denom = (yj - yi).let { if (kotlin.math.abs(it) < 1e-12) 1e-12 else it }
                val intersect = ((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / denom + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }
    }
}

/**
 * 零依赖的校区电子区划与校区适配 JSON 编解码器（同时支持应用原生格式与标准 GeoJSON Polygon 顶点导入）。
 */
object CustomCampusZoneCodec {

    fun encodeList(zones: List<CustomCampusZone>, pretty: Boolean = false): String {
        if (zones.isEmpty()) return "[]"
        val sep = if (pretty) ",\n" else ","
        val body = zones.joinToString(sep) { encodeZone(it, pretty) }
        return if (pretty) "[\n$body\n]" else "[$body]"
    }

    fun encodeZone(zone: CustomCampusZone, pretty: Boolean = true): String {
        val verticesJson = zone.polygonVertices.joinToString(",") { pt ->
            """{"lat":${pt.latitude},"lng":${pt.longitude}}"""
        }
        val buildingsJson = zone.buildings.joinToString(",") { b ->
            """{"id":"${escape(b.id)}","campusId":"${escape(b.campusId)}","name":"${escape(b.name)}","shortName":"${escape(b.shortName)}","lat":${b.latitude},"lng":${b.longitude},"category":"${b.category.name}","description":"${escape(b.description)}"}"""
        }
        return if (pretty) {
            """{
  "campusId": "${escape(zone.campusId)}",
  "campusName": "${escape(zone.campusName)}",
  "schoolName": "${escape(zone.schoolName)}",
  "centerLat": ${zone.centerLatitude},
  "centerLng": ${zone.centerLongitude},
  "geofenceRadiusMeters": ${zone.geofenceRadiusMeters},
  "polygonVertices": [$verticesJson],
  "buildings": [$buildingsJson]
}"""
        } else {
            """{"campusId":"${escape(zone.campusId)}","campusName":"${escape(zone.campusName)}","schoolName":"${escape(zone.schoolName)}","centerLat":${zone.centerLatitude},"centerLng":${zone.centerLongitude},"geofenceRadiusMeters":${zone.geofenceRadiusMeters},"polygonVertices":[$verticesJson],"buildings":[$buildingsJson]}"""
        }
    }

    fun decodeList(rawJson: String): List<CustomCampusZone> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        if (trimmed.startsWith("{")) {
            return listOfNotNull(decodeSingleObject(trimmed))
        }
        val objects = splitTopLevelJsonObjects(trimmed)
        return objects.mapNotNull { decodeSingleObject(it) }
    }

    fun decodeSingleOrList(rawJson: String): List<CustomCampusZone> = decodeList(rawJson)

    private fun decodeSingleObject(objText: String): CustomCampusZone? {
        val verticesSection = extractArraySection(objText, "polygonVertices")
        val vertices = mutableListOf<GeoPoint>()
        if (verticesSection != null) {
            val ptRegex = Regex("""\{\s*"lat(?:itude)?"\s*:\s*(-?\d+(?:\.\d+)?)\s*,\s*"l(?:ng|on|ongitude)"\s*:\s*(-?\d+(?:\.\d+)?)\s*\}""")
            ptRegex.findAll(verticesSection).forEach { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                    vertices.add(GeoPoint(lat, lng))
                }
            }
        }
        // 兼容标准 GeoJSON [lng, lat] 数组格式
        if (vertices.isEmpty() && objText.contains("coordinates")) {
            val coordSection = extractArraySection(objText, "coordinates") ?: objText
            val pairRegex = Regex("""\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\]""")
            pairRegex.findAll(coordSection).forEach { m ->
                val first = m.groupValues[1].toDoubleOrNull()
                val second = m.groupValues[2].toDoubleOrNull()
                if (first != null && second != null) {
                    val (lat, lng) = if (kotlin.math.abs(first) > 90.0 && kotlin.math.abs(second) <= 90.0) {
                        second to first
                    } else {
                        first to second
                    }
                    if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                        vertices.add(GeoPoint(lat, lng))
                    }
                }
            }
        }

        val campusName = extractString(objText, "campusName")
            ?: extractString(objText, "name")
            ?: return null
        val campusId = extractString(objText, "campusId")
            ?: extractString(objText, "id")
            ?: "custom-${campusName.hashCode().toUInt().toString(16)}"
        val schoolName = extractString(objText, "schoolName") ?: "自定义学校"

        val avgLat = if (vertices.isNotEmpty()) vertices.map { it.latitude }.average() else null
        val avgLng = if (vertices.isNotEmpty()) vertices.map { it.longitude }.average() else null
        val centerLat = extractDouble(objText, "centerLat")
            ?: extractDouble(objText, "centerLatitude")
            ?: avgLat
            ?: return null
        val centerLng = extractDouble(objText, "centerLng")
            ?: extractDouble(objText, "centerLongitude")
            ?: avgLng
            ?: return null
        val radius = extractDouble(objText, "geofenceRadiusMeters")?.toInt()?.coerceIn(100, 20_000) ?: 2000

        val buildingsSection = extractArraySection(objText, "buildings")
        val buildings = mutableListOf<Building>()
        if (buildingsSection != null) {
            splitTopLevelJsonObjects(buildingsSection).forEachIndexed { idx, bObj ->
                val bName = extractString(bObj, "name") ?: return@forEachIndexed
                val bLat = extractDouble(bObj, "lat") ?: extractDouble(bObj, "latitude") ?: return@forEachIndexed
                val bLng = extractDouble(bObj, "lng") ?: extractDouble(bObj, "longitude") ?: return@forEachIndexed
                val bId = extractString(bObj, "id") ?: "${campusId}_b_$idx"
                val bShort = extractString(bObj, "shortName") ?: bName.take(6)
                val catName = extractString(bObj, "category") ?: CampusCategory.TEACHING_BUILDING.name
                val cat = runCatching { CampusCategory.valueOf(catName) }.getOrDefault(CampusCategory.TEACHING_BUILDING)
                val desc = extractString(bObj, "description") ?: "$campusName · 自定义地标"
                buildings.add(
                    Building(
                        id = bId,
                        campusId = campusId,
                        name = bName,
                        shortName = bShort,
                        latitude = bLat,
                        longitude = bLng,
                        category = cat,
                        entrances = listOf("正门"),
                        description = desc,
                        verifiedAt = LocalDate.of(2026, 3, 1)
                    )
                )
            }
        }

        return CustomCampusZone(
            campusId = campusId,
            campusName = campusName,
            schoolName = schoolName,
            centerLatitude = centerLat,
            centerLongitude = centerLng,
            polygonVertices = vertices,
            buildings = buildings,
            geofenceRadiusMeters = radius
        )
    }

    private fun extractString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val raw = regex.find(json)?.groupValues?.get(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
    }

    private fun extractDouble(json: String, key: String): Double? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""")
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractArraySection(json: String, key: String): String? {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        val openIdx = json.indexOf('[', keyIdx)
        if (openIdx < 0) return null
        var depth = 0
        var inQuotes = false
        var escaped = false
        for (i in openIdx until json.length) {
            val ch = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (!inQuotes) {
                if (ch == '[') depth++
                else if (ch == ']') {
                    depth--
                    if (depth == 0) return json.substring(openIdx, i + 1)
                }
            }
        }
        return null
    }

    private fun splitTopLevelJsonObjects(json: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var startIdx = -1
        var inQuotes = false
        var escaped = false
        for (i in json.indices) {
            val ch = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (!inQuotes) {
                if (ch == '{') {
                    if (depth == 0) startIdx = i
                    depth++
                } else if (ch == '}') {
                    depth--
                    if (depth == 0 && startIdx >= 0) {
                        result.add(json.substring(startIdx, i + 1))
                        startIdx = -1
                    }
                }
            }
        }
        return result
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

enum class WidgetType(val label: String, val gridSpec: String) {
    NEXT_CLASS_2X2("下一节课", "2×2"),
    TODAY_4X2("今日课程", "4×2"),
    TIMELINE_4X4("今日日程", "4×4"),
    WEEK_4X4("本周课表", "4×4"),
    PARCEL_4X2("取件码", "4×2")
}

enum class WidgetThemeStyle(val label: String) {
    DYNAMIC_SYSTEM("跟随系统动态色"),
    WARM_PEACH("暖橙强调色"),
    NEUTRAL_RATIONAL("中性灰")
}

data class WidgetInstanceConfig(
    val appWidgetId: Int,
    val widgetType: WidgetType,
    val termId: String? = null,
    val themeStyle: WidgetThemeStyle = WidgetThemeStyle.DYNAMIC_SYSTEM,
    val compact: Boolean = false,
    val showTeacher: Boolean = true,
    val showRoom: Boolean = true,
    val privacyMode: Boolean = false
)

enum class ImportChangeType(val label: String) {
    ADDED("新增课程"),
    MODIFIED("教务更新"),
    CONFLICT_USER_MODIFIED("冲突（保留用户修改）"),
    REMOVED_FROM_REMOTE("教务已移除"),
    UNCHANGED("无变化")
}

data class ImportCourseCandidate(
    val externalId: String?,
    val courseName: String,
    val teacher: String?,
    val credit: BigDecimal?,
    val weekday: java.time.DayOfWeek,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Set<Int>,
    val rawRoom: String
)

data class ImportDiffEntry(
    val changeType: ImportChangeType,
    val incoming: ImportCourseCandidate?,
    val existingCourse: Course?,
    val existingRule: CourseScheduleRule?,
    val summary: String,
    val keepUserEditByDefault: Boolean = changeType == ImportChangeType.CONFLICT_USER_MODIFIED
)

data class ImportDiffPreview(
    val termId: String,
    val source: CourseSource,
    val entries: List<ImportDiffEntry>,
    val addedCount: Int,
    val modifiedCount: Int,
    val conflictUserEditCount: Int,
    val unchangedCount: Int
)

data class ImportAudit(
    val id: String,
    val termId: String,
    val importSource: CourseSource,
    val importedAt: Instant,
    val rowCount: Int,
    val conflictCount: Int,
    val outcome: String
)

/**
 * 正方教务系统学生考试安排模型（兼容期初补缓考、期中随堂分散考试、期末集中考试）。
 */
data class ExamScheduleItem(
    val id: String,
    val termId: String,
    val courseCode: String,
    val courseName: String,
    val examBatchName: String,
    val examStageLabel: String,
    val examFormat: String,
    val rawTimeText: String,
    val examDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val rawRoom: String,
    val seatNumber: String? = null,
    val campusName: String = "莲花街校区",
    val isRetake: Boolean = false,
    val syncedAt: Instant = Instant.now()
)
