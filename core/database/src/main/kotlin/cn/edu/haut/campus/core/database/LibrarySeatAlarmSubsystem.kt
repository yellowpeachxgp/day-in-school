package cn.edu.haut.campus.core.database

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class LocalSeatPermissionSnapshot(
    val exactAlarmAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val notificationGranted: Boolean
) {
    val allReady: Boolean
        get() = exactAlarmAllowed && ignoringBatteryOptimizations && notificationGranted

    val missingCount: Int
        get() = listOf(!exactAlarmAllowed, !ignoringBatteryOptimizations, !notificationGranted).count { it }
}

object LibrarySeatAlarmSubsystem {
    const val ACTION_EXECUTE_STAGE = "cn.edu.haut.campus.action.LIBRARY_SEAT_ALARM_STAGE"
    const val EXTRA_PLAN_ID = "extra_plan_id"
    const val EXTRA_STAGE = "extra_stage"
    const val EXTRA_AREA_NAME = "extra_area_name"
    const val EXTRA_SEAT_LABEL = "extra_seat_label"
    const val EXTRA_EXECUTION_MODE = "extra_execution_mode"

    private const val CHANNEL_ID = "library_seat_booking_channel"
    private const val CHANNEL_NAME = "图书馆抢座准点唤醒与结果通知"

    fun inspectPermissions(context: Context): LocalSeatPermissionSnapshot {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true

        val runtimeNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notifEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()

        return LocalSeatPermissionSnapshot(
            exactAlarmAllowed = exactAllowed,
            ignoringBatteryOptimizations = ignoringBattery,
            notificationGranted = runtimeNotifGranted && notifEnabled
        )
    }

    fun buildExactAlarmSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun buildIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildBatteryOptimizationListIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于 07:00 图书馆准点自动抢座唤醒、T-30m 会话预检与选座结果实时通知"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun schedulePlanAlarms(context: Context, plan: LibraryBookingPlanEntity) {
        val appContext = context.applicationContext
        if (plan.status in setOf("CANCELLED", "CONFIRMED", "FAILED", "EXPIRED")) {
            cancelPlanAlarms(appContext, plan.id)
            return
        }

        ensureNotificationChannel(appContext)
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val nowMs = System.currentTimeMillis()
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        val isCloudHosted = plan.executionMode.equals("CLOUD_HOSTED", ignoreCase = true)
        val fireTriggerMs = if (isCloudHosted) plan.fireAtEpochMillis + 2_000L else plan.fireAtEpochMillis
        val stages = listOf(
            "PRECHECK" to plan.precheckAtEpochMillis,
            "FINAL_CHECK" to plan.finalCheckAtEpochMillis,
            "FIRE" to fireTriggerMs
        )

