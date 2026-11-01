/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.reminder.focus

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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import cn.edu.haut.campus.core.database.toDomain
import cn.edu.haut.campus.core.database.toEntity
import cn.edu.haut.campus.core.domain.FocusSessionEngine
import cn.edu.haut.campus.core.model.FocusDeliveryTelemetry
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

sealed interface FocusAlarmScheduleOutcome {
    data class Scheduled(val mode: ReminderAccuracyMode) : FocusAlarmScheduleOutcome
    data class Failed(val reason: String) : FocusAlarmScheduleOutcome
}

interface FocusAlarmScheduler {
    fun canScheduleExactAlarms(): Boolean
    fun scheduleSessionAlarm(session: FocusSessionDomain): FocusAlarmScheduleOutcome
    fun cancelSessionAlarm(sessionId: String, generation: Int)
    fun scheduleTenSecondTestAlarm(generation: Int, triggerAtEpochMs: Long): FocusAlarmScheduleOutcome
}

/**
 * 与 `:core:reminder` 课程/考试提醒严格隔离的自习专注闹钟调度器。
 *
 * 隔离要素：
 * - 独立 Receiver: [FocusAlarmReceiver]
 * - 独立 Action: `cn.edu.haut.campus.action.FOCUS_ALARM_FIRE` / `FOCUS_ALARM_TEST`
 * - 独立 URI Scheme: `haut://focus/alarm/{sessionId}?gen={generation}`
 */
