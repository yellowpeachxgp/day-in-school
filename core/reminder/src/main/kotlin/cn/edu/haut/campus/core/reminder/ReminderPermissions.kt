package cn.edu.haut.campus.core.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class ReminderPermissionSnapshot(
    val notificationPermissionGranted: Boolean,
    val systemNotificationsEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val classChannelEnabled: Boolean,
    val examChannelEnabled: Boolean
) {
    val canPostNotifications: Boolean
        get() = notificationPermissionGranted && systemNotificationsEnabled
}

object ReminderPermissions {

    fun inspect(context: Context): ReminderPermissionSnapshot {
        val notificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notificationManagerCompat = NotificationManagerCompat.from(context)
        val systemNotificationsEnabled = notificationManagerCompat.areNotificationsEnabled()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val exactAlarmAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager?.canScheduleExactAlarms() ?: false }.getOrDefault(false)
        } else {
            true
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val classChannel = notificationManager?.getNotificationChannel(ReminderNotificationFactory.CHANNEL_CLASS_REMINDERS)
        val examChannel = notificationManager?.getNotificationChannel(ReminderNotificationFactory.CHANNEL_EXAM_REMINDERS)

        val classChannelEnabled = classChannel == null || classChannel.importance != NotificationManager.IMPORTANCE_NONE
        val examChannelEnabled = examChannel == null || examChannel.importance != NotificationManager.IMPORTANCE_NONE

        return ReminderPermissionSnapshot(
            notificationPermissionGranted = notificationPermissionGranted,
            systemNotificationsEnabled = systemNotificationsEnabled,
            exactAlarmAllowed = exactAlarmAllowed,
            classChannelEnabled = classChannelEnabled,
            examChannelEnabled = examChannelEnabled
        )
    }

    fun buildNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildExactAlarmSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            buildAppDetailsSettingsIntent(context)
        }
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
