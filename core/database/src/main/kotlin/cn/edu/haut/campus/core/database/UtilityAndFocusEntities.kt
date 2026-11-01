package cn.edu.haut.campus.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.campusservices.OnlineUtilityDevice
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.UtilityMeterCategory
import cn.edu.haut.campus.core.model.FocusCompletionSource
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.FocusSessionStatus
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1. 每日电表采样表 `utility_daily_samples`
 */
@Entity(
    tableName = "utility_daily_samples",
    indices = [
        Index(value = ["roomId", "deviceName", "dateText"], unique = true),
        Index(value = ["sampledAtEpochMs"])
    ]
)
data class UtilityDailySampleEntity(
    @PrimaryKey
    val id: String,
    val roomId: String,
    val roomName: String,
    val deviceName: String,
    val dateText: String,
    val usedValue: Double,
    val unit: String,
    val sampledAtEpochMs: Long
)

fun UtilityDailySampleEntity.toDomain(): UtilityDailySample = UtilityDailySample(
    id = id,
    roomId = roomId,
    roomName = roomName,
    deviceName = deviceName,
    dateText = dateText,
    usedValue = usedValue,
    unit = unit,
    sampledAtEpochMs = sampledAtEpochMs
)

fun UtilityDailySample.toEntity(): UtilityDailySampleEntity = UtilityDailySampleEntity(
    id = id,
    roomId = roomId,
    roomName = roomName,
    deviceName = deviceName,
    dateText = dateText,
    usedValue = usedValue,
    unit = unit,
    sampledAtEpochMs = sampledAtEpochMs
)

/**
 * 2. 在线绑定房间缓存表 `utility_online_rooms`（roomId 为 SHA-256(roomverify)，严禁持久化原始 roomverify）
 */
@Entity(
    tableName = "utility_online_rooms",
    indices = [
        Index(value = ["updatedAtEpochMs"])
    ]
)
data class UtilityOnlineRoomEntity(
    @PrimaryKey
    val roomId: String,
    val roomName: String,
    val campusCode: String?,
    val devicesJson: String,
    val updatedAtEpochMs: Long,
    val failed: Boolean
)

fun UtilityOnlineRoomEntity.toDomain(): UtilityOnlineRoomDomain = UtilityOnlineRoomDomain(
    roomId = roomId,
    roomName = roomName,
    campusCode = campusCode,
    devicesJson = devicesJson,
    updatedAtEpochMs = updatedAtEpochMs,
    failed = failed
)

fun encodeOnlineDevicesJson(devices: List<OnlineUtilityDevice>): String {
    val array = JSONArray()
    for (d in devices) {
        array.put(
            JSONObject()
                .put("name", d.name)
                .put("category", d.category.code)
                .put("balanceRaw", d.balanceRaw)
                .put("todayUseRaw", d.todayUseRaw)
                .put("totalBoughtRaw", d.totalBoughtRaw)
                .put("statusRaw", d.statusRaw)
        )
    }
    return array.toString()
}

