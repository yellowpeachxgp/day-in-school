package cn.edu.haut.campus.core.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.domain.ReminderPolicyEngine
import cn.edu.haut.campus.core.model.EffectiveReminderPolicy
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.SnoozeChoice
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

object ReminderNotificationFactory {

    const val CHANNEL_CLASS_REMINDERS = "class_reminders_v1"
    const val CHANNEL_EXAM_REMINDERS = "exam_reminders_v1"
    const val CHANNEL_LIVE_COURSE = "live_course_ongoing_v1"
    const val CHANNEL_REMINDER_STATUS = "reminder_status_v1"

    const val EXTRA_REMINDER_NAV_ACTION = "extra_reminder_nav_action"
    const val EXTRA_REMINDER_BUILDING_ID = "extra_reminder_building_id"
    const val EXTRA_REMINDER_ROOM_TEXT = "extra_reminder_room_text"
    const val EXTRA_REMINDER_EVENT_KEY = "extra_reminder_event_key"
    const val EXTRA_REMINDER_TASK_ID = "extra_reminder_task_id"

    const val NAV_ACTION_OPEN_TODAY = "OPEN_TODAY"
    const val NAV_ACTION_OPEN_EXAMS = "OPEN_EXAMS"
    const val NAV_ACTION_OPEN_MAP = "OPEN_MAP"

    private const val TEST_NOTIFICATION_ID = 990101
    private const val LIVE_COURSE_NOTIFICATION_ID = 990102
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val classChannel = NotificationChannel(
            CHANNEL_CLASS_REMINDERS,
            "上课提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "课前提醒、教室位置与稍后提醒快捷操作"
            enableVibration(true)
        }

        val examChannel = NotificationChannel(
            CHANNEL_EXAM_REMINDERS,
            "考试提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "考前 1 天与考前 1 小时提醒、考场座位与导航"
            enableVibration(true)
        }

        val liveCourseChannel = NotificationChannel(
            CHANNEL_LIVE_COURSE,
            "课程实时实况通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "基于 Android 原生 Chronometer 的课前实时倒计时与课中下课倒计时常驻实况卡片"
            enableVibration(false)
            setShowBadge(true)
        }

        val statusChannel = NotificationChannel(
            CHANNEL_REMINDER_STATUS,
            "提醒状态反馈",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "延后成功提示与测试提醒状态反馈"
            setShowBadge(false)
        }

