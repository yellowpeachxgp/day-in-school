package cn.edu.haut.campus.core.automation.android

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.edu.haut.campus.core.automation.LocalNotificationActionPort
import cn.edu.haut.campus.core.model.SchoolAccountScope

/** 自动化本地通知实现；通知 ID 只由 actionExecutionKey 派生，重试是对同一条通知的幂等更新。 */
class AutomationNotificationPoster(
    private val context: Context
) : LocalNotificationActionPort {
    // areNotificationsEnabled/channelEnabled 是先决门禁；lint 无法识别 NotificationManagerCompat 的运行时检查。
    @SuppressLint("MissingPermission")
    override suspend fun post(
        scope: SchoolAccountScope,
        actionExecutionKey: String,
        title: String,
        body: String,
        channel: String?,
        deeplink: String?
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureDefaultChannel()
        val channelId = channel?.takeIf { it.isNotBlank() }?.let { customChannelId(it) } ?: DEFAULT_CHANNEL_ID
        if (channelId != DEFAULT_CHANNEL_ID) ensureCustomChannel(channelId)
        if (!channelEnabled(channelId)) return false

        val launch = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS_NAME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_AUTOMATION_DEEPLINK, deeplink)
            putExtra(EXTRA_AUTOMATION_PROFILE_ID, scope.profileId.value)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            stableNotificationId(actionExecutionKey),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return runCatching {
            manager.notify(stableNotificationId(actionExecutionKey), notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureDefaultChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DEFAULT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(DEFAULT_CHANNEL_ID, "自动化提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "本地自动化规则创建的提醒"
                }
            )
        }
    }

    private fun ensureCustomChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "自定义自动化提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun channelEnabled(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = runCatching { manager.getNotificationChannel(channelId) }.getOrNull() ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun stableNotificationId(actionExecutionKey: String): Int =
        (actionExecutionKey.hashCode() and 0x7FFFFFFF).coerceAtLeast(1)

    private fun customChannelId(raw: String): String =
        "automation_custom_" + raw.lowercase().map { if (it.isLetterOrDigit() || it in "_-") it else '_' }.joinToString("").take(48)

    companion object {
        const val DEFAULT_CHANNEL_ID: String = "automation_rules"
        const val EXTRA_AUTOMATION_DEEPLINK: String = "extra_automation_deeplink"
        const val EXTRA_AUTOMATION_PROFILE_ID: String = "extra_automation_profile_id"
        private const val MAIN_ACTIVITY_CLASS_NAME: String = "cn.edu.haut.campus.MainActivity"
    }
}
