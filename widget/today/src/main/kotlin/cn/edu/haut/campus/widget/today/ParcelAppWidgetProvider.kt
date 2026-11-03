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
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.ScheduleSnapshotUseCase
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ParcelAppWidgetProvider : AppWidgetProvider() {

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
            val ids = manager.getAppWidgetIds(ComponentName(context, ParcelAppWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(context, manager, ids)
            }
        }
    }

    companion object {
        private fun String?.cleanWidgetText(): String? {
            val trimmed = this?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase()
            if (lower == "null" || lower == "undefined" || lower == "none" || lower == "nil") return null
            val cleaned = trimmed
                .replace(Regex("(?i)\\s*·\\s*null\\b"), "")
                .replace(Regex("(?i)\\bnull\\s*·\\s*"), "")
                .trim()
            if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return null
            return cleaned
        }

        suspend fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            options: Bundle?
        ) {
            val snapshot = ScheduleSnapshotUseCase.loadForWidget(
                context = context,
                appWidgetId = appWidgetId,
                defaultType = WidgetType.PARCEL_4X2
            ) ?: run { WidgetRefreshCoordinator.showSwitching(context, intArrayOf(appWidgetId)); return }

            val active = AccountSessionCoordinator.getInstance(context).currentSnapshot()
                ?: run { WidgetRefreshCoordinator.showSwitching(context, intArrayOf(appWidgetId)); return }
            val allParcels = runCatching { active.repository.parcelItemsFlow.first() }.getOrDefault(emptyList())
            val pending = allParcels.filter {
                val code = it.pickupCode.cleanWidgetText()
                val station = it.stationName.cleanWidgetText()
                val hasArrivalCue = code != null || station != null ||
                    it.estimatedDeliveryText.contains(Regex("驿站|快递柜|取件|代收|自提"))
                (it.status == "READY_FOR_PICKUP" && hasArrivalCue) || (code != null && it.status != "PICKED_UP")
            }

            val views = RemoteViews(context.packageName, R.layout.widget_parcel_pickup_4x2)
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260
            views.setViewVisibility(
                R.id.layout_parcel_secondary,
                if (minWidthDp < 210) View.GONE else View.VISIBLE
            )

            val accentArgb = snapshot.themeAccentArgb
            views.setTextColor(R.id.tv_parcel_count_badge, accentArgb)
            views.setTextColor(R.id.tv_parcel_code_main, accentArgb)
            views.setTextColor(R.id.tv_parcel_sub_1_code, accentArgb)

            if (pending.isNotEmpty()) {
                val first = pending.first()
                val stationCount = pending.map { it.stationName.cleanWidgetText() ?: "校园驿站" }.distinct().size
                views.setTextViewText(
                    R.id.tv_parcel_header,
                    if (stationCount > 1) "📦 待取快递 · $stationCount 个驿站" else "📦 校园快递取件码"
                )
                views.setTextViewText(R.id.tv_parcel_count_badge, "待取 ${pending.size} 件")
                views.setTextViewText(
                    R.id.tv_parcel_station,
                    "📍 ${first.stationName.cleanWidgetText() ?: "校园快递驿站"}"
                )
                views.setTextViewText(
                    R.id.tv_parcel_code_main,
                    first.pickupCode.cleanWidgetText() ?: "待出取件码"
                )
                val firstCarrier = first.carrierName.cleanWidgetText() ?: "快递"
                val firstDesc = first.itemTitle.cleanWidgetText() ?: "尾号 ${first.trackingNumber.takeLast(6)}"
                views.setTextViewText(R.id.tv_parcel_meta_main, "$firstCarrier · $firstDesc")

                val second = pending.getOrNull(1)
                val third = pending.getOrNull(2)
                if (second != null) {
                    views.setTextViewText(
                        R.id.tv_parcel_sub_1_code,
                        second.pickupCode.cleanWidgetText() ?: "待出码"
                    )
                    views.setTextViewText(
                        R.id.tv_parcel_sub_1_desc,
                        "${second.carrierName.cleanWidgetText() ?: "快递"} · ${second.stationName.cleanWidgetText() ?: second.itemTitle.cleanWidgetText() ?: second.trackingNumber.takeLast(6)}"
                    )
                } else {
                    views.setTextViewText(R.id.tv_parcel_sub_1_code, "其余已取完")
                    views.setTextViewText(R.id.tv_parcel_sub_1_desc, "仅剩左侧 1 件待取")
                }

                if (third != null) {
                    val thirdCode = third.pickupCode.cleanWidgetText()
                    views.setTextViewText(
                        R.id.tv_parcel_sub_2_code,
                        if (pending.size > 3) "${thirdCode ?: "待取"} 等${pending.size - 1}件" else (thirdCode ?: "待出码")
                    )
                    views.setTextViewText(
                        R.id.tv_parcel_sub_2_desc,
                        "${third.carrierName.cleanWidgetText() ?: "快递"} · ${third.stationName.cleanWidgetText() ?: third.itemTitle.cleanWidgetText() ?: third.trackingNumber.takeLast(6)}"
                    )
                } else {
                    views.setTextViewText(R.id.tv_parcel_sub_2_code, "去标记已取件 ›")
                    views.setTextViewText(R.id.tv_parcel_sub_2_desc, "点击直达快递中心")
                }
            } else {
                val inTransitCount = allParcels.count { it.status != "PICKED_UP" }
                views.setTextViewText(R.id.tv_parcel_header, "📦 校园快递取件码")
                views.setTextViewText(
                    R.id.tv_parcel_count_badge,
                    if (inTransitCount > 0) "运输中 $inTransitCount" else "无待取"
                )
                views.setTextViewText(R.id.tv_parcel_station, "📍 校园菜鸟驿站 / 快递柜")
                views.setTextViewText(R.id.tv_parcel_code_main, "暂无待取件")
                views.setTextViewText(
                    R.id.tv_parcel_meta_main,
                    if (inTransitCount > 0) "有 $inTransitCount 件包裹在途，到站自动显示取件码" else "点击同步小米快递或粘贴取件短信"
                )
                views.setTextViewText(R.id.tv_parcel_sub_1_code, "手机号同步")
                views.setTextViewText(R.id.tv_parcel_sub_1_desc, "自动拉取小米服务包裹")
                views.setTextViewText(R.id.tv_parcel_sub_2_code, "短信智能识码")
                views.setTextViewText(R.id.tv_parcel_sub_2_desc, "粘贴短信秒提取件码 ›")
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                WidgetRefreshCoordinator.stampIntent(this, snapshot, appWidgetId, "open_parcel_hub")
                putExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB, "parcel_hub")
            }
            if (launchIntent != null) {
                val pi = PendingIntent.getActivity(
                    context,
                    appWidgetId + 30000,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.parcel_widget_root, pi)
            }

            WidgetRefreshCoordinator.publishIfCurrent(context, appWidgetId, snapshot, views)
        }
    }
}
