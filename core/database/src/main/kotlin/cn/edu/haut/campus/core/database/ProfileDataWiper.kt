package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * W0-05 个人数据生命周期编排器。
 * 启动 null-literal 清洗从 CampusRepository 兼容门面逐字迁入；后续全量擦除继续归入本类。
 */
class ProfileDataWiper internal constructor(
    private val appContext: Context,
    private val database: HautDatabase,
    private val preferences: DataStore<Preferences>,
    private val dao: CampusDao,
    private val reminderDao: ReminderDao,
    private val utilityAndFocusDao: UtilityAndFocusDao,
    private val cloudAndParcelDao: CloudAndParcelDao,
    private val academicCredentialVault: AcademicCredentialVault,
    private val academicCredentialMutex: Mutex,
    private val cloudIdentityMutex: Mutex,
    private val cloudTokenMutex: Mutex,
    private val bookingPlanStateMutex: Mutex,
    private val cloudIdentityRepository: CloudIdentityRepository,
    private val syncOrchestrator: SyncOrchestrator
) {
    private val beforeWipeHooks = ConcurrentHashMap<String, suspend () -> Unit>()

    var onBeforeWipePersonalDataHook: (suspend () -> Unit)?
        get() = beforeWipeHooks["reminder_coordinator_hook"]
        set(value) {
            if (value == null) {
                beforeWipeHooks.remove("reminder_coordinator_hook")
            } else {
                beforeWipeHooks["reminder_coordinator_hook"] = value
            }
        }

    fun registerBeforeWipeHook(key: String, hook: suspend () -> Unit) {
        beforeWipeHooks[key] = hook
    }

    internal suspend fun sanitizePersistedNullLiteralsForFacade(
        sanitizeBookingPlanWriteHookForTest: (suspend (String) -> Unit)? = null
    ) = sanitizePersistedNullLiterals(sanitizeBookingPlanWriteHookForTest)

    private suspend fun sanitizePersistedNullLiterals(sanitizeBookingPlanWriteHookForTest: (suspend (String) -> Unit)? = null) {
        val parcels = cloudAndParcelDao.getParcelItemsSnapshot()
        for (p in parcels) {
            val cleanPickup = p.pickupCode.cleanNullableLiteral()
            val cleanStation = p.stationName.cleanNullableLiteral() ?: "莲花街北苑菜鸟驿站"
            val cleanCarrier = p.carrierName.cleanNullLiteral("校园快递")
            val cleanTitle = p.itemTitle.cleanNullableLiteral()
            val nextStatus = if (p.pickupCode != null && cleanPickup == null && p.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName) {
                ParcelLifecycleStatus.IN_TRANSIT.wireName
            } else {
                p.status.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName)
            }
            val cleanEstimated = p.estimatedDeliveryText.cleanNullLiteral(
                when (nextStatus) {
                    ParcelLifecycleStatus.PICKED_UP.wireName -> "本人已取件签收"
                    ParcelLifecycleStatus.READY_FOR_PICKUP.wireName -> "已到达驿站/快递柜，请凭取件码取件"
                    else -> "运输中，预计 1-2 日送达校园驿站"
                }
            )
            if (p.pickupCode != cleanPickup ||
                p.stationName != cleanStation ||
                p.carrierName != cleanCarrier ||
                p.itemTitle != cleanTitle ||
                p.status != nextStatus ||
                p.estimatedDeliveryText != cleanEstimated
            ) {
                cloudAndParcelDao.upsertParcelItem(
                    p.copy(
                        carrierName = cleanCarrier,
                        status = nextStatus,
                        pickupCode = cleanPickup,
                        stationName = cleanStation,
                        itemTitle = cleanTitle,
                        estimatedDeliveryText = cleanEstimated
                    )
                )
            }
        }

        val plans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
        for (plan in plans) {
            val cleanArea = plan.preferredAreaName.cleanNullLiteral("五层中区")
            val cleanSeat = plan.preferredSeatLabel.cleanNullLiteral("001")
            val cleanExtId = plan.externalBookingId.cleanNullableLiteral()
            val cleanEvidence = plan.evidenceSha256.cleanNullableLiteral()
            val cleanDiag = plan.lastDiagnosticMessage.cleanNullLiteral()
            if (plan.preferredAreaName != cleanArea || plan.preferredSeatLabel != cleanSeat ||
                plan.externalBookingId != cleanExtId || plan.evidenceSha256 != cleanEvidence ||
                plan.lastDiagnosticMessage != cleanDiag
            ) {
                sanitizeBookingPlanWriteHookForTest?.invoke("before_write")
                cloudAndParcelDao.updateLibraryBookingPlanNullLiteralFieldsIfVersionUnchanged(
                    id = plan.id, expectedStateVersion = plan.stateVersion, expectedGeneration = plan.generation,
                    expectedUpdatedAtEpochMillis = plan.updatedAtEpochMillis,
                    preferredAreaName = cleanArea, preferredSeatLabel = cleanSeat, externalBookingId = cleanExtId,
                    evidenceSha256 = cleanEvidence, lastDiagnosticMessage = cleanDiag
                )
                sanitizeBookingPlanWriteHookForTest?.invoke("after_write")
            }
        }

        // Purge any legacy sample plugins or unapproved sample moments records
        runCatching {
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.hello-campus")
            cloudAndParcelDao.deleteInstalledExtensionPlugin("org.example.parcel-station")
        }
        val legacyMomentsSampleIds = setOf(
            "mp_found_card_01",
            "mp_lost_airpods_02",
            "mp_found_keys_03",
            "mp_lost_cup_04",
            "mb_math_01",
            "mb_ds_02",
            "mb_circuit_03",
            "mb_food_04",
            "tag_default_01"
        )
        preferences.edit { prefs ->
            val rawMoments = prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
            if (!rawMoments.isNullOrBlank()) {
                val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                if (parsed != null) {
                    val cleanedSnap = parsed.copy(
                        posts = parsed.posts.filterNot { it.id in legacyMomentsSampleIds },
                        bookListings = parsed.bookListings.filterNot { it.id in legacyMomentsSampleIds },
                        antiLostTags = parsed.antiLostTags.filterNot { it.id in legacyMomentsSampleIds || it.tagCode == "HAUT-TAG-7E2A9B" }
                    )
                    prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(cleanedSnap)
                }
            }
        }
    }

    suspend fun wipeAllPersonalData() {
        academicCredentialMutex.withLock {
            val prefs = preferences.data.first()
            prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]?.let { accountId ->
                withContext(Dispatchers.IO) { academicCredentialVault.delete(syncOrchestrator.academicCredentialScopeForFacade(accountId)) }
            }
            preferences.edit {
                it.remove(CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID)
                it.remove(CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED)
                it.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                it.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
            }
        }
        for (hook in beforeWipeHooks.values) {
            runCatching { hook.invoke() }
        }
        runCatching {
            cloudAndParcelDao.getLibraryBookingPlansSnapshot().forEach { plan ->
                LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
            }
        }
        runCatching {
            java.io.File(appContext.filesDir, "cloud_auth_provenance").deleteRecursively()
        }
        // 全量清除同样推进世代并留存 tombstone，阻断清除前发出的在途响应写回。
        // 身份变更必须先过提交屏障（全局锁序 booking→identity）：
        // 世代推进先于 Room 事务执行（避免在事务内嵌套身份锁），
        // 整个擦除事务与同步的"复核→写入"临界区互斥——在途同步要么整体先落库、要么在复核处失败。
        bookingPlanStateMutex.withLock {
            cloudIdentityMutex.withLock {
                cloudIdentityRepository.bumpCloudIdentityGenerationLocked(
                    cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId,
                    markUnlinkTombstone = true
                )
            }
            database.withTransaction {
                dao.clearStudentProfile()
                dao.clearCourses()
                dao.clearRules()
                dao.clearOccurrenceOverrides()
                dao.clearGradeAttempts()
                dao.clearExamSchedules()
                dao.clearExamTermRepairAudits()
                dao.clearUnscheduledCourses()
                dao.clearImportAudits()
                dao.clearWidgetConfigs()
                // W0-04：作息方案与用户方案节次行是按 Profile 保存的个人排课数据；
                // 默认参考 period_times（schemeId=NULL）仍按既有 reference 语义保留。
                dao.clearPeriodTimeSchemes()
                dao.clearSchemePeriodTimes()
                reminderDao.clearAllTasks()
                reminderDao.clearAllEventPrefs()
                utilityAndFocusDao.clearAllDailySamples()
                utilityAndFocusDao.clearAllOnlineRooms()
                utilityAndFocusDao.clearAllReadings()
                utilityAndFocusDao.clearAllMeters()
                utilityAndFocusDao.clearAllFocusSessions()
                cloudAndParcelDao.clearCloudIdentityBinding()
                cloudAndParcelDao.clearLibraryBookingPlans()
                cloudAndParcelDao.clearParcelItems()
            }
        }
        cloudTokenMutex.withLock {
            cloudIdentityRepository.clearCachedCloudTokensLocked()
            preferences.edit { prefs ->
                prefs.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA)
                prefs.remove(CampusRepositoryPrefKeys.OFFICIAL_GPA_NOTE)
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                prefs.remove(CampusRepositoryPrefKeys.SAVED_SYNC_XNM)
                prefs.remove(CampusRepositoryPrefKeys.SAVED_SYNC_XQM)
                prefs.remove(CampusRepositoryPrefKeys.USER_MANUAL_TERM_SEASON)
                prefs.remove(CampusRepositoryPrefKeys.LAST_SEASONAL_ROLLOVER_TERM_ID)
                prefs.remove(CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS)
                prefs.remove(CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_MS)
                prefs.remove(CampusRepositoryPrefKeys.LAST_REMINDER_RECONCILE_SUMMARY)
                prefs.remove(CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS)
                prefs.remove(CampusRepositoryPrefKeys.LAST_UTILITY_SAMPLE_MS)
                prefs.remove(CampusRepositoryPrefKeys.ALLOW_UNVERIFIED_UTILITY_TRANSPORT)
                prefs.remove(CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN)
                prefs.remove(CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN)
                prefs.remove(CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS)
                prefs.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                prefs[CampusRepositoryPrefKeys.SEEDED] = true
                prefs[CampusRepositoryPrefKeys.CLEAN_BASE_V2] = true
                prefs[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED] = false
            }
        }
    }
}