        for ((stage, triggerAtMs) in stages) {
            if (triggerAtMs <= nowMs - 60_000L && stage != "FIRE") continue
            val effectiveTriggerMs = maxOf(triggerAtMs, nowMs + 1_500L)
            val pendingIntent = buildStagePendingIntent(
                context = appContext,
                planId = plan.id,
                stage = stage,
                areaName = plan.preferredAreaName,
                seatLabel = plan.preferredSeatLabel,
                executionMode = plan.executionMode
            )
            runCatching {
                if (canExact) {
                    if (stage == "FIRE") {
                        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
                        val showPending = launchIntent?.let {
                            PendingIntent.getActivity(
                                appContext,
                                requestCodeFor(plan.id, "SHOW"),
                                it,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                        }
                        alarmManager.setAlarmClock(
                            AlarmManager.AlarmClockInfo(effectiveTriggerMs, showPending),
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            effectiveTriggerMs,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        effectiveTriggerMs,
                        pendingIntent
                    )
                }
            }
        }
    }

    fun cancelPlanAlarms(context: Context, planId: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (stage in listOf("PRECHECK", "FINAL_CHECK", "FIRE")) {
            val intent = Intent(appContext, LibrarySeatAlarmReceiver::class.java).apply {
                action = ACTION_EXECUTE_STAGE
            }
            val pending = PendingIntent.getBroadcast(
                appContext,
                requestCodeFor(planId, stage),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                runCatching { alarmManager.cancel(pending) }
                pending.cancel()
            }
        }
    }

    fun triggerTestWakeupNotification(context: Context): Boolean {
        val appContext = context.applicationContext
        ensureNotificationChannel(appContext)
        val snap = inspectPermissions(appContext)
        postNotification(
            context = appContext,
            notificationId = 880100,
            title = "本地自动抢座唤醒测试成功",
            body = buildString {
                append("精准闹钟：")
                append(if (snap.exactAlarmAllowed) "已激活" else "待开启")
                append(" · 省电白名单：")
                append(if (snap.ignoringBatteryOptimizations) "已加入" else "待加入")
                append("。到点将准时为您唤醒并执行选座。")
            }
        )
        return snap.notificationGranted
    }

    @SuppressLint("MissingPermission")
    internal fun postNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String
    ) {
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .apply {
                if (contentIntent != null) setContentIntent(contentIntent)
            }
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    private fun buildStagePendingIntent(
        context: Context,
        planId: String,
        stage: String,
        areaName: String,
        seatLabel: String,
        executionMode: String
    ): PendingIntent {
        val intent = Intent(context, LibrarySeatAlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_STAGE
            putExtra(EXTRA_PLAN_ID, planId)
            putExtra(EXTRA_STAGE, stage)
            putExtra(EXTRA_AREA_NAME, areaName)
            putExtra(EXTRA_SEAT_LABEL, seatLabel)
            putExtra(EXTRA_EXECUTION_MODE, executionMode)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(planId, stage),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCodeFor(planId: String, stage: String): Int {
        return ("$planId|$stage").hashCode() and 0x7FFFFFFF
    }
}

class LibrarySeatAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LibrarySeatAlarmSubsystem.ACTION_EXECUTE_STAGE) return
        val planId = intent.getStringExtra(LibrarySeatAlarmSubsystem.EXTRA_PLAN_ID).orEmpty()
        val stage = intent.getStringExtra(LibrarySeatAlarmSubsystem.EXTRA_STAGE).orEmpty()
        val areaName = intent.getStringExtra(LibrarySeatAlarmSubsystem.EXTRA_AREA_NAME).orEmpty()
        val seatLabel = intent.getStringExtra(LibrarySeatAlarmSubsystem.EXTRA_SEAT_LABEL).orEmpty()
        val executionMode = intent.getStringExtra(LibrarySeatAlarmSubsystem.EXTRA_EXECUTION_MODE).orEmpty()
        if (planId.isBlank() || stage.isBlank()) return

        val appContext = context.applicationContext
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "dayinschool:seat_booking_wakelock"
        )?.apply {
            setReferenceCounted(false)
            runCatching { acquire(25_000L) }
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (executionMode.equals("MANUAL_REMINDER", ignoreCase = true) && stage == "FIRE") {
                    LibrarySeatAlarmSubsystem.postNotification(
                        context = appContext,
                        notificationId = (planId.hashCode() and 0x7FFFFFFF),
                        title = "⏰ 图书馆抢座时间已到",
                        body = "目标座位：$areaName · $seatLabel，点击立即打开「日在校园」一键选座！"
                    )
                }
                val parsedStage = runCatching { TripleScheduleStage.valueOf(stage) }.getOrNull() ?: return@launch
                val repo = CampusRepository.getInstance(appContext)
                val updated = repo.advanceBookingPlanStage(planId = planId, stage = parsedStage).getOrNull()
                if (updated != null) {
                    val (title, body) = when (stage) {
                        "PRECHECK" -> "图书馆抢座 T-30m 预检完成" to (updated.lastDiagnosticMessage ?: "已验证登录态与选座会话")
                        "FINAL_CHECK" -> "图书馆抢座 T-5m 临战就绪" to (updated.lastDiagnosticMessage ?: "距离准点抢座还有 5 分钟")
                        "FIRE" -> if (updated.status == "CONFIRMED") {
                            "🎉 图书馆自动抢座成功" to (updated.lastDiagnosticMessage ?: "已成功锁定 $areaName · $seatLabel")
                        } else {
                            "⚠️ 图书馆抢座结果提醒" to (updated.lastDiagnosticMessage ?: "请打开 App 查看选座详情")
                        }
                        else -> "图书馆抢座状态更新" to (updated.lastDiagnosticMessage ?: "")
                    }
                    if (body.isNotBlank()) {
                        LibrarySeatAlarmSubsystem.postNotification(
                            context = appContext,
                            notificationId = ((planId + stage).hashCode() and 0x7FFFFFFF),
                            title = title,
                            body = body
                        )
                    }
                }
            } finally {
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }
}

class LibrarySeatRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = CampusRepository.getInstance(appContext)
                repo.rescheduleActiveLocalBookingPlans()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
