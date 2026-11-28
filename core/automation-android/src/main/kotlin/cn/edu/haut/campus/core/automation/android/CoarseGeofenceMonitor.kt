package cn.edu.haut.campus.core.automation.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.automation.CampusPresence
import cn.edu.haut.campus.core.automation.CampusPresencePort
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import java.time.Instant

object CoarseGeofencePolicy {
    private val MAX_SAMPLE_AGE: java.time.Duration = java.time.Duration.ofHours(2)
    private const val MAX_ACCURACY_METERS = 1000f

    /** 发射回执与条件求值共用同一新鲜度/精度门禁；无精度或超龄位置一律不可用。 */
    fun isUsable(locationTimeEpochMs: Long, accuracyMeters: Float?, now: Instant): Boolean {
        val accuracy = accuracyMeters ?: return false
        if (!accuracy.isFinite() || accuracy < 0f || accuracy > MAX_ACCURACY_METERS) return false
        val sampledAt = Instant.ofEpochMilli(locationTimeEpochMs)
        return java.time.Duration.between(sampledAt, now).abs() <= MAX_SAMPLE_AGE
    }
}

/**
 * 低频率粗定位围栏采样器。只在使用 WorkManager/闹钟批量唤醒时读取 lastKnownLocation，
 * 不注册常驻定位监听；跨边界事实写入 domain_outbox，进程死亡后由 outbox 消费补处理。
 */
class CoarseGeofenceMonitor(
    private val context: Context,
    private val database: HautDatabase,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now
) : CampusPresencePort {
    private val outbox = RoomEventOutbox(database)
    private val codecRegistry = DomainEventCodecRegistry.DEFAULT

    suspend fun sampleAndPublish(): Int {
        val location = bestCoarseLocation() ?: return 0
        val now = clock()
        if (!CoarseGeofencePolicy.isUsable(location.time, location.takeIf { it.hasAccuracy() }?.accuracy, now)) return 0
        val transitions = existingGeofenceTransitions()
        var published = 0
        for ((campusId, polygon) in CAMPUS_POLYGONS) {
            val inside = pointInPolygon(location.latitude, location.longitude, polygon)
            val previous = transitions
                .filter { it.campusId == campusId }
                .maxByOrNull { it.occurredAt }
            val shouldEnter = inside && previous?.enter != true
            val shouldExit = !inside && previous?.enter == true
            if (!shouldEnter && !shouldExit) continue
            val event = DomainEvent.GeofenceTransition(
                eventId = "geo-" + campusId.value + "-" + (if (inside) "enter" else "exit") + "-" + location.time,
                scope = scope,
                occurredAt = now,
                campusId = campusId,
                enter = inside,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else MAX_EMIT_ACCURACY_METERS
            )
            outbox.append(codecRegistry.encode(event))
            published++
        }
        return published
    }

    override suspend fun currentPresence(scope: SchoolAccountScope, now: Instant): CampusPresence? {
        require(scope == this.scope) { "粗定位在校状态 scope 不一致" }
        val location = bestCoarseLocation() ?: return null
        val sampledAt = Instant.ofEpochMilli(location.time)
        if (!CoarseGeofencePolicy.isUsable(location.time, location.accuracy, now)) return null
        val insideCampus = CAMPUS_POLYGONS.entries.firstOrNull { (_, polygon) ->
            pointInPolygon(location.latitude, location.longitude, polygon)
        }?.key
        return CampusPresence(
            campusId = insideCampus,
            inside = insideCampus != null,
            accuracyMeters = location.accuracy,
            sampledAt = sampledAt
        )
    }

    private suspend fun existingGeofenceTransitions(): List<DomainEvent.GeofenceTransition> =
        database.vaultDao().getOutboxSnapshot().mapNotNull { row ->
            if (row.eventType != "geofence.transition") return@mapNotNull null
            runCatching {
                codecRegistry.decode(
                    cn.edu.haut.campus.core.events.CanonicalEventEnvelopeCodec.decode(
                        cn.edu.haut.campus.core.events.CanonicalJson.of(row.envelopeJson)
                    )
                ) as? DomainEvent.GeofenceTransition
            }.getOrNull()
        }.filter { it.scope == scope }

    // 先显式检查 COARSE/FINE 任一授权；lint 无法沿 getProviders/getLastKnownLocation 的控制流识别。
    @SuppressLint("MissingPermission")
    private fun bestCoarseLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { manager.getProviders(false) }.getOrDefault(emptyList())
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun pointInPolygon(latitude: Double, longitude: Double, polygon: List<GeoPoint>): Boolean {
        var inside = false
        var index = 0
        var previous = polygon.lastIndex
        while (index < polygon.size) {
            val a = polygon[index]
            val b = polygon[previous]
            val intersects = (a.longitude > longitude) != (b.longitude > longitude) &&
                latitude < (b.latitude - a.latitude) * (longitude - a.longitude) / (b.longitude - a.longitude) + a.latitude
            if (intersects) inside = !inside
            previous = index
            index++
        }
        return inside
    }

    companion object {
        /** W2-02 首期内置粗围栏；地点精度只到校区级，绝不请求或保存精确轨迹。 */
        val CAMPUS_POLYGONS: Map<CampusId, List<GeoPoint>> = linkedMapOf(
            CampusId("haut-lianhuajie") to ClassroomBuildingResolver.HAUT_LIANHUAJIE_POLYGON,
            CampusId("haut-songshanlu") to ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
        )
    }
}
