package cn.edu.haut.campus.core.automation.android

import android.content.Context
import cn.edu.haut.campus.core.automation.AutomationKeys
import cn.edu.haut.campus.core.automation.UtilityBalanceReadPort
import cn.edu.haut.campus.core.automation.UtilityBalanceReading
import cn.edu.haut.campus.core.automation.WeatherCachedResponse
import cn.edu.haut.campus.core.automation.WeatherLocationPort
import cn.edu.haut.campus.core.automation.WeatherResponseCacheStore
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CustomCampusZoneCodec
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 天气位置只使用用户自定义校区中心或既有校区级粗围栏质心。
 * 不读取 GPS、不把精确位置交给天气 Provider；找不到校区级证据时返回 null，条件必须为 UNKNOWN。
 */
class RepositoryWeatherLocationPort(
    private val repository: CampusRepository
) : WeatherLocationPort {
    override suspend fun referencePoint(scope: SchoolAccountScope, campusId: CampusId?): GeoPoint? {
        val target = campusId ?: scope.campusId ?: return null
        val preferences = repository.preferencesFlow.first()
        val custom = runCatching { CustomCampusZoneCodec.decodeList(preferences.customCampusZonesJson) }
            .getOrDefault(emptyList())
            .firstOrNull { it.campusId == target.value }
        if (custom != null) return GeoPoint(custom.centerLatitude, custom.centerLongitude)

        val polygon = CoarseGeofenceMonitor.CAMPUS_POLYGONS[target].orEmpty()
        if (polygon.isEmpty()) return null
        return GeoPoint(
            latitude = polygon.map { it.latitude }.average(),
            longitude = polygon.map { it.longitude }.average()
        )
    }
}

/**
 * 直接读取当前在线水电缓存。采样读取与 outbox 事件发布是两条分离语义：
 * 余额不变时不会产生新事件，但只要上游缓存仍在新近刷新，状态条件就必须看到“最新读数”；
 * 只读本地缓存，不触发新的网络采样，也不把投影发生时间伪造成来源采样时间。
 */
class RoomUtilityBalanceReadPort(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope
) : UtilityBalanceReadPort {
    override suspend fun latestSamples(scope: SchoolAccountScope): List<UtilityBalanceReading> {
        if (scope != this.scope) return emptyList()
        val readings = ArrayList<UtilityBalanceReading>()
        for (room in database.utilityAndFocusDao().getOnlineRoomsSnapshot()) {
            if (room.failed || room.updatedAtEpochMs <= 0L) continue
            val sampledAt = Instant.ofEpochMilli(room.updatedAtEpochMs)
            val devices = runCatching { decodeOnlineDevicesJson(room.devicesJson) }.getOrDefault(emptyList())
            for (device in devices) {
                val balanceCents = parseUtilityBalanceCents(device.balanceRaw) ?: continue
                val meterId = "online/" + device.category.name.lowercase() + "/" + room.roomId + "/" + device.name
                val provenance = DataProvenance(
                    providerId = "wanxiao.utility",
                    schoolId = scope.schoolId,
                    accountId = scope.accountId,
                    sourceSystem = "online-utility-cache",
                    sourceRecordId = meterId,
                    fetchedAt = sampledAt,
                    completeness = SnapshotCompleteness.UNKNOWN,
                    trust = VerificationLevel.SOURCE_CONFIRMED,
                    schemaVersion = 1
                )
                readings += UtilityBalanceReading(
                    meterId = meterId,
                    balanceCents = balanceCents,
                    sampledAt = sampledAt,
                    provenance = provenance
                )
            }
        }
        return readings
    }
}

/**
 * 按 Profile 隔离的天气原始响应文件缓存。文件名只存 scope+坐标的散列，不写账号、校区或坐标明文；
 * 损坏/过大文件按读取失败处理，让 Provider fail-closed，而不是绕过 3 小时频率约束。
 */
class FileWeatherResponseCacheStore(
    context: Context,
    scope: SchoolAccountScope
) : WeatherResponseCacheStore {
    private val directory = File(
        context.cacheDir,
        "automation-weather/" + AutomationKeys.sha256Hex(
            scope.schoolId.value + "|" + scope.accountId.value + "|" + scope.profileId.value
        ).substring(0, 24)
    )

    override suspend fun read(key: String): WeatherCachedResponse? = withContext(Dispatchers.IO) {
        val file = cacheFile(key) ?: return@withContext null
        if (!file.isFile) return@withContext null
        if (file.length() > MAX_CACHE_FILE_BYTES) throw IOException("天气缓存文件过大")
        val text = file.readText(Charsets.UTF_8)
        decodeCachedResponse(text) ?: throw IOException("天气缓存文件损坏")
    }

    override suspend fun write(key: String, value: WeatherCachedResponse) = withContext(Dispatchers.IO) {
        val file = cacheFile(key) ?: throw IOException("天气缓存键非法")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("天气缓存目录不可写")
        val encoded = encodeCachedResponse(value)
        val tmp = File(directory, file.name + ".tmp")
        tmp.writeText(encoded, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(encoded, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun cacheFile(key: String): File? {
        if (!CACHE_KEY_REGEX.matches(key)) return null
        return File(directory, key + ".json")
    }

    private companion object {
        const val MAX_CACHE_FILE_BYTES: Long = 384 * 1024L
        val CACHE_KEY_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

private fun encodeCachedResponse(value: WeatherCachedResponse): String = Json.canonical(
    JsonValue.Obj(
        mapOf(
            "body" to JsonValue.Str(value.body),
            "fetchedAtEpochMs" to JsonValue.Num(value.fetchedAt.toEpochMilli().toString()),
            "cacheExpiresAtEpochMs" to JsonValue.Num(value.cacheExpiresAt.toEpochMilli().toString()),
            "nextRequestAtEpochMs" to JsonValue.Num(value.nextRequestAt.toEpochMilli().toString()),
            "usableAsEvidence" to JsonValue.Bool(value.usableAsEvidence)
        ) + (value.contentRevision?.let { mapOf("contentRevision" to JsonValue.Str(it)) } ?: emptyMap())
    )
)

private fun decodeCachedResponse(text: String): WeatherCachedResponse? = runCatching {
    val root = Json.parse(text) as? JsonValue.Obj ?: return@runCatching null
    val body = root.str("body") ?: return@runCatching null
    val fetchedAt = root.obj("fetchedAtEpochMs")?.epochMillisOrNull() ?: return@runCatching null
    val cacheExpiresAt = root.obj("cacheExpiresAtEpochMs")?.epochMillisOrNull() ?: return@runCatching null
    val nextRequestAt = root.obj("nextRequestAtEpochMs")?.epochMillisOrNull() ?: return@runCatching null
    WeatherCachedResponse(
        body = body,
        fetchedAt = fetchedAt,
        cacheExpiresAt = cacheExpiresAt,
        nextRequestAt = nextRequestAt,
        contentRevision = root.str("contentRevision"),
        usableAsEvidence = root.obj("usableAsEvidence")?.boolOrNull() ?: true
    )
}.getOrNull()

private fun JsonValue.epochMillisOrNull(): Instant? =
    (this as? JsonValue.Num)?.literal?.toLongOrNull()?.let(Instant::ofEpochMilli)

