package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.OnlineUtilityRoomSnapshot
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.campusservices.UtilityAccountSecureStore
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.UtilitySamplingPolicy
import cn.edu.haut.campus.core.campusservices.WanxiaoUtilityClient
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

/** 水电账号、在线房间采样、通知配置与手工表计/读数服务；加密账号仓库与通知发送路径保持不变。 */
internal class UtilityService(
    private val environment: CampusRepositoryEnvironment,
    private val appContext: Context,
    private val utilityAccountStore: UtilityAccountSecureStore,
    private val runtimePort: CampusServicesRuntimePort
) {
    private val database = environment.database
    private val preferences = environment.preferences
    private val utilityAndFocusDao = environment.database.utilityAndFocusDao()
    private val wanxiaoUtilityClient = WanxiaoUtilityClient()
    private val profileId: String get() = environment.accountProfile.id
    private val supportsHautServices: Boolean get() = environment.supportsHautServices
    private val activeSessionGeneration: Long? get() = runtimePort.activeSessionGeneration

    val utilityDailySamplesFlow: Flow<List<UtilityDailySample>> =
        utilityAndFocusDao.observeDailySamples().map { list -> list.map { it.toDomain() } }

    val utilityOnlineRoomsFlow: Flow<List<UtilityOnlineRoomDomain>> =
        utilityAndFocusDao.observeOnlineRooms().map { list -> list.map { it.toDomain() } }

    val utilityMetersFlow: Flow<List<UtilityMeterDomain>> =
        utilityAndFocusDao.observeMeters().map { list -> list.map { it.toDomain() } }

    val utilityReadingsFlow: Flow<List<UtilityReadingDomain>> =
        utilityAndFocusDao.observeReadings().map { list -> list.map { it.toDomain() } }

    val activeFocusSessionFlow: Flow<FocusSessionDomain?> =
        utilityAndFocusDao.observeActiveRunningFocusSession().map { it?.toDomain() }

    val completedFocusSessionsFlow: Flow<List<FocusSessionDomain>> =
        utilityAndFocusDao.observeCompletedFocusSessions().map { list -> list.map { it.toDomain() } }

    fun utilityAndFocusDao(): UtilityAndFocusDao = utilityAndFocusDao

    fun utilityAccountSecureStore(): UtilityAccountSecureStore = utilityAccountStore

    suspend fun setAllowUnverifiedUtilityTransport(allowed: Boolean) {
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] = allowed
        }
    }


    fun readUtilityAccountResult(): UtilityAccountReadResult =
        utilityAccountStore.readAccountResult()

    /**
     * 在线查询本人已绑定房间水电并同步更新缓存与每日电表采样。
     */
    suspend fun saveAndRefreshOnlineUtilities(
        rawAccount: String? = null,
        allowUnverified: Boolean = false
    ): Result<List<OnlineUtilityRoomSnapshot>> = runCatching {
        val accountToUse = if (!rawAccount.isNullOrBlank()) {
            val clean = UtilityAccountSecureStore.validateAccountFormat(rawAccount)
            var switched = false
            utilityAccountStore.saveOrSwitchAccount(clean) {
                switched = true
            }
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] = true
            }
            if (switched) {
                database.withTransaction {
                    utilityAndFocusDao.clearAllOnlineRooms()
                    utilityAndFocusDao.clearAllDailySamples()
                }
            }
            clean
        } else {
            when (val saved = utilityAccountStore.readAccountResult()) {
                is UtilityAccountReadResult.Active -> saved.plainAccount
                is UtilityAccountReadResult.InvalidatedAndReset ->
                    throw IllegalStateException("安全密钥已重置，请重新输入本人校园卡账号")
                is UtilityAccountReadResult.Empty ->
                    throw IllegalArgumentException("请先填写本人校园卡账号（通常为学号）")
            }
        }

        val startedAtMs = System.currentTimeMillis()
        val effectiveAllowOnline = allowUnverified || !rawAccount.isNullOrBlank() ||
            (preferences.data.first()[CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false)
        val snapshots = wanxiaoUtilityClient.queryOwnBoundRooms(
            account = accountToUse,
            allowUnverifiedTransport = effectiveAllowOnline
        )
        val finishedAtMs = System.currentTimeMillis()

        database.withTransaction {
            val existingRooms = utilityAndFocusDao.getOnlineRoomsSnapshot().associateBy { it.roomId }
            val updatedEntities = snapshots.map { snap ->
                val prev = existingRooms[snap.roomId]
                if (snap.failed && prev != null) {
                    prev.copy(failed = true)
                } else {
                    UtilityOnlineRoomEntity(
                        roomId = snap.roomId,
                        roomName = snap.roomName,
                        campusCode = prev?.campusCode,
                        devicesJson = encodeOnlineDevicesJson(snap.devices),
                        updatedAtEpochMs = if (snap.fetchedAtEpochMs > 0L) snap.fetchedAtEpochMs else finishedAtMs,
                        failed = snap.failed
                    )
                }
            }
            utilityAndFocusDao.clearAllOnlineRooms()
            utilityAndFocusDao.upsertOnlineRooms(updatedEntities)

            val existingSamples = utilityAndFocusDao.getDailySamplesSnapshot().map { it.toDomain() }
            val mergedSamples = UtilitySamplingPolicy.mergeDailySamples(
                existingSamples = existingSamples,
                rooms = snapshots,
                startedAtEpochMs = startedAtMs,
                finishedAtEpochMs = finishedAtMs
            )
            utilityAndFocusDao.clearAllDailySamples()
            utilityAndFocusDao.upsertDailySamples(mergedSamples.map { it.toEntity() })
        }

        if (snapshots.any { !it.failed }) {
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.LAST_UTILITY_SAMPLE_MS] = finishedAtMs
            }
        }
        runCatching { notifyDailyUtilitySummaryIfEligible(finishedAtMs) }
        snapshots
    }

    /**
     * 供后台周期 Worker 或前台恢复时调用的按需水电采样：
     * 仅在已开启水电功能、已开启在线传输许可、存在有效加密账号且满足采样间隔时触发。
     */
    suspend fun performPeriodicUtilitySampleIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = preferences.data.first()
        val utilitiesEnabled = supportsHautServices && (prefs[CampusRepositoryPrefKeys.FF_UTILITIES_ENABLED] ?: true)
        val allowUnverified = prefs[CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT] ?: false
        if (!utilitiesEnabled || !allowUnverified) {
            runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
            return false
        }

        val lastSampleMs = prefs[CampusRepositoryPrefKeys.LAST_UTILITY_SAMPLE_MS] ?: 0L
        if (!UtilitySamplingPolicy.shouldSample(lastSampleMs, nowEpochMs)) {
            runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
            return false
        }

        val activeAccount = (utilityAccountStore.readAccountResult() as? UtilityAccountReadResult.Active)
            ?.plainAccount ?: run {
                runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
                return false
            }

        val sampled = saveAndRefreshOnlineUtilities(
            rawAccount = activeAccount,
            allowUnverified = true
        ).isSuccess
        runCatching { notifyDailyUtilitySummaryIfEligible(nowEpochMs) }
        return sampled
    }

    suspend fun updateUtilityNotificationConfig(
        enabled: Boolean? = null,
        dailyHour: Int? = null,
        lowBalanceThreshold: Float? = null
    ) {
        preferences.edit { prefs ->
            enabled?.let { prefs[CampusRepositoryPrefKeys.UTILITY_DAILY_NOTIFY_ENABLED] = it }
            dailyHour?.let { prefs[CampusRepositoryPrefKeys.UTILITY_DAILY_NOTIFY_HOUR] = it.coerceIn(8, 23) }
            lowBalanceThreshold?.let { prefs[CampusRepositoryPrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] = it.coerceIn(5f, 100f) }
        }
    }

    suspend fun notifyDailyUtilitySummaryIfEligible(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = preferences.data.first()
        val utilitiesEnabled = supportsHautServices && (prefs[CampusRepositoryPrefKeys.FF_UTILITIES_ENABLED] ?: true)
        if (!utilitiesEnabled) return false

        val notifyEnabled = prefs[CampusRepositoryPrefKeys.UTILITY_DAILY_NOTIFY_ENABLED] ?: true
        if (!notifyEnabled) return false

        val zdt = Instant.ofEpochMilli(nowEpochMs).atZone(HAUT_ZONE_ID)
        val todayDateText = zdt.toLocalDate().toString()
        val currentHour = zdt.hour
        val targetHour = (prefs[CampusRepositoryPrefKeys.UTILITY_DAILY_NOTIFY_HOUR] ?: 20).coerceIn(8, 23)
        val lowThreshold = (prefs[CampusRepositoryPrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] ?: 15f).toDouble()
        val lastNotifiedDate = prefs[CampusRepositoryPrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE]

        val payload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = utilityOnlineRoomsFlow.first(),
            dailySamples = utilityDailySamplesFlow.first(),
            manualMeters = utilityMetersFlow.first(),
            manualReadings = utilityReadingsFlow.first(),
            todayDateText = todayDateText,
            lowThreshold = lowThreshold
        ) ?: return false

        val shouldSend = DormElectricityNotificationSubsystem.shouldTriggerAutoNotify(
            enabled = notifyEnabled,
            lastNotifiedDate = lastNotifiedDate,
            todayDateText = todayDateText,
            currentHourInShanghai = currentHour,
            targetHour = targetHour,
            isLowBalance = payload.isLowBalance
        )
        if (!shouldSend) return false

        val posted = DormElectricityNotificationSubsystem.postNotification(
            context = appContext,
            notificationId = DormElectricityNotificationSubsystem.NOTIFICATION_ID_DAILY,
            title = payload.title,
            body = payload.body,
            profileId = profileId,
            generation = activeSessionGeneration
        )
        if (posted) {
            preferences.edit { editPrefs ->
                editPrefs[CampusRepositoryPrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE] = todayDateText
            }
        }
        return posted
    }

    suspend fun sendTestUtilityNotification(
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = preferences.data.first()
        val todayDateText = Instant.ofEpochMilli(nowEpochMs).atZone(HAUT_ZONE_ID).toLocalDate().toString()
        val lowThreshold = (prefs[CampusRepositoryPrefKeys.UTILITY_LOW_BALANCE_THRESHOLD] ?: 15f).toDouble()
        val payload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = utilityOnlineRoomsFlow.first(),
            dailySamples = utilityDailySamplesFlow.first(),
            manualMeters = utilityMetersFlow.first(),
            manualReadings = utilityReadingsFlow.first(),
            todayDateText = todayDateText,
            lowThreshold = lowThreshold
        ) ?: DormElectricityNotificationPayload(
            title = "今日宿舍用电 · 通知预览",
            body = "今日用电 3.20度 · 剩余电量 28.50度",
            isLowBalance = false,
            primaryRoomName = "宿舍"
        )
        return DormElectricityNotificationSubsystem.postNotification(
            context = appContext,
            notificationId = DormElectricityNotificationSubsystem.NOTIFICATION_ID_TEST,
            title = payload.title,
            body = payload.body,
            profileId = profileId,
            generation = activeSessionGeneration
        )
    }

    suspend fun updateOnlineRoomCampus(roomId: String, campusCode: String?) {
        utilityAndFocusDao.updateOnlineRoomCampus(roomId, campusCode)
    }

    suspend fun clearUtilityAccountAndOnlineCache() {
        runCatching { utilityAccountStore.wipeAll() }
        database.withTransaction {
            utilityAndFocusDao.clearAllOnlineRooms()
            utilityAndFocusDao.clearAllDailySamples()
        }
        preferences.edit { prefs ->
            prefs.remove(CampusRepositoryPrefKeys.LAST_UTILITY_SAMPLE_MS)
            prefs.remove(CampusRepositoryPrefKeys.LAST_UTILITY_DAILY_NOTIFY_DATE)
        }
    }

    suspend fun saveUtilityMeter(
        name: String,
        campusName: String,
        roomName: String,
        unit: String,
        lowThreshold: Double
    ) {
        val cleanName = name.trim()
        val cleanRoom = roomName.trim()
        val cleanUnit = unit.trim()
        require(cleanName.isNotEmpty() && cleanRoom.isNotEmpty() && cleanUnit.isNotEmpty()) {
            "请完整填写表计名称、房间号与单位"
        }
        require(lowThreshold.isFinite() && lowThreshold >= 0.0) {
            "低量提醒阈值不能为负数"
        }
        val entity = UtilityMeterEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName.take(40),
            campusName = campusName.trim().ifBlank { "莲花街校区" },
            roomName = cleanRoom.take(40),
            unit = cleanUnit.take(12),
            lowThreshold = lowThreshold,
            createdAtEpochMs = System.currentTimeMillis()
        )
        utilityAndFocusDao.upsertMeter(entity)
    }

    suspend fun deleteUtilityMeter(meterId: String) {
        database.withTransaction {
            utilityAndFocusDao.deleteReadingsByMeterId(meterId)
            utilityAndFocusDao.deleteMeterById(meterId)
        }
    }

    suspend fun addUtilityReading(
        meterId: String,
        value: Double,
        topUpValue: Double = 0.0,
        note: String = ""
    ) {
        require(value.isFinite() && value >= 0.0) { "当前读数不能为负数" }
        require(topUpValue.isFinite() && topUpValue >= 0.0) { "充值/购入量不能为负数" }
        val entity = UtilityReadingEntity(
            id = UUID.randomUUID().toString(),
            meterId = meterId,
            value = value,
            topUpValue = topUpValue,
            recordedAtEpochMs = System.currentTimeMillis(),
            note = note.trim().take(80)
        )
        utilityAndFocusDao.upsertReading(entity)
        runCatching { notifyDailyUtilitySummaryIfEligible(entity.recordedAtEpochMs) }
    }

    suspend fun deleteUtilityReading(readingId: String) {
        utilityAndFocusDao.deleteReadingById(readingId)
    }

}
