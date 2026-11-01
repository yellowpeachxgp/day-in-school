package cn.edu.haut.campus.core.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.edu.haut.campus.core.model.SnoozeChoice
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class ReminderFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val accountScope = AccountBackgroundScope.read(intent) ?: return
        val taskId = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_TASK_ID) ?: return
        val generation = intent.getIntExtra(AndroidAlarmScheduler.EXTRA_GENERATION, -1)
        if (generation < 0) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                val sessions = AccountSessionCoordinator.getInstance(appContext)
                sessions.initialize()
                val completed = withTimeoutOrNull(8_000L) {
                    sessions.withActiveSession(accountScope.profileId, accountScope.epoch) {
                        ReminderCoordinator.getInstance(appContext).onAlarmFired(
                            taskId = taskId,
                            expectedGeneration = generation
                        )
                        true
                    }
                }
                if (completed == null && accountScope.isActive(appContext)) {
                    ReminderReconcileWorker.enqueueImmediate(
                        context = appContext,
                        accountScope = accountScope,
                        reason = "FIRE_RECEIVER_TIMEOUT",
                        forceReschedule = true
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class SnoozeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val accountScope = AccountBackgroundScope.read(intent) ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val generation = intent.getIntExtra(EXTRA_GENERATION, -1).takeIf { it >= 0 }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                val sessions = AccountSessionCoordinator.getInstance(appContext)
                sessions.initialize()
                withTimeoutOrNull(8_000L) {
                    sessions.withActiveSession(accountScope.profileId, accountScope.epoch) {
                        val coordinator = ReminderCoordinator.getInstance(appContext)
                        when (action) {
                            ACTION_SNOOZE_REMINDER -> {
                                val choiceCode = intent.getStringExtra(EXTRA_SNOOZE_CHOICE)
                                    ?: return@withActiveSession
                                val choice = SnoozeChoice.fromActionCode(choiceCode)
                                    ?: return@withActiveSession
                                coordinator.snoozeTask(
                                    taskId = taskId,
                                    expectedGeneration = generation,
                                    choice = choice
                                )
                            }
                            ACTION_DISMISS_REMINDER -> {
                                coordinator.dismissTask(
                                    taskId = taskId,
                                    expectedGeneration = generation
                                )
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE_REMINDER = "cn.edu.haut.campus.action.SNOOZE_REMINDER"
        const val ACTION_DISMISS_REMINDER = "cn.edu.haut.campus.action.DISMISS_REMINDER"
        const val EXTRA_TASK_ID = "extra_snooze_task_id"
        const val EXTRA_GENERATION = "extra_snooze_generation"
        const val EXTRA_SNOOZE_CHOICE = "extra_snooze_choice"

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class ReminderSystemReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
        if (action !in supportedActions) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                val sessions = AccountSessionCoordinator.getInstance(appContext)
                val active = sessions.initialize()
                val accountScope = AccountBackgroundScope.from(active)
                val shortReason = action.substringAfterLast('.')
                val completed = withTimeoutOrNull(7_000L) {
                    sessions.withActiveSession(active.profileId, active.generation) {
                        ReminderCoordinator.getInstance(appContext).reconcileNow(
                            reason = "SYSTEM_$shortReason",
                            forceRescheduleAlarms = true
                        )
                        true
                    }
                }
                if (completed == null && accountScope.isActive(appContext)) {
                    ReminderReconcileWorker.enqueueImmediate(
                        context = appContext,
                        accountScope = accountScope,
                        reason = "SYSTEM_TIMEOUT_$shortReason",
                        forceReschedule = true
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class ReminderReconcileWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profile = inputData.getString(AccountBackgroundScope.EXTRA_PROFILE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val epoch = inputData.getLong(AccountBackgroundScope.EXTRA_EPOCH, -1L)
            .takeIf { it > 0L }
            ?: return Result.success()
        val sessions = AccountSessionCoordinator.getInstance(applicationContext)
        return try {
            sessions.initialize()
            sessions.withActiveSession(profile, epoch) {
                ReminderCoordinator.getInstance(applicationContext).reconcileNow(
                    reason = inputData.getString(KEY_REASON) ?: "PERIODIC_WORKER",
                    forceRescheduleAlarms = inputData.getBoolean(KEY_FORCE_RESCHEDULE, false)
                )
            }
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK_NAME_PREFIX = "haut_reminder_periodic_reconcile_v2"
        private const val UNIQUE_IMMEDIATE_WORK_NAME_PREFIX = "haut_reminder_immediate_reconcile_v2"
        private const val LEGACY_PERIODIC_WORK_NAME = "haut_reminder_periodic_reconcile"
        private const val LEGACY_IMMEDIATE_WORK_NAME = "haut_reminder_immediate_reconcile"
        private const val KEY_REASON = "key_reconcile_reason"
        private const val KEY_FORCE_RESCHEDULE = "key_force_reschedule"

        private fun periodicWorkName(accountScope: AccountBackgroundScope): String =
            "$UNIQUE_PERIODIC_WORK_NAME_PREFIX:${accountScope.profileId}:${accountScope.epoch}"

        private fun immediateWorkName(accountScope: AccountBackgroundScope): String =
            "$UNIQUE_IMMEDIATE_WORK_NAME_PREFIX:${accountScope.profileId}:${accountScope.epoch}"

        suspend fun cancelPeriodicAndImmediate(
            context: Context,
            accountScope: AccountBackgroundScope
        ) {
            val workManager = WorkManager.getInstance(context)
            listOf(
                periodicWorkName(accountScope),
                immediateWorkName(accountScope),
                LEGACY_PERIODIC_WORK_NAME,
                LEGACY_IMMEDIATE_WORK_NAME
            ).distinct().forEach { name ->
                runCatching { workManager.cancelUniqueWork(name) }
            }
        }

        fun enqueuePeriodic(context: Context, accountScope: AccountBackgroundScope) {
            val request = PeriodicWorkRequestBuilder<ReminderReconcileWorker>(
                12, TimeUnit.HOURS
            )
                .setInputData(
                    workDataOf(
                        AccountBackgroundScope.EXTRA_PROFILE_ID to accountScope.profileId,
                        AccountBackgroundScope.EXTRA_EPOCH to accountScope.epoch,
                        KEY_REASON to "PERIODIC_12H",
                        KEY_FORCE_RESCHEDULE to false
                    )
                )
                .build()

            runCatching {
                val workManager = WorkManager.getInstance(context)
                workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
                workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK_NAME)
                workManager.enqueueUniquePeriodicWork(
                    periodicWorkName(accountScope),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }

        fun enqueueImmediate(
            context: Context,
            accountScope: AccountBackgroundScope,
            reason: String,
            forceReschedule: Boolean = false
        ) {
            val request = OneTimeWorkRequestBuilder<ReminderReconcileWorker>()
                .setInputData(
                    workDataOf(
                        AccountBackgroundScope.EXTRA_PROFILE_ID to accountScope.profileId,
                        AccountBackgroundScope.EXTRA_EPOCH to accountScope.epoch,
                        KEY_REASON to reason,
                        KEY_FORCE_RESCHEDULE to forceReschedule
                    )
                )
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    immediateWorkName(accountScope),
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
