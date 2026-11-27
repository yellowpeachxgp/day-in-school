package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.DualEngineReservationPlanner
import cn.edu.haut.campus.core.campusservices.HautLibrarySeatAuthClient
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 微桃 → 🍑 整数/小数自适应显示（1.0 显示为 "1"，0.5 显示为 "0.5"） */
private fun formatMicropeachAsPeach(micropeach: Long): String {
    val whole = micropeach / 10_000L
    val rem = micropeach % 10_000L
    return if (rem == 0L) whole.toString() else (micropeach / 10_000.0).toString()
}

/**
 * 云端预约计划报价漂移冲突（R3 §5 价格钉死诚实再确认）：
 * 客户端按创建时向用户展示的价格提交 expected_price_micropeach，云端调价后以 409 拒绝。
 * UI 必须如实向用户展示新旧价格并等待其重新发起创建——绝不自动按新价静默重试，
 * 否则会在用户不知情的情况下改签合同价格。
 */
class CloudPlanPriceDriftException(
    /** 创建时向用户展示/钉入请求的价格（微桃） */
    val expectedPriceMicropeach: Long,
    /** 云端错误 detail 中返回的现行价格（微桃），云端未提供时为 null */
    val currentPriceMicropeach: Long?,
    serverReason: String?,
    /** 预取报价短路时带回的完整新报价（quote_id/截止时间），409 路径为 null */
    val proposedQuote: cn.edu.haut.campus.core.campusservices.CloudReservationQuote? = null
) : IllegalStateException(
    buildString {
        append("云端抢座价格已调整")
        if (currentPriceMicropeach != null && currentPriceMicropeach != expectedPriceMicropeach) {
            append("：您创建时看到的价格为 ")
            append(formatMicropeachAsPeach(expectedPriceMicropeach))
            append(" 🍑，云端最新价格为 ")
            append(formatMicropeachAsPeach(currentPriceMicropeach))
            append(" 🍑")
        }
        serverReason?.takeIf { it.isNotBlank() }?.let { append("（").append(it).append("）") }
        append("。请核实最新价格后重新创建计划")
    }
)

/**
 * 图书馆预约计划创建、归属可见性、云端切换协调与显式学校退座。
 * 实现从 CampusRepository 兼容门面逐字迁入；bookingPlanStateMutex 仍为门面同一锁实例，
 * 身份复核、补偿恢复、测试钩子与后台云同步回连时序保持不变。
 */
class LibraryBookingPlanRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val appContext: Context,
    private val cloudIdentityRepository: CloudIdentityRepository,
    private val librarySeatRepository: LibrarySeatRepository,
    private val bookingPlanStateMutex: Mutex,
    private val runtimePort: LibraryBookingRuntimePort
) {
    private val database = environment.database
    private val cloudAndParcelDao = database.cloudAndParcelDao()

    private val preferencesFlow: Flow<AppPreferencesState>
        get() = runtimePort.preferencesFlow

    private val cloudHttpTransportForTest: CloudHttpTransportForTest?
        get() = runtimePort.cloudHttpTransportForTest()

    private val repoScope
        get() = runtimePort.repoScope

    private val profileId: String
        get() = environment.accountProfile.id

    private val schoolId: String
        get() = environment.accountProfile.schoolId

    private val isSessionActive: Boolean
        get() = runtimePort.isSessionActive

    private fun requireActiveSession() = runtimePort.requireActiveSession()

    private suspend fun syncWithCampusCloud() = runtimePort.syncWithCampusCloud()

    private fun readCachedLibrarySeatSession() =
        librarySeatRepository.readCachedLibrarySeatSession()

    private suspend fun ensureLibrarySeatSession(forceRefresh: Boolean = false) =
        librarySeatRepository.ensureLibrarySeatSession(forceRefresh)

    private suspend fun snapshotCloudIdentitySession() =
        cloudIdentityRepository.snapshotCloudIdentitySession()

    private suspend fun isCloudIdentitySnapshotCurrent(snapshot: CampusRepository.CloudIdentitySessionSnapshot) =
        cloudIdentityRepository.isCloudIdentitySnapshotCurrent(snapshot)

    /**
     * 仅测试用：云端计划"401→令牌刷新→重提交"窗口的确定性闩锁（生产恒为 null）。
     * 存储保留原 Volatile 语义，门面只做 get/set 委托。
     */
    @Volatile
    internal var cloudSubmitRetryBarrierHookForTest: (suspend () -> Unit)? = null

    val allLibraryBookingPlansForAuditFlow: Flow<List<LibraryBookingPlanEntity>> =
        cloudAndParcelDao.observeLibraryBookingPlans()

    /** 无法证明唯一归属的升级遗留记录（待认领/本地审计专用，普通登录账号页面与同步不可见） */
    val unattributedLegacyBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        cloudAndParcelDao.observeLibraryBookingPlans().map { plans ->
            plans.filter(cloudIdentityRepository::isUnattributedLegacyBookingPlan)
        }


    val libraryBookingPlansFlow: Flow<List<LibraryBookingPlanEntity>> =
        combine(
            cloudAndParcelDao.observeLibraryBookingPlans(),
            cloudAndParcelDao.observeCloudIdentityBinding(),
            preferencesFlow
        ) { plans, binding, prefs ->
            plans.filter { plan ->
                cloudIdentityRepository.isBookingPlanVisibleToIdentity(
                    plan = plan,
                    activeCloudUserId = binding?.cloudUserId,
                    activeStudentId = prefs.savedStudentId
                )
            }
        }

    suspend fun getAllLibraryBookingPlansForAuditSnapshot(): List<LibraryBookingPlanEntity> =
        cloudAndParcelDao.getLibraryBookingPlansSnapshot()

    suspend fun snapshotBookingSubmissionIdentity(): CampusRepository.BookingSubmissionIdentity {
        val session = snapshotCloudIdentitySession()
        val prefs = preferencesFlow.first()
        return CampusRepository.BookingSubmissionIdentity(
            profileId, session?.cloudUserId.orEmpty(), session?.generation ?: -1L,
            session?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs.networkProxyConfig.customCloudApiBaseUrl),
            (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: prefs.savedStudentId.orEmpty()).trim()
        )
    }

    suspend fun createDualEngineBookingPlan(
        executionMode: BookingExecutionMode,
        targetDate: LocalDate,
        targetTimeText: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        allowAlternativeSeat: Boolean = true,
        repeatDays: Int = 1,
        /**
         * 用户已重新确认的成交价（微桃）：云端调价 409 之后由 UI 诚实再确认路径传入。
         * 一旦存在用户确认的报价，本次创建钉入并持久化该价，绝不再回落覆盖为
         * executionMode 常量价；仅 CLOUD_HOSTED 模式消费该值（本机/提醒恒为 0），
         * 防止上一轮云端报价跨引擎/跨身份残留进入新的本机计划。
         */
        acceptedPriceMicropeach: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        expectedIdentity: CampusRepository.BookingSubmissionIdentity? = null
    ): Result<LibraryBookingPlanEntity> = runCatching {
        CampusCloudOidcHelper.configurePersistentAuthStorageDir(
            java.io.File(appContext.filesDir, "cloud_auth_provenance")
        )
        val normalizedRepeatDays = repeatDays.coerceIn(1, 30)
        // 本次创建实际钉入/计费的单价：存在用户确认报价时一律用确认价，
        // 否则回退为该执行模式向用户展示过的常量价（绝不静默按云端新价改签）。
        val effectiveExpectedPriceMicropeach = if (executionMode == BookingExecutionMode.CLOUD_HOSTED) {
            (acceptedPriceMicropeach ?: executionMode.priceMicropeach).coerceAtLeast(0L)
        } else {
            executionMode.priceMicropeach
        }
        val targetDateStr = targetDate.toString()
        val prefsSnap = preferencesFlow.first()
        val activeBindingSnap = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        // 统一身份快照（profileId + cloudUserId + generation + apiBase + ssoSubject）：
        // 云端分支的余额写回与委托提交前复核均以此为准
        val identitySessionAtStart = snapshotCloudIdentitySession()
        val (studentNoBundlePre, delegatedBundlePre) = cloudIdentityRepository.buildDelegatedCredentialBundle()
        val currentStudentNo = (studentNoBundlePre ?: prefsSnap.savedStudentId.orEmpty()).trim()
        if (expectedIdentity != null) {
            requireActiveSession()
            val actualIdentity = CampusRepository.BookingSubmissionIdentity(
                profileId, identitySessionAtStart?.cloudUserId.orEmpty(),
                identitySessionAtStart?.generation ?: -1L,
                identitySessionAtStart?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(prefsSnap.networkProxyConfig.customCloudApiBaseUrl),
                currentStudentNo
            )
            check(actualIdentity == expectedIdentity) { "确认身份已变化，请刷新页面后重新确认创建" }
        }

        // 0) 跨引擎切换与同日同人防重协调：统一按真实学校身份（学号）与明确 owner 归属判重（不同人隔离不误伤，同一学号跨账号仍严格防重！）
        val allPlansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
        val existingForDate = allPlansSnapshot.filter { plan ->
            cloudIdentityRepository.isSamePersonBookingConflictCandidate(
                candidate = plan,
                targetLocalDate = targetDateStr,
                activeCloudUserId = activeBindingSnap?.cloudUserId,
                activeStudentNo = currentStudentNo
            )
        }

        val confirmedForDate = existingForDate.firstOrNull {
            it.status in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED")
        }
        if (confirmedForDate != null) {
            throw IllegalStateException(
                "该日期（$targetDateStr）已在学校端成功锁定座位（状态：${confirmedForDate.status}），无需切换引擎或重复提交，请先核实当前预约记录"
            )
        }

        val unknownOrInFlightForDate = existingForDate.firstOrNull {
            it.status in setOf(
                "RESULT_UNKNOWN",
                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                "EXECUTING",
                "DISPATCH_INTENT",
                "CANCEL_RESULT_UNKNOWN"
            ) || it.fireStatus.startsWith("DISPATCH_INTENT")
        }
        if (unknownOrInFlightForDate != null) {
            throw IllegalStateException(
                "该日期（$targetDateStr）已有已发出或结果待核对的抢座请求（状态：${unknownOrInFlightForDate.status}），在核实学校端结果前禁止切换引擎或重复预约"
            )
        }

        val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
            prefsSnap.networkProxyConfig.customCloudApiBaseUrl
        )
        val parsedTime = DualEngineReservationPlanner.parseTargetLocalTime(targetTimeText)
        val planId = "plan_${UUID.randomUUID().toString().replace("-", "").take(14)}"
        val schedule = DualEngineReservationPlanner.computeSchedule(
            planId = planId,
            targetDate = targetDate,
            targetTime = parsedTime,
            generation = 1,
            nowEpochMillis = nowEpochMillis
        )
        val initialStatus = when {
            schedule.finalCheckInitialStatus == "FINAL_CHECK_LATE_OK" -> "FINAL_CHECK_OK"
            schedule.precheckInitialStatus == "PRECHECK_LATE_OK" -> "PRECHECK_OK"
            else -> "ARMED"
        }

        var remoteCloudJobId: String? = null
        var planOwnerCloudUserId: String? = null

        // 若目标为本机抢座/提醒：必须按「父计划关联与续期可能性」选取全部相关云端计划（包括旧 FAILED/EXPIRED 子任务及跨日连续计划），
        // 先向云端执行权威核查与父计划停止，绝不因本地旧子任务已终态而跳过协调！
        if (executionMode != BookingExecutionMode.CLOUD_HOSTED) {
            val cloudPlansRequiringCoordination = allPlansSnapshot.filter { plan ->
                if (plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName) return@filter false
                val belongsToSamePerson =
                    cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(plan, activeBindingSnap?.cloudUserId, currentStudentNo) ||
                        (currentStudentNo.isNotEmpty() && plan.ownerStudentId?.trim() == currentStudentNo)
                if (!belongsToSamePerson) return@filter false
                // 仅排除已在本地完成过云端父计划协调停止且 remainingDays == 0 的 CANCELLED/SCHOOL_CANCELLED 记录；
                // FAILED / EXPIRED 以及跨日连续计划（repeatDays > 1 或 remainingDays > 0）必须纳入云端父计划权威核查！
                val alreadyCoordinatedStopped =
                    (plan.status == "CANCELLED" && plan.remainingDays <= 0 &&
                        plan.lastDiagnosticMessage?.contains("已与云端协调停止父计划") == true) ||
                        plan.status == "SCHOOL_CANCELLED"
                if (alreadyCoordinatedStopped) return@filter false

                val planStartDate = runCatching { LocalDate.parse(plan.targetLocalDate) }.getOrNull()
                val spanDays = plan.repeatDays.coerceAtLeast(1).toLong()
                val coversTargetDate = plan.targetLocalDate == targetDateStr ||
                    plan.remainingDays > 0 ||
                    (planStartDate != null &&
                        !targetDate.isBefore(planStartDate) &&
                        !targetDate.isAfter(planStartDate.plusDays(spanDays - 1)))
                if (!coversTargetDate) return@filter false

                plan.status != "CANCELLED" || !plan.cloudJobId.isNullOrBlank() || plan.repeatDays > 1
            }

            for (cloudPlan in cloudPlansRequiringCoordination) {
                var cloudToken = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = false)
                if (cloudToken.isBlank()) {
                    cloudToken = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = true)
                }
                val checkRes = withContext(Dispatchers.IO) {
                    CampusCloudOidcHelper.coordinateStopCloudJobBeforeEngineSwitchDetailed(
                        accessToken = cloudToken,
                        cloudJobId = cloudPlan.cloudJobId,
                        planId = cloudPlan.id,
                        targetLocalDate = targetDateStr,
                        cloudBaseUrl = cloudBaseUrl,
                        httpTransport = cloudHttpTransportForTest
                    )
                }
                val isSameTargetDateRow = cloudPlan.targetLocalDate == targetDateStr
                when (checkRes.outcome) {
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.SAFE_TO_SWITCH_STOPPED -> {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, cloudPlan.id)
                        bookingPlanStateMutex.withLock {
                            val nextStatus = if (cloudPlan.status in setOf("FAILED", "EXPIRED", "CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") &&
                                (!isSameTargetDateRow || cloudPlan.status in setOf("FAILED", "EXPIRED"))
                            ) {
                                if (cloudPlan.status == "CONFIRMED") "DELEGATION_STOPPED_SCHOOL_CONFIRMED" else cloudPlan.status
                            } else {
                                "CANCELLED"
                            }
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                cloudPlan.copy(
                                    status = nextStatus,
                                    fireStatus = nextStatus,
                                    generation = cloudPlan.generation + 1,
                                    stateVersion = cloudPlan.stateVersion + 1,
                                    remainingDays = 0,
                                    lastDiagnosticMessage = "已与云端协调停止父计划及排队子任务，安全切换为本机模式",
                                    updatedAtEpochMillis = nowEpochMillis
                                )
                            )
                        }
                    }
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_CONFIRMED -> {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, cloudPlan.id)
                        if (isSameTargetDateRow) {
                            bookingPlanStateMutex.withLock {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    cloudPlan.copy(
                                        status = checkRes.resolvedStatus,
                                        fireStatus = checkRes.resolvedStatus,
                                        externalBookingId = checkRes.externalBookingId ?: cloudPlan.externalBookingId,
                                        evidenceSha256 = checkRes.evidenceSha256 ?: cloudPlan.evidenceSha256,
                                        preferredAreaName = checkRes.bookedRegionAlias?.takeIf { it.isNotBlank() } ?: cloudPlan.preferredAreaName,
                                        preferredSeatLabel = checkRes.bookedSeatAlias?.takeIf { it.isNotBlank() } ?: cloudPlan.preferredSeatLabel,
                                        remainingDays = 0,
                                        stateVersion = cloudPlan.stateVersion + 1,
                                        lastDiagnosticMessage = checkRes.reason,
                                        updatedAtEpochMillis = nowEpochMillis
                                    )
                                )
                            }
                        }
                        throw IllegalStateException(checkRes.reason)
                    }
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_DISPATCHED_OR_UNKNOWN -> {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, cloudPlan.id)
                        if (isSameTargetDateRow) {
                            bookingPlanStateMutex.withLock {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    cloudPlan.copy(
                                        status = checkRes.resolvedStatus,
                                        fireStatus = checkRes.resolvedStatus,
                                        remainingDays = 0,
                                        stateVersion = cloudPlan.stateVersion + 1,
                                        lastDiagnosticMessage = checkRes.reason,
                                        updatedAtEpochMillis = nowEpochMillis
                                    )
                                )
                            }
                        }
                        throw IllegalStateException(checkRes.reason)
                    }
                    CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_OFFLINE_UNCOORDINATED -> {
                        throw IllegalStateException(checkRes.reason)
                    }
                }
            }
        } else {
            val binding = activeBindingSnap
                ?: throw IllegalStateException("使用云端抢座需先登录 XPeach 云账号；您也可随时切换为「本机抢座」（无需登录云账号）")
            if (cloudHttpTransportForTest == null) {
                if (currentStudentNo.isBlank()) {
                    throw IllegalStateException("开启云端准点抢座需先登录并保存校园学号与统一认证密码")
                }
                val hasSavedPassword = !delegatedBundlePre.isNullOrBlank() && runCatching {
                    org.json.JSONObject(delegatedBundlePre).optString("password").isNotBlank()
                }.getOrDefault(false)
                if (!hasSavedPassword) {
                    throw IllegalStateException("开启云端准点抢座需保存校园统一认证密码（避免凌晨登录态过期），请重新验证并保存学号与密码")
                }
            }
            var token = cloudIdentityRepository.resolveValidCloudAccessToken(
                forceRefresh = false,
                expectedIdentity = identitySessionAtStart,
                expectedStudentNo = currentStudentNo
            )
            if (token.isBlank()) {
                token = cloudIdentityRepository.resolveValidCloudAccessToken(
                    forceRefresh = true,
                    expectedIdentity = identitySessionAtStart,
                    expectedStudentNo = currentStudentNo
                )
            }
            if (token.isBlank()) {
                throw IllegalStateException("云端会话已过期或当前离线，无法创建云端抢座计划；原有的本机排队计划已为您保留")
            }
            var effectiveBalanceMicropeach = binding.peachBalanceMicropeach
            if (effectiveBalanceMicropeach < effectiveExpectedPriceMicropeach) {
                val livePeach = withContext(Dispatchers.IO) {
                    CampusCloudOidcHelper.fetchLivePeachBalance(
                        accessToken = token,
                        cloudBaseUrl = cloudBaseUrl,
                        httpTransport = cloudHttpTransportForTest
                    )
                }
                if (livePeach != null) {
                    effectiveBalanceMicropeach = kotlin.math.round(livePeach * 10_000.0).toLong()
                    // 世代守卫写回：等待云端响应期间解绑/换号/重登的，丢弃旧身份余额结果，
                    // 绝不 upsert binding.copy 覆盖新账号或复活已解绑绑定行
                    if (identitySessionAtStart != null) {
                        cloudIdentityRepository.writeBackCloudBindingIfCurrent(
                            snapshot = identitySessionAtStart,
                            newPeachBalanceMicropeach = effectiveBalanceMicropeach
                        )
                    }
                }
            }
            if (effectiveBalanceMicropeach < effectiveExpectedPriceMicropeach) {
                throw IllegalStateException(
                    "当前 XPeach 账户余额不足（云端抢座成功需 ${formatMicropeachAsPeach(effectiveExpectedPriceMicropeach)} 🍑，未抢中不扣费），请先前往 XPeach 账户补充余额，或改用「本机抢座」"
                )
            }
            planOwnerCloudUserId = binding.cloudUserId

            data class StoppedLocalPlanLease(
                val snapshotBeforeStop: LibraryBookingPlanEntity,
                val expectedStoppedGeneration: Int,
                val expectedStoppedStateVersion: Int,
                val expectedOwnerCloudUserId: String?,
                val expectedOwnerStudentId: String?,
                val handoffDiagnosticMarker: String
            )

            // 1) 严格交接顺序：先在互斥锁与事务内原子停止并围栏隔离同日本机排队计划，记录临时停止租约标识，验证撤回成功后才允许向云端发起创建！
            val queuedLocalPlans = existingForDate.filter {
                it.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    it.status != "REMINDER_SENT"
            }
            val handoffOpToken = "HANDOFF_TO_CLOUD:$planId:${UUID.randomUUID().toString().replace("-", "").take(8)}"
            val handoffDiagnostic = "正在切换至云端托管抢座，已先行原子停止本机排队计划 [$handoffOpToken]"
            val stoppedLocalLeases = mutableListOf<StoppedLocalPlanLease>()

            suspend fun restoreStoppedLocalPlansSafely() {
                if (stoppedLocalLeases.isEmpty()) return
                val liveBindingNow = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                val liveStuNow = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (liveBindingNow?.cloudUserId?.trim() != binding.cloudUserId.trim() ||
                    (currentStudentNo.isNotEmpty() && liveStuNow != currentStudentNo) ||
                    (currentStudentNo.isEmpty() && liveStuNow.isNotEmpty())
                ) {
                    // 等待云端响应期间已切换云账号或学号：严禁恢复旧快照或重排闹钟！
                    return
                }
                val restoredForAlarm = mutableListOf<LibraryBookingPlanEntity>()
                bookingPlanStateMutex.withLock {
                    val liveBindingLocked = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                    val liveStuLocked = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                    if (liveBindingLocked?.cloudUserId?.trim() != binding.cloudUserId.trim() ||
                        (currentStudentNo.isNotEmpty() && liveStuLocked != currentStudentNo) ||
                        (currentStudentNo.isEmpty() && liveStuLocked.isNotEmpty())
                    ) {
                        return@withLock
                    }
                    database.withTransaction {
                        val restoreTs = System.currentTimeMillis()
                        for (lease in stoppedLocalLeases) {
                            val currentDbRow = cloudAndParcelDao.getLibraryBookingPlanById(lease.snapshotBeforeStop.id)
                                ?: continue
                            val unchangedSinceTempStop =
                                currentDbRow.status == "CANCELLED" &&
                                    currentDbRow.generation == lease.expectedStoppedGeneration &&
                                    currentDbRow.stateVersion == lease.expectedStoppedStateVersion &&
                                    currentDbRow.ownerCloudUserId.orEmpty() == lease.expectedOwnerCloudUserId.orEmpty() &&
                                    currentDbRow.ownerStudentId.orEmpty() == lease.expectedOwnerStudentId.orEmpty() &&
                                    currentDbRow.lastDiagnosticMessage == lease.handoffDiagnosticMarker
                            if (!unchangedSinceTempStop) {
                                // 等待期间用户已取消、编辑或变更了该计划：保留用户最新决定，绝不用旧快照覆盖！
                                continue
                            }
                            val restored = lease.snapshotBeforeStop.copy(
                                generation = currentDbRow.generation + 1,
                                stateVersion = currentDbRow.stateVersion + 1,
                                updatedAtEpochMillis = restoreTs
                            )
                            cloudAndParcelDao.upsertLibraryBookingPlan(restored)
                            val reread = cloudAndParcelDao.getLibraryBookingPlanById(restored.id)
                            if (reread != null && reread.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                                restoredForAlarm += reread
                            }
                        }
                    }
                }
                for (rereadPlan in restoredForAlarm) {
                    val latestVerify = cloudAndParcelDao.getLibraryBookingPlanById(rereadPlan.id)
                    if (latestVerify != null &&
                        latestVerify.generation == rereadPlan.generation &&
                        latestVerify.stateVersion == rereadPlan.stateVersion &&
                        latestVerify.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                    ) {
                        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, latestVerify)
                    }
                }
            }

            for (localPlan in queuedLocalPlans) {
                LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, localPlan.id)
                var stopFailedReason: String? = null
                bookingPlanStateMutex.withLock {
                    database.withTransaction {
                        val cancelledRows = cloudAndParcelDao.cancelQueuedBookingPlanAtomically(
                            planId = localPlan.id,
                            diagnosticMessage = handoffDiagnostic,
                            nowEpochMillis = nowEpochMillis
                        )
                        if (cancelledRows > 0) {
                            val stoppedRow = cloudAndParcelDao.getLibraryBookingPlanById(localPlan.id)
                            if (stoppedRow != null) {
                                stoppedLocalLeases += StoppedLocalPlanLease(
                                    snapshotBeforeStop = localPlan,
                                    expectedStoppedGeneration = stoppedRow.generation,
                                    expectedStoppedStateVersion = stoppedRow.stateVersion,
                                    expectedOwnerCloudUserId = stoppedRow.ownerCloudUserId,
                                    expectedOwnerStudentId = stoppedRow.ownerStudentId,
                                    handoffDiagnosticMarker = handoffDiagnostic
                                )
                            }
                        } else {
                            val latestLocal = cloudAndParcelDao.getLibraryBookingPlanById(localPlan.id)
                            if (latestLocal == null || latestLocal.status !in setOf("CANCELLED", "REMINDER_SENT")) {
                                stopFailedReason = "本机同日抢座计划已进入执行或待核对状态（状态：${latestLocal?.status ?: "UNKNOWN"}），已拒绝创建云端任务以避免双引擎重复预约"
                            }
                        }
                    }
                }
                if (stopFailedReason != null) {
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException(stopFailedReason!!)
                }
            }

            // 2) 本机已 100% 原子停止后，向云端提交托管计划（带幂等键 planId）
            //    提交前即时复核：身份/世代/base-url 与学号必须仍与开始快照一致，
            //    切号期间的排队提交一律停止并恢复原有本机计划
            if (identitySessionAtStart == null ||
                !isCloudIdentitySnapshotCurrent(identitySessionAtStart) ||
                (expectedIdentity != null && !isSessionActive)
            ) {
                restoreStoppedLocalPlansSafely()
                throw IllegalStateException("云端账号在任务创建期间已切换或解绑，已停止向云端提交旧会话委托")
            }
            val liveStudentBeforeSubmit = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
            if (liveStudentBeforeSubmit != currentStudentNo) {
                restoreStoppedLocalPlansSafely()
                throw IllegalStateException("校园学号在任务创建期间已切换，已停止向云端提交旧学号委托")
            }
            val formattedTime = DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)
            val cleanArea = preferredAreaName.trim().ifBlank { "五层中区" }
            val cleanSeat = preferredSeatLabel.trim().ifBlank { "01桌 B座" }
            var submitOutcome = withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.submitCloudHostedReservationPlanDetailed(
                    accessToken = token,
                    studentNo = currentStudentNo,
                    delegatedSecret = delegatedBundlePre.orEmpty(),
                    targetDate = targetDateStr,
                    targetExecuteAtIso = formattedTime,
                    preferredAreaName = cleanArea,
                    preferredSeatLabel = cleanSeat,
                    planIdempotencyKey = planId,
                    cloudBaseUrl = cloudBaseUrl,
                    repeatDays = normalizedRepeatDays,
                    // 报价钉死：把创建时向用户展示/用户已重新确认的价格写入 expected_price_micropeach，
                    // 云端调价即 409 走诚实再确认（下同），绝不静默按新价改签；
                    // 存在用户确认报价时绝不再回落覆盖为 executionMode 常量价
                    expectedPriceMicropeach = effectiveExpectedPriceMicropeach,
                    // 报价归属校验：服务端返回他人 quote 必须保守拒绝重新取价
                    expectedCloudUserId = identitySessionAtStart?.cloudUserId,
                    httpTransport = cloudHttpTransportForTest
                )
            }
            if (submitOutcome is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.RejectedPrePersist && submitOutcome.isAuthExpired) {
                // R5-C 令牌刷新重提前的身份/学号复验（复验①·发起前）：
                // 首交后被 401 拒绝只能证明 pre-persist，切号/换址/换学号期间的旧会话提交
                // 绝不得携新令牌重试——refresh 发起、返回、凭据写回三点再验（resolver 内），
                // 此处先做业务侧复核，身份不符即恢复本机计划并中止
                val liveStudentBeforeRetry = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                    ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (!isCloudIdentitySnapshotCurrent(identitySessionAtStart) ||
                    liveStudentBeforeRetry != currentStudentNo
                ) {
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端账号或学号在任务提交期间已切换，已停止旧会话的令牌刷新与重提交")
                }
                // 测试闩锁（生产为 null）：确定性覆盖"401→refresh"窗口的切号/换址交错
                cloudSubmitRetryBarrierHookForTest?.invoke()
                token = cloudIdentityRepository.resolveValidCloudAccessToken(
                    forceRefresh = true,
                    failedAccessToken = token,
                    expectedIdentity = identitySessionAtStart,
                    expectedStudentNo = currentStudentNo
                )
                if (token.isBlank() && !isCloudIdentitySnapshotCurrent(identitySessionAtStart)) {
                    // resolver 以身份失效为由拒绝：旧提交绝不得携新 token 重试
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端账号、学号或网络地址在任务提交期间已切换，已停止旧会话重提交")
                }
                if (token.isNotBlank()) {
                    // 复验③·重提前：拿回的令牌必须仍属于开始时的身份会话与学号——
                    // 切号后旧提交不得携新 token 重试，网络地址也继续沿用已验证 base
                    val liveStudentAfterRetry = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                        ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                    if (!isCloudIdentitySnapshotCurrent(identitySessionAtStart) ||
                        liveStudentAfterRetry != currentStudentNo
                    ) {
                        restoreStoppedLocalPlansSafely()
                        throw IllegalStateException("令牌刷新期间云端账号或学号已切换，已停止携新令牌重提旧提交")
                    }
                    submitOutcome = withContext(Dispatchers.IO) {
                        CampusCloudOidcHelper.submitCloudHostedReservationPlanDetailed(
                            accessToken = token,
                            studentNo = currentStudentNo,
                            delegatedSecret = delegatedBundlePre.orEmpty(),
                            targetDate = targetDateStr,
                            targetExecuteAtIso = formattedTime,
                            preferredAreaName = cleanArea,
                            preferredSeatLabel = cleanSeat,
                            planIdempotencyKey = planId,
                            cloudBaseUrl = cloudBaseUrl,
                            repeatDays = normalizedRepeatDays,
                            // 令牌刷新重提交仍钉入同一成交价（用户确认价优先于常量价），绝不漂移
                            expectedPriceMicropeach = effectiveExpectedPriceMicropeach,
                            expectedCloudUserId = identitySessionAtStart.cloudUserId,
                            httpTransport = cloudHttpTransportForTest
                        )
                    }
                }
            }

            when (val finalOutcome = submitOutcome) {
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.Accepted -> {
                    remoteCloudJobId = finalOutcome.remoteJobOrPlanId
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.RejectedPrePersist -> {
                    // 云端在持久化计划前明确拒绝（4xx 或前置授权失败），云端无半途任务，安全补偿恢复原有本机排队计划
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端未能受理该抢座计划（${finalOutcome.reason}）；已为您恢复原有本机排队计划")
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.QuoteUnusable -> {
                    // 报价端点存在但返回坏数据/未知错误：提示重新获取报价，绝不静默降级
                    // 到仅 expected_price 的 legacy 钉价路径；此时计划与绑定写均未发生
                    restoreStoppedLocalPlansSafely()
                    throw IllegalStateException("云端报价获取异常（${finalOutcome.reason}），请重新获取报价后再创建；已为您恢复原有本机排队计划")
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.PriceDriftConflict -> {
                    // 报价漂移 409：云端价格已变更——恢复本机计划后抛出专用异常，
                    // UI 必须向用户如实展示新旧价格并要求"重新确认创建"，绝不自动按新价重试
                    restoreStoppedLocalPlansSafely()
                    throw CloudPlanPriceDriftException(
                        expectedPriceMicropeach = finalOutcome.expectedPriceMicropeach
                            ?: effectiveExpectedPriceMicropeach,
                        currentPriceMicropeach = finalOutcome.currentPriceMicropeach,
                        serverReason = finalOutcome.reason,
                        proposedQuote = finalOutcome.quote
                    )
                }
                is CampusCloudOidcHelper.CloudPlanSubmissionOutcome.ResponseAmbiguous -> {
                    // 云端 POST /reservation-plans 已发出但响应丢失/超时：可能已在云端落库！
                    // 先尝试向云端查询并撤回该半途计划；仅当云端权威确认已撤回且无在途任务时才恢复本机，否则严格失败闭合为 RESULT_UNKNOWN（保持本机停止，绝不双端并跑！）
                    val reconcile = withContext(Dispatchers.IO) {
                        CampusCloudOidcHelper.coordinateStopCloudJobBeforeEngineSwitchDetailed(
                            accessToken = token,
                            cloudJobId = null,
                            planId = planId,
                            targetLocalDate = targetDateStr,
                            cloudBaseUrl = cloudBaseUrl,
                            httpTransport = cloudHttpTransportForTest
                        )
                    }
                    if (reconcile.outcome == CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.SAFE_TO_SWITCH_STOPPED) {
                        restoreStoppedLocalPlansSafely()
                        throw IllegalStateException("云端创建响应超时，已向云端核实并安全撤回未执行请求，已为您恢复原有本机计划")
                    }
                    val closedStatus = when (reconcile.outcome) {
                        CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_CONFIRMED -> reconcile.resolvedStatus
                        CampusCloudOidcHelper.EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_DISPATCHED_OR_UNKNOWN -> reconcile.resolvedStatus
                        else -> "RESULT_UNKNOWN"
                    }
                    val ambiguousEntity = LibraryBookingPlanEntity(
                        id = planId,
                        schoolId = schoolId,
                        executionMode = BookingExecutionMode.CLOUD_HOSTED.wireName,
                        targetLocalDate = targetDateStr,
                        targetLocalTime = formattedTime,
                        timezoneId = DualEngineReservationPlanner.HAUT_ZONE.id,
                        precheckAtEpochMillis = schedule.precheckAtEpochMillis,
                        finalCheckAtEpochMillis = schedule.finalCheckAtEpochMillis,
                        fireAtEpochMillis = schedule.fireAtEpochMillis,
                        precheckStatus = schedule.precheckInitialStatus,
                        finalCheckStatus = schedule.finalCheckInitialStatus,
                        fireStatus = closedStatus,
                        preferredAreaName = reconcile.bookedRegionAlias?.takeIf { it.isNotBlank() } ?: cleanArea,
                        preferredSeatLabel = reconcile.bookedSeatAlias?.takeIf { it.isNotBlank() } ?: cleanSeat,
                        allowAlternativeSeat = allowAlternativeSeat,
                        cloudJobId = null,
                        externalBookingId = reconcile.externalBookingId,
                        evidenceSha256 = reconcile.evidenceSha256,
                        // 响应中断待核对单也必须记录本次钉入价，后续核对按原成交价对账
                        priceMicropeach = effectiveExpectedPriceMicropeach,
                        paymentState = "HELD_PENDING_REVIEW",
                        status = closedStatus,
                        stateVersion = 1,
                        generation = 1,
                        lastDiagnosticMessage = "云端创建响应中断（${finalOutcome.reason}），无法确认云端未受理；已保持本机计划停止并记录待核对状态以防双引擎重复预约",
                        createdAtEpochMillis = nowEpochMillis,
                        updatedAtEpochMillis = nowEpochMillis,
                        repeatDays = normalizedRepeatDays,
                        remainingDays = 0,
                        ownerCloudUserId = planOwnerCloudUserId,
                        ownerStudentId = currentStudentNo.ifBlank { null }
                    )
                    bookingPlanStateMutex.withLock {
                        cloudAndParcelDao.upsertLibraryBookingPlan(ambiguousEntity)
                    }
                    throw IllegalStateException(ambiguousEntity.lastDiagnosticMessage!!)
                }
            }
        }

        val repeatHint = if (normalizedRepeatDays > 1) {
            "（已开启连续 $normalizedRepeatDays 天自动抢座，每日完成后自动续期次日任务）"
        } else {
            ""
        }

        val entity = LibraryBookingPlanEntity(
            id = planId,
            schoolId = schoolId,
            executionMode = executionMode.wireName,
            targetLocalDate = targetDateStr,
            targetLocalTime = DualEngineReservationPlanner.formatLocalTimeHms(parsedTime),
            timezoneId = DualEngineReservationPlanner.HAUT_ZONE.id,
            precheckAtEpochMillis = schedule.precheckAtEpochMillis,
            finalCheckAtEpochMillis = schedule.finalCheckAtEpochMillis,
            fireAtEpochMillis = schedule.fireAtEpochMillis,
            precheckStatus = schedule.precheckInitialStatus,
            finalCheckStatus = schedule.finalCheckInitialStatus,
            fireStatus = schedule.fireInitialStatus,
            preferredAreaName = preferredAreaName.trim().ifBlank { "五层中区" },
            preferredSeatLabel = preferredSeatLabel.trim().ifBlank { "01桌 B座" },
            allowAlternativeSeat = allowAlternativeSeat,
            cloudJobId = remoteCloudJobId,
            externalBookingId = null,
            evidenceSha256 = null,
            // 持久化本次钉入/用户确认的成交价：后续核对、续期与再分派一律以该原成交价为准，
            // 绝不因云端后续调价而改写历史计划行
            priceMicropeach = effectiveExpectedPriceMicropeach,
            paymentState = if (effectiveExpectedPriceMicropeach == 0L) "NOT_REQUIRED" else "DEBIT_PENDING",
            status = initialStatus,
            stateVersion = 1,
            generation = 1,
            lastDiagnosticMessage = when (executionMode) {
                BookingExecutionMode.CLOUD_HOSTED ->
                    "已交由云端托管（任务号 ${remoteCloudJobId ?: planId.takeLast(8)}）：将在 ${targetDate} ${DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)} 自动登录并准点抢座$repeatHint"
                BookingExecutionMode.MANUAL_REMINDER ->
                    "已开启到点提醒：将在 ${targetDate} ${DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)} 弹出选座通知$repeatHint"
                BookingExecutionMode.LOCAL_DEVICE ->
                    "已开启本机准点抢座：将在 ${targetDate} ${DualEngineReservationPlanner.formatLocalTimeHms(parsedTime)} 唤醒本机执行自动抢座$repeatHint"
            },
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
            repeatDays = normalizedRepeatDays,
            remainingDays = normalizedRepeatDays,
            ownerCloudUserId = planOwnerCloudUserId,
            ownerStudentId = currentStudentNo.ifBlank { null }
        )
        bookingPlanStateMutex.withLock {
            // 云端已受理时必须保留原归属回执；本机尚未创建则在实际落库前复核旧确认。
            if (expectedIdentity != null && executionMode != BookingExecutionMode.CLOUD_HOSTED) {
                requireActiveSession()
                check(snapshotBookingSubmissionIdentity() == expectedIdentity) { "确认身份已变化，请刷新页面后重新确认创建" }
            }
            cloudAndParcelDao.upsertLibraryBookingPlan(entity)
        }
        if (executionMode == BookingExecutionMode.CLOUD_HOSTED) {
            LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, entity.id)
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        } else {
            LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, entity)
            if (executionMode == BookingExecutionMode.LOCAL_DEVICE) {
                repoScope.launch {
                    runCatching {
                        val token = readCachedLibrarySeatSession()?.rawToken
                            ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
                        if (!token.isNullOrBlank()) {
                            HautLibrarySeatAuthClient.prewarmAutomatedSeatTargets(
                                rawToken = token,
                                targetDate = entity.targetLocalDate,
                                preferredAreaName = entity.preferredAreaName,
                                preferredSeatLabel = entity.preferredSeatLabel,
                                planId = entity.id,
                                planVersion = entity.generation
                            )
                        }
                    }
                }
            }
        }
        entity
    }

    @Volatile
    internal var liveSeatCancelObserverForTest: ((planId: String?, bookingId: String?) -> Unit)? = null

    suspend fun cancelActiveLibrarySeatBooking(
        planId: String? = null,
        bookingId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        liveSeatCancelObserverForTest?.invoke(planId, bookingId)
        runCatching {
            val plan = planId?.let { cloudAndParcelDao.getLibraryBookingPlanById(it) }
            val hasCloudJob = plan != null && !plan.cloudJobId.isNullOrBlank() && !plan.cloudJobId.startsWith("cjob_")
            var cloudCancelConfirmed = false
            if (hasCloudJob && plan != null) {
                val cloudToken = cloudIdentityRepository.resolveValidCloudAccessToken()
                if (cloudToken.isNotBlank()) {
                    val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                        preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                    )
                    cloudCancelConfirmed = CampusCloudOidcHelper.cancelCloudHostedReservationSeat(
                        accessToken = cloudToken,
                        jobId = plan.cloudJobId!!,
                        cloudBaseUrl = cloudBaseUrl
                    )
                }
            }
            val currentToken = readCachedLibrarySeatSession()?.rawToken
                ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            val targetBookingId = bookingId ?: plan?.externalBookingId
            val msg = if (cloudCancelConfirmed) {
                "云端已通过学校权威回查确认取消该笔座位预约"
            } else {
                val validLocalToken = currentToken ?: throw IllegalStateException(
                    if (hasCloudJob) "云端退座未确认完成且本地未登录图书馆账号，退座结果待核对"
                    else "请先登录图书馆账号"
                )
                val firstRes = HautLibrarySeatAuthClient.cancelActiveSeatBooking(validLocalToken, targetBookingId)
                if (firstRes.isSuccess) {
                    firstRes.getOrThrow()
                } else {
                    val refreshed = ensureLibrarySeatSession(forceRefresh = true)
                    val nextToken = (refreshed as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
                        ?: throw (firstRes.exceptionOrNull() ?: IllegalStateException("退座失败"))
                    HautLibrarySeatAuthClient.cancelActiveSeatBooking(nextToken, targetBookingId).getOrThrow()
                }
            }
            if (plan != null) {
                LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                bookingPlanStateMutex.withLock {
                    database.withTransaction {
                        val latest = cloudAndParcelDao.getLibraryBookingPlanById(plan.id) ?: plan
                        cloudAndParcelDao.upsertLibraryBookingPlan(
                            latest.copy(
                                status = "SCHOOL_CANCELLED",
                                generation = latest.generation + 1,
                                stateVersion = latest.stateVersion + 1,
                                remainingDays = 0,
                                lastDiagnosticMessage = msg,
                                updatedAtEpochMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            msg
        }
    }
}
