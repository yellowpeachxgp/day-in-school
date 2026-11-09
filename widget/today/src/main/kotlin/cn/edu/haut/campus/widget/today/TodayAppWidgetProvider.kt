package cn.edu.haut.campus.widget.today

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.ScheduleSnapshotUseCase
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import cn.edu.haut.campus.widget.common.WidgetRoomFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class TodayAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (id in appWidgetIds) {
                    val options = appWidgetManager.getAppWidgetOptions(id)
                    updateWidget(context, appWidgetManager, id, options)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updateWidget(context, appWidgetManager, appWidgetId, newOptions)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRefreshCoordinator.ACTION_REFRESH_ALL_WIDGETS ||
            intent.action == Intent.ACTION_DATE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayAppWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FMT = DateTimeFormatter.ofPattern("MM月dd日")

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle?
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.TODAY_4X2
            ) ?: run { WidgetRefreshCoordinator.showSwitching(context, intArrayOf(appWidgetId)); return }
            val day = snapshot.daySnapshot
            val views = RemoteViews(context.packageName, R.layout.widget_today_4x2)

            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260
            views.setViewVisibility(
                R.id.layout_subsequent,
                if (minWidthDp < 210) View.GONE else View.VISIBLE
            )

            val accentArgb = snapshot.themeAccentArgb
            views.setTextColor(R.id.chronometer_today, accentArgb)
            views.setTextColor(R.id.tv_focus_status, accentArgb)
            views.setTextColor(R.id.tv_focus_meta, accentArgb)
            views.setTextColor(R.id.tv_sub_1_room, accentArgb)
            views.setTextColor(R.id.tv_sub_2_room, accentArgb)

            val weekText = day.teachingWeek?.let { "第 $it 教学周" } ?: "非教学周"
            views.setTextViewText(
                R.id.tv_today_header,
                "${day.date.format(DATE_FMT)} ${day.date.dayOfWeek.ChineseLabel} · $weekText"
            )

            val focus = day.activeOrNextClass
            if (focus != null) {
                val occ = focus.occurrence
                val isOngoing = focus.status == ClassStatus.IN_PROGRESS
                views.setTextViewText(
                    R.id.tv_focus_status,
                    if (isOngoing) "正在上课" else "下一节课"
                )
                views.setTextViewText(
                    R.id.tv_focus_time,
                    occ.formatTimeOrPeriodRange(TIME_FMT)
                )
                views.setTextViewText(
                    R.id.tv_focus_title,
                    if (snapshot.config.privacyMode) "课程已隐藏" else occ.courseName
                )
                val fullFocusRoom = WidgetRoomFormatter.formatFullRoom(occ.rawRoom, snapshot.config.privacyMode)
                views.setTextViewText(
                    R.id.tv_focus_meta,
                    "$fullFocusRoom · ${occ.periodRangeText}"
                )

                val base = snapshot.chronometerTargetElapsedRealtime
                if (base != null) {
                    views.setViewVisibility(R.id.chronometer_today, View.VISIBLE)
                    views.setChronometerCountDown(R.id.chronometer_today, true)
                    views.setChronometer(
                        R.id.chronometer_today,
                        base,
                        if (isOngoing) "下课 %s" else "上课 %s",
                        true
                    )
                } else {
                    views.setViewVisibility(R.id.chronometer_today, View.GONE)
                }

                // 后续两节课：标题与完整教室分行展示，确保教室绝不截断
                val focusIdx = day.items.indexOf(focus)
                val remaining = if (focusIdx >= 0) day.items.drop(focusIdx + 1) else emptyList()
                val sub1 = remaining.getOrNull(0)
                val sub2 = remaining.getOrNull(1)

                if (sub1 != null) {
                    val o1 = sub1.occurrence
                    val t1 = if (o1.hasResolvedTime) o1.startTime.format(TIME_FMT) else o1.periodRangeText
                    views.setTextViewText(
                        R.id.tv_sub_1,
                        "$t1 ${if (snapshot.config.privacyMode) "课程" else o1.courseName}"
                    )
                    views.setTextViewText(
                        R.id.tv_sub_1_room,
                        "${WidgetRoomFormatter.formatFullRoom(o1.rawRoom, snapshot.config.privacyMode)} · ${o1.periodRangeText}"
                    )
                } else {
                    views.setTextViewText(R.id.tv_sub_1, "后续无其他课程")
                    views.setTextViewText(R.id.tv_sub_1_room, "今日共 ${day.items.size} 节课")
                }

                if (sub2 != null) {
                    val o2 = sub2.occurrence
                    val t2 = if (o2.hasResolvedTime) o2.startTime.format(TIME_FMT) else o2.periodRangeText
                    views.setTextViewText(
                        R.id.tv_sub_2,
                        "$t2 ${if (snapshot.config.privacyMode) "课程" else o2.courseName}"
                    )
                    views.setTextViewText(
                        R.id.tv_sub_2_room,
                        "${WidgetRoomFormatter.formatFullRoom(o2.rawRoom, snapshot.config.privacyMode)} · ${o2.periodRangeText}"
                    )
                } else {
                    views.setTextViewText(
                        R.id.tv_sub_2,
                        if (sub1 != null) "再后无课" else "点击查看课表"
                    )
                    views.setTextViewText(
                        R.id.tv_sub_2_room,
                        "今日共 ${day.items.size} 节课"
                    )
                }
            } else {
                views.setViewVisibility(R.id.chronometer_today, View.GONE)
                views.setTextViewText(R.id.tv_focus_status, if (!snapshot.hasImportedCourses) "未同步" else "今日结课")
                views.setTextViewText(R.id.tv_focus_time, "")
                views.setTextViewText(
                    R.id.tv_focus_title,
                    when {
                        !snapshot.hasImportedCourses -> "点击导入本学期课表"
                        day.items.isEmpty() -> "今天没有课程安排"
                        else -> "今日 ${day.items.size} 节课已结束"
                    }
                )
                val hint = day.nextClassDayHint
                views.setTextViewText(
                    R.id.tv_focus_meta,
                    if (!snapshot.hasImportedCourses) {
                        "登录教务系统即可一键同步课表与考试"
                    } else {
                        hint?.let {
                            val ht = if (it.hasResolvedStartTime) it.firstCourseStartTime.format(TIME_FMT) else it.firstCoursePeriodRangeText
                            "下次课：${it.nextDate.format(DATE_FMT)} $ht"
                        } ?: (day.calendarNotice ?: "自主安排学习与休息")
                    }
                )
                views.setTextViewText(
                    R.id.tv_sub_1,
                    if (snapshot.config.privacyMode && hint != null) "课程已隐藏" else hint?.firstCourseName ?: "查看完整学期课表"
                )
                views.setTextViewText(
                    R.id.tv_sub_1_room,
                    hint?.let { WidgetRoomFormatter.formatFullRoom(it.firstCourseRoom, snapshot.config.privacyMode) }
                        ?: "点击进入应用"
                )
                views.setTextViewText(R.id.tv_sub_2, "校园地图与自习")
                views.setTextViewText(R.id.tv_sub_2_room, "点击打开")
            }

            val defaultTargetTab = if (!snapshot.hasImportedCourses) "importer" else "today"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                WidgetRefreshCoordinator.stampIntent(this, snapshot, appWidgetId, "open")
                putExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB, defaultTargetTab)
            }
            if (launchIntent != null) {
                val pi = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.today_widget_root, pi)
            }

            val mapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                WidgetRefreshCoordinator.stampIntent(this, snapshot, appWidgetId, "map")
                putExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB, "map")
                putExtra(WidgetRefreshCoordinator.EXTRA_RAW_ROOM, focus?.occurrence?.rawRoom)
            }
            if (mapIntent != null && focus != null) {
                val mapPi = PendingIntent.getActivity(
                    context,
                    appWidgetId + 20000,
                    mapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.tv_focus_meta, mapPi)
            }

            if (!WidgetRefreshCoordinator.publishIfCurrent(context, appWidgetId, snapshot, views)) return
            WidgetRefreshCoordinator.scheduleNextBoundaryRefresh(
                context = context,
                daySnapshot = day,
                receiverClass = TodayAppWidgetProvider::class.java
            )
        }
    }
}
