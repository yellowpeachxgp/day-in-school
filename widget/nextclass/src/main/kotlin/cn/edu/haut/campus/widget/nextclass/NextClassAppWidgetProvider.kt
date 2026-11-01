package cn.edu.haut.campus.widget.nextclass

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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

class NextClassAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (appWidgetId in appWidgetIds) {
                    val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                    updateSingleWidget(context, appWidgetManager, appWidgetId, options)
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
                updateSingleWidget(context, appWidgetManager, appWidgetId, newOptions)
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
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, NextClassAppWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FMT = DateTimeFormatter.ofPattern("MM/dd")

        suspend fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle?
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.NEXT_CLASS_2X2
            )
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140) ?: 140
            val isNarrow = minWidthDp < 150 || snapshot.config.compact

            val views = RemoteViews(context.packageName, R.layout.widget_next_class_2x2)
            val day = snapshot.daySnapshot
            val weekLabel = day.teachingWeek?.let { "第${it}周" } ?: "非教学周"
            views.setTextViewText(R.id.tv_week_tag, weekLabel)

            val accentArgb = snapshot.themeAccentArgb
            views.setTextColor(R.id.tv_status_badge, accentArgb)
            views.setTextColor(R.id.chronometer_countdown, accentArgb)
            views.setTextColor(R.id.tv_room_and_teacher, accentArgb)

            val activeOrNext = day.activeOrNextClass
            if (!snapshot.hasImportedCourses) {
                views.setTextViewText(R.id.tv_status_badge, "未同步")
                views.setTextViewText(R.id.tv_course_name, "点击导入课表")
                views.setTextViewText(R.id.tv_time_range, "暂无课程数据")
                views.setTextViewText(R.id.tv_room_and_teacher, "支持教务直连同步")
                views.setTextViewText(R.id.tv_teacher_tag, "去导入 ›")
                views.setViewVisibility(R.id.chronometer_countdown, View.GONE)
            } else if (activeOrNext != null) {
                val occ = activeOrNext.occurrence
                val isOngoing = activeOrNext.status == ClassStatus.IN_PROGRESS
                views.setTextViewText(
                    R.id.tv_status_badge,
                    if (isOngoing) "正在上课" else "下一节课"
                )
                views.setTextViewText(
                    R.id.tv_course_name,
                    if (snapshot.config.privacyMode) "课程已隐藏" else occ.courseName
                )
                val timeText = if (isNarrow) {
                    "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}"
                } else {
                    "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)} · ${occ.periodRangeText}"
                }
                views.setTextViewText(R.id.tv_time_range, timeText)

                val baseRealtime = snapshot.chronometerTargetElapsedRealtime
                if (baseRealtime != null) {
                    views.setViewVisibility(R.id.chronometer_countdown, View.VISIBLE)
                    views.setChronometerCountDown(R.id.chronometer_countdown, true)
                    views.setChronometer(
                        R.id.chronometer_countdown,
                        baseRealtime,
                        if (isOngoing) "剩 %s" else "距 %s",
                        true
                    )
                } else {
                    views.setViewVisibility(R.id.chronometer_countdown, View.GONE)
                }

                // 独立完整展示教室，教师姓名放在右侧副标签，彻底杜绝教室显示不全
                val fullRoom = WidgetRoomFormatter.formatFullRoom(occ.rawRoom, snapshot.config.privacyMode)
                views.setTextViewText(R.id.tv_room_and_teacher, fullRoom)
                val rightTag = if (!isNarrow && !snapshot.config.privacyMode && snapshot.config.showTeacher && !occ.teacher.isNullOrBlank()) {
                    "${occ.teacher} ›"
                } else {
                    "去教室 ›"
                }
                views.setTextViewText(R.id.tv_teacher_tag, rightTag)
            } else {
                views.setTextViewText(R.id.tv_status_badge, "今日结课")
                views.setViewVisibility(R.id.chronometer_countdown, View.GONE)
                val hint = day.nextClassDayHint
                if (hint != null) {
                    views.setTextViewText(R.id.tv_course_name, hint.firstCourseName)
                    views.setTextViewText(
                        R.id.tv_time_range,
                        "下次课 ${hint.nextDate.format(DATE_FMT)} ${hint.nextDate.dayOfWeek.ChineseLabel} ${hint.firstCourseStartTime.format(TIME_FMT)}"
                    )
                    views.setTextViewText(
                        R.id.tv_room_and_teacher,
                        WidgetRoomFormatter.formatFullRoom(hint.firstCourseRoom, snapshot.config.privacyMode)
                    )
                    views.setTextViewText(R.id.tv_teacher_tag, "预习 ›")
                } else {
                    views.setTextViewText(R.id.tv_course_name, "今天没有安排课程")
                    views.setTextViewText(R.id.tv_time_range, day.calendarNotice ?: "自主安排学习与休息")
                    views.setTextViewText(R.id.tv_room_and_teacher, "查看完整学期课表")
                    views.setTextViewText(R.id.tv_teacher_tag, "打开 ›")
                }
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB, "today")
            }
            if (launchIntent != null) {
                val pi = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pi)
            }

            val mapIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB, "map")
                putExtra(WidgetRefreshCoordinator.EXTRA_RAW_ROOM, activeOrNext?.occurrence?.rawRoom)
            }
            if (mapIntent != null && activeOrNext != null) {
                val mapPi = PendingIntent.getActivity(
                    context,
                    appWidgetId + 10000,
                    mapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.layout_room_bar, mapPi)
                views.setOnClickPendingIntent(R.id.tv_room_and_teacher, mapPi)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
            WidgetRefreshCoordinator.scheduleNextBoundaryRefresh(
                context = context,
                daySnapshot = day,
                receiverClass = NextClassAppWidgetProvider::class.java
            )
        }
    }
}