        manager.createNotificationChannels(
            listOf(classChannel, examChannel, liveCourseChannel, statusChannel)
        )
    }

    @SuppressLint("MissingPermission")
    fun postReminderNotification(
        context: Context,
        accountScope: AccountBackgroundScope,
        task: ReminderTaskDomain,
        event: ReminderEvent,
        policy: EffectiveReminderPolicy,
        lockscreenPrivate: Boolean,
        now: Instant
    ): Boolean {
        if (!accountScope.isActive(context) || !hasPostNotificationPermission(context)) return false
        ensureChannels(context)

        val channelId = when (task.eventType) {
            ReminderEventType.CLASS -> CHANNEL_CLASS_REMINDERS
            ReminderEventType.EXAM -> CHANNEL_EXAM_REMINDERS
        }

        val startLocalTime = event.startAt.atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FORMATTER)
        val endLocalTime = event.endAt?.atZone(HAUT_ZONE_ID)?.toLocalTime()?.format(TIME_FORMATTER)
        val timeRangeText = if (endLocalTime != null) "$startLocalTime–$endLocalTime" else startLocalTime
        val minutesUntilStart = Duration.between(now, event.startAt).toMinutes().coerceAtLeast(0L)

        val title = when (task.eventType) {
            ReminderEventType.CLASS -> {
                val prefix = if (task.snoozeCount > 0) "再次提醒" else "即将上课"
                "$prefix · ${event.displayTitle}"
            }
            ReminderEventType.EXAM -> {
                val prefix = when (task.reminderKind) {
                    ReminderKind.EXAM_DAY_BEFORE -> "明日考试提醒"
                    ReminderKind.EXAM_HOUR_BEFORE -> if (task.snoozeCount > 0) "考试再次提醒" else "即将开考"
                    ReminderKind.CLASS_BEFORE -> "考试提醒"
                }
                "$prefix · ${event.displayTitle}"
            }
        }

        val roomPart = event.locationText?.takeIf { it.isNotBlank() } ?: "教室待定"
        val seatPart = event.seatNumber?.takeIf { it.isNotBlank() }?.let { " · 座位 $it" }.orEmpty()
        val leadHint = when {
            minutesUntilStart >= 60 -> "约 ${minutesUntilStart / 60} 小时后开始"
            minutesUntilStart > 0 -> "${minutesUntilStart} 分钟后开始"
            else -> "即将开始"
        }
        val body = "$timeRangeText · $roomPart$seatPart（$leadHint）"

        val contentAction = if (task.eventType == ReminderEventType.EXAM) {
            NAV_ACTION_OPEN_EXAMS
        } else {
            NAV_ACTION_OPEN_TODAY
        }
        val contentIntent = buildAppLaunchPendingIntent(
            context = context,
            accountScope = accountScope,
            requestKey = "content:${task.id}:${task.generation}",
            navAction = contentAction,
            taskId = task.id,
            eventKey = task.eventKey,
            buildingId = event.buildingId,
            roomText = event.locationText
        )

        val deleteIntent = buildDismissPendingIntent(
            context = context,
            accountScope = accountScope,
            taskId = task.id,
            generation = task.generation
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(roomPart)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(event.startAt.toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(event.startAt.isAfter(now))
            .setChronometerCountDown(true)

        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        builder.extras.putString(
            "android.shortCriticalText",
            if (minutesUntilStart > 0) "${minutesUntilStart}分后" else "即将开始"
        )

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        if (deleteIntent != null) {
            builder.setDeleteIntent(deleteIntent)
        }

        if (lockscreenPrivate) {
            val publicTitle = if (task.eventType == ReminderEventType.EXAM) {
                "日在校园 · 有一场考试即将开始"
            } else {
                "日在校园 · 有一门课程即将开始"
            }
            val publicNotification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(publicTitle)
                .setContentText("解锁查看具体课程名称、教室与座位信息")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            builder.setPublicVersion(publicNotification)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        // 动态计算可用「稍后提醒」按钮（最多展示 2 个延后按钮 + 1 个导航按钮，适配所有 Android ROM 3 按钮上限）
        val evaluatedOptions = ReminderPolicyEngine.evaluateSnoozeOptions(
            task = task,
            currentEvent = event,
            policy = policy,
            now = now,
            notificationsAllowed = true
        ).filter { it.available }

        val preferredSnoozeChoices = when (task.eventType) {
            ReminderEventType.CLASS -> listOf(SnoozeChoice.Plus5Minutes, SnoozeChoice.Plus10Minutes)
            ReminderEventType.EXAM -> listOf(SnoozeChoice.ExamPlus15Minutes, SnoozeChoice.ExamPlus30Minutes)
        }

        for (choice in preferredSnoozeChoices) {
            if (evaluatedOptions.any { it.choice == choice }) {
                val snoozePendingIntent = buildSnoozePendingIntent(
                    context = context,
                    accountScope = accountScope,
                    taskId = task.id,
                    generation = task.generation,
                    choice = choice
                )
                if (snoozePendingIntent != null) {
                    builder.addAction(
                        0,
                        choice.displayLabel,
                        snoozePendingIntent
                    )
                }
            }
        }

        val mapActionLabel = if (task.eventType == ReminderEventType.EXAM) "去考场" else "去教室"
        val mapPendingIntent = buildAppLaunchPendingIntent(
            context = context,
            accountScope = accountScope,
            requestKey = "map:${task.id}:${task.generation}",
            navAction = NAV_ACTION_OPEN_MAP,
            taskId = task.id,
            eventKey = task.eventKey,
            buildingId = event.buildingId,
            roomText = event.locationText
        )
        if (mapPendingIntent != null) {
            builder.addAction(0, mapActionLabel, mapPendingIntent)
        }

        return runCatching {
            NotificationManagerCompat.from(context).notify(task.stableNotificationId, builder.build())
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun postTestNotification(
        context: Context,
        accountScope: AccountBackgroundScope,
        lockscreenPrivate: Boolean
    ): Boolean {
        if (!accountScope.isActive(context) || !hasPostNotificationPermission(context)) return false
        ensureChannels(context)

        val contentIntent = buildAppLaunchPendingIntent(
            context = context,
            accountScope = accountScope,
            requestKey = "test_notification_tap",
            navAction = NAV_ACTION_OPEN_TODAY,
            taskId = null,
            eventKey = null,
            buildingId = "b_31",
            roomText = "31号教学楼204"
        )
        val mapIntent = buildAppLaunchPendingIntent(
            context = context,
            accountScope = accountScope,
            requestKey = "test_notification_map",
            navAction = NAV_ACTION_OPEN_MAP,
            taskId = null,
            eventKey = null,
            buildingId = "b_31",
            roomText = "31号教学楼204"
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CLASS_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("测试提醒 · 算法设计与分析")
            .setContentText("08:00–09:40 · 31号教学楼204（20 分钟后开始）")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "08:00–09:40 · 31号教学楼204（20 分钟后开始）\n点击「去教室」可在地图中查看路线。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVisibility(
                if (lockscreenPrivate) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC
            )

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        if (mapIntent != null) {
            builder.addAction(0, "去教室", mapIntent)
        }

        if (lockscreenPrivate) {
            val publicVersion: Notification = NotificationCompat.Builder(context, CHANNEL_CLASS_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("日在校园 · 测试课程提醒")
                .setContentText("解锁查看具体课程与教室信息")
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            builder.setPublicVersion(publicVersion)
        }

        return runCatching {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, builder.build())
            true
        }.getOrDefault(false)
    }

    /**
     * 调用 Android 原生 Notification SDK 发布/更新「课程实时实况通知」：
     * - 课前阶段（now < startAt）：启用硬件级 `setUsesChronometer(true)` + `setChronometerCountDown(true)` 精准倒计时至上课时刻；
     * - 课中阶段（startAt <= now < endAt）：自动切换倒计时至下课时刻 `endAt`，并展示原生课程进度条与下一节课预览；
     * - 适配 Android 16 / API 34+ 状态栏实况胶囊（`setShortCriticalText` + `android.requestPromotedOngoing`）。
     */
    @SuppressLint("MissingPermission")
    fun postOrUpdateLiveCourseNotification(
        context: Context,
        accountScope: AccountBackgroundScope,
        event: ReminderEvent,
        nextEventTitleAndRoom: String? = null,
        lockscreenPrivate: Boolean = false,
        now: Instant = Instant.now()
    ): Boolean {
        if (!accountScope.isActive(context) || !hasPostNotificationPermission(context)) return false
        ensureChannels(context)

        val endInstant = event.endAt ?: event.startAt.plus(Duration.ofMinutes(95))
        if (!now.isBefore(endInstant)) {
            cancelLiveCourseNotification(context)
            return false
        }

        val isInClass = !now.isBefore(event.startAt)
        val targetCountdownEpochMs = if (isInClass) {
            endInstant.toEpochMilli()
        } else {
            event.startAt.toEpochMilli()
        }

        val startLocalTime = event.startAt.atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FORMATTER)
        val endLocalTime = endInstant.atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FORMATTER)
        val roomPart = event.locationText?.takeIf { it.isNotBlank() } ?: "教室待定"

        val totalMinutes = Duration.between(event.startAt, endInstant).toMinutes().toInt().coerceAtLeast(1)
        val elapsedMinutes = Duration.between(event.startAt, now).toMinutes().toInt().coerceIn(0, totalMinutes)
        val remainingMinutes = if (isInClass) {
            Duration.between(now, endInstant).toMinutes().coerceAtLeast(1L)
        } else {
            Duration.between(now, event.startAt).toMinutes().coerceAtLeast(1L)
        }

        val title = if (isInClass) {
            "正在上课 · ${event.displayTitle}"
        } else {
            "即将上课 · ${event.displayTitle}"
        }

        val statusSummary = if (isInClass) {
            "$startLocalTime–$endLocalTime · $roomPart · 距下课约 ${remainingMinutes} 分钟"
        } else {
            "$startLocalTime–$endLocalTime · $roomPart · ${remainingMinutes} 分钟后上课"
        }

        val expandedBody = buildString {
            append(statusSummary)
            if (!nextEventTitleAndRoom.isNullOrBlank()) {
                append("\n下一节：").append(nextEventTitleAndRoom)
            }
        }

        val shortChipText = if (isInClass) {
            "下课 ${remainingMinutes}m"
        } else {
            "上课 ${remainingMinutes}m"
        }

        val contentIntent = buildAppLaunchPendingIntent(
            context = context,
            accountScope = accountScope,
            requestKey = "live_course_tap:${event.eventKey}",
            navAction = NAV_ACTION_OPEN_TODAY,
            taskId = null,
            eventKey = event.eventKey,
            buildingId = event.buildingId,
            roomText = event.locationText
        )
        val mapIntent = buildAppLaunchPendingIntent(
            context = context,
            accountScope = accountScope,
            requestKey = "live_course_map:${event.eventKey}",
            navAction = NAV_ACTION_OPEN_MAP,
            taskId = null,
            eventKey = event.eventKey,
            buildingId = event.buildingId,
            roomText = event.locationText
        )
        val dismissLiveIntent = buildDismissLiveCoursePendingIntent(context, accountScope)

        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE_COURSE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(statusSummary)
            .setSubText(if (isInClass) "课中实况 · $roomPart" else "课前实况 · $roomPart")
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setWhen(targetCountdownEpochMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setVisibility(
                if (lockscreenPrivate) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC
            )

        builder.extras.putBoolean("android.requestPromotedOngoing", true)
        builder.extras.putString("android.shortCriticalText", shortChipText)

        if (isInClass) {
            builder.setProgress(totalMinutes, elapsedMinutes, false)
        } else {
            val leadWindow = 45
            val waitProgress = (leadWindow - remainingMinutes.toInt()).coerceIn(0, leadWindow)
            builder.setProgress(leadWindow, waitProgress, false)
        }

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        if (mapIntent != null) {
            builder.addAction(0, "去教室", mapIntent)
        }
        if (dismissLiveIntent != null) {
            builder.addAction(0, "关闭实况", dismissLiveIntent)
        }

        if (lockscreenPrivate) {
            val publicVersion: Notification = NotificationCompat.Builder(context, CHANNEL_LIVE_COURSE)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (isInClass) "日在校园 · 课程进行中" else "日在校园 · 课程即将开始")
                .setContentText("解锁查看实时课程进度与教室位置")
                .setWhen(targetCountdownEpochMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            builder.setPublicVersion(publicVersion)
        }

        return runCatching {
            NotificationManagerCompat.from(context).notify(LIVE_COURSE_NOTIFICATION_ID, builder.build())
            true
        }.getOrDefault(false)
    }

    fun cancelLiveCourseNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(LIVE_COURSE_NOTIFICATION_ID)
        }
    }

    fun cancelNotificationForTask(context: Context, taskId: String) {
        val stableId = (taskId.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
        runCatching {
            NotificationManagerCompat.from(context).cancel(stableId)
        }
    }

    fun cancelAllNotifications(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancelAll()
        }
    }

    private fun hasPostNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun buildSnoozePendingIntent(
        context: Context,
        accountScope: AccountBackgroundScope,
        taskId: String,
        generation: Int,
        choice: SnoozeChoice
    ): PendingIntent? {
        val intent = Intent(context, SnoozeActionReceiver::class.java).apply {
            action = SnoozeActionReceiver.ACTION_SNOOZE_REMINDER
            data = Uri.parse("reminder://snooze/$taskId/$generation/${choice.actionCode}")
            putExtra(SnoozeActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(SnoozeActionReceiver.EXTRA_GENERATION, generation)
            putExtra(SnoozeActionReceiver.EXTRA_SNOOZE_CHOICE, choice.actionCode)
        }
        accountScope.attach(intent)
        val requestCode = ("snooze:$taskId:$generation:${choice.actionCode}".hashCode() and 0x7FFFFFFF)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildDismissLiveCoursePendingIntent(
        context: Context,
        accountScope: AccountBackgroundScope
    ): PendingIntent? {
        val intent = Intent(context, SnoozeActionReceiver::class.java).apply {
            action = SnoozeActionReceiver.ACTION_DISMISS_LIVE_COURSE
            data = Uri.parse("reminder://dismiss_live_course/${accountScope.profileId}")
            putExtra(SnoozeActionReceiver.EXTRA_TASK_ID, "live_course")
        }
        accountScope.attach(intent)
        val requestCode = ("dismiss_live:${accountScope.profileId}".hashCode() and 0x7FFFFFFF)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildDismissPendingIntent(
        context: Context,
        accountScope: AccountBackgroundScope,
        taskId: String,
        generation: Int
    ): PendingIntent? {
        val intent = Intent(context, SnoozeActionReceiver::class.java).apply {
            action = SnoozeActionReceiver.ACTION_DISMISS_REMINDER
            data = Uri.parse("reminder://dismiss/$taskId/$generation")
            putExtra(SnoozeActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(SnoozeActionReceiver.EXTRA_GENERATION, generation)
        }
        accountScope.attach(intent)
        val requestCode = ("dismiss:$taskId:$generation".hashCode() and 0x7FFFFFFF)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildAppLaunchPendingIntent(
        context: Context,
        accountScope: AccountBackgroundScope,
        requestKey: String,
        navAction: String,
        taskId: String?,
        eventKey: String?,
        buildingId: String?,
        roomText: String?
    ): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launchIntent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("hautcampus://reminder/$navAction/${requestKey.hashCode()}")
            putExtra(EXTRA_REMINDER_NAV_ACTION, navAction)
            if (taskId != null) putExtra(EXTRA_REMINDER_TASK_ID, taskId)
            if (eventKey != null) putExtra(EXTRA_REMINDER_EVENT_KEY, eventKey)
            if (buildingId != null) putExtra(EXTRA_REMINDER_BUILDING_ID, buildingId)
            if (roomText != null) putExtra(EXTRA_REMINDER_ROOM_TEXT, roomText)
        }
        accountScope.attach(launchIntent)
        val requestCode = (requestKey.hashCode() and 0x7FFFFFFF)
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
