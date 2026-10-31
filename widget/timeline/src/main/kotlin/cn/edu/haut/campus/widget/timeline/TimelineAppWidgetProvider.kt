package cn.edu.haut.campus.widget.timeline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.ScheduleSnapshotUseCase
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class TimelineAppWidgetProvider : AppWidgetProvider() {

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
            val ids = manager.getAppWidgetIds(ComponentName(context, TimelineAppWidgetProvider::class.java))
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
            appWidgetId: Int
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.TIMELINE_4X4
            )
            val day = snapshot.daySnapshot
            val views = RemoteViews(context.packageName, R.layout.widget_timeline_4x4)

            val weekText = day.teachingWeek?.let { "第 $it 教学周" } ?: "假期/非教学周"
            views.setTextViewText(
                R.id.tv_tl_title,
                "${day.date.format(DATE_FMT)} ${day.date.dayOfWeek.ChineseLabel} · $weekText"
            )
            views.setTextViewText(
                R.id.tv_tl_now_indicator,
                "● 基准 ${day.now.toLocalTime().format(TIME_FMT)}"
            )

            val slotIds = listOf(
                R.id.tv_tl_slot_1,
                R.id.tv_tl_slot_2,
                R.id.tv_tl_slot_3,
                R.id.tv_tl_slot_4
            )

            if (day.items.isEmpty()) {
                views.setTextViewText(R.id.tv_tl_slot_1, "08:00 │ 今天没有安排课程")
                val hint = day.nextClassDayHint
                views.setTextViewText(
                    R.id.tv_tl_slot_2,
                    hint?.let {
                        "下次课 │ ${it.nextDate.format(DATE_FMT)} ${it.firstCourseStartTime.format(TIME_FMT)} ${it.firstCourseName}"
                    } ?: "全天自由安排 · 可前往图书馆或自习室"
                )
                views.setTextViewText(
                    R.id.tv_tl_slot_3,
                    hint?.let { "上课地点 │ ${it.firstCourseRoom}" } ?: (day.calendarNotice ?: "祝学业顺利")
                )
                views.setTextViewText(R.id.tv_tl_slot_4, "点击打开 App 查看完整周课表")
            } else {
                for (index in slotIds.indices) {
                    val item = day.items.getOrNull(index)
                    val slotId = slotIds[index]
                    if (item != null) {
                        views.setTextViewText(slotId, formatSlotLine(item, snapshot.config.privacyMode))
                    } else {
                        views.setTextViewText(slotId, "──  空闲时段（自习/休息）")
                    }
                }
            }

            val active = day.activeOrNextClass
            views.setTextViewText(
                R.id.tv_tl_footer,
                active?.let { "当前焦点：${it.occurrence.courseName} (${it.statusText})" }
                    ?: "今日课程已全部完成 · 点击打开时间轴"
            )

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
                views.setOnClickPendingIntent(R.id.timeline_widget_root, pi)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun formatSlotLine(item: TimelineOccurrenceState, privacyMode: Boolean): String {
            val occ = item.occurrence
            val statusSymbol = when (item.status) {
                ClassStatus.IN_PROGRESS -> "● [上课中]"
                ClassStatus.UPCOMING -> "▲ [${item.statusText}]"
                ClassStatus.FINISHED -> "✓ [已结束]"
            }
            val title = if (privacyMode) "课程已隐藏" else "${occ.courseName} · ${occ.rawRoom}"
            return "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)} │ $title $statusSymbol"
        }
    }
}