class AndroidFocusAlarmScheduler(
    private val context: Context,
    private val accountScope: AccountBackgroundScope? = null,
    private val isActive: () -> Boolean = { true }
) : FocusAlarmScheduler {

    override fun canScheduleExactAlarms(): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
    }

    override fun scheduleSessionAlarm(session: FocusSessionDomain): FocusAlarmScheduleOutcome {
        if (!isActive()) return FocusAlarmScheduleOutcome.Failed("ACCOUNT_SESSION_EXPIRED")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return FocusAlarmScheduleOutcome.Failed("ALARM_MANAGER_NULL")

        val pendingIntent = buildSessionPendingIntent(
            sessionId = session.id,
            generation = session.generation,
            expectedEndAtEpochMs = session.expectedEndAtEpochMs,
            noCreate = false
        ) ?: return FocusAlarmScheduleOutcome.Failed("PENDING_INTENT_NULL")

        return scheduleWithExactFallback(
            alarmManager = alarmManager,
            triggerAtEpochMs = session.expectedEndAtEpochMs,
            pendingIntent = pendingIntent,
            deliveryKind = FocusDeliveryTracker.KIND_FOCUS
        )
    }

    override fun cancelSessionAlarm(sessionId: String, generation: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        listOfNotNull(
            buildSessionPendingIntent(
                sessionId = sessionId,
                generation = generation,
                expectedEndAtEpochMs = 0L,
                noCreate = true,
                attachAccountScope = true
            ),
            buildSessionPendingIntent(
                sessionId = sessionId,
                generation = generation,
                expectedEndAtEpochMs = 0L,
                noCreate = true,
                attachAccountScope = false
            )
        ).forEach { existing ->
            runCatching {
                alarmManager.cancel(existing)
                existing.cancel()
            }
        }
    }

    override fun scheduleTenSecondTestAlarm(
        generation: Int,
        triggerAtEpochMs: Long
    ): FocusAlarmScheduleOutcome {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return FocusAlarmScheduleOutcome.Failed("ALARM_MANAGER_NULL")

        val intent = Intent(context, FocusAlarmReceiver::class.java).apply {
            action = ACTION_FOCUS_ALARM_TEST
            data = Uri.parse("haut://focus/test?gen=$generation")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_FOCUS_GENERATION, generation)
        }
        if (!isActive()) return FocusAlarmScheduleOutcome.Failed("ACCOUNT_SESSION_EXPIRED")
        accountScope?.attach(intent)
        val requestCode = ("focus_test:$generation".hashCode() and 0x7FFFFFFF)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return FocusAlarmScheduleOutcome.Failed("TEST_PENDING_INTENT_NULL")

        return scheduleWithExactFallback(
            alarmManager = alarmManager,
            triggerAtEpochMs = triggerAtEpochMs,
            pendingIntent = pendingIntent,
            deliveryKind = FocusDeliveryTracker.KIND_TEST
        )
    }

    private fun scheduleWithExactFallback(
        alarmManager: AlarmManager,
        triggerAtEpochMs: Long,
        pendingIntent: PendingIntent,
        deliveryKind: String
    ): FocusAlarmScheduleOutcome {
        val exactAllowed = canScheduleExactAlarms()
        FocusDeliveryTracker.recordPlanned(
            context = context,
            kind = deliveryKind,
            plannedAtEpochMs = triggerAtEpochMs,
            exact = exactAllowed
        )
        if (exactAllowed) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtEpochMs,
                    pendingIntent
                )
                return FocusAlarmScheduleOutcome.Scheduled(ReminderAccuracyMode.EXACT)
            } catch (_: SecurityException) {
                FocusDeliveryTracker.recordPlanned(
                    context = context,
                    kind = deliveryKind,
                    plannedAtEpochMs = triggerAtEpochMs,
                    exact = false
                )
            } catch (t: Throwable) {
                return FocusAlarmScheduleOutcome.Failed("EXACT_ERROR:${t.javaClass.simpleName}")
            }
        }

        return try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMs,
                pendingIntent
            )
            FocusAlarmScheduleOutcome.Scheduled(ReminderAccuracyMode.INEXACT)
        } catch (t: Throwable) {
            FocusAlarmScheduleOutcome.Failed("INEXACT_ERROR:${t.javaClass.simpleName}")
        }
    }

    internal fun buildSessionPendingIntent(
        sessionId: String,
        generation: Int,
        expectedEndAtEpochMs: Long,
        noCreate: Boolean,
        attachAccountScope: Boolean = true
    ): PendingIntent? {
        val intent = Intent(context, FocusAlarmReceiver::class.java).apply {
            action = ACTION_FOCUS_ALARM_FIRE
            data = buildFocusAlarmUri(sessionId, generation)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_FOCUS_SESSION_ID, sessionId)
            putExtra(EXTRA_FOCUS_GENERATION, generation)
            putExtra(EXTRA_FOCUS_EXPECTED_END_MS, expectedEndAtEpochMs)
        }
        if (attachAccountScope) accountScope?.attach(intent)
        val flags = if (noCreate) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        val requestCode = ("focus_alarm:$sessionId:$generation".hashCode() and 0x7FFFFFFF)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        const val ACTION_FOCUS_ALARM_FIRE = "cn.edu.haut.campus.action.FOCUS_ALARM_FIRE"
        const val ACTION_FOCUS_ALARM_TEST = "cn.edu.haut.campus.action.FOCUS_ALARM_TEST"

        const val EXTRA_FOCUS_SESSION_ID = "extra_focus_session_id"
        const val EXTRA_FOCUS_GENERATION = "extra_focus_generation"
        const val EXTRA_FOCUS_EXPECTED_END_MS = "extra_focus_expected_end_ms"

        fun buildFocusAlarmUri(sessionId: String, generation: Int): Uri =
            Uri.parse("haut://focus/alarm/$sessionId?gen=$generation")
    }
}

/**
 * 专注提醒本地送达遥测记录器（不记录任何通知正文或个人身份信息）。
 */
object FocusDeliveryTracker {

    const val KIND_FOCUS = "focus"
    const val KIND_TEST = "test"
    private const val PREFS_NAME = "haut_focus_delivery_diag"
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Volatile
    var isAppInForeground: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordPlanned(
        context: Context,
        kind: String,
        plannedAtEpochMs: Long,
        exact: Boolean
    ) {
        prefs(context).edit()
            .putLong("$kind.planned", plannedAtEpochMs)
            .putBoolean("$kind.exact", exact)
            .remove("$kind.received")
            .remove("$kind.submitted")
            .remove("$kind.error")
            .remove("$kind.background")
            .apply()
    }

    fun recordReceived(
        context: Context,
        kind: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putLong("$kind.received", nowEpochMs)
            .putBoolean("$kind.background", !isAppInForeground)
            .apply()
    }

    fun recordSubmitted(
        context: Context,
        kind: String,
        error: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putLong("$kind.submitted", if (error == null) nowEpochMs else 0L)
            .putString("$kind.error", error)
            .apply()
    }

