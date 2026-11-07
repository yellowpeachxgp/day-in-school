package cn.edu.haut.campus.widget.week

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.ScheduleSnapshotUseCase
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import cn.edu.haut.campus.widget.common.WidgetRoomFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek

class WeekAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (id in appWidgetIds) {
                    updateWidget(context, appWidgetManager, id)
                }
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
            val ids = manager.getAppWidgetIds(ComponentName(context, WeekAppWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    companion object {
        private data class WeekDayRowSpec(
            val weekday: DayOfWeek,
            val rowContainerId: Int,
            val labelViewId: Int,
            val summaryViewId: Int,
            val badgeViewId: Int
        )

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.WEEK_4X4
            ) ?: run { WidgetRefreshCoordinator.showSwitching(context, intArrayOf(appWidgetId)); return }
            val day = snapshot.daySnapshot
            val views = RemoteViews(context.packageName, R.layout.widget_week_4x4)

            val weekLabel = day.teachingWeek?.let { "第 $it 教学周" } ?: "非教学周"
            views.setTextViewText(R.id.tv_wk_header, "本周课表 · $weekLabel")
            views.setTextViewText(R.id.tv_wk_sub, "今日 ${day.date.dayOfWeek.ChineseLabel}")

            val rowSpecs = listOf(
                WeekDayRowSpec(DayOfWeek.MONDAY, R.id.layout_wk_row_1, R.id.tv_wk_label_1, R.id.tv_wk_day_1, R.id.tv_wk_badge_1),
                WeekDayRowSpec(DayOfWeek.TUESDAY, R.id.layout_wk_row_2, R.id.tv_wk_label_2, R.id.tv_wk_day_2, R.id.tv_wk_badge_2),
                WeekDayRowSpec(DayOfWeek.WEDNESDAY, R.id.layout_wk_row_3, R.id.tv_wk_label_3, R.id.tv_wk_day_3, R.id.tv_wk_badge_3),
                WeekDayRowSpec(DayOfWeek.THURSDAY, R.id.layout_wk_row_4, R.id.tv_wk_label_4, R.id.tv_wk_day_4, R.id.tv_wk_badge_4),
                WeekDayRowSpec(DayOfWeek.FRIDAY, R.id.layout_wk_row_5, R.id.tv_wk_label_5, R.id.tv_wk_day_5, R.id.tv_wk_badge_5),
                WeekDayRowSpec(DayOfWeek.SATURDAY, R.id.layout_wk_row_6, R.id.tv_wk_label_6, R.id.tv_wk_day_6, R.id.tv_wk_badge_6),
                WeekDayRowSpec(DayOfWeek.SUNDAY, R.id.layout_wk_row_7, R.id.tv_wk_label_7, R.id.tv_wk_day_7, R.id.tv_wk_badge_7)
            )

            val primaryColor = ContextCompat.getColor(context, R.color.wk_widget_primary)
            val secondaryColor = ContextCompat.getColor(context, R.color.wk_widget_secondary)
            val accentColor = snapshot.themeAccentArgb
            views.setTextColor(R.id.tv_wk_sub, accentColor)

            val byWeekday = snapshot.weekOccurrences.entries.associate { (date, list) ->
                date.dayOfWeek to (date to list)
            }
            val hasWeekendClasses = byWeekday[DayOfWeek.SATURDAY]?.second.orEmpty().isNotEmpty() ||
                byWeekday[DayOfWeek.SUNDAY]?.second.orEmpty().isNotEmpty()

            for (spec in rowSpecs) {
                val pair = byWeekday[spec.weekday]
                val occurrences = pair?.second.orEmpty().sortedBy { it.startPeriod }
                if (spec.weekday.value >= 6 && !hasWeekendClasses) {
                    views.setViewVisibility(spec.rowContainerId, View.GONE)
                    continue
                }
                views.setViewVisibility(spec.rowContainerId, View.VISIBLE)
                val isToday = pair?.first == day.date || spec.weekday == day.date.dayOfWeek
                val dayLabel = if (isToday) "● ${spec.weekday.ChineseLabel}" else spec.weekday.ChineseLabel
                val summary = if (occurrences.isEmpty()) {
                    "无课"
                } else {
                    occurrences.joinToString("  ·  ") { occ ->
                        val shortName = if (snapshot.config.privacyMode) "课程" else occ.courseName.take(10)
                        val compactRoom = WidgetRoomFormatter.formatCompactRoom(occ.rawRoom, snapshot.config.privacyMode)
                        "${occ.startPeriod}-${occ.endPeriod} $shortName @$compactRoom"
                    }
                }
                val badgeText = if (occurrences.isEmpty()) "无课" else "${occurrences.size}节"

                views.setInt(
                    spec.rowContainerId,
                    "setBackgroundResource",
                    if (isToday) R.drawable.bg_week_row_today else R.drawable.bg_week_row
                )
                views.setTextViewText(spec.labelViewId, dayLabel)
                views.setTextColor(spec.labelViewId, if (isToday) accentColor else secondaryColor)

                views.setTextViewText(spec.summaryViewId, summary)
                views.setTextColor(
                    spec.summaryViewId,
                    when {
                        isToday -> accentColor
                        occurrences.isEmpty() -> secondaryColor
                        else -> primaryColor
                    }
                )

                views.setTextViewText(spec.badgeViewId, badgeText)
                views.setTextColor(
                    spec.badgeViewId,
                    if (isToday || occurrences.isNotEmpty()) accentColor else secondaryColor
                )
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                WidgetRefreshCoordinator.stampIntent(this, snapshot, appWidgetId, "open")
                putExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB, "timetable")
            }
            if (launchIntent != null) {
                val pi = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.week_widget_root, pi)
            }

            if (!WidgetRefreshCoordinator.publishIfCurrent(context, appWidgetId, snapshot, views)) return
        }
    }
}
