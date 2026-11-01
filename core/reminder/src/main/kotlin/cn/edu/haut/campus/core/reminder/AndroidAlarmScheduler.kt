package cn.edu.haut.campus.core.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain

sealed interface ScheduleOutcome {
    data class Scheduled(
        val mode: ReminderAccuracyMode,
        val schedulerState: ReminderSchedulerState = ReminderSchedulerState.SCHEDULED,
        val fallbackNote: String? = null
    ) : ScheduleOutcome

    data class Failed(
        val errorCode: String
    ) : ScheduleOutcome
}

interface AlarmScheduler {
    fun schedule(task: ReminderTaskDomain, preferExact: Boolean): ScheduleOutcome
    fun cancel(taskId: String, generation: Int)
}

class AndroidAlarmScheduler(
    private val context: Context,
    private val accountScope: AccountBackgroundScope? = null,
    private val isActive: () -> Boolean = { true }
) : AlarmScheduler {

    override fun schedule(
        task: ReminderTaskDomain,
        preferExact: Boolean
    ): ScheduleOutcome {
        if (!isActive()) return ScheduleOutcome.Failed("ACCOUNT_SESSION_EXPIRED")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return ScheduleOutcome.Failed("ALARM_MANAGER_NULL")

        val pendingIntent = buildFirePendingIntent(
            taskId = task.id,
            generation = task.generation,
            eventKey = task.eventKey,
            noCreate = false
        ) ?: return ScheduleOutcome.Failed("PENDING_INTENT_NULL")

        val canUseExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

        val triggerAtMillis = task.scheduledAtEpochMs

        if (preferExact && canUseExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                return ScheduleOutcome.Scheduled(
                    mode = ReminderAccuracyMode.EXACT,
                    schedulerState = ReminderSchedulerState.SCHEDULED
                )
            } catch (sec: SecurityException) {
                return scheduleInexactFallback(
                    alarmManager = alarmManager,
                    triggerAtMillis = triggerAtMillis,
                    pendingIntent = pendingIntent,
                    fallbackNote = "SECURITY_EXCEPTION_FALLBACK_INEXACT"
                )
            } catch (t: Throwable) {
                return ScheduleOutcome.Failed("EXACT_SCHEDULE_ERROR:${t.javaClass.simpleName}")
            }
        }

        return scheduleInexactFallback(
            alarmManager = alarmManager,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            fallbackNote = if (preferExact && !canUseExact) "EXACT_PERMISSION_DENIED_INEXACT" else null
        )
    }

    private fun scheduleInexactFallback(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        fallbackNote: String?
    ): ScheduleOutcome {
        return try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            ScheduleOutcome.Scheduled(
                mode = ReminderAccuracyMode.INEXACT,
                schedulerState = ReminderSchedulerState.SCHEDULED,
                fallbackNote = fallbackNote
            )
        } catch (t: Throwable) {
            ScheduleOutcome.Failed("INEXACT_SCHEDULE_ERROR:${t.javaClass.simpleName}")
        }
    }

    override fun cancel(taskId: String, generation: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        listOfNotNull(
            buildFirePendingIntent(
                taskId = taskId,
                generation = generation,
                eventKey = null,
                noCreate = true,
                attachAccountScope = true
            ),
            buildFirePendingIntent(
                taskId = taskId,
                generation = generation,
                eventKey = null,
                noCreate = true,
                attachAccountScope = false
            )
        ).forEach { existingPendingIntent ->
            runCatching {
                alarmManager.cancel(existingPendingIntent)
                existingPendingIntent.cancel()
            }
        }
    }

    private fun buildFirePendingIntent(
        taskId: String,
        generation: Int,
        eventKey: String?,
        noCreate: Boolean,
        attachAccountScope: Boolean = true
    ): PendingIntent? {
        val intent = Intent(context, ReminderFireReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            data = Uri.parse("reminder://fire/$taskId/$generation")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_GENERATION, generation)
            if (eventKey != null) {
                putExtra(EXTRA_EVENT_KEY, eventKey)
            }
        }
        if (attachAccountScope) accountScope?.attach(intent)
        val flags = if (noCreate) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        val requestCode = ("$taskId:$generation".hashCode() and 0x7FFFFFFF)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        const val ACTION_FIRE_REMINDER = "cn.edu.haut.campus.action.FIRE_REMINDER"
        const val EXTRA_TASK_ID = "extra_reminder_task_id"
        const val EXTRA_GENERATION = "extra_reminder_generation"
        const val EXTRA_EVENT_KEY = "extra_reminder_event_key"
    }
}