    fun readTelemetry(context: Context, kind: String): FocusDeliveryTelemetry {
        val p = prefs(context)
        return FocusDeliveryTelemetry(
            kind = kind,
            plannedAtEpochMs = p.getLong("$kind.planned", 0L),
            exact = p.getBoolean("$kind.exact", false),
            receivedAtEpochMs = p.getLong("$kind.received", 0L),
            receivedInBackground = if (p.contains("$kind.background")) p.getBoolean("$kind.background", false) else null,
            submittedAtEpochMs = p.getLong("$kind.submitted", 0L),
            errorText = p.getString("$kind.error", null)
        )
    }

    fun buildDiagnosticSummary(context: Context, kind: String = KIND_TEST): String {
        val t = readTelemetry(context, kind)
        if (t.plannedAtEpochMs <= 0L) {
            return if (kind == KIND_TEST) {
                "尚未发起 10 秒后台测试。点击下方按钮后可切回系统桌面等待通知。"
            } else {
                "暂无最近一次专注提醒记录。"
            }
        }
        val modeLabel = if (t.exact) "精确闹钟" else "标准闹钟"
        val plannedStr = formatClock(t.plannedAtEpochMs)
        val receivedStr = if (t.receivedAtEpochMs > 0L) {
            val env = when (t.receivedInBackground) {
                true -> "后台唤醒"
                false -> "前台到达"
                null -> "已到达"
            }
            "${formatClock(t.receivedAtEpochMs)} ($env)"
        } else {
            "等待中"
        }
        val submitStr = when {
            !t.errorText.isNullOrBlank() -> "未送达（${t.errorText}）"
            t.submittedAtEpochMs > 0L -> "${formatClock(t.submittedAtEpochMs)} 已提交系统"
            else -> "等待提交"
        }
        return "模式：$modeLabel · 计划：$plannedStr · 广播：$receivedStr · 通知：$submitStr"
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun formatClock(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FMT)
}

/**
 * 独立专注计时通知工厂（专属渠道 `focus_timer_channel_v1` 与专属 Tag `"haut_focus_timer"`）。
 */
object FocusNotificationFactory {

    const val CHANNEL_FOCUS_TIMER = "focus_timer_channel_v1"
    const val NOTIFICATION_TAG_FOCUS = "haut_focus_timer"
    const val NOTIFICATION_ID_FOCUS_COMPLETE = 880001
    const val NOTIFICATION_ID_FOCUS_TEST = 880002
    const val EXTRA_OVERLAY_ROUTE = "extra_overlay_route"
    const val OVERLAY_ROUTE_FOCUS = "focus"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_FOCUS_TIMER,
                "自习专注计时提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "自习专注倒计时完成提醒与后台测试通知"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun postFocusCompletedNotification(
        context: Context,
        durationMinutes: Int
    ): Boolean {
        return postInternal(
            context = context,
            notificationId = NOTIFICATION_ID_FOCUS_COMPLETE,
            deliveryKind = FocusDeliveryTracker.KIND_FOCUS,
            title = "自习专注已完成",
            body = "已完成 $durationMinutes 分钟自习专注，起来活动一下吧。"
        )
    }

    fun postTenSecondTestNotification(context: Context): Boolean {
        return postInternal(
            context = context,
            notificationId = NOTIFICATION_ID_FOCUS_TEST,
            deliveryKind = FocusDeliveryTracker.KIND_TEST,
            title = "自习专注 · 10 秒后台测试提醒",
            body = "后台闹钟唤醒与通知渠道工作正常。"
        )
    }

