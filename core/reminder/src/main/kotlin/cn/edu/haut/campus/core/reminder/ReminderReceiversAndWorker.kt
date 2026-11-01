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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

class ReminderFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val taskId = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_TASK_ID) ?: return
        val generation = intent.getIntExtra(AndroidAlarmScheduler.EXTRA_GENERATION, -1)
        if (generation < 0) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                val completed = withTimeoutOrNull(8_000L) {
                    ReminderCoordinator.getInstance(appContext).onAlarmFired(
                        taskId = taskId,
                        expectedGeneration = generation
                    )
                    true
                }
                if (completed == null) {
                    ReminderReconcileWorker.enqueueImmediate(
                        context = appContext,
                        reason = "FIRE_RECEIVER_TIMEOUT",
                        forceReschedule = true
                    )
                }
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
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val generation = intent.getIntExtra(EXTRA_GENERATION, -1).takeIf { it >= 0 }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                withTimeoutOrNull(8_000L) {
                    val coordinator = ReminderCoordinator.getInstance(appContext)
                    when (action) {
                        ACTION_SNOOZE_REMINDER -> {
                            val choiceCode = intent.getStringExtra(EXTRA_SNOOZE_CHOICE) ?: return@withTimeoutOrNull
                            val choice = SnoozeChoice.fromActionCode(choiceCode) ?: return@withTimeoutOrNull
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
                val shortReason = action.substringAfterLast('.')
                val completed = withTimeoutOrNull(7_000L) {
                    ReminderCoordinator.getInstance(appContext).reconcileNow(
                        reason = "SYSTEM_$shortReason",
                        forceRescheduleAlarms = true
                    )
                    true
                }
                if (completed == null) {
                    ReminderReconcileWorker.enqueueImmediate(
                        context = appContext,
                        reason = "SYSTEM_TIMEOUT_$shortReason",
                        forceReschedule = true
                    )
                }
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
        val reason = inputData.getString(KEY_REASON) ?: "PERIODIC_WORKER"
        val forceReschedule = inputData.getBoolean(KEY_FORCE_RESCHEDULE, false)
        return runCatching {
            ReminderCoordinator.getInstance(applicationContext).reconcileNow(
                reason = reason,
                forceRescheduleAlarms = forceReschedule
            )
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_WORK_NAME = "haut_reminder_periodic_reconcile"
        private const val UNIQUE_IMMEDIATE_WORK_NAME = "haut_reminder_immediate_reconcile"
        private const val KEY_REASON = "key_reconcile_reason"
        private const val KEY_FORCE_RESCHEDULE = "key_force_reschedule"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderReconcileWorker>(
                12, TimeUnit.HOURS
            )
                .setInputData(
                    workDataOf(
                        KEY_REASON to "PERIODIC_12H",
                        KEY_FORCE_RESCHEDULE to false
                    )
                )
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }

        fun enqueueImmediate(
            context: Context,
            reason: String,
            forceReschedule: Boolean = false
        ) {
            val request = OneTimeWorkRequestBuilder<ReminderReconcileWorker>()
                .setInputData(
                    workDataOf(
                        KEY_REASON to reason,
                        KEY_FORCE_RESCHEDULE to forceReschedule
                    )
                )
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }
}
