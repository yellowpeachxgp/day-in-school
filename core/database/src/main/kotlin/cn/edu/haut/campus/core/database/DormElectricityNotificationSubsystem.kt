package cn.edu.haut.campus.core.database

import android.Manifest
import android.annotation.SuppressLint
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
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.UtilityMeterCategory
import cn.edu.haut.campus.core.campusservices.WanxiaoProtocolParser
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain

data class DormElectricityNotificationPayload(
    val title: String,
    val body: String,
    val isLowBalance: Boolean,
    val primaryRoomName: String
)

object DormElectricityNotificationSubsystem {

    const val CHANNEL_ID = "dorm_electricity_daily_channel_v1"
    const val CHANNEL_NAME = "宿舍电费提醒"
    const val NOTIFICATION_ID_DAILY = 880201
    const val NOTIFICATION_ID_TEST = 880202

    private const val EXTRA_PROFILE_ID = "extra_account_profile_id"
    private const val EXTRA_EPOCH = "extra_account_epoch"
    private const val EXTRA_OVERLAY_ROUTE = "extra_overlay_route"
    private const val OVERLAY_ROUTE_ELECTRICITY = "electricity"

    private val NUMERIC_PREFIX_REGEX = Regex("^\\s*([+-]?\\d+(?:\\.\\d+)?)")
    private val PURE_NUMBER_REGEX = Regex("^\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*$")

