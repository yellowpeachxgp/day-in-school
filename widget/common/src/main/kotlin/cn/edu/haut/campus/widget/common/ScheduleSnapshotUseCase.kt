package cn.edu.haut.campus.widget.common

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import android.widget.RemoteViews
import android.net.Uri
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CampusDeepLinkContract
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
    val profileId: String,
    val generation: Long,
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
        if (privacyMode) return "地点已隐藏"
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
    ): WidgetScheduleSnapshot? {
        val coordinator = AccountSessionCoordinator.getInstance(context)
        runCatching { coordinator.initialize() }.getOrElse { return null }
        val active = coordinator.currentSnapshot() ?: return null
        val repository = active.repository
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
            profileId = active.profileId,
            generation = active.generation,
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
    const val EXTRA_NAVIGATE_TAB = CampusDeepLinkContract.EXTRA_NAVIGATE_TAB
    const val EXTRA_COURSE_ID = CampusDeepLinkContract.EXTRA_COURSE_ID
    const val EXTRA_RAW_ROOM = CampusDeepLinkContract.EXTRA_RAW_ROOM
    const val EXTRA_PROFILE_ID = CampusDeepLinkContract.EXTRA_PROFILE_ID
    const val EXTRA_GENERATION = CampusDeepLinkContract.EXTRA_GENERATION

    const val PROVIDER_NEXT_CLASS_2X2 = "cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider"
    const val PROVIDER_TODAY_4X2 = "cn.edu.haut.campus.widget.today.TodayAppWidgetProvider"
    const val PROVIDER_PARCEL_4X2 = "cn.edu.haut.campus.widget.today.ParcelAppWidgetProvider"
    const val PROVIDER_TIMELINE_4X4 = "cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider"
    const val PROVIDER_WEEK_4X4 = "cn.edu.haut.campus.widget.week.WeekAppWidgetProvider"

    fun requestPinAppWidget(context: Context, providerClassName: String): Boolean {
        return runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context.packageName, providerClassName)
            mgr.requestPinAppWidget(provider, null, null)
        }.getOrDefault(false)
    }

    fun stampIntent(intent: Intent, snapshot: WidgetScheduleSnapshot, widgetId: Int, action: String) {
        intent.data = Uri.Builder().scheme(CampusDeepLinkContract.SCHEME).authority(CampusDeepLinkContract.HOST_WIDGET)
            .appendPath(snapshot.profileId).appendPath(snapshot.generation.toString())
            .appendPath(widgetId.toString()).appendPath(action).build()
        intent.putExtra(EXTRA_PROFILE_ID, snapshot.profileId)
        intent.putExtra(EXTRA_GENERATION, snapshot.generation)
    }

    suspend fun publishIfCurrent(context: Context, widgetId: Int, snapshot: WidgetScheduleSnapshot, views: RemoteViews): Boolean =
        AccountSessionCoordinator.getInstance(context).withActiveSession(snapshot.profileId, snapshot.generation) {
            AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
            true
        } ?: false

    fun showSwitching(context: Context, ids: IntArray) {
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_account_switching)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            val pi = PendingIntent.getActivity(
                context,
                9900 + ids.first(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_switching_root, pi)
        }
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
    }

    fun clearForAccountSwitch(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        for (provider in registeredProviderClasses) {
            showSwitching(context, manager.getAppWidgetIds(ComponentName(context, provider)))
            val pending = PendingIntent.getBroadcast(context, 2026,
                Intent(context, provider).setAction(ACTION_REFRESH_ALL_WIDGETS),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarms?.cancel(pending); pending.cancel() }
        }
    }

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
