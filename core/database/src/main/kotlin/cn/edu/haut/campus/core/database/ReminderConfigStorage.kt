package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.Preferences
import cn.edu.haut.campus.core.model.ReminderGlobalConfig

/** 提醒全局配置的 DataStore 读取与默认值/夹取规则；包含 W0-02 live progress 接缝，语义逐字保持。 */
internal fun extractReminderConfig(prefs: Preferences): ReminderGlobalConfig {
    val quietMode = prefs[CampusRepositoryPrefKeys.REMINDER_QUIET_MODE] ?: "FOLLOW_SYSTEM"
    return ReminderGlobalConfig(
        enabled = prefs[CampusRepositoryPrefKeys.REMINDERS_ENABLED] ?: true,
        classLeadMinutes = (prefs[CampusRepositoryPrefKeys.CLASS_LEAD_MINUTES] ?: 20).coerceIn(5, 120),
        classSnoozeDefaultMinutes = (prefs[CampusRepositoryPrefKeys.CLASS_SNOOZE_DEFAULT_MINUTES] ?: 5).coerceIn(5, 30),
        classSnoozeMaxCount = (prefs[CampusRepositoryPrefKeys.CLASS_SNOOZE_MAX_COUNT] ?: 2).coerceIn(0, 5),
        classSnoozeCutoffMinutes = (prefs[CampusRepositoryPrefKeys.CLASS_SNOOZE_CUTOFF_MINUTES] ?: 2).coerceIn(1, 15),
        examDayBeforeEnabled = prefs[CampusRepositoryPrefKeys.EXAM_DAY_BEFORE_ENABLED] ?: true,
        examHourBeforeEnabled = prefs[CampusRepositoryPrefKeys.EXAM_HOUR_BEFORE_ENABLED] ?: true,
        examSnoozeMaxCount = (prefs[CampusRepositoryPrefKeys.EXAM_SNOOZE_MAX_COUNT] ?: 2).coerceIn(0, 5),
        preciseReminderPreferred = prefs[CampusRepositoryPrefKeys.PRECISE_REMINDER_PREFERRED] ?: true,
        liveProgressRefreshMinutes = (prefs[CampusRepositoryPrefKeys.LIVE_PROGRESS_REFRESH_MINUTES] ?: 5).takeIf { it in setOf(1, 5, 10) } ?: 5,
        lockscreenPrivate = prefs[CampusRepositoryPrefKeys.REMINDER_LOCKSCREEN_PRIVATE] ?: true,
        quietMode = quietMode
    )
}
