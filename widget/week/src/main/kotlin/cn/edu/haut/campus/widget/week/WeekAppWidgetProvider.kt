package cn.edu.haut.campus.widget.week

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.ScheduleSnapshotUseCase
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
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
        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.WEEK_4X4
            )
            val day = snapshot.daySnapshot
            val views = RemoteViews(context.packageName, R.layout.widget_week_4x4)

            val weekLabel = day.teachingWeek?.let { "第 $it 教学周" } ?: "非教学周"
            views.setTextViewText(R.id.tv_wk_header, "本周课表 · $weekLabel")
            views.setTextViewText(R.id.tv_wk_sub, "★ 今日 ${day.date.dayOfWeek.ChineseLabel}")

            val rowIds = listOf(
                DayOfWeek.MONDAY to R.id.tv_wk_day_1,
                DayOfWeek.TUESDAY to R.id.tv_wk_day_2,
                DayOfWeek.WEDNESDAY to R.id.tv_wk_day_3,
                DayOfWeek.THURSDAY to R.id.tv_wk_day_4,
                DayOfWeek.FRIDAY to R.id.tv_wk_day_5,
                DayOfWeek.SATURDAY to R.id.tv_wk_day_6,
                DayOfWeek.SUNDAY to R.id.tv_wk_day_7
            )

            val primaryColor = ContextCompat.getColor(context, R.color.wk_widget_primary)
            val secondaryColor = ContextCompat.getColor(context, R.color.wk_widget_secondary)
            val accentColor = ContextCompat.getColor(context, R.color.wk_widget_accent)

            val byWeekday = snapshot.weekOccurrences.entries.associate { (date, list) ->
                date.dayOfWeek to (date to list)
            }

            for ((weekday, viewId) in rowIds) {
                val pair = byWeekday[weekday]
                val isToday = pair?.first == day.date || weekday == day.date.dayOfWeek
                val prefix = if (isToday) "★${weekday.ChineseLabel}" else "  ${weekday.ChineseLabel}"
                val occurrences = pair?.second.orEmpty()
                val summary = if (occurrences.isEmpty()) {
                    "无课（自由安排）"
                } else {
                    occurrences.joinToString(" · ") { occ ->
                        val shortName = if (snapshot.config.privacyMode) "课程" else occ.courseName.take(6)
                        "${occ.startPeriod}-${occ.endPeriod}$shortName"
                    }
                }
                views.setInt(
                    viewId,
                    "setBackgroundResource",
                    if (isToday) R.drawable.bg_week_row_today else R.drawable.bg_week_row
                )
                views.setTextColor(
                    viewId,
                    when {
                        isToday -> accentColor
                        occurrences.isEmpty() -> secondaryColor
                        else -> primaryColor
                    }
                )
                views.setTextViewText(viewId, "$prefix │ $summary")
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
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

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
