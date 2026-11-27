package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.AutomatedSeatBookingResult
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.DualEngineReservationPlanner
import cn.edu.haut.campus.core.campusservices.HautLibrarySeatAuthClient
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionExpiredException
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 图书馆预约三阶段派发执行、停止委托与崩溃恢复重排。
 * 实现从 CampusRepository 兼容门面逐字迁入；bookingPlanStateMutex、attemptId 活跃表、
 * 唯一派发领取、学校写入前熔断、云端/本机回连与闹钟恢复时序保持不变。
 */
class LibraryBookingExecutionEngine internal constructor(
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

    private suspend fun syncWithCampusCloud() = runtimePort.syncWithCampusCloud()

    private fun readCachedLibrarySeatSession() =
        librarySeatRepository.readCachedLibrarySeatSession()

    private suspend fun ensureLibrarySeatSession(forceRefresh: Boolean = false) =
        librarySeatRepository.ensureLibrarySeatSession(forceRefresh)

    private val activeInProcessFireAttempts = ConcurrentHashMap<String, String>()
    private val activeDispatchedFireAttempts = ConcurrentHashMap<String, Boolean>()

    suspend fun advanceBookingPlanStage(
        planId: String,
        stage: TripleScheduleStage,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> =
        advanceBookingPlanStageInternal(
            planId = planId,
            stage = stage,
            allowCloudExecuteNow = false,
            nowEpochMillis = nowEpochMillis
        )

    /**
     * 用户显式"现在执行"入口（与定时 stage API 分离）：仅接受 CLOUD_HOSTED 计划，
     * 由服务端窗口裁决是否可提前执行；客户端绝不替代云端定时引擎。
     */
    suspend fun executeCloudBookingPlanNow(
        planId: String,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> {
        val plan = cloudAndParcelDao.getLibraryBookingPlanById(planId)
            ?: return Result.failure(IllegalArgumentException("未找到预约计划 $planId"))
        if (plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName) {
            return Result.failure(IllegalStateException("立即执行入口仅适用于云端托管抢座计划"))
        }
        return advanceBookingPlanStageInternal(
            planId = planId,
            stage = TripleScheduleStage.FIRE,
            allowCloudExecuteNow = true,
            nowEpochMillis = nowEpochMillis
        )
    }

    private suspend fun advanceBookingPlanStageInternal(
        planId: String,
        stage: TripleScheduleStage,
        allowCloudExecuteNow: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Result<LibraryBookingPlanEntity> = runCatching {
        val current = cloudAndParcelDao.getLibraryBookingPlanById(planId)
            ?: throw IllegalArgumentException("未找到预约计划 $planId")
        if (current.status in setOf(
                "CONFIRMED",
                "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                "SCHOOL_CANCELLED",
                "CANCEL_RESULT_UNKNOWN",
                "FAILED",
                "CANCELLED",
                "EXPIRED",
                "REMINDER_SENT",
                "RESULT_UNKNOWN",
                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                "NEEDS_RECONFIRMATION"
            )
        ) {
            return@runCatching current
        }
        if (stage != TripleScheduleStage.FIRE &&
            current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName
        ) {
            runCatching { syncWithCampusCloud() }
            return@runCatching cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: current
        }
        // 定时入口（含旧版本排定的 pending intent、开机/时区/升级/账号切换恢复）命中云端托管
        // 计划的 FIRE：校验 owner 与当前身份归属后仅做 GET 同步合并服务端权威状态——
        // 严禁 execute-now、严禁本地续期、严禁改写服务端权威执行状态。
        if (stage == TripleScheduleStage.FIRE &&
            current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
            !allowCloudExecuteNow
        ) {
            val activeCloudId = cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId?.trim()
            val liveStuForFire = (cloudIdentityRepository.buildDelegatedCredentialBundle().first
                ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
            val belongsToActiveIdentity = cloudIdentityRepository.isBookingPlanVisibleToIdentity(
                plan = current,
                activeCloudUserId = activeCloudId,
                activeStudentId = liveStuForFire
            )
            if (belongsToActiveIdentity &&
                current.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "EXECUTING")
            ) {
                runCatching { syncWithCampusCloud() }
            }
            return@runCatching cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: current
        }
        if (stage != TripleScheduleStage.FIRE &&
            (current.status == "EXECUTING" || current.fireStatus.startsWith("DISPATCH_INTENT"))
        ) {
            return@runCatching current
        }
        val activeFireAttemptIdHolder = arrayOfNulls<String>(1)
        try {
        val updated = when (stage) {
            TripleScheduleStage.PRECHECK -> {
                val sessionState = ensureLibrarySeatSession(forceRefresh = false)
                if (sessionState is LibrarySeatSessionState.Authenticated) {
                    runCatching {
                        HautLibrarySeatAuthClient.prewarmAutomatedSeatTargets(
                            rawToken = sessionState.snapshot.rawToken,
                            targetDate = current.targetLocalDate,
                            preferredAreaName = current.preferredAreaName,
                            preferredSeatLabel = current.preferredSeatLabel,
                            planId = current.id,
                            planVersion = current.generation
                        )
                    }
                }
                val diag = when (sessionState) {
                    is LibrarySeatSessionState.Authenticated ->
                        "已提前为您检查登录状态并预热候选座位（${sessionState.snapshot.userInfo.userName.ifBlank { sessionState.snapshot.userInfo.userNo }} 已在线）"
                    else ->
                        "已检查抢座时间配置（建议先保存学校账号密码以便自动登录）"
                }
                current.copy(
                    precheckStatus = "PRECHECK_OK",
                    status = "PRECHECK_OK",
                    stateVersion = current.stateVersion + 1,
                    lastDiagnosticMessage = diag,
                    updatedAtEpochMillis = nowEpochMillis
                )
            }
            TripleScheduleStage.FINAL_CHECK -> {
                val sessionState = ensureLibrarySeatSession(forceRefresh = true)
                if (sessionState is LibrarySeatSessionState.Authenticated) {
                    runCatching {
                        HautLibrarySeatAuthClient.prewarmAutomatedSeatTargets(
                            rawToken = sessionState.snapshot.rawToken,
                            targetDate = current.targetLocalDate,
                            preferredAreaName = current.preferredAreaName,
                            preferredSeatLabel = current.preferredSeatLabel,
                            planId = current.id,
                            planVersion = current.generation
                        )
                    }
                }
                val diag = when (sessionState) {
                    is LibrarySeatSessionState.Authenticated ->
                        "已为您刷新最新登录状态并锁定候选座位号，等待 ${current.targetLocalTime} 准点开抢"
                    else ->
                        "倒计时已就绪，等待 ${current.targetLocalTime} 准点开抢"
                }
                current.copy(
                    precheckStatus = "PRECHECK_OK",
                    finalCheckStatus = "FINAL_CHECK_OK",
                    status = "FINAL_CHECK_OK",
                    stateVersion = current.stateVersion + 1,
                    lastDiagnosticMessage = diag,
                    updatedAtEpochMillis = nowEpochMillis
                )
            }
            TripleScheduleStage.FIRE -> {
                if (current.executionMode == BookingExecutionMode.MANUAL_REMINDER.wireName) {
                    current.copy(
                        precheckStatus = "PRECHECK_OK",
                        finalCheckStatus = "FINAL_CHECK_OK",
                        fireStatus = "REMINDER_SENT",
                        status = "REMINDER_SENT",
                        stateVersion = current.stateVersion + 1,
                        lastDiagnosticMessage = "已到点发送选座提醒：请点击上方选座面板一键预约 ${current.preferredAreaName} · ${current.preferredSeatLabel}",
                        updatedAtEpochMillis = nowEpochMillis
                    )
                } else {
                    // 原子唯一领取：页面手动触发与系统闹钟并发进入时，仅首个成功抢占唯一 attemptId 的调用者获得派发权；
                    // 同时注册本进程活跃 attemptId，防止 ON_RESUME 崩溃恢复误中断正在执行的 07:00 实时抢座！
                    val attemptId = UUID.randomUUID().toString().replace("-", "")
                    val claimedExclusively = bookingPlanStateMutex.withLock {
                        database.withTransaction {
                            val rows = cloudAndParcelDao.claimBookingPlanForDispatch(
                                planId = current.id,
                                expectedExecutionMode = current.executionMode,
                                expectedGeneration = current.generation,
                                attemptId = attemptId,
                                nowEpochMillis = nowEpochMillis
                            )
                            if (rows > 0) {
                                activeInProcessFireAttempts[current.id] = attemptId
                                activeFireAttemptIdHolder[0] = attemptId
                            }
                            rows
                        }
                    }
                    if (claimedExclusively <= 0) {
                        return@runCatching cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: current
                    }

                    val hadExternalWriteAttempt = java.util.concurrent.atomic.AtomicBoolean(false)
                    val liveBooking: AutomatedSeatBookingResult? =
                        if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                            // 云端托管计划严格校验归属当前云账号且只走云端执行与核验，严禁在云端返回失败、缺数据或未知状态时退回本机发起预约
                            val activeCloudId = cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId?.trim().orEmpty()
                            val planOwnerCloud = current.ownerCloudUserId?.trim().orEmpty()
                            val validCloudJobId = current.cloudJobId?.takeIf { it.isNotBlank() && !it.startsWith("cjob_") }
                            val cloudOwnerMatched = planOwnerCloud.isNotEmpty() && activeCloudId.isNotEmpty() && planOwnerCloud == activeCloudId
                            val cloudToken = if (validCloudJobId != null && cloudOwnerMatched) cloudIdentityRepository.resolveValidCloudAccessToken() else ""
                            if (!cloudOwnerMatched) {
                                AutomatedSeatBookingResult(
                                    confirmed = false,
                                    bookingId = "",
                                    seatTitle = "",
                                    evidenceSha256 = "",
                                    diagnosticMessage = "当前登录的云端账号与预约计划归属不一致或归属未知，已在发起请求前终止执行",
                                    outcomeState = "FAILED",
                                    externalExecutionState = "NOT_DISPATCHED"
                                )
                            } else if (validCloudJobId != null && cloudToken.isNotBlank()) {
                                hadExternalWriteAttempt.set(true)
                                activeDispatchedFireAttempts[current.id] = true
                                withContext(Dispatchers.IO) {
                                    CampusCloudOidcHelper.executeCloudHostedReservationJobNow(
                                        accessToken = cloudToken,
                                        jobId = validCloudJobId,
                                        httpTransport = cloudHttpTransportForTest
                                    )
                                }
                            } else {
                                AutomatedSeatBookingResult(
                                    confirmed = false,
                                    bookingId = "",
                                    seatTitle = "",
                                    evidenceSha256 = "",
                                    diagnosticMessage = "云端任务状态未知或凭证未就绪，已停止执行以避免本机重复写入",
                                    outcomeState = "RESULT_UNKNOWN",
                                    externalExecutionState = "RESULT_UNKNOWN"
                                )
                            }
                        } else {
                            // 本机抢座在发起实际外部写入前：1) 核验计划归属学号与当前活跃学号一致；2) 按实际学校身份与 owner 核对同日冲突（不同人隔离，同一学号跨云账号仍严格防重！）
                            val activeCloudIdForFire = cloudAndParcelDao.getCloudIdentityBindingSnapshot()?.cloudUserId?.trim()
                            val liveStuForFire = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                            val planOwnerStu = current.ownerStudentId?.trim().orEmpty()
                            val effectiveStudentForConflict = planOwnerStu.ifEmpty { liveStuForFire }

                            if (planOwnerStu.isNotEmpty() && liveStuForFire.isNotEmpty() && planOwnerStu != liveStuForFire) {
                                AutomatedSeatBookingResult(
                                    confirmed = false,
                                    bookingId = "",
                                    seatTitle = "",
                                    evidenceSha256 = "",
                                    diagnosticMessage = "当前登录的图书馆学号与本机预约计划归属学号不一致，已在发起学校请求前终止执行",
                                    outcomeState = "FAILED",
                                    externalExecutionState = "NOT_DISPATCHED"
                                )
                            } else {
                                val conflictSameDay = cloudAndParcelDao.getLibraryBookingPlansSnapshot().firstOrNull { cand ->
                                    cloudIdentityRepository.isSamePersonBookingConflictCandidate(
                                        candidate = cand,
                                        targetLocalDate = current.targetLocalDate,
                                        activeCloudUserId = current.ownerCloudUserId?.takeIf { it.isNotBlank() } ?: activeCloudIdForFire,
                                        activeStudentNo = effectiveStudentForConflict,
                                        excludePlanId = current.id
                                    )
                                }
                                if (conflictSameDay != null) {
                                    AutomatedSeatBookingResult(
                                        confirmed = false,
                                        bookingId = "",
                                        seatTitle = "",
                                        evidenceSha256 = "",
                                        diagnosticMessage = "检测到同一学号/账号在同日存在另一笔有效或待核对的预约任务（${conflictSameDay.executionMode} · ${conflictSameDay.status}），已停止本机发起重复写入",
                                        outcomeState = "RESULT_UNKNOWN",
                                        externalExecutionState = "RESULT_UNKNOWN"
                                    )
                                } else {
                                val checkStillActive: () -> Boolean = {
                                    kotlinx.coroutines.runBlocking {
                                        cloudAndParcelDao.isBookingPlanOwnedByAttempt(
                                            planId = current.id,
                                            expectedExecutionMode = current.executionMode,
                                            expectedGeneration = current.generation,
                                            attemptId = attemptId
                                        ) > 0
                                    }
                                }
                                val beforeWriteGuard: () -> Unit = {
                                    if (!checkStillActive()) {
                                        throw java.util.concurrent.CancellationException("计划已取消、切换引擎或非当前有效执行轮次，已在学校写入前终止")
                                    }
                                }
                                val onBeforeWriteIntent: () -> Unit = {
                                    kotlinx.coroutines.runBlocking {
                                        bookingPlanStateMutex.withLock {
                                            database.withTransaction {
                                                val owned = cloudAndParcelDao.retryBookingPlanDispatchForAttempt(
                                                    planId = current.id,
                                                    expectedExecutionMode = current.executionMode,
                                                    expectedGeneration = current.generation,
                                                    attemptId = attemptId,
                                                    nowEpochMillis = System.currentTimeMillis()
                                                )
                                                if (owned <= 0) {
                                                    throw java.util.concurrent.CancellationException(
                                                        "未能校验唯一派发权：计划已取消、已切换引擎或不属于当前 attempt，已在发起学校写入前终止"
                                                    )
                                                }
                                                hadExternalWriteAttempt.set(true)
                                                activeDispatchedFireAttempts[current.id] = true
                                            }
                                        }
                                    }
                                }
                                // 若 T-5m (FINAL_CHECK) 已在最近 15 分钟内刷新过 Token，直接使用缓存 Token 零延迟开抢，省去 07:00:00 的在线校验往返
                                val recentCachedSnap = readCachedLibrarySeatSession()?.takeIf {
                                    it.rawToken.isNotBlank() && (nowEpochMillis - it.updatedAtEpochMs) in 0L..(15 * 60 * 1000L)
                                }
                                val token0 = recentCachedSnap?.rawToken ?: run {
                                    val sessionState = ensureLibrarySeatSession(forceRefresh = false)
                                    (sessionState as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
                                        ?: readCachedLibrarySeatSession()?.rawToken.orEmpty()
                                }
                                val firstAttemptResult = if (token0.isNotBlank()) {
                                    HautLibrarySeatAuthClient.executeAutomatedSeatBooking(
                                        rawToken = token0,
                                        targetDate = current.targetLocalDate,
                                        preferredAreaName = current.preferredAreaName,
                                        preferredSeatLabel = current.preferredSeatLabel,
                                        planId = current.id,
                                        planVersion = current.generation,
                                        beforeEachWriteCheck = beforeWriteGuard,
                                        onBeforeExternalWrite = onBeforeWriteIntent,
                                        isStillActiveForPlan = checkStillActive
                                    )
                                } else {
                                    null
                                }
                                val firstAttempt = firstAttemptResult?.getOrNull()
                                // 仅当未持有 Token 或首轮因 Token 401 过期抛出 LibrarySeatSessionExpiredException 时才在同一个 attemptId 下重登重试；
                                // 若首轮已正常执行（含因未知写结果熔断停止），严禁二次调用 executeAutomatedSeatBooking 造成重复写入
                                if (firstAttempt != null) {
                                    firstAttempt
                                } else if (firstAttemptResult?.exceptionOrNull() is java.util.concurrent.CancellationException) {
                                    if (hadExternalWriteAttempt.get()) {
                                        AutomatedSeatBookingResult(
                                            confirmed = false,
                                            bookingId = "",
                                            seatTitle = "",
                                            evidenceSha256 = "",
                                            diagnosticMessage = "委托已停止，但此前在同一执行轮次中已发出学校预约请求，结果待核对",
                                            outcomeState = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                            userIntentState = "STOP_REQUESTED",
                                            externalExecutionState = "RESULT_UNKNOWN",
                                            billingState = "NOT_BILLED"
                                        )
                                    } else {
                                        AutomatedSeatBookingResult(
                                            confirmed = false,
                                            bookingId = "",
                                            seatTitle = "",
                                            evidenceSha256 = "",
                                            diagnosticMessage = "已在发起学校预约写入前安全停止本机任务",
                                            outcomeState = "DELEGATION_STOPPED",
                                            userIntentState = "STOP_REQUESTED",
                                            externalExecutionState = "NOT_DISPATCHED",
                                            billingState = "NOT_BILLED"
                                        )
                                    }
                                } else if (token0.isBlank() || firstAttemptResult?.exceptionOrNull() is LibrarySeatSessionExpiredException) {
                                    val refreshedState = ensureLibrarySeatSession(forceRefresh = true)
                                    val token1 = (refreshedState as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken.orEmpty()
                                    if (token1.isNotBlank()) {
                                        val retryRes = HautLibrarySeatAuthClient.executeAutomatedSeatBooking(
                                            rawToken = token1,
                                            targetDate = current.targetLocalDate,
                                            preferredAreaName = current.preferredAreaName,
                                            preferredSeatLabel = current.preferredSeatLabel,
                                            planId = current.id,
                                            planVersion = current.generation,
                                            beforeEachWriteCheck = beforeWriteGuard,
                                            onBeforeExternalWrite = onBeforeWriteIntent,
                                            isStillActiveForPlan = checkStillActive
                                        )
                                        if (retryRes.exceptionOrNull() is java.util.concurrent.CancellationException) {
                                            if (hadExternalWriteAttempt.get()) {
                                                AutomatedSeatBookingResult(
                                                    confirmed = false,
                                                    bookingId = "",
                                                    seatTitle = "",
                                                    evidenceSha256 = "",
                                                    diagnosticMessage = "委托已停止，但此前已发出学校预约请求，结果待核对",
                                                    outcomeState = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                                    userIntentState = "STOP_REQUESTED",
                                                    externalExecutionState = "RESULT_UNKNOWN",
                                                    billingState = "NOT_BILLED"
                                                )
                                            } else {
                                                AutomatedSeatBookingResult(
                                                    confirmed = false,
                                                    bookingId = "",
                                                    seatTitle = "",
                                                    evidenceSha256 = "",
                                                    diagnosticMessage = "已在发起学校预约写入前安全停止本机任务",
                                                    outcomeState = "DELEGATION_STOPPED",
                                                    userIntentState = "STOP_REQUESTED",
                                                    externalExecutionState = "NOT_DISPATCHED",
                                                    billingState = "NOT_BILLED"
                                                )
                                            }
                                        } else {
                                            retryRes.getOrNull()
                                        }
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                                }
                            }
                        }

                    // 统一归一化状态：严禁伪造预约编号或证据摘要！缺少完整预约凭据的确认结果一律降级为 RESULT_UNKNOWN / DELEGATION_STOPPED_RESULT_UNKNOWN
                    val isSchoolConfirmed = liveBooking?.isSchoolConfirmed == true
                    val isDelegationStopped = liveBooking?.isDelegationStopped == true
                    val isActiveConfirmed = isSchoolConfirmed && !isDelegationStopped
                    val verifiedExtId = liveBooking?.bookingId?.takeIf { isSchoolConfirmed && it.isNotBlank() }
                    val verifiedEvidenceHash = liveBooking?.evidenceSha256?.takeIf { isSchoolConfirmed && it.isNotBlank() }
                    val diagMsg = liveBooking?.diagnosticMessage ?: when {
                        isSchoolConfirmed && isDelegationStopped ->
                            "委托已停止，但学校预约已成功（座位：${current.preferredAreaName} ${current.preferredSeatLabel}，预约号：$verifiedExtId）。系统未自动扣费且已停止后续计划，请核对或按需退座"
                        isActiveConfirmed ->
                            "已为您抢到 ${current.preferredAreaName} ${current.preferredSeatLabel}，请按规定时间到馆刷脸签到"
                        hadExternalWriteAttempt.get() ->
                            "选座请求结果待核对，已停止重试以避免重复预约"
                        else ->
                            "未能锁定 ${current.preferredAreaName} ${current.preferredSeatLabel}：请先绑定/登录河工大统一身份认证账号"
                    }

                    val finalStatus = when {
                        isSchoolConfirmed && isDelegationStopped -> "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                        isActiveConfirmed -> "CONFIRMED"
                        liveBooking != null && (liveBooking.outcomeState == "DELEGATION_STOPPED_RESULT_UNKNOWN" || (liveBooking.isResultUnknown && isDelegationStopped)) ->
                            "DELEGATION_STOPPED_RESULT_UNKNOWN"
                        liveBooking != null && liveBooking.isResultUnknown -> "RESULT_UNKNOWN"
                        liveBooking == null && (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName || hadExternalWriteAttempt.get()) ->
                            "RESULT_UNKNOWN"
                        isDelegationStopped -> "CANCELLED"
                        else -> "FAILED"
                    }
                    val nextPaymentState = when {
                        finalStatus in setOf("DELEGATION_STOPPED_SCHOOL_CONFIRMED", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") &&
                            current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName ->
                            liveBooking?.billingState?.takeIf { it.isNotBlank() } ?: "HELD_PENDING_REVIEW"
                        liveBooking != null && liveBooking.billingState.isNotBlank() && current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName ->
                            liveBooking.billingState
                        finalStatus == "CONFIRMED" && current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName && current.priceMicropeach > 0L ->
                            "PAID"
                        else -> current.paymentState
                    }
                    current.copy(
                        precheckStatus = "PRECHECK_OK",
                        finalCheckStatus = "FINAL_CHECK_OK",
                        fireStatus = finalStatus,
                        status = finalStatus,
                        externalBookingId = verifiedExtId,
                        evidenceSha256 = verifiedEvidenceHash,
                        paymentState = nextPaymentState,
                        stateVersion = current.stateVersion + 1,
                        lastDiagnosticMessage = diagMsg,
                        updatedAtEpochMillis = nowEpochMillis
                    )
                }
            }
        }
        val finalEntity = bookingPlanStateMutex.withLock {
            database.withTransaction {
                val latestInDb = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: return@withTransaction current
                // 若在 PRECHECK / FINAL_CHECK 期间计划已被取消、切换引擎或代次更新，严禁回写覆盖最新状态
                if (stage != TripleScheduleStage.FIRE) {
                    if (latestInDb.generation != current.generation ||
                        latestInDb.executionMode != current.executionMode ||
                        latestInDb.status !in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                    ) {
                        return@withTransaction latestInDb
                    }
                    val stageUpdated = updated.copy(
                        generation = latestInDb.generation,
                        stateVersion = latestInDb.stateVersion + 1
                    )
                    cloudAndParcelDao.upsertLibraryBookingPlan(stageUpdated)
                    return@withTransaction stageUpdated
                }

                // FIRE 阶段完成回写：若未发起任何外部写入前计划已被取消，保持 CANCELLED 绝不回写覆盖
                if (latestInDb.status == "CANCELLED" && updated.status == "CANCELLED") {
                    return@withTransaction latestInDb
                }
                val stoppedMidFlight =
                    latestInDb.generation != current.generation ||
                        latestInDb.status in setOf(
                            "CANCELLED",
                            "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                        )
                val reconciledUpdated = if (stoppedMidFlight) {
                    when (updated.status) {
                        "CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ->
                            updated.copy(
                                status = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                fireStatus = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                paymentState = "HELD_PENDING_REVIEW",
                                remainingDays = 0,
                                generation = latestInDb.generation,
                                stateVersion = latestInDb.stateVersion + 1
                            )
                        "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN" ->
                            updated.copy(
                                status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                remainingDays = 0,
                                generation = latestInDb.generation,
                                stateVersion = latestInDb.stateVersion + 1
                            )
                        else ->
                            latestInDb.copy(
                                status = "CANCELLED",
                                fireStatus = "CANCELLED",
                                remainingDays = 0,
                                stateVersion = latestInDb.stateVersion + 1,
                                updatedAtEpochMillis = nowEpochMillis
                            )
                    }
                } else {
                    updated.copy(
                        generation = latestInDb.generation,
                        stateVersion = latestInDb.stateVersion + 1
                    )
                }

                // 仅当委托处于有效状态且今日结果为明确的 CONFIRMED 或 FAILED 时才允许推进次日多日计划；
                // 若为 DELEGATION_STOPPED_SCHOOL_CONFIRMED、RESULT_UNKNOWN、DELEGATION_STOPPED_RESULT_UNKNOWN 或 CANCELLED，一律禁止续期次日任务！
                if (!stoppedMidFlight &&
                    current.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    reconciledUpdated.status in setOf("CONFIRMED", "FAILED", "REMINDER_SENT") &&
                    current.remainingDays > 1 &&
                    latestInDb.status !in setOf("CANCELLED", "NEEDS_RECONFIRMATION")
                ) {
                    val nextDate = runCatching { LocalDate.parse(current.targetLocalDate).plusDays(1) }
                        .getOrElse { LocalDate.now(DualEngineReservationPlanner.HAUT_ZONE).plusDays(1) }
                    val parsedTime = runCatching { DualEngineReservationPlanner.parseTargetLocalTime(current.targetLocalTime) }
                        .getOrDefault(LocalTime.of(7, 0))
                    val nextGen = latestInDb.generation + 1
                    val nextRem = (current.remainingDays - 1).coerceAtLeast(1)
                    val nextSchedule = runCatching {
                        DualEngineReservationPlanner.computeSchedule(
                            planId = current.id,
                            targetDate = nextDate,
                            targetTime = parsedTime,
                            generation = nextGen,
                            nowEpochMillis = nowEpochMillis
                        )
                    }.getOrNull()
                    if (nextSchedule != null) {
                        val dayArchiveId = "${current.id}_d_${current.targetLocalDate.replace("-", "")}"
                        cloudAndParcelDao.upsertLibraryBookingPlan(
                            reconciledUpdated.copy(
                                id = dayArchiveId,
                                remainingDays = 0,
                                updatedAtEpochMillis = nowEpochMillis
                            )
                        )
                        val nextCloudJobId = if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                            "cjob_${current.id.takeLast(8)}_${nextDate.toString().replace("-", "")}"
                        } else {
                            null
                        }
                        val rolledOver = current.copy(
                            targetLocalDate = nextDate.toString(),
                            precheckAtEpochMillis = nextSchedule.precheckAtEpochMillis,
                            finalCheckAtEpochMillis = nextSchedule.finalCheckAtEpochMillis,
                            fireAtEpochMillis = nextSchedule.fireAtEpochMillis,
                            precheckStatus = "ARMED",
                            finalCheckStatus = "ARMED",
                            fireStatus = "ARMED",
                            status = "ARMED",
                            cloudJobId = nextCloudJobId,
                            externalBookingId = null,
                            evidenceSha256 = null,
                            paymentState = if (current.priceMicropeach == 0L) "NOT_REQUIRED" else "DEBIT_PENDING",
                            remainingDays = nextRem,
                            generation = nextGen,
                            stateVersion = reconciledUpdated.stateVersion + 1,
                            lastDiagnosticMessage = "${reconciledUpdated.lastDiagnosticMessage.orEmpty()} · 已自动续期次日 ($nextDate ${current.targetLocalTime}) 抢座（剩余 $nextRem/${current.repeatDays} 天）",
                            updatedAtEpochMillis = nowEpochMillis
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(rolledOver)
                        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, rolledOver)
                        rolledOver
                    } else {
                        val halted = reconciledUpdated.copy(remainingDays = 0)
                        cloudAndParcelDao.upsertLibraryBookingPlan(halted)
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, halted.id)
                        halted
                    }
                } else {
                    val halted = if (reconciledUpdated.status !in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                        reconciledUpdated.copy(remainingDays = 0)
                    } else {
                        reconciledUpdated
                    }
                    cloudAndParcelDao.upsertLibraryBookingPlan(halted)
                    if (halted.status !in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, halted.id)
                    }
                    halted
                }
            }
        }
        if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            repoScope.launch { runCatching { syncWithCampusCloud() } }
        }
        finalEntity
        } finally {
            val activeAttempt = activeFireAttemptIdHolder[0]
            if (activeAttempt != null) {
                activeInProcessFireAttempts.remove(current.id, activeAttempt)
                activeDispatchedFireAttempts.remove(current.id)
            }
        }
    }

    /**
     * 停止排队中或正在执行中的抢座委托（仅停止后续尝试与多日续期，绝不伪造学校已退座，也不自动取消已确认座位）：
     * - 若在未向学校发出外部写请求前停止，转入 CANCELLED；
     * - 若外部请求已发出或处于云端执行/未知状态，转入 DELEGATION_STOPPED_RESULT_UNKNOWN 保留待核对证据。
     */
    suspend fun stopInFlightOrQueuedDelegation(planId: String): LibraryBookingPlanEntity? {
        val current = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: return null
        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, planId)
        val nowMs = System.currentTimeMillis()
        var cloudStopSent = false
        if (current.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            val token = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isNotBlank()) {
                val cloudBaseUrl = runCatching {
                    NetworkProxyConfig.normalizeCloudApiBaseUrl(
                        preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                    )
                }.getOrDefault(CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL)
                cloudStopSent = withContext(Dispatchers.IO) {
                    CampusCloudOidcHelper.stopCloudDelegationWithoutLiveSeatCancel(
                        accessToken = token,
                        cloudJobId = current.cloudJobId,
                        planId = current.id,
                        cloudBaseUrl = cloudBaseUrl,
                        httpTransport = cloudHttpTransportForTest
                    )
                }
            }
        }

        val updated = bookingPlanStateMutex.withLock {
            database.withTransaction {
                val cancelledQueued = cloudAndParcelDao.cancelQueuedBookingPlanAtomically(
                    planId = planId,
                    diagnosticMessage = "已停止委托并取消排队中的预约计划",
                    nowEpochMillis = nowMs
                )
                if (cancelledQueued > 0) {
                    val latestAfterCancel = cloudAndParcelDao.getLibraryBookingPlanById(planId)
                    if (latestAfterCancel != null &&
                        latestAfterCancel.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        !latestAfterCancel.cloudJobId.isNullOrBlank() &&
                        !latestAfterCancel.cloudJobId.startsWith("cjob_") &&
                        !cloudStopSent
                    ) {
                        // 离线停止已有云端真实 jobId 的排队任务：不能假定云端已停，转入 DELEGATION_STOPPED_RESULT_UNKNOWN
                        val guarded = latestAfterCancel.copy(
                            status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            lastDiagnosticMessage = "本机已记录停止委托，但当前未联网确认云端任务已停止，结果待核对",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(guarded)
                        return@withTransaction guarded
                    }
                    return@withTransaction latestAfterCancel
                }

                val latest = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: return@withTransaction null
                val inProcessPreWriteLocal =
                    latest.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        activeInProcessFireAttempts.containsKey(planId) &&
                        activeDispatchedFireAttempts[planId] != true

                when {
                    inProcessPreWriteLocal && latest.status == "EXECUTING" -> {
                        val stoppedPreWrite = latest.copy(
                            status = "CANCELLED",
                            fireStatus = "CANCELLED",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已在发起学校预约写入前停止本机抢座委托（后续尝试与多日续期已终止）",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedPreWrite)
                        stoppedPreWrite
                    }
                    latest.status in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") -> {
                        // 点击「停止委托」与在途 FIRE 成功返回发生交错竞态：仅停止后续委托与多日续期，严禁越权代调学校退座接口！
                        val stoppedConfirmed = latest.copy(
                            status = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            fireStatus = "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已停止后续委托与自动续期；本次在途抢座已在学校端成功锁定座位（未发起退座），如需释放请点击「学校退座」",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedConfirmed)
                        stoppedConfirmed
                    }
                    latest.status == "CANCEL_RESULT_UNKNOWN" -> {
                        // 已处于退座结果待核对状态：停止委托不得覆盖退座待核对证据
                        val preservedUnknownCancel = latest.copy(
                            remainingDays = 0,
                            stateVersion = latest.stateVersion + 1,
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(preservedUnknownCancel)
                        preservedUnknownCancel
                    }
                    latest.status in setOf("EXECUTING", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") ||
                        latest.fireStatus.startsWith("DISPATCH_INTENT") -> {
                        val stoppedUnknown = latest.copy(
                            status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已停止后续尝试与多日续期；此前已发出的请求结果待核对（不代表学校已退座）",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedUnknown)
                        stoppedUnknown
                    }
                    latest.status == "NEEDS_RECONFIRMATION" -> {
                        val stoppedReconfirm = latest.copy(
                            status = "CANCELLED",
                            fireStatus = "CANCELLED",
                            generation = latest.generation + 1,
                            stateVersion = latest.stateVersion + 1,
                            remainingDays = 0,
                            lastDiagnosticMessage = "已取消待确认授权的预约计划",
                            updatedAtEpochMillis = nowMs
                        )
                        cloudAndParcelDao.upsertLibraryBookingPlan(stoppedReconfirm)
                        stoppedReconfirm
                    }
                    else -> latest
                }
            }
        }
        repoScope.launch { runCatching { syncWithCampusCloud() } }
        return updated
    }

    /**
     * 仅执行「停止委托 / 取消排队计划」，绝不根据刷新后的最新状态暗中升级为真实学校退座！
     * 如需释放学校端已锁定的座位，必须由用户在已确认状态下显式触发 [cancelActiveLibrarySeatBooking]。
     */
    suspend fun cancelDualEngineBookingPlan(planId: String) {
        stopInFlightOrQueuedDelegation(planId)
    }

    suspend fun rescheduleActiveLocalBookingPlans() {
        val now = System.currentTimeMillis()
        val activeBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        val activeStudentId = runCatching { preferencesFlow.first().savedStudentId }.getOrNull()
        bookingPlanStateMutex.withLock {
            val all = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
            for (plan in all) {
                // 若该计划正由当前进程内的协程活跃执行（activeInProcessFireAttempts 匹配），绝不误判为崩溃中断！
                val liveAttemptInThisProcess = activeInProcessFireAttempts[plan.id]
                if (liveAttemptInThisProcess != null &&
                    plan.fireStatus == "DISPATCH_INTENT:$liveAttemptInThisProcess"
                ) {
                    continue
                }
                // 崩溃恢复：若进程中断前计划遗留在 EXECUTING 或 DISPATCH_INTENT，立即恢复为 RESULT_UNKNOWN 并取消重复闹钟（完整保留原账号归属与证据）
                if (plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT")) {
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                    cloudAndParcelDao.upsertLibraryBookingPlan(
                        plan.copy(
                            status = "RESULT_UNKNOWN",
                            fireStatus = "RESULT_UNKNOWN",
                            remainingDays = 0,
                            stateVersion = plan.stateVersion + 1,
                            lastDiagnosticMessage = "进程中断恢复：此前抢座请求可能已发出，已转入结果待核对状态（禁止重复提交）",
                            updatedAtEpochMillis = now
                        )
                    )
                    continue
                }
                val belongsToActiveAccount = cloudIdentityRepository.isBookingPlanVisibleToIdentity(
                    plan = plan,
                    activeCloudUserId = activeBinding?.cloudUserId,
                    activeStudentId = activeStudentId
                )
                // 恢复入口与定时入口同规则：CLOUD_HOSTED 绝不本地重排闹钟（schedulePlanAlarms 入口
                // 亦自带拦截），仅撤销旧 pending intent；本机三阶段闹钟仅限非云端计划
                if (belongsToActiveAccount &&
                    plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    plan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                ) {
                    LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, plan)
                } else {
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                }
            }
        }
    }
}