    fun hasNotificationPermission(context: Context): Boolean {
        val appContext = context.applicationContext
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "每日宿舍用电量与剩余电费提醒"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun parseBalanceNumber(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == WanxiaoProtocolParser.MISSING_PLACEHOLDER) return null
        val match = NUMERIC_PREFIX_REGEX.find(trimmed) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun formatMetricWithDefaultUnit(raw: String, defaultUnit: String = "度"): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == WanxiaoProtocolParser.MISSING_PLACEHOLDER) {
            return WanxiaoProtocolParser.MISSING_PLACEHOLDER
        }
        if (PURE_NUMBER_REGEX.matches(trimmed)) {
            val num = trimmed.toDoubleOrNull()
            return if (num != null) "${"%.2f".format(num)}$defaultUnit" else "$trimmed$defaultUnit"
        }
        return trimmed
    }

    fun shouldTriggerAutoNotify(
        enabled: Boolean,
        lastNotifiedDate: String?,
        todayDateText: String,
        currentHourInShanghai: Int,
        targetHour: Int,
        isLowBalance: Boolean
    ): Boolean {
        if (!enabled) return false
        if (todayDateText.isBlank()) return false
        if (lastNotifiedDate == todayDateText) return false
        if (isLowBalance && currentHourInShanghai >= 8) return true
        return currentHourInShanghai >= targetHour.coerceIn(8, 23)
    }

    fun buildNotificationPayload(
        onlineRooms: List<UtilityOnlineRoomDomain>,
        dailySamples: List<UtilityDailySample>,
        manualMeters: List<UtilityMeterDomain>,
        manualReadings: List<UtilityReadingDomain>,
        todayDateText: String,
        lowThreshold: Double
    ): DormElectricityNotificationPayload? {
        data class DeviceSummary(
            val roomName: String,
            val deviceName: String,
            val todayUseText: String,
            val balanceText: String,
            val isLow: Boolean
        )

        val onlineSummaries = mutableListOf<DeviceSummary>()
        for (room in onlineRooms) {
            val devices = decodeOnlineDevicesJson(room.devicesJson)
                .filter { it.category == UtilityMeterCategory.ELECTRICITY }
            for (dev in devices) {
                val sampleToday = dailySamples.lastOrNull {
                    it.roomId == room.roomId &&
                        it.deviceName == dev.name.trim() &&
                        it.dateText == todayDateText
                }
                val todayUseText = formatMetricWithDefaultUnit(dev.todayUseRaw, "度")
                    .takeIf { it != WanxiaoProtocolParser.MISSING_PLACEHOLDER }
                    ?: sampleToday?.let { "${"%.2f".format(it.usedValue)}度" }
                    ?: "0.00度"
                val balanceText = formatMetricWithDefaultUnit(dev.balanceRaw, "度")
                val balanceVal = parseBalanceNumber(dev.balanceRaw)
                val isLow = balanceVal != null && balanceVal <= lowThreshold
                onlineSummaries += DeviceSummary(
                    roomName = room.roomName,
                    deviceName = dev.name.trim().ifBlank { "电表" },
                    todayUseText = todayUseText,
                    balanceText = balanceText,
                    isLow = isLow
                )
            }
        }

        if (onlineSummaries.isNotEmpty()) {
            val anyLow = onlineSummaries.any { it.isLow }
            val distinctRooms = onlineSummaries.map { it.roomName }.distinct()
            val primaryRoom = distinctRooms.first()
            val title = when {
                anyLow && distinctRooms.size == 1 -> "电费不足 · $primaryRoom"
                anyLow -> "宿舍电费不足提醒"
                distinctRooms.size == 1 -> "今日宿舍用电 · $primaryRoom"
                else -> "今日宿舍用电"
            }
            val body = if (onlineSummaries.size == 1) {
                val item = onlineSummaries.first()
                val suffix = if (item.isLow) "，请及时充电费" else ""
                "今日用电 ${item.todayUseText} · 剩余电量 ${item.balanceText}$suffix"
            } else {
                val segments = onlineSummaries.map { item ->
                    val prefix = if (distinctRooms.size > 1) "${item.roomName} ${item.deviceName}" else item.deviceName
                    "$prefix：今日 ${item.todayUseText}，剩余 ${item.balanceText}"
                }
                val suffix = if (anyLow) "。请及时充电费" else ""
                segments.joinToString("；") + suffix
            }
            return DormElectricityNotificationPayload(
                title = title,
                body = body,
                isLowBalance = anyLow,
                primaryRoomName = primaryRoom
            )
        }

        // 回退至手工水电台账
        for (meter in manualMeters) {
            val meterReadings = manualReadings
                .filter { it.meterId == meter.id }
                .sortedBy { it.recordedAtEpochMs }
            val latest = meterReadings.lastOrNull() ?: continue
            val prev = meterReadings.getOrNull(meterReadings.size - 2)
            val deltaUsed = if (prev != null) {
                (prev.value + latest.topUpValue - latest.value).coerceAtLeast(0.0)
            } else {
                null
            }
            val effectiveThreshold = if (meter.lowThreshold > 0.0) minOf(lowThreshold, meter.lowThreshold) else lowThreshold
            val isLow = latest.value <= effectiveThreshold
            val title = if (isLow) "电费不足 · ${meter.roomName}" else "宿舍电费 · ${meter.roomName}"
            val usedPart = if (deltaUsed != null) {
                "近期已用 ${"%.2f".format(deltaUsed)}${meter.unit} · "
            } else {
                ""
            }
            val suffix = if (isLow) "，请及时充电费" else ""
            val body = "${usedPart}剩余 ${"%.2f".format(latest.value)}${meter.unit}$suffix"
            return DormElectricityNotificationPayload(
                title = title,
                body = body,
                isLowBalance = isLow,
                primaryRoomName = meter.roomName
            )
        }

        return null
    }

    @SuppressLint("MissingPermission")
    fun postNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        profileId: String? = null,
        generation: Long? = null
    ): Boolean {
        val appContext = context.applicationContext
        ensureNotificationChannel(appContext)
        if (!hasNotificationPermission(appContext)) return false

        val activeSnap = AccountSessionCoordinator.getInstance(appContext).currentSnapshot()
        val resolvedProfileId = profileId ?: activeSnap?.profileId
        val resolvedEpoch = generation ?: activeSnap?.generation

        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OVERLAY_ROUTE, OVERLAY_ROUTE_ELECTRICITY)
            if (!resolvedProfileId.isNullOrBlank() && resolvedEpoch != null && resolvedEpoch > 0L) {
                putExtra(EXTRA_PROFILE_ID, resolvedProfileId)
                putExtra(EXTRA_EPOCH, resolvedEpoch)
                data = Uri.parse("dayinschool://electricity/open/$notificationId")
                    .buildUpon()
                    .appendQueryParameter("profile", resolvedProfileId)
                    .appendQueryParameter("epoch", resolvedEpoch.toString())
                    .build()
            } else {
                data = Uri.parse("dayinschool://electricity/open/$notificationId")
            }
        }

        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (contentIntent != null) {
                    setContentIntent(contentIntent)
                }
            }
            .build()

        return runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }
}
