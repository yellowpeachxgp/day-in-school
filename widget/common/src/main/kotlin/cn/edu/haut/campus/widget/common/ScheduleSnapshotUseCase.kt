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
    val chronometerTargetElapsedRealtime: Long?,
    val themeAccentArgb: Int = 0xFF0066CC.toInt()
)

object WidgetRoomFormatter {
    private val BUILDING_SPACE_REGEX = Regex("""^(\d+号教学楼|钟楼报告厅|钟楼|工业中心|实验楼|体育馆)(\d+[A-Za-z0-9\-]*)$""")
    private val COMPACT_BUILDING_REGEX = Regex("""^(\d+)号教学楼(\d+[A-Za-z0-9\-]*)$""")

    /**
     * 去除冗余校区前缀并保留完整「楼宇 + 教室号」，确保小组件中教室信息完整清晰、绝不丢失楼号或房间号。
     */
    fun formatFullRoom(rawRoom: String, privacyMode: Boolean = false): String {
        if (privacyMode) return "莲花街校区"
        val cleaned = rawRoom
            .replace("河南工业大学", "")
            .replace("莲花街校区", "")
            .replace("莲花街", "")
            .trim()
        if (cleaned.isEmpty()) return "待定教室"
        val match = BUILDING_SPACE_REGEX.matchEntire(cleaned)
        return if (match != null) {
            "${match.groupValues[1]} ${match.groupValues[2]}"
        } else {
            cleaned
        }
    }

    /**
     * 紧凑教室名（用于周课表多课并列场景）：如「莲花街校区5号教学楼5204」->「5教5204」，完整保留楼号与房间号。
     */
    fun formatCompactRoom(rawRoom: String, privacyMode: Boolean = false): String {
        if (privacyMode) return "校区"
        val cleaned = rawRoom
            .replace("河南工业大学", "")
            .replace("莲花街校区", "")
            .replace("莲花街", "")
            .trim()
        if (cleaned.isEmpty()) return "待定"
        val match = COMPACT_BUILDING_REGEX.matchEntire(cleaned)
        if (match != null) {
            return "${match.groupValues[1]}教${match.groupValues[2]}"
        }
        return cleaned
            .replace("号教学楼", "教")
            .replace("教学楼", "教")
            .replace("报告厅", "")
    }
}

object ScheduleSnapshotUseCase {

    suspend fun loadForWidget(
        context: Context,
        appWidgetId: Int,
        defaultType: WidgetType,
        now: ZonedDateTime = ZonedDateTime.now(HAUT_ZONE_ID)
    ): WidgetScheduleSnapshot {
        val repository = CampusRepository.getInstance(context)
        val config = repository.getWidgetConfigOrDefault(appWidgetId, defaultType)
        val themeAccentArgb = runCatching { repository.getActiveThemeAccentArgb() }.getOrDefault(0xFF0066CC.toInt())
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
            chronometerTargetElapsedRealtime = chronometerBase,
            themeAccentArgb = themeAccentArgb
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
