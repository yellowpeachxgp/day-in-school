package cn.edu.haut.campus.feature.settings.root.academic.state

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.reminder.ReconcileReport
import java.time.Instant

/** 学期/小组件/提醒分区用户动作面；粒度与拆分前调用一致，确保纯搬移。 */
interface SettingsAcademicUiAction {
    suspend fun selectCurrentTerm(termId: String)
    suspend fun deleteCalendarOverride(overrideId: String)
    suspend fun saveCalendarOverride(override: CalendarOverride)
    suspend fun updatePeriodTime(periodTime: PeriodTime)
    suspend fun updateAllWidgetDefaults(compact: Boolean? = null, privacyMode: Boolean? = null)
    suspend fun updateReminderGlobalConfig(transform: (ReminderGlobalConfig) -> ReminderGlobalConfig)
    suspend fun reconcileNow(
        reason: String,
        forceRescheduleAlarms: Boolean = false,
        now: Instant = Instant.now()
    ): ReconcileReport
    suspend fun triggerTestReminder(): Boolean
}