    fun cancelFocusNotifications(context: Context) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(NOTIFICATION_TAG_FOCUS, NOTIFICATION_ID_FOCUS_COMPLETE)
            nm.cancel(NOTIFICATION_TAG_FOCUS, NOTIFICATION_ID_FOCUS_TEST)
        }
    }

    @SuppressLint("MissingPermission")
    private fun postInternal(
        context: Context,
        notificationId: Int,
        deliveryKind: String,
        title: String,
        body: String
    ): Boolean {
        val accountScope = AccountBackgroundScope.current(context) ?: return false
        ensureChannel(context)
        if (!hasNotificationPermission(context)) {
            FocusDeliveryTracker.recordSubmitted(context, deliveryKind, error = "通知权限或渠道未开启")
            return false
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("hautcampus://focus/open/$notificationId")
            putExtra(EXTRA_OVERLAY_ROUTE, OVERLAY_ROUTE_FOCUS)
        }
        launchIntent?.let(accountScope::attach)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_FOCUS_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (contentPendingIntent != null) {
                    setContentIntent(contentPendingIntent)
                }
            }
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_TAG_FOCUS,
                notificationId,
                notification
            )
            FocusDeliveryTracker.recordSubmitted(context, deliveryKind, error = null)
            true
        } catch (_: SecurityException) {
            FocusDeliveryTracker.recordSubmitted(context, deliveryKind, error = "通知权限已变化")
            false
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/**
 * 自习专注协调器（管理状态机持久化、闹钟注册与恢复，与 `ReminderCoordinator` 物理隔离）。
 */
class FocusCoordinator private constructor(
    private val appContext: Context,
    private val repository: CampusRepository,
    private val alarmScheduler: FocusAlarmScheduler,
    private val accountScope: AccountBackgroundScope
) {
    @Volatile private var active = true
    private fun requireActive() {
        check(active && repository.isSessionActive) { "账号已切换，请返回当前账号重试" }
    }

    private val dao = repository.utilityAndFocusDao()
    private val mutex = Mutex()
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val testGenerationCounter = AtomicInteger(1)

    init {
        FocusNotificationFactory.ensureChannel(appContext)
        repository.registerBeforeWipeHook("focus_coordinator_hook") {
            clearAllFocusData()
        }
    }

    suspend fun startFocusSession(
        durationMinutes: Int,
        nowEpochMs: Long = System.currentTimeMillis()
    ): FocusSessionDomain = mutex.withLock {
        requireActive()
        val currentRunning = dao.getActiveRunningFocusSessionSnapshot()?.toDomain()
        val transition = FocusSessionEngine.startSession(
            currentRunningSession = currentRunning,
            durationMinutes = durationMinutes,
            nowEpochMs = nowEpochMs
        )
        transition.alarmToCancel?.let { (oldId, oldGen) ->
            alarmScheduler.cancelSessionAlarm(oldId, oldGen)
        }
        val toSave = buildList {
            transition.cancelledPreviousSession?.let { add(it.toEntity()) }
            add(transition.updatedSession.toEntity())
        }
        dao.upsertFocusSessions(toSave)
        repository.setSelectedFocusMinutes(durationMinutes)

        val outcome = alarmScheduler.scheduleSessionAlarm(transition.updatedSession)
        val scheduledMode = (outcome as? FocusAlarmScheduleOutcome.Scheduled)?.mode ?: ReminderAccuracyMode.INEXACT
        dao.updateFocusScheduleModeIfMatching(
            sessionId = transition.updatedSession.id,
            expectedGeneration = transition.updatedSession.generation,
            scheduleMode = scheduledMode.name,
            nowMs = nowEpochMs
        )
        transition.updatedSession.copy(scheduleMode = scheduledMode)
    }

    suspend fun stopActiveFocusSession(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean = mutex.withLock {
        if (!active || !repository.isSessionActive) return@withLock false
        val currentRunning = dao.getActiveRunningFocusSessionSnapshot()?.toDomain() ?: return false
        when (val res = FocusSessionEngine.cancelSession(currentRunning, nowEpochMs)) {
            is FocusSessionEngine.TransitionResult.Updated -> {
                res.alarmToCancel?.let { (id, gen) -> alarmScheduler.cancelSessionAlarm(id, gen) }
                dao.compareAndCancelFocusSession(
                    sessionId = currentRunning.id,
                    expectedGeneration = currentRunning.generation,
                    reason = "USER_STOPPED",
                    nowMs = nowEpochMs
                )
                true
            }
            is FocusSessionEngine.TransitionResult.Ignored -> false
        }
    }

    /**
     * 前台协程倒计时到点或广播唤醒时触发完成（具备严格 CAS 幂等保护，确保仅首次完成者发送通知）。
     */
    suspend fun completeFocusSessionIfDue(
        sessionId: String,
        expectedGeneration: Int? = null,
        expectedEndAtEpochMs: Long? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        isRestorePath: Boolean = false
    ): Boolean = mutex.withLock {
        if (!active || !repository.isSessionActive) return@withLock false
        val current = dao.getFocusSessionById(sessionId)?.toDomain() ?: return false
        val transition = FocusSessionEngine.completeSession(
            session = current,
            expectedSessionId = sessionId,
            expectedGeneration = expectedGeneration,
            expectedEndAtEpochMs = expectedEndAtEpochMs,
            nowEpochMs = nowEpochMs,
            isRestorePath = isRestorePath
        )
        if (transition !is FocusSessionEngine.TransitionResult.Updated) {
            return false
        }

        val updatedRows = dao.compareAndCompleteFocusSession(
            sessionId = current.id,
            expectedGeneration = current.generation,
            expectedEndAtEpochMs = current.expectedEndAtEpochMs,
            completionSource = transition.updatedSession.completionSource?.name ?: "FOREGROUND_TIMER",
            notified = transition.shouldPostCompletionNotification,
            nowMs = nowEpochMs
        )
        if (updatedRows <= 0) {
            return false
        }

        transition.alarmToCancel?.let { (id, gen) ->
            alarmScheduler.cancelSessionAlarm(id, gen)
        }
        if (transition.shouldPostCompletionNotification && active && repository.isSessionActive) {
            FocusNotificationFactory.postFocusCompletedNotification(
                context = appContext,
                durationMinutes = current.durationMinutes
            )
        }
        true
    }

    /**
     * 应用启动、前后台切换、开机广播或系统时间变更时恢复专注状态：
     * - 未过期会话：重新注册 AlarmManager 闹钟
     * - 已过期会话：15 分钟内补发完成通知，超过 15 分钟仅静默标记完成
     */
    suspend fun restoreOrCompleteActiveSession(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Unit = mutex.withLock {
        if (!active || !repository.isSessionActive) return@withLock
        val running = dao.getActiveRunningFocusSessionSnapshot()?.toDomain() ?: return@withLock
        when (val decision = FocusSessionEngine.evaluateRestoreAction(running, nowEpochMs)) {
            is FocusSessionEngine.RestoreDecision.NoOp -> Unit
            is FocusSessionEngine.RestoreDecision.RescheduleActive -> {
                val outcome = alarmScheduler.scheduleSessionAlarm(decision.session)
                val mode = (outcome as? FocusAlarmScheduleOutcome.Scheduled)?.mode ?: ReminderAccuracyMode.INEXACT
                dao.updateFocusScheduleModeIfMatching(
                    sessionId = decision.session.id,
                    expectedGeneration = decision.session.generation,
                    scheduleMode = mode.name,
                    nowMs = nowEpochMs
                )
            }
            is FocusSessionEngine.RestoreDecision.CompleteExpired -> {
                val updatedRows = dao.compareAndCompleteFocusSession(
                    sessionId = running.id,
                    expectedGeneration = running.generation,
                    expectedEndAtEpochMs = running.expectedEndAtEpochMs,
                    completionSource = decision.completedSession.completionSource?.name
                        ?: "RESTORE_SILENT_PAST_GRACE_WINDOW",
                    notified = decision.shouldNotify,
                    nowMs = nowEpochMs
                )
                decision.alarmToCancel?.let { (id, gen) ->
                    alarmScheduler.cancelSessionAlarm(id, gen)
                }
                if (updatedRows > 0 && decision.shouldNotify && active && repository.isSessionActive) {
                    FocusNotificationFactory.postFocusCompletedNotification(
                        context = appContext,
                        durationMinutes = running.durationMinutes
                    )
                }
            }
        }
    }

    fun restoreOrCompleteActiveSessionAsync(nowEpochMs: Long = System.currentTimeMillis()) {
        coordinatorScope.launch {
            restoreOrCompleteActiveSession(nowEpochMs)
        }
    }

    fun triggerTenSecondTestAlarm(nowEpochMs: Long = System.currentTimeMillis()): FocusAlarmScheduleOutcome {
        requireActive()
        val gen = testGenerationCounter.incrementAndGet()
        return alarmScheduler.scheduleTenSecondTestAlarm(
            generation = gen,
            triggerAtEpochMs = nowEpochMs + 10_000L
        )
    }

    suspend fun clearAllFocusData() = mutex.withLock {
        val running = dao.getActiveRunningFocusSessionSnapshot()?.toDomain()
        if (running != null) {
            alarmScheduler.cancelSessionAlarm(running.id, running.generation)
        }
        dao.clearAllFocusSessions()
        FocusNotificationFactory.cancelFocusNotifications(appContext)
        FocusDeliveryTracker.clearAll(appContext)
    }

    suspend fun deactivate() {
        active = false
        coordinatorScope.cancel()
        mutex.withLock {
            dao.getActiveRunningFocusSessionSnapshot()?.toDomain()?.let { running ->
                alarmScheduler.cancelSessionAlarm(running.id, running.generation)
            }
            FocusNotificationFactory.cancelFocusNotifications(appContext)
            FocusDeliveryTracker.clearAll(appContext)
        }
    }

    companion object {
        @Volatile private var INSTANCE: FocusCoordinator? = null

        fun bind(context: Context, session: ActiveSession): FocusCoordinator = synchronized(this) {
            val scope = AccountBackgroundScope.from(session)
            val scheduler = AndroidFocusAlarmScheduler(context.applicationContext, scope) { session.repository.isSessionActive }
            FocusCoordinator(context.applicationContext, session.repository, scheduler, scope).also { INSTANCE = it }
        }

        suspend fun deactivateCurrent() {
            val old = synchronized(this) { INSTANCE.also { INSTANCE = null } }
            old?.deactivate()
        }

        fun getInstance(context: Context): FocusCoordinator {
            val session = AccountSessionCoordinator.getInstance(context).currentSnapshot()
                ?: error("账号正在初始化或切换，请稍后重试")
            return INSTANCE?.takeIf { it.active && it.accountScope.matches(session) }
                ?: error("专注计时尚未绑定当前账号")
        }
    }
}

/**
 * 专注计时到期与 10 秒后台测试广播接收器。
 */
class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val accountScope = AccountBackgroundScope.read(intent) ?: return
        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000L) {
                    val sessions = AccountSessionCoordinator.getInstance(appCtx)
                    sessions.initialize()
                    sessions.withActiveSession(accountScope.profileId, accountScope.epoch) {
                        when (action) {
                            AndroidFocusAlarmScheduler.ACTION_FOCUS_ALARM_FIRE -> {
                                FocusDeliveryTracker.recordReceived(appCtx, FocusDeliveryTracker.KIND_FOCUS)
                                val sessionId = intent.getStringExtra(
                                    AndroidFocusAlarmScheduler.EXTRA_FOCUS_SESSION_ID
                                ) ?: return@withActiveSession
                                val gen = intent.getIntExtra(
                                    AndroidFocusAlarmScheduler.EXTRA_FOCUS_GENERATION,
                                    -1
                                ).takeIf { it > 0 }
                                val expectedEnd = intent.getLongExtra(
                                    AndroidFocusAlarmScheduler.EXTRA_FOCUS_EXPECTED_END_MS,
                                    -1L
                                ).takeIf { it > 0L }
                                FocusCoordinator.getInstance(appCtx).completeFocusSessionIfDue(
                                    sessionId = sessionId,
                                    expectedGeneration = gen,
                                    expectedEndAtEpochMs = expectedEnd,
                                    nowEpochMs = System.currentTimeMillis(),
                                    isRestorePath = false
                                )
                            }

                            AndroidFocusAlarmScheduler.ACTION_FOCUS_ALARM_TEST -> {
                                FocusDeliveryTracker.recordReceived(appCtx, FocusDeliveryTracker.KIND_TEST)
                                FocusNotificationFactory.postTenSecondTestNotification(appCtx)
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * 开机、安装包升级、系统时间变更与精确闹钟权限变更时的自习专注恢复接收器。
 */
class FocusRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in RESTORE_ACTIONS) return
        val appCtx = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000L) {
                    val sessions = AccountSessionCoordinator.getInstance(appCtx)
                    val active = sessions.initialize()
                    sessions.withActiveSession(active.profileId, active.generation) {
                        FocusCoordinator.getInstance(appCtx).restoreOrCompleteActiveSession()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
