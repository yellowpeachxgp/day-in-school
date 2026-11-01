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
            val weekLabel = day.teachingWeek?.let { "第${it}周" } ?: "假期/非教学周"
            views.setTextViewText(R.id.tv_week_tag, weekLabel)

            val activeOrNext = day.activeOrNextClass
            if (!snapshot.hasImportedCourses) {
                views.setTextViewText(R.id.tv_status_badge, "未同步")
                views.setTextViewText(R.id.tv_course_name, "点击导入课表")
                views.setTextViewText(R.id.tv_time_range, "暂无课程数据")
                views.setTextViewText(R.id.tv_room_and_teacher, "支持教务同步与 CSV 导入")
                views.setViewVisibility(R.id.chronometer_countdown, View.GONE)
            } else if (activeOrNext != null) {
                val occ = activeOrNext.occurrence
                val isOngoing = activeOrNext.status == ClassStatus.IN_PROGRESS
                views.setTextViewText(
                    R.id.tv_status_badge,
                    if (isOngoing) "● 正在上课" else "▲ 下一节课"
                )
                views.setTextViewText(
                    R.id.tv_course_name,
                    if (snapshot.config.privacyMode) "课程已隐藏（隐私模式）" else occ.courseName
                )
                val timeText = if (isNarrow) {
                    occ.startTime.format(TIME_FMT)
                } else {
                    "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}"
                }
                views.setTextViewText(R.id.tv_time_range, timeText)

                // 使用系统级 RemoteViews.setChronometer + setChronometerCountDown 推进倒计时
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

                // 自适应布局：横向受限时优先隐藏教师，保留教室
                val locationLine = buildString {
                    if (snapshot.config.privacyMode) {
                        append("莲花街校区")
                    } else {
                        append(occ.rawRoom)
                        if (!isNarrow && snapshot.config.showTeacher && !occ.teacher.isNullOrBlank()) {
                            append(" · ")
                            append(occ.teacher)
                        }
                    }
                }
                views.setTextViewText(R.id.tv_room_and_teacher, locationLine)
            } else {
                // 今日无课或今日课程已全部结束：显示短文案与下一次课日期，不留空白
                views.setTextViewText(R.id.tv_status_badge, "✓ 今日无后续课")
                views.setViewVisibility(R.id.chronometer_countdown, View.GONE)
                val hint = day.nextClassDayHint
                if (hint != null) {
                    views.setTextViewText(R.id.tv_course_name, "今日课程已完成")
                    views.setTextViewText(
                        R.id.tv_time_range,
                        "下次课：${hint.nextDate.format(DATE_FMT)} ${hint.nextDate.dayOfWeek.ChineseLabel}"
                    )
                    views.setTextViewText(
                        R.id.tv_room_and_teacher,
                        "${hint.firstCourseStartTime.format(TIME_FMT)} ${hint.firstCourseName} @${hint.firstCourseRoom}"
                    )
                } else {
                    views.setTextViewText(R.id.tv_course_name, "今天没有安排课程")
                    views.setTextViewText(R.id.tv_time_range, day.calendarNotice ?: "好好休息或自主学习")
                    views.setTextViewText(R.id.tv_room_and_teacher, "点击查看完整学期课表")
                }
            }

            // 点击卡片打开今日课程，点击地点跳到校园地图
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
            if (mapIntent != null) {
                val mapPi = PendingIntent.getActivity(
                    context,
                    appWidgetId + 10000,
                    mapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
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