fun decodeOnlineDevicesJson(rawJson: String): List<OnlineUtilityDevice> {
    if (rawJson.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawJson)
        (0 until array.length()).mapNotNull { idx ->
            val obj = array.optJSONObject(idx) ?: return@mapNotNull null
            val cat = UtilityMeterCategory.fromProtocolCode(obj.optString("category", "0"))
                ?: UtilityMeterCategory.ELECTRICITY
            OnlineUtilityDevice(
                name = obj.optString("name", "—"),
                category = cat,
                balanceRaw = obj.optString("balanceRaw", "—"),
                todayUseRaw = obj.optString("todayUseRaw", "—"),
                totalBoughtRaw = obj.optString("totalBoughtRaw", "—"),
                statusRaw = obj.optString("statusRaw", "—")
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * 3. 手工水电表计表 `utility_meters`
 */
@Entity(
    tableName = "utility_meters",
    indices = [
        Index(value = ["createdAtEpochMs"])
    ]
)
data class UtilityMeterEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val campusName: String,
    val roomName: String,
    val unit: String,
    val lowThreshold: Double,
    val createdAtEpochMs: Long
)

fun UtilityMeterEntity.toDomain(): UtilityMeterDomain = UtilityMeterDomain(
    id = id,
    name = name,
    campusName = campusName,
    roomName = roomName,
    unit = unit,
    lowThreshold = lowThreshold,
    createdAtEpochMs = createdAtEpochMs
)

fun UtilityMeterDomain.toEntity(): UtilityMeterEntity = UtilityMeterEntity(
    id = id,
    name = name,
    campusName = campusName,
    roomName = roomName,
    unit = unit,
    lowThreshold = lowThreshold,
    createdAtEpochMs = createdAtEpochMs
)

/**
 * 4. 手工水电读数记录表 `utility_readings`
 */
@Entity(
    tableName = "utility_readings",
    indices = [
        Index(value = ["meterId", "recordedAtEpochMs"])
    ]
)
data class UtilityReadingEntity(
    @PrimaryKey
    val id: String,
    val meterId: String,
    val value: Double,
    val topUpValue: Double,
    val recordedAtEpochMs: Long,
    val note: String
)

fun UtilityReadingEntity.toDomain(): UtilityReadingDomain = UtilityReadingDomain(
    id = id,
    meterId = meterId,
    value = value,
    topUpValue = topUpValue,
    recordedAtEpochMs = recordedAtEpochMs,
    note = note
)

fun UtilityReadingDomain.toEntity(): UtilityReadingEntity = UtilityReadingEntity(
    id = id,
    meterId = meterId,
    value = value,
    topUpValue = topUpValue,
    recordedAtEpochMs = recordedAtEpochMs,
    note = note
)

/**
 * 5. 自习专注会话表 `focus_sessions`（与 `:core:reminder` 的 `reminder_tasks` 表物理隔离）
 */
@Entity(
    tableName = "focus_sessions",
    indices = [
        Index(value = ["status", "expectedEndAtEpochMs"]),
        Index(value = ["startedAtEpochMs"])
    ]
)
data class FocusSessionEntity(
    @PrimaryKey
    val id: String,
    val generation: Int,
    val durationMinutes: Int,
    val startedAtEpochMs: Long,
    val expectedEndAtEpochMs: Long,
    val status: String,
    val scheduleMode: String,
    val completedAtEpochMs: Long?,
    val cancelledAtEpochMs: Long?,
    val completionSource: String?,
    val notified: Boolean,
    val cancelReason: String?,
    val updatedAtEpochMs: Long
)

fun FocusSessionEntity.toDomain(): FocusSessionDomain = FocusSessionDomain(
    id = id,
    generation = generation,
    durationMinutes = durationMinutes,
    startedAtEpochMs = startedAtEpochMs,
    expectedEndAtEpochMs = expectedEndAtEpochMs,
    status = FocusSessionStatus.fromName(status),
    scheduleMode = ReminderAccuracyMode.fromName(scheduleMode),
    completedAtEpochMs = completedAtEpochMs,
    cancelledAtEpochMs = cancelledAtEpochMs,
    completionSource = FocusCompletionSource.fromName(completionSource),
    notified = notified,
    cancelReason = cancelReason,
    updatedAtEpochMs = updatedAtEpochMs
)

fun FocusSessionDomain.toEntity(): FocusSessionEntity = FocusSessionEntity(
    id = id,
    generation = generation,
    durationMinutes = durationMinutes,
    startedAtEpochMs = startedAtEpochMs,
    expectedEndAtEpochMs = expectedEndAtEpochMs,
    status = status.name,
    scheduleMode = scheduleMode.name,
    completedAtEpochMs = completedAtEpochMs,
    cancelledAtEpochMs = cancelledAtEpochMs,
    completionSource = completionSource?.name,
    notified = notified,
    cancelReason = cancelReason,
    updatedAtEpochMs = updatedAtEpochMs
)

@Dao
interface UtilityAndFocusDao {

    // --- 每日电表采样 ---
    @Query("SELECT * FROM utility_daily_samples ORDER BY dateText ASC, sampledAtEpochMs ASC")
    fun observeDailySamples(): Flow<List<UtilityDailySampleEntity>>

    @Query("SELECT * FROM utility_daily_samples ORDER BY dateText ASC, sampledAtEpochMs ASC")
    suspend fun getDailySamplesSnapshot(): List<UtilityDailySampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailySamples(samples: List<UtilityDailySampleEntity>)

    @Query("DELETE FROM utility_daily_samples")
    suspend fun clearAllDailySamples()

    // --- 在线房间缓存 ---
    @Query("SELECT * FROM utility_online_rooms ORDER BY roomName ASC")
    fun observeOnlineRooms(): Flow<List<UtilityOnlineRoomEntity>>

    @Query("SELECT * FROM utility_online_rooms ORDER BY roomName ASC")
    suspend fun getOnlineRoomsSnapshot(): List<UtilityOnlineRoomEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOnlineRooms(rooms: List<UtilityOnlineRoomEntity>)

    @Query("UPDATE utility_online_rooms SET campusCode = :campusCode WHERE roomId = :roomId")
    suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?)

    @Query("DELETE FROM utility_online_rooms")
    suspend fun clearAllOnlineRooms()

    // --- 手工水电表计与读数 ---
    @Query("SELECT * FROM utility_meters ORDER BY createdAtEpochMs ASC")
    fun observeMeters(): Flow<List<UtilityMeterEntity>>

    @Query("SELECT * FROM utility_readings ORDER BY recordedAtEpochMs ASC")
    fun observeReadings(): Flow<List<UtilityReadingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeter(meter: UtilityMeterEntity)

    @Query("DELETE FROM utility_meters WHERE id = :meterId")
    suspend fun deleteMeterById(meterId: String)

    @Query("DELETE FROM utility_readings WHERE meterId = :meterId")
    suspend fun deleteReadingsByMeterId(meterId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReading(reading: UtilityReadingEntity)

    @Query("DELETE FROM utility_readings WHERE id = :readingId")
    suspend fun deleteReadingById(readingId: String)

    @Query("DELETE FROM utility_meters")
    suspend fun clearAllMeters()

    @Query("DELETE FROM utility_readings")
    suspend fun clearAllReadings()

    // --- 自习专注会话 ---
    @Query("SELECT * FROM focus_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getFocusSessionById(sessionId: String): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE status = 'RUNNING' ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getActiveRunningFocusSessionSnapshot(): FocusSessionEntity?

    @Query("SELECT * FROM focus_sessions WHERE status = 'RUNNING' ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeActiveRunningFocusSession(): Flow<FocusSessionEntity?>

    @Query("SELECT * FROM focus_sessions WHERE status = 'COMPLETED' ORDER BY completedAtEpochMs DESC")
    fun observeCompletedFocusSessions(): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE status = 'COMPLETED' ORDER BY completedAtEpochMs DESC")
    suspend fun getCompletedFocusSessionsSnapshot(): List<FocusSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFocusSession(session: FocusSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFocusSessions(sessions: List<FocusSessionEntity>)

    @Query(
        """
        UPDATE focus_sessions
        SET status = 'COMPLETED',
            generation = generation + 1,
            completedAtEpochMs = expectedEndAtEpochMs,
            completionSource = :completionSource,
            notified = :notified,
            updatedAtEpochMs = :nowMs
        WHERE id = :sessionId
          AND generation = :expectedGeneration
          AND expectedEndAtEpochMs = :expectedEndAtEpochMs
          AND status = 'RUNNING'
          AND :nowMs >= expectedEndAtEpochMs
        """
    )
    suspend fun compareAndCompleteFocusSession(
        sessionId: String,
        expectedGeneration: Int,
        expectedEndAtEpochMs: Long,
        completionSource: String,
        notified: Boolean,
        nowMs: Long
    ): Int

    @Query(
        """
        UPDATE focus_sessions
        SET status = 'CANCELLED',
            generation = generation + 1,
            cancelledAtEpochMs = :nowMs,
            cancelReason = :reason,
            updatedAtEpochMs = :nowMs
        WHERE id = :sessionId
          AND generation = :expectedGeneration
          AND status = 'RUNNING'
        """
    )
    suspend fun compareAndCancelFocusSession(
        sessionId: String,
        expectedGeneration: Int,
        reason: String,
        nowMs: Long
    ): Int

    @Query(
        """
        UPDATE focus_sessions
        SET scheduleMode = :scheduleMode,
            updatedAtEpochMs = :nowMs
        WHERE id = :sessionId
          AND generation = :expectedGeneration
          AND status = 'RUNNING'
        """
    )
    suspend fun updateFocusScheduleModeIfMatching(
        sessionId: String,
        expectedGeneration: Int,
        scheduleMode: String,
        nowMs: Long
    ): Int

    @Query("DELETE FROM focus_sessions")
    suspend fun clearAllFocusSessions()
}

/**
 * Room 数据库 v3 -> v4 无损迁移：
 * 新增 `utility_daily_samples`、`utility_online_rooms`、`utility_meters`、`utility_readings`、`focus_sessions` 五张表及对应索引。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. utility_daily_samples
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `utility_daily_samples` (
                `id` TEXT NOT NULL,
                `roomId` TEXT NOT NULL,
                `roomName` TEXT NOT NULL,
                `deviceName` TEXT NOT NULL,
                `dateText` TEXT NOT NULL,
                `usedValue` REAL NOT NULL,
                `unit` TEXT NOT NULL,
                `sampledAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_utility_daily_samples_roomId_deviceName_dateText` ON `utility_daily_samples` (`roomId`, `deviceName`, `dateText`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_utility_daily_samples_sampledAtEpochMs` ON `utility_daily_samples` (`sampledAtEpochMs`)"
        )

        // 2. utility_online_rooms
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `utility_online_rooms` (
                `roomId` TEXT NOT NULL,
                `roomName` TEXT NOT NULL,
                `campusCode` TEXT,
                `devicesJson` TEXT NOT NULL,
                `updatedAtEpochMs` INTEGER NOT NULL,
                `failed` INTEGER NOT NULL,
                PRIMARY KEY(`roomId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_utility_online_rooms_updatedAtEpochMs` ON `utility_online_rooms` (`updatedAtEpochMs`)"
        )

        // 3. utility_meters
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `utility_meters` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `campusName` TEXT NOT NULL,
                `roomName` TEXT NOT NULL,
                `unit` TEXT NOT NULL,
                `lowThreshold` REAL NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_utility_meters_createdAtEpochMs` ON `utility_meters` (`createdAtEpochMs`)"
        )

        // 4. utility_readings
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `utility_readings` (
                `id` TEXT NOT NULL,
                `meterId` TEXT NOT NULL,
                `value` REAL NOT NULL,
                `topUpValue` REAL NOT NULL,
                `recordedAtEpochMs` INTEGER NOT NULL,
                `note` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_utility_readings_meterId_recordedAtEpochMs` ON `utility_readings` (`meterId`, `recordedAtEpochMs`)"
        )

        // 5. focus_sessions
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `focus_sessions` (
                `id` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `durationMinutes` INTEGER NOT NULL,
                `startedAtEpochMs` INTEGER NOT NULL,
                `expectedEndAtEpochMs` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `scheduleMode` TEXT NOT NULL,
                `completedAtEpochMs` INTEGER,
                `cancelledAtEpochMs` INTEGER,
                `completionSource` TEXT,
                `notified` INTEGER NOT NULL,
                `cancelReason` TEXT,
                `updatedAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_sessions_status_expectedEndAtEpochMs` ON `focus_sessions` (`status`, `expectedEndAtEpochMs`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_focus_sessions_startedAtEpochMs` ON `focus_sessions` (`startedAtEpochMs`)"
        )
    }
}
