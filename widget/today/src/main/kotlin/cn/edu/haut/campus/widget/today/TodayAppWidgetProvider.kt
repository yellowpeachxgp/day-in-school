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
            )
            val day = snapshot.daySnapshot
            val views = RemoteViews(context.packageName, R.layout.widget_today_4x2)

            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260
            views.setViewVisibility(
                R.id.layout_subsequent,
                if (minWidthDp < 210) View.GONE else View.VISIBLE
            )

            val weekText = day.teachingWeek?.let { "第 $it 教学周" } ?: "假期/非教学周"
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
                    if (isOngoing) "● 正在上课 (${occ.periodRangeText})" else "▲ 下一节课 (${occ.periodRangeText})"
                )
                views.setTextViewText(
                    R.id.tv_focus_title,
                    if (snapshot.config.privacyMode) "课程已隐藏" else occ.courseName
                )
                views.setTextViewText(
                    R.id.tv_focus_meta,
                    "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)} · ${if (snapshot.config.privacyMode) "莲花街" else occ.rawRoom}"
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

                // 后续两节课
                val focusIdx = day.items.indexOf(focus)
                val remaining = if (focusIdx >= 0) day.items.drop(focusIdx + 1) else emptyList()
                val sub1 = remaining.getOrNull(0)
                val sub2 = remaining.getOrNull(1)

                views.setTextViewText(
                    R.id.tv_sub_1,
                    sub1?.let {
                        "${it.occurrence.startTime.format(TIME_FMT)} ${it.occurrence.courseName} · ${it.occurrence.rawRoom}"
                    } ?: "今日后续无其他课程"
                )
                views.setTextViewText(
                    R.id.tv_sub_2,
                    sub2?.let {
                        "${it.occurrence.startTime.format(TIME_FMT)} ${it.occurrence.courseName} · ${it.occurrence.rawRoom}"
                    } ?: "共 ${day.items.size} 节今日大课"
                )
            } else {
                views.setViewVisibility(R.id.chronometer_today, View.GONE)
                views.setTextViewText(R.id.tv_focus_status, "✓ 今日课程状态")
                views.setTextViewText(
                    R.id.tv_focus_title,
                    if (day.items.isEmpty()) "今天没有课程安排" else "今日 ${day.items.size} 节课已全部结束"
                )
                val hint = day.nextClassDayHint
                views.setTextViewText(
                    R.id.tv_focus_meta,
                    hint?.let {
                        "下次课：${it.nextDate.format(DATE_FMT)} ${it.firstCourseStartTime.format(TIME_FMT)} ${it.firstCourseName}"
                    } ?: (day.calendarNotice ?: "祝你拥有充实愉快的一天")
                )
                views.setTextViewText(
                    R.id.tv_sub_1,
                    hint?.let { "地点：${it.firstCourseRoom}" } ?: "支持离线查看全学期课表"
                )
                views.setTextViewText(R.id.tv_sub_2, "点击打开 App 时间轴")
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
                views.setOnClickPendingIntent(R.id.today_widget_root, pi)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
