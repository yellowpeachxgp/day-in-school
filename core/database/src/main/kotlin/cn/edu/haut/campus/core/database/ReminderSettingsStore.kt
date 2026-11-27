package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.edit
import cn.edu.haut.campus.core.domain.ReminderRevision
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 提醒任务、全局提醒配置、事件级覆盖与诊断日志存储；事务外通知语义保持原样。 */
internal class ReminderSettingsStore(
    private val environment: CampusRepositoryEnvironment
) {
    private val preferences = environment.preferences
    private val reminderDao = environment.database.reminderDao()

    val activeReminderTasksFlow: Flow<List<ReminderTaskDomain>> =
        reminderDao.observeActiveTasks().map { list -> list.map { it.toDomain() } }

    val reminderEventOverridesFlow: Flow<List<ReminderEventOverride>> =
        reminderDao.observeEventPrefs().map { list -> list.map { it.toDomain() } }

    fun observeReminderTasksInWindow(windowStartMs: Long, windowEndMs: Long): Flow<List<ReminderTaskDomain>> =
        reminderDao.observeTasksInEventWindow(windowStartMs, windowEndMs).map { list ->
            list.map { it.toDomain() }
        }

    fun reminderDao(): ReminderDao = reminderDao

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }

    suspend fun getReminderConfigSnapshot(): ReminderGlobalConfig {
        val prefs = preferences.data.first()
        return extractReminderConfig(prefs)
    }



    suspend fun getReminderEventOverridesSnapshot(): List<ReminderEventOverride> {
        return reminderDao.getEventPrefsSnapshot().map { it.toDomain() }
    }


    suspend fun updateReminderGlobalConfig(transform: (ReminderGlobalConfig) -> ReminderGlobalConfig) {
        preferences.edit { prefs ->
            val current = extractReminderConfig(prefs)
            val updated = transform(current)
            prefs[CampusRepositoryPrefKeys.REMINDERS_ENABLED] = updated.enabled
            prefs[CampusRepositoryPrefKeys.CLASS_LEAD_MINUTES] = updated.classLeadMinutes.coerceIn(5, 120)
            prefs[CampusRepositoryPrefKeys.CLASS_SNOOZE_DEFAULT_MINUTES] = updated.classSnoozeDefaultMinutes.coerceIn(5, 30)
            prefs[CampusRepositoryPrefKeys.CLASS_SNOOZE_MAX_COUNT] = updated.classSnoozeMaxCount.coerceIn(0, 5)
            prefs[CampusRepositoryPrefKeys.CLASS_SNOOZE_CUTOFF_MINUTES] = updated.classSnoozeCutoffMinutes.coerceIn(1, 15)
            prefs[CampusRepositoryPrefKeys.EXAM_DAY_BEFORE_ENABLED] = updated.examDayBeforeEnabled
            prefs[CampusRepositoryPrefKeys.EXAM_HOUR_BEFORE_ENABLED] = updated.examHourBeforeEnabled
            prefs[CampusRepositoryPrefKeys.EXAM_SNOOZE_MAX_COUNT] = updated.examSnoozeMaxCount.coerceIn(0, 5)
            prefs[CampusRepositoryPrefKeys.PRECISE_REMINDER_PREFERRED] = updated.preciseReminderPreferred
            prefs[CampusRepositoryPrefKeys.LIVE_PROGRESS_REFRESH_MINUTES] = updated.liveProgressRefreshMinutes.takeIf { it in setOf(1, 5, 10) } ?: 5
            prefs[CampusRepositoryPrefKeys.REMINDER_LOCKSCREEN_PRIVATE] = updated.lockscreenPrivate
            prefs[CampusRepositoryPrefKeys.REMINDER_QUIET_MODE] = updated.quietMode
            prefs[CampusRepositoryPrefKeys.REMINDER_POLICY_REVISION] = ReminderRevision.computePolicyRevision(
                enabled = updated.enabled,
                classLeadMinutes = updated.classLeadMinutes,
                maxSnoozes = updated.classSnoozeMaxCount,
                cutoffMinutesBeforeStart = updated.classSnoozeCutoffMinutes,
                examDayEnabled = updated.examDayBeforeEnabled,
                examHourEnabled = updated.examHourBeforeEnabled
            )
        }
        notifyScheduleOrPolicyChanged("REMINDER_GLOBAL_CONFIG_UPDATED")
    }


    suspend fun upsertReminderEventOverride(override: ReminderEventOverride) {
        reminderDao.upsertEventPref(override.toEntity())
        notifyScheduleOrPolicyChanged("REMINDER_EVENT_OVERRIDE_UPSERTED:${override.eventScopeKey}")
    }

    suspend fun removeReminderEventOverride(scopeKey: String) {
        reminderDao.deleteEventPref(scopeKey)
        notifyScheduleOrPolicyChanged("REMINDER_EVENT_OVERRIDE_REMOVED:$scopeKey")
    }

    suspend fun appendReminderDiagnosticLog(message: String, reconcileSummary: String? = null) {
        val nowMs = System.currentTimeMillis()
        val timeText = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(HAUT_ZONE_ID)
            .toLocalTime()
            .withNano(0)
            .toString()
        val formatted = "[$timeText] $message"
        preferences.edit { prefs ->
            val existing = prefs[CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS]
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val updated = (listOf(formatted) + existing).take(30)
            prefs[CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS] = updated.joinToString("\n")
            if (reconcileSummary != null) {
                prefs[CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_MS] = nowMs
                prefs[CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_SUMMARY] = reconcileSummary
            }
        }
    }

}
