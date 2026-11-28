package cn.edu.haut.campus.core.automation.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.edu.haut.campus.core.automation.PlannedTriggerInstance
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 内置时间触发的账号级 inexact 唤醒；只调度最近一个批量点， wake 后统一补处理并重排。 */
object AutomationAlarmScheduler {
    private const val ACTION_FIRE = "cn.edu.haut.campus.action.AUTOMATION_BATCH_WAKE"

    fun synchronize(
        context: Context,
        accountScope: AccountBackgroundScope,
        next: PlannedTriggerInstance?
    ) {
        cancel(context, accountScope)
        if (next == null) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(context, accountScope, noCreate = false) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.fireAt.toEpochMilli(),
                pending
            )
        }
    }

    fun cancel(context: Context, accountScope: AccountBackgroundScope) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        pendingIntent(context, accountScope, noCreate = true)?.let { pending ->
            runCatching {
                alarmManager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun pendingIntent(
        context: Context,
        accountScope: AccountBackgroundScope,
        noCreate: Boolean
    ): PendingIntent? {
        val intent = Intent(context, AutomationWakeReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("automation://batch-wake/" + accountScope.profileId + "/" + accountScope.epoch)
        }
        accountScope.attach(intent)
        val flags = if (noCreate) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode(accountScope), intent, flags)
    }

    private fun requestCode(accountScope: AccountBackgroundScope): Int =
        (("automation:" + accountScope.profileId + ":" + accountScope.epoch).hashCode() and 0x7FFFFFFF)
}

class AutomationWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accountScope = AccountBackgroundScope.read(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val sessions = AccountSessionCoordinator.getInstance(context.applicationContext)
                sessions.initialize()
                sessions.withActiveSession(accountScope.profileId, accountScope.epoch) { active ->
                    val runtime = AutomationRuntime.current(active) ?: AutomationRuntime.bind(context.applicationContext, active)
                    runtime.processWake("alarm")
                }
            } catch (error: Throwable) {
                if (error is CancellationException || error is Error) throw error
                // 闹钟补处理失败由 30 分钟 WorkManager backstop 继续；不在这里重试常驻循环。
            } finally {
                pending.finish()
            }
        }
    }
}

class AutomationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val sessions = AccountSessionCoordinator.getInstance(context.applicationContext)
                val active = runCatching { sessions.initialize() }.getOrNull()
                if (active != null) {
                    val runtime = AutomationRuntime.current(active) ?: AutomationRuntime.bind(context.applicationContext, active)
                    runtime.processWake("restore")
                }
            } catch (error: Throwable) {
                if (error is CancellationException || error is Error) throw error
            } finally {
                pending.finish()
            }
        }
    }
}

class AutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getString(AccountBackgroundScope.EXTRA_PROFILE_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val epoch = inputData.getLong(AccountBackgroundScope.EXTRA_EPOCH, -1L).takeIf { it > 0L }
            ?: return Result.success()
        return try {
            val sessions = AccountSessionCoordinator.getInstance(applicationContext)
            sessions.initialize()
            sessions.withActiveSession(profileId, epoch) { active ->
                val runtime = AutomationRuntime.current(active) ?: AutomationRuntime.bind(applicationContext, active)
                runtime.processWake(inputData.getString(KEY_REASON) ?: "workmanager")
            }
            Result.success()
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_REASON = "key_automation_reason"
        private const val PERIODIC_PREFIX = "dayinschool.automation.periodic.v1"
        private const val IMMEDIATE_PREFIX = "dayinschool.automation.immediate.v1"

        private fun periodicName(scope: AccountBackgroundScope) = PERIODIC_PREFIX + ":" + scope.profileId + ":" + scope.epoch
        private fun immediateName(scope: AccountBackgroundScope) = IMMEDIATE_PREFIX + ":" + scope.profileId + ":" + scope.epoch
    }
}

/** WorkManager 编排入口；周期任务只做批量补处理，不是保活机制。 */
object AutomationRuntimeCoordinator {
    fun schedulePeriodic(context: Context, accountScope: AccountBackgroundScope) {
        val request = PeriodicWorkRequestBuilder<AutomationWorker>(30L, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    AccountBackgroundScope.EXTRA_PROFILE_ID to accountScope.profileId,
                    AccountBackgroundScope.EXTRA_EPOCH to accountScope.epoch,
                    "key_automation_reason" to "periodic-30m"
                )
            )
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                periodicName(accountScope),
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    fun enqueueImmediate(context: Context, accountScope: AccountBackgroundScope, reason: String) {
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(
                workDataOf(
                    AccountBackgroundScope.EXTRA_PROFILE_ID to accountScope.profileId,
                    AccountBackgroundScope.EXTRA_EPOCH to accountScope.epoch,
                    "key_automation_reason" to reason.take(48)
                )
            )
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                immediateName(accountScope),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun cancelAll(context: Context, accountScope: AccountBackgroundScope) {
        val manager = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        runCatching { manager.cancelUniqueWork(periodicName(accountScope)) }
        runCatching { manager.cancelUniqueWork(immediateName(accountScope)) }
        AutomationAlarmScheduler.cancel(context.applicationContext, accountScope)
    }

    private fun periodicName(scope: AccountBackgroundScope) = "dayinschool.automation.periodic.v1:" + scope.profileId + ":" + scope.epoch
    private fun immediateName(scope: AccountBackgroundScope) = "dayinschool.automation.immediate.v1:" + scope.profileId + ":" + scope.epoch
}
