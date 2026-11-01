package cn.edu.haut.campus.widget.timeline

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
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

        private data class SlotViewGroup(
            val containerId: Int,
            val timeId: Int,
            val titleId: Int,
            val roomId: Int
        )

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.TIMELINE_4X4
            ) ?: run { WidgetRefreshCoordinator.showSwitching(context, intArrayOf(appWidgetId)); return }
            val day = snapshot.daySnapshot
            val views = RemoteViews(context.packageName, R.layout.widget_timeline_4x4)

            val weekText = day.teachingWeek?.let { "第 $it 教学周" } ?: "非教学周"
            views.setTextViewText(
                R.id.tv_tl_title,
                "${day.date.format(DATE_FMT)} ${day.date.dayOfWeek.ChineseLabel} · $weekText"
            )
            views.setTextViewText(
                R.id.tv_tl_now_indicator,
                "共 ${day.items.size} 节课"
            )

            val slotGroups = listOf(
                SlotViewGroup(R.id.layout_tl_slot_1, R.id.tv_tl_time_1, R.id.tv_tl_slot_1, R.id.tv_tl_room_1),
                SlotViewGroup(R.id.layout_tl_slot_2, R.id.tv_tl_time_2, R.id.tv_tl_slot_2, R.id.tv_tl_room_2),
                SlotViewGroup(R.id.layout_tl_slot_3, R.id.tv_tl_time_3, R.id.tv_tl_slot_3, R.id.tv_tl_room_3),
                SlotViewGroup(R.id.layout_tl_slot_4, R.id.tv_tl_time_4, R.id.tv_tl_slot_4, R.id.tv_tl_room_4)
            )

            val primaryColor = ContextCompat.getColor(context, R.color.tl_widget_primary)
            val secondaryColor = ContextCompat.getColor(context, R.color.tl_widget_secondary)
            val accentColor = snapshot.themeAccentArgb
            views.setTextColor(R.id.tv_tl_now_indicator, accentColor)

            val activeItem = day.activeOrNextClass

            if (day.items.isEmpty()) {
                val hint = day.nextClassDayHint
                for ((idx, grp) in slotGroups.withIndex()) {
                    views.setInt(
                        grp.containerId,
                        "setBackgroundResource",
                        if (idx == 0) R.drawable.bg_timeline_slot_active else R.drawable.bg_timeline_slot
                    )
                    views.setTextColor(grp.timeId, secondaryColor)
                    views.setTextColor(grp.titleId, if (idx == 0) accentColor else secondaryColor)
                    views.setTextColor(grp.roomId, accentColor)
                }
                views.setTextViewText(R.id.tv_tl_time_1, "今日")
                views.setTextViewText(R.id.tv_tl_slot_1, "今天没有安排课程")
                views.setTextViewText(R.id.tv_tl_room_1, day.calendarNotice ?: "无课")

                views.setTextViewText(
                    R.id.tv_tl_time_2,
                    hint?.let { "${it.nextDate.format(DATE_FMT)} ${it.firstCourseStartTime.format(TIME_FMT)}" } ?: "下次课"
                )
                views.setTextViewText(
                    R.id.tv_tl_slot_2,
                    hint?.firstCourseName ?: "近期暂无排课"
                )
                views.setTextViewText(
                    R.id.tv_tl_room_2,
                    hint?.let { WidgetRoomFormatter.formatFullRoom(it.firstCourseRoom, snapshot.config.privacyMode) } ?: ""
                )

                views.setTextViewText(R.id.tv_tl_time_3, "自习")
                views.setTextViewText(R.id.tv_tl_slot_3, "图书馆选座与空教室查询")
                views.setTextViewText(R.id.tv_tl_room_3, "去学业页 ›")

                views.setTextViewText(R.id.tv_tl_time_4, "课表")
                views.setTextViewText(R.id.tv_tl_slot_4, "查看完整学期课表")
                views.setTextViewText(R.id.tv_tl_room_4, "打开 ›")
            } else {
                for (index in slotGroups.indices) {
                    val item = day.items.getOrNull(index)
                    val grp = slotGroups[index]
                    val isFocus = item != null && item == activeItem
                    views.setInt(
                        grp.containerId,
                        "setBackgroundResource",
                        if (isFocus) R.drawable.bg_timeline_slot_active else R.drawable.bg_timeline_slot
                    )
                    if (item != null) {
                        val occ = item.occurrence
                        val isFinished = item.status == ClassStatus.FINISHED
                        views.setTextColor(grp.timeId, if (isFocus) accentColor else secondaryColor)
                        views.setTextColor(
                            grp.titleId,
                            when {
                                isFocus -> accentColor
                                isFinished -> secondaryColor
                                else -> primaryColor
                            }
                        )
                        views.setTextColor(grp.roomId, if (isFinished) secondaryColor else accentColor)
                        views.setTextViewText(
                            grp.timeId,
                            "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)}"
                        )
                        views.setTextViewText(
                            grp.titleId,
                            if (snapshot.config.privacyMode) "课程已隐藏" else occ.courseName
                        )
                        views.setTextViewText(
                            grp.roomId,
                            WidgetRoomFormatter.formatFullRoom(occ.rawRoom, snapshot.config.privacyMode)
                        )
                    } else {
                        views.setTextColor(grp.timeId, secondaryColor)
                        views.setTextColor(grp.titleId, secondaryColor)
                        views.setTextColor(grp.roomId, secondaryColor)
                        views.setTextViewText(grp.timeId, "空闲")
                        views.setTextViewText(grp.titleId, "自由学习或休息时段")
                        views.setTextViewText(grp.roomId, "")
                    }
                }
            }

            views.setTextViewText(
                R.id.tv_tl_footer,
                activeItem?.let {
                    val room = WidgetRoomFormatter.formatFullRoom(it.occurrence.rawRoom, snapshot.config.privacyMode)
                    "${it.statusText} · ${it.occurrence.courseName} · $room"
                } ?: "今日课程已全部结束"
            )

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                WidgetRefreshCoordinator.stampIntent(this, snapshot, appWidgetId, "open")
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

            if (!WidgetRefreshCoordinator.publishIfCurrent(context, appWidgetId, snapshot, views)) return
        }
    }
}
