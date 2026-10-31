package cn.edu.haut.campus.widget.common

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.DayTimelineSnapshot
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetType
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

data class WidgetScheduleSnapshot(
    val daySnapshot: DayTimelineSnapshot,
    val weekOccurrences: Map<LocalDate, List<ClassOccurrence>>,
    val config: WidgetInstanceConfig,
    val hasImportedCourses: Boolean,
    val chronometerTargetElapsedRealtime: Long?
)

object ScheduleSnapshotUseCase {

    suspend fun loadForWidget(
        context: Context,
        appWidgetId: Int,
        defaultType: WidgetType,
        now: ZonedDateTime = ZonedDateTime.now(HAUT_ZONE_ID)
    ): WidgetScheduleSnapshot {
        val repository = CampusRepository.getInstance(context)
        val config = repository.getWidgetConfigOrDefault(appWidgetId, defaultType)
        val data = repository.getDirectScheduleDataSnapshot()
        val today = now.toLocalDate()

        val daySnapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = today,
            now = now,
            term = data.term,
            courses = data.courses,
            rules = data.rules,
            periodTimes = data.periodTimes,
            calendarOverrides = data.calendarOverrides,
            occurrenceOverrides = data.occurrenceOverrides,
            buildings = data.buildings
        )

        val currentWeekNo = daySnapshot.teachingWeek ?: 1
        val activeTerm = data.term
        val weekOccurrences = if (activeTerm != null) {
            ScheduleEngine.resolveOccurrencesForTeachingWeek(
                teachingWeek = currentWeekNo,
                term = activeTerm,
                courses = data.courses,
                rules = data.rules,
                periodTimes = data.periodTimes,
                calendarOverrides = data.calendarOverrides,
                occurrenceOverrides = data.occurrenceOverrides,
                buildings = data.buildings
            )
        } else {
            emptyMap()
        }

        // 计算基于 SystemClock.elapsedRealtime() 的 Chronometer 目标基准时间
        val activeOrNext = daySnapshot.activeOrNextClass
        val chronometerBase = if (activeOrNext != null) {
            val targetTime = when (activeOrNext.status) {
                ClassStatus.UPCOMING -> activeOrNext.occurrence.startTime
                ClassStatus.IN_PROGRESS -> activeOrNext.occurrence.endTime
                ClassStatus.FINISHED -> null
            }
            if (targetTime != null) {
                val deltaMillis = Duration.between(now.toLocalTime(), targetTime).toMillis()
                if (deltaMillis > 0) {
                    SystemClock.elapsedRealtime() + deltaMillis
                } else null
            } else null
        } else null

        return WidgetScheduleSnapshot(
            daySnapshot = daySnapshot,
            weekOccurrences = weekOccurrences,
            config = config,
            hasImportedCourses = data.rules.isNotEmpty(),
            chronometerTargetElapsedRealtime = chronometerBase
        )
    }

    /**
     * 计算下一次课程开始或结束的边界时刻，用于设置非精确 AlarmManager 窗口刷新。
     */
    fun findNextBoundaryEpochMillis(
        daySnapshot: DayTimelineSnapshot,
        now: ZonedDateTime = ZonedDateTime.now(HAUT_ZONE_ID)
    ): Long? {
        val nowTime = now.toLocalTime()
        val candidateTimes = daySnapshot.items.flatMap {
            listOf(it.occurrence.startTime, it.occurrence.endTime)
        }.filter { it.isAfter(nowTime) }.sorted()

        val nextBoundaryLocalTime = candidateTimes.firstOrNull() ?: return null
        return now.with(nextBoundaryLocalTime).toInstant().toEpochMilli()
    }
}

object WidgetRefreshCoordinator {
    const val ACTION_REFRESH_ALL_WIDGETS = "cn.edu.haut.campus.action.REFRESH_ALL_WIDGETS"
    const val EXTRA_NAVIGATE_TAB = "extra_navigate_tab"
    const val EXTRA_COURSE_ID = "extra_course_id"
    const val EXTRA_RAW_ROOM = "extra_raw_room"

    private val registeredProviderClasses = mutableSetOf<Class<*>>()

    fun registerWidgetProviders(vararg classes: Class<*>) {
        registeredProviderClasses.addAll(classes)
    }

    fun requestImmediateRefreshAll(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        for (providerClass in registeredProviderClasses) {
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, providerClass))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, providerClass).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    /**
     * 使用非精确闹钟（AlarmManager.setWindow）在下一节课开始/结束节点请求刷新，
     * 不依赖精确闹钟权限，优雅接受系统 Doze 延迟。
     */
    fun scheduleNextBoundaryRefresh(
        context: Context,
        daySnapshot: DayTimelineSnapshot,
        receiverClass: Class<*>
    ) {
        val triggerAtMillis = ScheduleSnapshotUseCase.findNextBoundaryEpochMillis(daySnapshot) ?: return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, receiverClass).apply {
            action = ACTION_REFRESH_ALL_WIDGETS
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2026,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            // 5 分钟弹性窗口的非精确唤醒，无需 SCHEDULE_EXACT_ALARM 权限
            alarmManager.setWindow(
                AlarmManager.RTC,
                triggerAtMillis,
                5 * 60 * 1000L,
                pendingIntent
            )
        }
    }
}
