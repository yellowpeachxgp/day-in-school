package cn.edu.haut.campus.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "reminder_tasks",
    indices = [
        Index(value = ["taskKey"], unique = true),
        Index(value = ["status", "scheduledAtEpochMs"]),
        Index(value = ["eventKey", "status"]),
        Index(value = ["schedulerState", "scheduledAtEpochMs"])
    ]
)
data class ReminderTaskEntity(
    @PrimaryKey
    val id: String,
    val taskKey: String,
    val eventKey: String,
    val eventType: String,
    val reminderKind: String,
    val eventRevision: String,
    val policyRevision: String,
    val eventStartAtEpochMs: Long,
    val originalScheduledAtEpochMs: Long,
    val scheduledAtEpochMs: Long,
    val status: String,
    val schedulerState: String,
    val generation: Int,
    val snoozeCount: Int,
    val maxSnoozes: Int,
    val claimedAtEpochMs: Long?,
    val deliveredAtEpochMs: Long?,
    val lastActionAtEpochMs: Long?,
    val scheduleMode: String,
    val lastErrorCode: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

fun ReminderTaskEntity.toDomain(): ReminderTaskDomain = ReminderTaskDomain(
    id = id,
    taskKey = taskKey,
    eventKey = eventKey,
    eventType = runCatching { ReminderEventType.valueOf(eventType) }.getOrDefault(ReminderEventType.CLASS),
    reminderKind = ReminderKind.fromCode(reminderKind),
    eventRevision = eventRevision,
    policyRevision = policyRevision,
    eventStartAtEpochMs = eventStartAtEpochMs,
    originalScheduledAtEpochMs = originalScheduledAtEpochMs,
    scheduledAtEpochMs = scheduledAtEpochMs,
    status = ReminderTaskStatus.fromName(status),
    schedulerState = ReminderSchedulerState.fromName(schedulerState),
    generation = generation,
    snoozeCount = snoozeCount,
    maxSnoozes = maxSnoozes,
    claimedAtEpochMs = claimedAtEpochMs,
    deliveredAtEpochMs = deliveredAtEpochMs,
    lastActionAtEpochMs = lastActionAtEpochMs,
    scheduleMode = ReminderAccuracyMode.fromName(scheduleMode),
    lastErrorCode = lastErrorCode,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

fun ReminderTaskDomain.toEntity(): ReminderTaskEntity = ReminderTaskEntity(
    id = id,
    taskKey = taskKey,
    eventKey = eventKey,
    eventType = eventType.name,
    reminderKind = reminderKind.code,
    eventRevision = eventRevision,
    policyRevision = policyRevision,
    eventStartAtEpochMs = eventStartAtEpochMs,
    originalScheduledAtEpochMs = originalScheduledAtEpochMs,
    scheduledAtEpochMs = scheduledAtEpochMs,
    status = status.name,
    schedulerState = schedulerState.name,
    generation = generation,
    snoozeCount = snoozeCount,
    maxSnoozes = maxSnoozes,
    claimedAtEpochMs = claimedAtEpochMs,
    deliveredAtEpochMs = deliveredAtEpochMs,
    lastActionAtEpochMs = lastActionAtEpochMs,
    scheduleMode = scheduleMode.name,
    lastErrorCode = lastErrorCode,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

@Entity(tableName = "reminder_event_prefs")
data class ReminderEventPrefEntity(
    @PrimaryKey
    val eventScopeKey: String,
    val enabledOverride: Boolean?,
    val leadMinutesOverride: Int?,
    val maxSnoozesOverride: Int?,
    val updatedAtEpochMs: Long
)

fun ReminderEventPrefEntity.toDomain(): ReminderEventOverride = ReminderEventOverride(
    eventScopeKey = eventScopeKey,
    enabledOverride = enabledOverride,
    leadMinutesOverride = leadMinutesOverride,
    maxSnoozesOverride = maxSnoozesOverride,
    updatedAtEpochMs = updatedAtEpochMs
)

fun ReminderEventOverride.toEntity(): ReminderEventPrefEntity = ReminderEventPrefEntity(
    eventScopeKey = eventScopeKey,
    enabledOverride = enabledOverride,
    leadMinutesOverride = leadMinutesOverride,
    maxSnoozesOverride = maxSnoozesOverride,
    updatedAtEpochMs = updatedAtEpochMs
)

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminder_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: String): ReminderTaskEntity?

    @Query("SELECT * FROM reminder_tasks WHERE taskKey = :taskKey LIMIT 1")
    suspend fun getByTaskKey(taskKey: String): ReminderTaskEntity?

    @Query(
        """
        SELECT * FROM reminder_tasks
        WHERE eventStartAtEpochMs >= :windowStartMs AND eventStartAtEpochMs <= :windowEndMs
        ORDER BY scheduledAtEpochMs ASC
        """
    )
    suspend fun getTasksInEventWindow(windowStartMs: Long, windowEndMs: Long): List<ReminderTaskEntity>

    @Query(
        """
        SELECT * FROM reminder_tasks
        WHERE status IN ('PENDING', 'SNOOZED', 'CLAIMED', 'FIRED')
        ORDER BY scheduledAtEpochMs ASC
        """
    )
    suspend fun getAllActiveTasksSnapshot(): List<ReminderTaskEntity>

    @Query(
        """
        SELECT * FROM reminder_tasks
        WHERE status IN ('PENDING', 'SNOOZED', 'FIRED')
        ORDER BY scheduledAtEpochMs ASC
        """
    )
    fun observeActiveTasks(): Flow<List<ReminderTaskEntity>>

    @Query(
        """
        SELECT * FROM reminder_tasks
        WHERE eventStartAtEpochMs >= :windowStartMs AND eventStartAtEpochMs <= :windowEndMs
        ORDER BY scheduledAtEpochMs ASC
        """
    )
    fun observeTasksInEventWindow(windowStartMs: Long, windowEndMs: Long): Flow<List<ReminderTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTasks(items: List<ReminderTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(item: ReminderTaskEntity)

    /**
     * 原子认领到期闹钟任务：
     * 仅当 generation 严格匹配且当前状态处于 PENDING 或 SNOOZED 时才切为 CLAIMED。
     */
    @Query(
        """
        UPDATE reminder_tasks
        SET status = 'CLAIMED',
            claimedAtEpochMs = :nowMs,
            updatedAtEpochMs = :nowMs
        WHERE id = :taskId
          AND generation = :expectedGeneration
          AND status IN ('PENDING', 'SNOOZED')
        """
    )
    suspend fun claimFire(
        taskId: String,
        expectedGeneration: Int,
        nowMs: Long
    ): Int

    /**
     * 通知成功发送后，原子推进到 FIRED。
     */
    @Query(
        """
        UPDATE reminder_tasks
        SET status = 'FIRED',
            deliveredAtEpochMs = :nowMs,
            lastErrorCode = NULL,
            updatedAtEpochMs = :nowMs
        WHERE id = :taskId
          AND generation = :expectedGeneration
          AND status IN ('CLAIMED', 'PENDING', 'SNOOZED')
        """
    )
    suspend fun markFired(
        taskId: String,
        expectedGeneration: Int,
        nowMs: Long
    ): Int

    /**
     * 回收超时的 CLAIMED 孤儿任务，恢复为 SNOOZED 或 PENDING，以便 Reconciler 重新评估。
     */
    @Query(
        """
        UPDATE reminder_tasks
        SET status = CASE WHEN snoozeCount > 0 THEN 'SNOOZED' ELSE 'PENDING' END,
            schedulerState = 'NEEDS_SCHEDULE',
            lastErrorCode = 'CLAIMED_TIMEOUT_RECOVERED',
            updatedAtEpochMs = :nowMs
        WHERE status = 'CLAIMED'
          AND updatedAtEpochMs < :staleBeforeMs
        """
    )
    suspend fun recoverStaleClaimedTasks(
        staleBeforeMs: Long,
        nowMs: Long
    ): Int

    /**
     * 原子执行「稍后提醒」状态推进（CAS）：
     * - 校验 generation == expectedGeneration
     * - 校验 eventRevision == expectedEventRevision
     * - 校验 snoozeCount < maxSnoozes
     * - 校验 newTriggerMs < eventStartAtEpochMs
     * - 推进 generation = generation + 1, snoozeCount = snoozeCount + 1, status = 'SNOOZED'
     */
    @Query(
        """
        UPDATE reminder_tasks
        SET status = 'SNOOZED',
            scheduledAtEpochMs = :newTriggerMs,
            snoozeCount = snoozeCount + 1,
            generation = generation + 1,
            schedulerState = 'NEEDS_SCHEDULE',
            lastActionAtEpochMs = :nowMs,
            lastErrorCode = NULL,
            updatedAtEpochMs = :nowMs
        WHERE id = :taskId
          AND generation = :expectedGeneration
          AND eventRevision = :expectedEventRevision
          AND status IN ('PENDING', 'SNOOZED', 'FIRED')
          AND snoozeCount < maxSnoozes
          AND :newTriggerMs < eventStartAtEpochMs
        """
    )
    suspend fun compareAndSnooze(
        taskId: String,
        expectedGeneration: Int,
        expectedEventRevision: String,
        newTriggerMs: Long,
        nowMs: Long
    ): Int

    /**
     * 仅当 generation 未被其他协程推进时，才写回 AlarmManager 注册结果。
     */
    @Query(
        """
        UPDATE reminder_tasks
        SET schedulerState = :schedulerState,
            scheduleMode = :scheduleMode,
            lastErrorCode = :errorCode,
            updatedAtEpochMs = :nowMs
        WHERE id = :taskId
          AND generation = :expectedGeneration
        """
    )
    suspend fun markScheduleResultIfGenerationMatches(
        taskId: String,
        expectedGeneration: Int,
        schedulerState: String,
        scheduleMode: String,
        errorCode: String?,
        nowMs: Long
    ): Int

    @Query(
        """
        UPDATE reminder_tasks
        SET status = :terminalStatus,
            schedulerState = 'NONE',
            generation = generation + 1,
            lastErrorCode = :reason,
            updatedAtEpochMs = :nowMs
        WHERE id = :taskId
        """
    )
    suspend fun markTerminal(
        taskId: String,
        terminalStatus: String,
        reason: String?,
        nowMs: Long
    ): Int

    @Query(
        """
        UPDATE reminder_tasks
        SET status = 'CANCELLED',
            schedulerState = 'NONE',
            generation = generation + 1,
            lastErrorCode = :reason,
            updatedAtEpochMs = :nowMs
        WHERE eventKey = :eventKey
          AND status IN ('PENDING', 'SNOOZED', 'CLAIMED', 'FIRED')
        """
    )
    suspend fun cancelEventTasks(eventKey: String, reason: String, nowMs: Long): Int

    @Query(
        """
        UPDATE reminder_tasks
        SET status = 'CANCELLED',
            schedulerState = 'NONE',
            generation = generation + 1,
            lastErrorCode = :reason,
            updatedAtEpochMs = :nowMs
        WHERE status IN ('PENDING', 'SNOOZED', 'CLAIMED', 'FIRED')
        """
    )
    suspend fun cancelAllActiveTasks(reason: String, nowMs: Long): Int

    @Query(
        """
        DELETE FROM reminder_tasks
        WHERE eventStartAtEpochMs < :olderThanEpochMs
          AND status IN ('DONE', 'CANCELLED', 'EXPIRED', 'SUPPRESSED')
        """
    )
    suspend fun pruneHistoricalTasks(olderThanEpochMs: Long): Int

    @Query("SELECT * FROM reminder_event_prefs")
    fun observeEventPrefs(): Flow<List<ReminderEventPrefEntity>>

    @Query("SELECT * FROM reminder_event_prefs")
    suspend fun getEventPrefsSnapshot(): List<ReminderEventPrefEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEventPref(pref: ReminderEventPrefEntity)

    @Query("DELETE FROM reminder_event_prefs WHERE eventScopeKey = :scopeKey")
    suspend fun deleteEventPref(scopeKey: String)

    @Query("DELETE FROM reminder_tasks")
    suspend fun clearAllTasks()

    @Query("DELETE FROM reminder_event_prefs")
    suspend fun clearAllEventPrefs()
}
