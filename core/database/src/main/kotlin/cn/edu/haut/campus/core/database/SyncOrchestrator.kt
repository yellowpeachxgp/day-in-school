package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.academic.AcademicReadException
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.CloudSyncedParcelDto
import cn.edu.haut.campus.core.campusservices.DualEngineReservationPlanner
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportCourseCandidate
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.StudentProfile
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sync 拆分期运行时端口。
 * preferencesFlow、测试传输钩子和通知回调仍由兼容外观持有；
 * 编排器逐次现读同一实例，保持搬迁前触发与回连时序。
 */
internal interface SyncOrchestratorRuntimePort {
    val preferencesFlow: Flow<AppPreferencesState>

    fun cloudHttpTransportForTest(): CloudHttpTransportForTest?

    suspend fun notifyScheduleOrPolicyChanged(reason: String)

    fun requireActiveSession()
}

/**
 * 云同步编排器：双向快照同步与学校绑定/云端计划重新确认。
 * 实现从 CampusRepository 兼容门面逐字迁入；cloudSyncMutex 与 bookingPlanStateMutex
 * 仍是门面同一锁实例，身份快照复核、提交屏障锁序与网络/落库时序保持不变。
 */
class SyncOrchestrator internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val appContext: Context,
    private val cloudIdentityRepository: CloudIdentityRepository,
    private val termRepository: TermRepository,
    private val gradesRepository: GradesRepository,
    private val examRepository: ExamRepository,
    private val timetableRepository: TimetableRepository,
    private val academicCredentialVault: AcademicCredentialVault,
    private val academicCredentialMutex: Mutex,
    private val cloudSyncMutex: Mutex,
    private val bookingPlanStateMutex: Mutex,
    private val runtimePort: SyncOrchestratorRuntimePort
) {
    private val database = environment.database
    private val preferences = environment.preferences
    private val dao = environment.dao
    private val cloudAndParcelDao = database.cloudAndParcelDao()

    private val schoolId: String
        get() = environment.accountProfile.schoolId

    private val supportsHautServices: Boolean
        get() = environment.supportsHautServices

    private val timeZone
        get() = environment.timeZone

    /** 惰性读取门面 preferencesFlow（实例固定），保持原读取时点。 */
    private val preferencesFlow: Flow<AppPreferencesState>
        get() = runtimePort.preferencesFlow

    /** 逐次现读门面测试传输钩子；生产恒 null。 */
    private val cloudHttpTransportForTest: CloudHttpTransportForTest?
        get() = runtimePort.cloudHttpTransportForTest()

    /**
     * 仅测试用：同步提交屏障内的挂起闩锁，在身份复核通过之后、首个落库写之前调用。
     * 生产恒为 null（零开销）；存储仍带 Volatile 语义，门面只做 get/set 委托。
     */
    @Volatile
    internal var syncCommitBarrierHookForTest: (suspend () -> Unit)? = null

    /** 保持原门面内联调用路径：身份快照实现归 CloudIdentityRepository。 */
    private suspend fun snapshotCloudIdentitySession(): CampusRepository.CloudIdentitySessionSnapshot? =
        cloudIdentityRepository.snapshotCloudIdentitySession()

    /** 保持原门面内联调用路径：身份世代复核实现归 CloudIdentityRepository。 */
    private suspend fun isCloudIdentitySnapshotCurrent(snapshot: CampusRepository.CloudIdentitySessionSnapshot): Boolean =
        cloudIdentityRepository.isCloudIdentitySnapshotCurrent(snapshot)

    /** 保持原门面内联通知路径。 */
    private suspend fun notifyScheduleOrPolicyChanged(reason: String) =
        runtimePort.notifyScheduleOrPolicyChanged(reason)

    /**
     * 与 https://campus.yellowpeachxgp.com 双向同步：
     * 1. 上推本机学号绑定、快递包裹、已取件状态与已取消的预约任务
     * 2. 拉取 XPeach 最新 🍑 余额、网页端创建的占座预约计划与快递取件码并落库 Room
     */
    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> = withContext(Dispatchers.IO) {
        cloudSyncMutex.withLock {
            runCatching {
                CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                    java.io.File(appContext.filesDir, "cloud_auth_provenance")
                )
                CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                    java.io.File(appContext.filesDir, "cloud_auth_provenance")
                )
                val identitySession = snapshotCloudIdentitySession()
                    ?: throw IllegalStateException("尚未连接 XPeach 通行证")
                val expectedCloudUserId = identitySession.cloudUserId.trim()
                if (expectedCloudUserId.isEmpty()) {
                    throw IllegalStateException("当前 XPeach 通行证标识异常，请重新登录")
                }
                var token = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = false, expectedIdentity = identitySession)
                if (token.isBlank()) {
                    throw IllegalStateException("云端会话已过期，请重新登录 XPeach 通行证")
                }

                val prefsBeforeSync = preferencesFlow.first()
                val (bundleStudentNo, localDelegatedSecret) = cloudIdentityRepository.buildDelegatedCredentialBundle()
                val localStudentNo = bundleStudentNo?.trim()?.takeIf { it.isNotEmpty() }
                    ?: prefsBeforeSync.savedStudentId?.trim()?.takeIf { it.isNotEmpty() }
                val expectedStudentNo = localStudentNo.orEmpty()

                val existingPlansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                val activeCloudPlans = existingPlansSnapshot.filter {
                    it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(it, expectedCloudUserId, expectedStudentNo) &&
                        it.status !in setOf(
                            "CANCELLED",
                            "FAILED",
                            "EXPIRED",
                            "CONFIRMED",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            "SCHOOL_CANCELLED"
                        )
                }
                val hasActiveCloudBookingPlan = existingPlansSnapshot.any {
                    it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(it, expectedCloudUserId, expectedStudentNo) &&
                        it.status !in setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED")
                }
                val effectiveDelegatedSecret = if (hasActiveCloudBookingPlan) localDelegatedSecret else null
                val cloudBaseUrl = identitySession.apiBaseUrl

                val localPlanUpserts = activeCloudPlans.map { plan ->
                    val seats = plan.preferredSeatLabel
                        .split(",", "，", "、", "/", "|", ";", "；")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .ifEmpty { listOf(plan.preferredSeatLabel.trim().ifBlank { "01桌 B座" }) }
                    cn.edu.haut.campus.core.campusservices.CloudUnsyncedPlanUpsertDto(
                        planId = plan.id,
                        targetLocalDate = plan.targetLocalDate,
                        targetLocalTime = plan.targetLocalTime,
                        areaName = plan.preferredAreaName.trim().ifBlank { "五层中区" },
                        candidateSeats = seats,
                        repeatDays = plan.repeatDays.coerceIn(1, 30),
                        remainingDays = plan.remainingDays.coerceIn(1, 30),
                        allowAlternativeSeat = plan.allowAlternativeSeat,
                        studentNo = plan.ownerStudentId?.trim()?.takeIf { it.isNotEmpty() } ?: localStudentNo
                    )
                }

                val localParcels = cloudAndParcelDao.getParcelItemsSnapshot().map { p ->
                    CloudSyncedParcelDto(
                        id = p.id,
                        trackingNumber = p.trackingNumber,
                        carrierCode = p.carrierCode,
                        carrierName = p.carrierName,
                        status = p.status,
                        pickupCode = p.pickupCode,
                        stationName = p.stationName,
                        itemTitle = p.itemTitle,
                        rawNotificationText = p.estimatedDeliveryText
                    )
                }
                val cancelledJobIds = existingPlansSnapshot
                    .filter {
                        it.ownerCloudUserId?.trim() == expectedCloudUserId &&
                            (expectedStudentNo.isEmpty() || it.ownerStudentId.isNullOrBlank() || it.ownerStudentId.trim() == expectedStudentNo) &&
                            it.status in setOf("CANCELLED", "DELEGATION_STOPPED_RESULT_UNKNOWN") &&
                            !it.cloudJobId.isNullOrBlank() &&
                            !it.cloudJobId.startsWith("cjob_")
                    }
                    .mapNotNull { it.cloudJobId?.trim()?.takeIf { id -> id.isNotEmpty() } }

                var snapshot = CampusCloudOidcHelper.syncCloudSnapshot(
                    accessToken = token,
                    studentNo = localStudentNo,
                    delegatedSecret = effectiveDelegatedSecret,
                    localParcels = localParcels,
                    cancelledJobOrPlanIds = cancelledJobIds,
                    localActiveCloudPlans = localPlanUpserts,
                    cloudBaseUrl = cloudBaseUrl,
                    expectedCloudUserId = expectedCloudUserId,
                    httpTransport = cloudHttpTransportForTest
                )
                if (snapshot == null) {
                    token = cloudIdentityRepository.resolveValidCloudAccessToken(
                        forceRefresh = true, failedAccessToken = token, expectedIdentity = identitySession
                    )
                    if (token.isNotBlank()) {
                        snapshot = CampusCloudOidcHelper.syncCloudSnapshot(
                            accessToken = token,
                            studentNo = localStudentNo,
                            delegatedSecret = effectiveDelegatedSecret,
                            localParcels = localParcels,
                            cancelledJobOrPlanIds = cancelledJobIds,
                            localActiveCloudPlans = localPlanUpserts,
                            cloudBaseUrl = cloudBaseUrl,
                            expectedCloudUserId = expectedCloudUserId,
                            httpTransport = cloudHttpTransportForTest
                        )
                    }
                }
                val verifiedSnapshot = snapshot ?: throw IllegalStateException("同步云端数据失败，请稍后重试")
                if (verifiedSnapshot.cloudUserId.trim() != expectedCloudUserId) {
                    throw IllegalStateException("云端同步返回账号与当前绑定账号不一致，已拒绝落库")
                }
                // 世代复核：同步期间解绑/换号/同号重登/换 base-url 一律丢弃整份旧会话响应
                if (!isCloudIdentitySnapshotCurrent(identitySession)) {
                    throw IllegalStateException("云端账号已切换或解绑，已忽略旧会话同步结果")
                }
                val liveStuAfterSync = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (expectedStudentNo.isNotEmpty() && liveStuAfterSync.isNotEmpty() && liveStuAfterSync != expectedStudentNo) {
                    throw IllegalStateException("同步期间学号已切换，已忽略旧学号会话同步结果")
                }
                val now = System.currentTimeMillis()

            // 1) 更新本地 XPeach 通行证与 🍑 余额：身份锁内世代守卫条件 UPDATE（rows=0 即丢弃），
            //    绝不 upsert 旧快照整行覆盖新账号绑定或复活已解绑行
            val bindingWritten = cloudIdentityRepository.writeBackCloudBindingIfCurrent(
                snapshot = identitySession,
                newPeachBalanceMicropeach = kotlin.math.round(verifiedSnapshot.peachBalance * 10_000.0).toLong(),
                newDisplayName = verifiedSnapshot.displayName
            )
            if (!bindingWritten) {
                throw IllegalStateException("云端账号已切换或解绑，已忽略旧会话同步结果")
            }

            // 3) 合并云端图书馆预约任务到本地 Room（严格隔离 ownerCloudUserId + 学号，严禁混并空 owner 旧记录或跨云账号记录）
            var parcelChanged = false
            bookingPlanStateMutex.withLock {
                val liveBindingLocked = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                val liveStuLocked = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (liveBindingLocked == null || liveBindingLocked.cloudUserId.trim() != expectedCloudUserId) {
                    throw IllegalStateException("同步落库前云端账号已切换或解绑，已放弃写入旧会话同步结果")
                }
                // 世代复核（ABA 防护）：cloudUserId 相同也可能已经历"解绑→重绑"，世代不一致即放弃落库
                if (!isCloudIdentitySnapshotCurrent(identitySession)) {
                    throw IllegalStateException("同步落库前云端身份世代已变更，已放弃写入旧会话同步结果")
                }
                if (expectedStudentNo.isNotEmpty() && liveStuLocked.isNotEmpty() && liveStuLocked != expectedStudentNo) {
                    throw IllegalStateException("同步落库前学号已切换，已放弃写入旧学号会话同步结果")
                }
                // 测试闩锁（生产为 null）：确定性覆盖"复核→首写"窗口的身份变更交错
                syncCommitBarrierHookForTest?.invoke()
                    // 2) 合并云端快递包裹到本地 Room — moved INSIDE the identity-checked lock:
                    // a stale-session merge must never commit account-A parcels after
                    // an account switch that the final recheck would reject.
                for (rp in verifiedSnapshot.parcels) {
                    val cleanTracking = rp.trackingNumber.cleanNullLiteral()
                    if (cleanTracking.isBlank()) continue
                    val existing = cloudAndParcelDao.getParcelByTrackingNumber(cleanTracking)
                    val cleanRemotePickup = rp.pickupCode.cleanNullableLiteral()
                    val cleanExistingPickup = existing?.pickupCode.cleanNullableLiteral()
                    val mergedPickup = cleanRemotePickup ?: cleanExistingPickup
                    val mergedStation = rp.stationName.cleanNullableLiteral()
                        ?: existing?.stationName.cleanNullableLiteral()
                        ?: "莲花街北苑菜鸟驿站"
                    val mergedTitle = rp.itemTitle.cleanNullableLiteral()
                        ?: existing?.itemTitle.cleanNullableLiteral()
                    val mergedCarrier = rp.carrierName.cleanNullLiteral(
                        existing?.carrierName.cleanNullLiteral("校园快递")
                    )
                    val mergedStatus = when {
                        existing?.status == ParcelLifecycleStatus.PICKED_UP.wireName || rp.status == ParcelLifecycleStatus.PICKED_UP.wireName ->
                            ParcelLifecycleStatus.PICKED_UP.wireName
                        !mergedPickup.isNullOrBlank() ->
                            ParcelLifecycleStatus.READY_FOR_PICKUP.wireName
                        rp.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName && mergedPickup == null ->
                            ParcelLifecycleStatus.IN_TRANSIT.wireName
                        else -> rp.status.cleanNullLiteral(ParcelLifecycleStatus.IN_TRANSIT.wireName)
                    }
                    if (existing == null ||
                        existing.status != mergedStatus ||
                        existing.pickupCode != mergedPickup ||
                        existing.stationName != mergedStation ||
                        existing.itemTitle != mergedTitle
                    ) {
                        parcelChanged = true
                        cloudAndParcelDao.upsertParcelItem(
                            ParcelItemEntity(
                                id = existing?.id ?: rp.id.cleanNullLiteral("prc_${UUID.randomUUID().toString().replace("-", "").take(12)}"),
                                trackingNumber = cleanTracking,
                                carrierCode = rp.carrierCode.cleanNullLiteral(existing?.carrierCode.cleanNullLiteral("CAINIAO")),
                                carrierName = mergedCarrier,
                                status = mergedStatus,
                                pickupCode = mergedPickup,
                                stationName = mergedStation,
                                itemTitle = mergedTitle,
                                estimatedDeliveryText = when (mergedStatus) {
                                    ParcelLifecycleStatus.PICKED_UP.wireName -> "本人已取件签收"
                                    ParcelLifecycleStatus.READY_FOR_PICKUP.wireName -> "已到达驿站/快递柜，请凭取件码取件"
                                    else -> existing?.estimatedDeliveryText.cleanNullableLiteral() ?: "运输中，预计 1-2 日送达校园驿站"
                                },
                                sourcesJson = existing?.sourcesJson ?: """[{"provider_id":"parcel.cloud-sync","priority":90}]""",
                                fieldProvenanceJson = existing?.fieldProvenanceJson ?: """{"provider_id":"parcel.cloud-sync","updated_at":$now}""",
                                checkpointsJson = existing?.checkpointsJson ?: "[]",
                                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                                updatedAtEpochMillis = now
                            )
                        )
                    }
                }
    
                    val syncedCloudUserId = expectedCloudUserId
                val activeStuNo = expectedStudentNo.ifEmpty { liveStuLocked }.takeIf { it.isNotEmpty() }
                val allExistingInLock = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                val mutableExistingById = allExistingInLock.associateBy { it.id }.toMutableMap()
                val claimedLocalPlanIds = HashSet<String>()
                val authoritativeServerMergedPlanIds = HashSet<String>()

                val incomingJobIds = verifiedSnapshot.jobs.mapNotNull { it.jobId.takeIf { id -> id.isNotBlank() } }.toSet()
                val incomingPlanIds = verifiedSnapshot.jobs.mapNotNull { it.planId.takeIf { id -> id.isNotBlank() } }.toSet()
                val preReservedExactLocalIds = allExistingInLock
                    .filter { row ->
                        row.ownerCloudUserId?.trim() == syncedCloudUserId && (
                            (!row.cloudJobId.isNullOrBlank() && row.cloudJobId in incomingJobIds) ||
                                (row.id in incomingPlanIds)
                            )
                    }
                    .map { it.id }
                    .toSet()

                val terminalRemoteStates = setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED")
                val orderedRemoteJobs = verifiedSnapshot.jobs.sortedWith(
                    compareBy<cn.edu.haut.campus.core.campusservices.CloudSyncedReservationJobDto> { rj ->
                        val hasExact = mutableExistingById.values.any { row ->
                            row.ownerCloudUserId?.trim() == syncedCloudUserId && (
                                (rj.jobId.isNotBlank() && row.cloudJobId == rj.jobId) ||
                                    (rj.planId.isNotBlank() && row.id == rj.planId)
                                )
                        }
                        when {
                            hasExact -> 0
                            rj.state !in terminalRemoteStates && rj.displayStatus !in terminalRemoteStates -> 1
                            else -> 2
                        }
                    }
                )

                for (rj in orderedRemoteJobs) {
                    // 3.1 先按稳定云端 jobId（次选精确 planId）在实时 mutableExistingById 中一对一查找（已认领行严禁二次复用！）
                    val existingByStableJob = mutableExistingById.values.firstOrNull {
                        it.id !in claimedLocalPlanIds &&
                            it.ownerCloudUserId?.trim() == syncedCloudUserId &&
                            rj.jobId.isNotBlank() &&
                            it.cloudJobId == rj.jobId
                    } ?: mutableExistingById.values.firstOrNull {
                        it.id !in claimedLocalPlanIds &&
                            it.ownerCloudUserId?.trim() == syncedCloudUserId &&
                            rj.planId.isNotBlank() &&
                            it.id == rj.planId &&
                            (it.cloudJobId.isNullOrBlank() || it.cloudJobId == rj.jobId)
                    }

                    // 3.2 核定不可变的学校学号归属（existingImmutableStu 优先且不可被新学号篡改；新返回任务仅认服务端/绑定/本机已核验来源，未知归属保持 null 隔离！）
                    val existingImmutableStu = existingByStableJob?.ownerStudentId?.trim()?.takeIf { it.isNotEmpty() }
                    val localProvenanceStu = listOfNotNull(
                        existingByStableJob?.id,
                        rj.planId.takeIf { it.isNotBlank() }
                    ).firstNotNullOfOrNull { pid ->
                        CampusCloudOidcHelper.resolveScopedPlanProvenanceStudentNo(
                            cloudBaseUrl = cloudBaseUrl,
                            accessToken = token,
                            planId = pid,
                            expectedBindingId = rj.schoolBindingId
                        )
                    }
                    val verifiedRemoteStu = rj.studentNo?.trim()?.takeIf { it.isNotEmpty() && !it.contains("***") }
                        ?: localProvenanceStu
                    if (existingImmutableStu != null &&
                        verifiedRemoteStu != null &&
                        verifiedRemoteStu != existingImmutableStu
                    ) {
                        // 同一 jobId 的学号归属发生矛盾：拒绝覆盖原有学校身份归属
                        continue
                    }
                    val resolvedJobStudentId: String? = existingImmutableStu ?: verifiedRemoteStu

                    // 3.3 一对一认领同日未绑定（cloudJobId 为空）本地计划：
                    //     - 已被前序 job 认领或已被精确 jobId/planId 预留的本地行绝不重复匹配；
                    //     - 远端已取消/失败/过期的历史 job 绝不抢占本地排队中的 ARMED 待绑定行；
                    //     - 同日存在多个待绑定本地行时按目标时间、区域、座位相似度最佳一对一配对。
                    val rjIsTerminal = rj.state in terminalRemoteStates || rj.displayStatus in terminalRemoteStates
                    val matchingLocal = existingByStableJob ?: if (resolvedJobStudentId != null) {
                        val unboundCandidates = mutableExistingById.values.filter { cand ->
                            cand.id !in claimedLocalPlanIds &&
                                cand.id !in preReservedExactLocalIds &&
                                cand.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                                cand.ownerCloudUserId?.trim() == syncedCloudUserId &&
                                cand.ownerStudentId?.trim() == resolvedJobStudentId &&
                                cand.cloudJobId.isNullOrBlank() &&
                                cand.targetLocalDate == rj.targetLocalDate &&
                                if (rjIsTerminal) {
                                    cand.status in terminalRemoteStates
                                } else {
                                    cand.status !in terminalRemoteStates
                                }
                        }
                        unboundCandidates.maxWithOrNull(
                            compareBy<LibraryBookingPlanEntity> { cand ->
                                var score = 0
                                if (cand.targetLocalTime == rj.targetLocalTime) score += 8
                                if (cand.preferredAreaName.trim() == rj.areaName.trim()) score += 4
                                if (rj.candidateSeats.any { seat -> cand.preferredSeatLabel.contains(seat) }) score += 2
                                score
                            }.thenBy { -it.createdAtEpochMillis }
                        )
                    } else {
                        null
                    }
                    // 若本地任务当前正处于本进程或已领取的 EXECUTING/DISPATCH_INTENT、或本地已请求取消而云端快照尚停留在排队态，拒绝被旧排队状态回退覆盖！
                    if (matchingLocal != null) {
                        val localIsExecuting =
                            matchingLocal.status == "EXECUTING" || matchingLocal.fireStatus.startsWith("DISPATCH_INTENT")
                        if (localIsExecuting && rj.state in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                            continue
                        }
                        if (matchingLocal.status in setOf("CANCELLED", "CANCEL_RESULT_UNKNOWN", "SCHOOL_CANCELLED") &&
                            rj.state in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                        ) {
                            continue
                        }
                    }
                    val matchingOrder = verifiedSnapshot.orders.firstOrNull { it.jobId == rj.jobId }
                    val cleanAreaName = (rj.bookedRegionAlias ?: rj.areaName).cleanNullLiteral(
                        matchingLocal?.preferredAreaName.cleanNullLiteral("五层中区")
                    )
                    val candidateSeatsLabel = rj.candidateSeats
                        .map { it.cleanNullLiteral() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                        .ifBlank { matchingLocal?.preferredSeatLabel.cleanNullLiteral("01桌 B座") }
                    val primarySeat = rj.bookedSeatAlias?.cleanNullableLiteral()
                        ?: rj.candidateSeats.firstOrNull { it.cleanNullLiteral().isNotEmpty() }
                        ?: matchingLocal?.preferredSeatLabel.cleanNullableLiteral()
                        ?: "001"
                    val parsedDate = runCatching { LocalDate.parse(rj.targetLocalDate) }.getOrNull() ?: LocalDate.now().plusDays(1)
                    val parsedTime = runCatching { DualEngineReservationPlanner.parseTargetLocalTime(rj.targetLocalTime) }.getOrNull() ?: LocalTime.of(7, 0)
                    val fireEpochMs = parsedDate.atTime(parsedTime).atZone(DualEngineReservationPlanner.HAUT_ZONE).toInstant().toEpochMilli()
                    val effRepeatDays = rj.repeatDays.coerceAtLeast(matchingLocal?.repeatDays ?: 1)
                    val hasCompleteSchoolConfirmation =
                        !rj.externalBookingId.cleanNullableLiteral().isNullOrBlank() &&
                            !rj.bookedSeatAlias.cleanNullableLiteral().isNullOrBlank() &&
                            !rj.bookedRegionAlias.cleanNullableLiteral().isNullOrBlank() &&
                            !rj.evidenceSha256.cleanNullableLiteral().isNullOrBlank()
                    val rawEffectiveStatus = when {
                        rj.displayStatus == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ||
                            rj.state == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" -> "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                        rj.displayStatus == "DELEGATION_STOPPED_RESULT_UNKNOWN" -> "DELEGATION_STOPPED_RESULT_UNKNOWN"
                        rj.displayStatus == "SCHOOL_CANCELLED" || rj.state == "SCHOOL_CANCELLED" -> "SCHOOL_CANCELLED"
                        rj.displayStatus == "CANCEL_RESULT_UNKNOWN" || rj.state == "CANCEL_RESULT_UNKNOWN" -> "CANCEL_RESULT_UNKNOWN"
                        rj.displayStatus == "NEEDS_RECONFIRMATION" -> "NEEDS_RECONFIRMATION"
                        else -> rj.state
                    }
                    val effectiveStatus = if (
                        rawEffectiveStatus in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") &&
                        !hasCompleteSchoolConfirmation
                    ) {
                        if (rawEffectiveStatus == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ||
                            rj.userIntentState in setOf("REVOKED", "STOP_REQUESTED")
                        ) {
                            "DELEGATION_STOPPED_RESULT_UNKNOWN"
                        } else {
                            "RESULT_UNKNOWN"
                        }
                    } else {
                        rawEffectiveStatus
                    }
                    val effRemainingDays = if (effectiveStatus in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")) {
                        rj.remainingDays.coerceAtLeast(1)
                    } else {
                        0
                    }
                    val repeatDiagSuffix = if (effRepeatDays > 1 && effRemainingDays > 0) {
                        "（连续 $effRepeatDays 天 · 剩余 $effRemainingDays 天）"
                    } else {
                        ""
                    }
                    val diagText = when (effectiveStatus) {
                        "CONFIRMED" -> "云端抢座已成功为您锁定 $cleanAreaName $primarySeat，请按时到馆刷脸签到$repeatDiagSuffix"
                        "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ->
                            "委托已停止，但学校预约已成功（座位：$cleanAreaName $primarySeat，预约号：${rj.externalBookingId ?: "已生成"}）。系统未扣费且已停止后续计划，请核对或按需退座"
                        "DELEGATION_STOPPED_RESULT_UNKNOWN" ->
                            "委托已停止，但此前已发出的学校预约请求结果待核对（禁止盲目重试）"
                        "RESULT_UNKNOWN" ->
                            "结果待核对：云端已发出选座请求，待回查核实学校端状态（核实前不重试、不扣费）"
                        "SCHOOL_CANCELLED" -> "学校端已确认取消该笔座位预约并释放座位"
                        "CANCEL_RESULT_UNKNOWN" -> "退座结果待核对：取消请求已发出，正在等待学校端确认"
                        "NEEDS_RECONFIRMATION" -> "需要重新确认授权：请点击「重新确认授权」以恢复云端同步"
                        "CANCELLED" -> "已停止委托（未发起学校预约写入，本次未扣费）"
                        "FAILED" -> "目标座位已被抢满，本次未扣费$repeatDiagSuffix"
                        "EXPIRED" -> "已过开抢有效期窗口（本次未扣费）"
                        else -> "云端抢座已就绪：将在 ${rj.targetLocalDate} ${rj.targetLocalTime} 准点自动抢座$repeatDiagSuffix"
                    }
                    val isConfirmedOnSchool =
                        effectiveStatus == "CONFIRMED" || effectiveStatus == "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
                    val candidatePlanId = "plan_${rj.jobId.ifBlank { rj.planId }.replace("-", "").take(14)}"
                    val resolvedPlanId = if (matchingLocal != null) {
                        matchingLocal.id
                    } else {
                        val occupant = mutableExistingById[candidatePlanId]
                        val canReuseCandidateId = occupant == null && candidatePlanId !in claimedLocalPlanIds
                        if (canReuseCandidateId) {
                            candidatePlanId
                        } else if (
                            occupant != null &&
                            occupant.id !in claimedLocalPlanIds &&
                            occupant.ownerCloudUserId?.trim() == syncedCloudUserId &&
                            !resolvedJobStudentId.isNullOrBlank() &&
                            occupant.ownerStudentId?.trim() == resolvedJobStudentId &&
                            rj.jobId.isNotBlank() &&
                            occupant.cloudJobId == rj.jobId
                        ) {
                            candidatePlanId
                        } else {
                            val stuTag = (resolvedJobStudentId ?: "unscoped")
                                .replace(Regex("[^A-Za-z0-9]"), "")
                                .takeLast(6)
                                .ifEmpty { "unscoped" }
                            val rawKey = "${syncedCloudUserId}|${resolvedJobStudentId.orEmpty()}|${rj.jobId.ifBlank { rj.planId }}"
                            val shaHex = java.security.MessageDigest.getInstance("SHA-256")
                                .digest(rawKey.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it) }
                                .take(10)
                            "${candidatePlanId}_${stuTag}_$shaHex"
                        }
                    }
                    claimedLocalPlanIds += resolvedPlanId
                    if (effectiveStatus != "NEEDS_RECONFIRMATION") {
                        authoritativeServerMergedPlanIds += resolvedPlanId
                    }
                    val mergedPlan = LibraryBookingPlanEntity(
                        id = resolvedPlanId,
                        schoolId = schoolId,
                        executionMode = BookingExecutionMode.CLOUD_HOSTED.wireName,
                        targetLocalDate = rj.targetLocalDate,
                        targetLocalTime = rj.targetLocalTime,
                        timezoneId = DualEngineReservationPlanner.HAUT_ZONE.id,
                        precheckAtEpochMillis = fireEpochMs - 1800_000L,
                        finalCheckAtEpochMillis = fireEpochMs - 300_000L,
                        fireAtEpochMillis = fireEpochMs,
                        precheckStatus = if (isConfirmedOnSchool) "PRECHECK_OK" else (matchingLocal?.precheckStatus ?: "ARMED"),
                        finalCheckStatus = if (isConfirmedOnSchool) "FINAL_CHECK_OK" else (matchingLocal?.finalCheckStatus ?: "ARMED"),
                        fireStatus = if (isConfirmedOnSchool) effectiveStatus else effectiveStatus,
                        preferredAreaName = cleanAreaName,
                        preferredSeatLabel = rj.bookedSeatAlias?.cleanNullableLiteral() ?: candidateSeatsLabel,
                        allowAlternativeSeat = matchingLocal?.allowAlternativeSeat ?: (rj.candidateSeats.size > 1),
                        cloudJobId = rj.jobId.cleanNullableLiteral(),
                        externalBookingId = when {
                            isConfirmedOnSchool -> rj.externalBookingId.cleanNullableLiteral()
                            effectiveStatus == "CANCEL_RESULT_UNKNOWN" ->
                                rj.externalBookingId.cleanNullableLiteral() ?: matchingLocal?.externalBookingId.cleanNullableLiteral()
                            else -> null
                        },
                        evidenceSha256 = if (isConfirmedOnSchool) rj.evidenceSha256.cleanNullableLiteral() else null,
                        // 对账合并一律沿用本地记录的原成交价（用户创建时确认价）；
                        // 无本地行时回退服务端订单实价，绝不按今日全局价覆盖历史记录
                        priceMicropeach = matchingLocal?.priceMicropeach
                            ?: matchingOrder?.pricePeach?.let { kotlin.math.round(it * 10_000.0).toLong() }
                            ?: 10_000L,
                        paymentState = when {
                            effectiveStatus in setOf("DELEGATION_STOPPED_SCHOOL_CONFIRMED", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") ->
                                "HELD_PENDING_REVIEW"
                            matchingOrder != null -> matchingOrder.paymentState
                            effectiveStatus == "CONFIRMED" -> "PAID"
                            else -> "DEBIT_PENDING"
                        },
                        status = effectiveStatus,
                        stateVersion = (matchingLocal?.stateVersion ?: 0) + 1,
                        generation = matchingLocal?.generation ?: 1,
                        lastDiagnosticMessage = diagText,
                        createdAtEpochMillis = matchingLocal?.createdAtEpochMillis ?: now,
                        updatedAtEpochMillis = now,
                        repeatDays = effRepeatDays,
                        remainingDays = effRemainingDays,
                        ownerCloudUserId = syncedCloudUserId,
                        ownerStudentId = resolvedJobStudentId
                    )
                    cloudAndParcelDao.upsertLibraryBookingPlan(mergedPlan)
                    mutableExistingById[mergedPlan.id] = mergedPlan
                    val belongsToCurrentStudentForAlarm =
                        activeStuNo == null || (!resolvedJobStudentId.isNullOrBlank() && resolvedJobStudentId == activeStuNo)
                    if (belongsToCurrentStudentForAlarm &&
                        mergedPlan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName &&
                        mergedPlan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
                    ) {
                        LibrarySeatAlarmSubsystem.schedulePlanAlarms(appContext, mergedPlan)
                    } else {
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, mergedPlan.id)
                    }
                }

                // 4) 对因新授权世代未确认而被安全阻断上推的当前账号本地计划，显式标记为 NEEDS_RECONFIRMATION 并取消闹钟（绝不改动其他账号保留记录！）
                if (verifiedSnapshot.needsReconfirmationPlanIds.isNotEmpty() ||
                    verifiedSnapshot.needsReconfirmationBindingStudentNos.isNotEmpty()
                ) {
                    val reconfirmSet = verifiedSnapshot.needsReconfirmationPlanIds.toSet()
                    val latestLocalPlans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                    for (lp in latestLocalPlans) {
                        if (lp.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                            lp.id !in authoritativeServerMergedPlanIds &&
                            cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(lp, syncedCloudUserId, activeStuNo.orEmpty()) &&
                            (lp.id in reconfirmSet ||
                                (verifiedSnapshot.needsReconfirmationBindingStudentNos.isNotEmpty() &&
                                    lp.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")))
                        ) {
                            LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, lp.id)
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                lp.copy(
                                    status = "NEEDS_RECONFIRMATION",
                                    stateVersion = lp.stateVersion + 1,
                                    lastDiagnosticMessage = "需要重新确认授权：云端授权世代已变更，请点击「重新确认授权并恢复同步」后继续云端托管抢座",
                                    updatedAtEpochMillis = now
                                )
                            )
                        }
                    }
                }
            }

            val rawCampusContent = verifiedSnapshot.campusContentRawJson
            if (!rawCampusContent.isNullOrBlank()) {
                preferences.edit { prefs ->
                    prefs[CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_JSON] = rawCampusContent
                    prefs[CampusRepositoryPrefKeys.CLOUD_CAMPUS_CONTENT_SYNCED_MS] = now
                }
            }

            if (parcelChanged) {
                notifyScheduleOrPolicyChanged("PARCEL_CLOUD_SYNCED")
            }
            verifiedSnapshot
            }
        }
    }

    /**
     * 显式重新确认当前学校绑定与本地待确认的云端预约计划，在新授权世代下恢复同步
     */
    suspend fun reconfirmAndRestoreCloudBookingPlans(): Result<CloudSyncSnapshotResult> = withContext(Dispatchers.IO) {
        runCatching {
            CampusCloudOidcHelper.configurePersistentAuthStorageDir(
                java.io.File(appContext.filesDir, "cloud_auth_provenance")
            )
            val bindingAtStart = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                ?: throw IllegalStateException("尚未连接 XPeach 通行证")
            val expectedCloudUserId = bindingAtStart.cloudUserId.trim()
            if (expectedCloudUserId.isEmpty()) {
                throw IllegalStateException("当前 XPeach 通行证标识异常，请重新登录")
            }
            val token = cloudIdentityRepository.resolveValidCloudAccessToken(forceRefresh = false)
            if (token.isBlank()) {
                throw IllegalStateException("云端会话已过期，请重新登录 XPeach 通行证")
            }
            val (localStudentNo, localDelegatedSecret) = cloudIdentityRepository.buildDelegatedCredentialBundle()
            val prefsSnap = preferencesFlow.first()
            val studentNo = (localStudentNo ?: prefsSnap.savedStudentId.orEmpty()).trim()
            if (studentNo.isBlank()) {
                throw IllegalStateException("请先绑定并登录学校图书馆或教务账号")
            }
            val cloudBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                prefsSnap.networkProxyConfig.customCloudApiBaseUrl
            )
            val plansSnapshot = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
            val matchingPlans = plansSnapshot.filter {
                it.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                    cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(it, expectedCloudUserId, studentNo) &&
                    it.status in setOf("NEEDS_RECONFIRMATION", "ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")
            }
            val expectedStateVersionsById = matchingPlans.associate { it.id to it.stateVersion }
            val plansToReconfirm = matchingPlans.map { plan ->
                val seats = plan.preferredSeatLabel
                    .split(",", "，", "、", "/", "|", ";", "；")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf(plan.preferredSeatLabel.trim().ifBlank { "01桌 B座" }) }
                cn.edu.haut.campus.core.campusservices.CloudUnsyncedPlanUpsertDto(
                    planId = plan.id,
                    targetLocalDate = plan.targetLocalDate,
                    targetLocalTime = plan.targetLocalTime,
                    areaName = plan.preferredAreaName.trim().ifBlank { "五层中区" },
                    candidateSeats = seats,
                    repeatDays = plan.repeatDays.coerceIn(1, 30),
                    remainingDays = plan.remainingDays.coerceIn(1, 30),
                    allowAlternativeSeat = plan.allowAlternativeSeat,
                    studentNo = studentNo
                )
            }
            val reconfirmed = CampusCloudOidcHelper.reconfirmSchoolBindingAndPlans(
                accessToken = token,
                studentNo = studentNo,
                delegatedSecret = localDelegatedSecret.orEmpty(),
                plansToReconfirm = plansToReconfirm,
                cloudBaseUrl = cloudBaseUrl,
                httpTransport = cloudHttpTransportForTest
            )
            if (!reconfirmed) {
                throw IllegalStateException("重新确认学校授权失败，请检查网络连接后重试")
            }
            val now = System.currentTimeMillis()
            bookingPlanStateMutex.withLock {
                val liveBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
                val liveStu = (cloudIdentityRepository.buildDelegatedCredentialBundle().first ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                if (liveBinding == null || liveBinding.cloudUserId.trim() != expectedCloudUserId || liveStu != studentNo) {
                    throw IllegalStateException("重新确认授权期间账号或学号已切换，已停止恢复旧会话计划")
                }
                database.withTransaction {
                    for ((planId, expectedVer) in expectedStateVersionsById) {
                        val currentRow = cloudAndParcelDao.getLibraryBookingPlanById(planId) ?: continue
                        if (currentRow.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName &&
                            cloudIdentityRepository.doesCloudPlanBelongToActiveAccount(currentRow, expectedCloudUserId, studentNo) &&
                            currentRow.status == "NEEDS_RECONFIRMATION" &&
                            currentRow.stateVersion == expectedVer
                        ) {
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                currentRow.copy(
                                    status = "ARMED",
                                    stateVersion = currentRow.stateVersion + 1,
                                    ownerCloudUserId = expectedCloudUserId,
                                    ownerStudentId = currentRow.ownerStudentId?.takeIf { it.isNotBlank() } ?: studentNo,
                                    lastDiagnosticMessage = "已重新确认云端授权，正在同步恢复云端托管抢座",
                                    updatedAtEpochMillis = now
                                )
                            )
                        }
                    }
                }
            }
            syncWithCampusCloud().getOrThrow()
        }
    }


    /** 教务同步编排仍使用 facade 同一会话门禁；由 runtimePort 原位回连。 */
    private fun requireActiveSession() = runtimePort.requireActiveSession()

    private suspend fun ensureSeasonalTermRolloverIfNeeded(today: LocalDate = LocalDate.now(timeZone)) =
        termRepository.ensureSeasonalTermRolloverIfNeeded(today)

    private suspend fun saveStudentProfile(profile: StudentProfile) =
        gradesRepository.saveStudentProfile(profile)

    private suspend fun saveImportedGrades(
        attempts: List<GradeAttempt>,
        officialGpa: BigDecimal? = null,
        officialNote: String? = null,
        replaceExisting: Boolean = false
    ) = gradesRepository.saveImportedGrades(
        attempts = attempts,
        officialGpa = officialGpa,
        officialNote = officialNote,
        replaceExisting = replaceExisting
    )

    private suspend fun saveImportedExams(
        exams: List<ExamScheduleItem>,
        replaceExisting: Boolean = true,
        targetTermId: String? = null,
        targetTermComplete: Boolean = true,
        completedTermIds: Set<String> = emptySet()
    ) = examRepository.saveImportedExams(
        exams = exams,
        replaceExisting = replaceExisting,
        targetTermId = targetTermId,
        targetTermComplete = targetTermComplete,
        completedTermIds = completedTermIds
    )

    private suspend fun ensureTermExistsAndUpdateCalendar(
        termId: String,
        semesterStart: LocalDate? = null,
        totalWeeks: Int? = null,
        setCurrent: Boolean = false
    ) = termRepository.ensureTermExistsAndUpdateCalendar(
        termId = termId,
        semesterStart = semesterStart,
        totalWeeks = totalWeeks,
        setCurrent = setCurrent
    )

    private suspend fun previewCourseImportDiff(
        termId: String,
        source: CourseSource,
        candidates: List<ImportCourseCandidate>
    ): ImportDiffPreview = timetableRepository.previewCourseImportDiff(
        termId = termId,
        source = source,
        candidates = candidates
    )

    private suspend fun commitCourseImportDiff(
        preview: ImportDiffPreview,
        overwriteUserConflicts: Boolean = false,
        setAsCurrentTerm: Boolean = true
    ) = timetableRepository.commitCourseImportDiff(
        preview = preview,
        overwriteUserConflicts = overwriteUserConflicts,
        setAsCurrentTerm = setAsCurrentTerm
    )

    /** 过渡兼容缝：Wiper 与 CampusServices runtimePort 在后续批次迁出前仍复用同一作用域构造。 */
    internal fun academicCredentialScopeForFacade(accountId: String): AcademicCredentialScope =
        academicCredentialScope(accountId)

    /** 过渡兼容缝：facade 初始化与既有 runtimePort 原位进入同一 legacy 迁移实现。 */
    internal suspend fun migrateLegacyAcademicCredentialsIfNeededForFacade() =
        migrateLegacyAcademicCredentialsIfNeeded()

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        preferences.edit { it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = enabled }
    }

    suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ) = withContext(Dispatchers.IO) {
        requireActiveSession()
        check(supportsHautServices) { "当前学校教务登录尚未适配，可使用离线导入" }
        academicCredentialMutex.withLock {
            val previousId = preferences.data.first()[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            val accountId = UUID.randomUUID().toString()
            val scope = academicCredentialScope(accountId)
            try {
                val previousStudentId = previousId?.let { prevAccId ->
                    (academicCredentialVault.read(academicCredentialScope(prevAccId)) as? AcademicCredentialReadResult.Available)
                        ?.credentials?.use { it.studentId }
                }?.trim().orEmpty()
                academicCredentialVault.save(scope, studentId, passwordPlain)
                preferences.edit { prefs ->
                    prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] = accountId
                    prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = false
                    prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                    prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                    prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = xnm
                    prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = xqm
                    prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
                }
                val nextStudentId = studentId.trim()
                if (previousStudentId.isNotEmpty() && previousStudentId != nextStudentId) {
                    cloudIdentityRepository.isolateBookingPlansForStudentSwitch(previousStudentId, System.currentTimeMillis())
                }
            } catch (error: Exception) {
                runCatching { academicCredentialVault.delete(scope) }
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw IllegalStateException("教务凭据无法安全保存，请重新登录；本地课表和成绩已保留")
            }
            previousId?.let { runCatching { academicCredentialVault.delete(academicCredentialScope(it)) } }
        }
    }

    private fun academicCredentialScope(accountId: String) = AcademicCredentialScope(
        schoolId = schoolId,
        accountId = accountId,
        providerId = if (supportsHautServices) "haut-zf-modern" else "offline"
    )

    /** 先保留稳定迁移引用；AES-GCM 密文回读成功后，在同一 DataStore 写入中删除旧学号和 Base64。 */
    private suspend fun migrateLegacyAcademicCredentialsIfNeeded() = withContext(Dispatchers.IO) {
        val current = preferences.data.first()
        if (current[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64] == null &&
            current[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID] == null
        ) {
            return@withContext
        }
        academicCredentialMutex.withLock {
            val original = preferences.data.first()
            if (original[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64] == null &&
                original[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID] == null
            ) {
                return@withLock
            }
            val accountId = original[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: UUID.randomUUID().toString()
            preferences.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] = accountId
            }
            val migrated = migrateLegacyAcademicCredentials(
                vault = academicCredentialVault,
                scope = academicCredentialScope(accountId),
                studentId = original[CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID],
                encodedPassword = original[CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64]
            )
            preferences.edit { prefs ->
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_STUDENT_ID)
                prefs.remove(CampusRepositoryPrefKeys.LEGACY_SAVED_PASSWORD_BASE64)
                prefs[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = !migrated
                if (!migrated) prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
            }
        }
    }

    /**
     * 应用全部实时教务同步数据（学籍名片 + 课表差异合并 + 成绩明细 + 期初/期中/期末全部考试安排）。
     */
    suspend fun applyLiveSyncData(
        syncData: HautLiveSyncResult,
        overwriteUserConflicts: Boolean = false
    ) {
        requireActiveSession()
        check(supportsHautServices) { "当前学校不接受工大教务同步数据" }
        syncData.studentProfile?.let { profile ->
            saveStudentProfile(profile)
        }
        if (syncData.gradesComplete) {
            saveImportedGrades(
                attempts = syncData.gradeAttempts,
                replaceExisting = true
            )
        }
        if (syncData.examsComplete || syncData.targetTermExamsComplete) {
            saveImportedExams(
                exams = syncData.examItems,
                replaceExisting = true,
                targetTermId = syncData.termId,
                targetTermComplete = syncData.targetTermExamsComplete,
                completedTermIds = syncData.completedExamTermIds
            )
        }
        for ((sibTermId, sibCandidates) in syncData.additionalTermTimetables) {
            if (sibTermId == syncData.termId) continue
            val sibCal = syncData.additionalTermCalendars[sibTermId]
            ensureTermExistsAndUpdateCalendar(
                termId = sibTermId,
                semesterStart = sibCal?.first,
                totalWeeks = sibCal?.second,
                setCurrent = false
            )
            val sibDiff = previewCourseImportDiff(
                termId = sibTermId,
                source = CourseSource.OFFICIAL_JW,
                candidates = sibCandidates
            )
            commitCourseImportDiff(
                preview = sibDiff,
                overwriteUserConflicts = overwriteUserConflicts,
                setAsCurrentTerm = false
            )
        }
        val targetTermId = syncData.termId
        environment.academicEntityWritePort.writeMany(
            refsProvider = {
                if (!overwriteUserConflicts) {
                    emptyList()
                } else {
                    dao.getCoursesSnapshot()
                        .filter {
                            it.source == CourseSource.OFFICIAL_JW.name &&
                                it.externalId?.startsWith("sjk:$targetTermId:") == true
                        }
                        .map { environment.academicRef(EntityType.COURSE, it.id) }
                }
            },
            actor = Actor.SYNC,
            actorDetail = "sync-practical-course-cleanup",
            writeGroupId = "sync-practical-course-cleanup:" + UUID.randomUUID().toString()
        ) {
            database.withTransaction {
            val allRules = dao.getRulesSnapshot()
            val allCourses = dao.getCoursesSnapshot()
            if (overwriteUserConflicts) {
                val oldPracticalCourses = allCourses.filter {
                    it.source == CourseSource.OFFICIAL_JW.name &&
                        it.externalId?.startsWith("sjk:$targetTermId:") == true
                }
                val oldPracticalCourseIds = oldPracticalCourses.map { it.id }.toSet()
                val oldPracticalRules = allRules.filter {
                    it.termId == targetTermId && (it.id.startsWith("r_prac_") || it.courseId in oldPracticalCourseIds)
                }
                oldPracticalRules.forEach { r ->
                    dao.deleteRuleById(r.id)
                    dao.deleteOccurrenceOverridesByRuleId(r.id)
                }
                oldPracticalCourses.forEach { c ->
                    dao.deleteCourseById(c.id)
                }
            }
            val scheduledUserCourseIds = dao.getRulesSnapshot()
                .filter { it.termId == targetTermId && it.userModified }
                .map { it.courseId }
                .toSet()
            val scheduledPracticalExternalIds = dao.getCoursesSnapshot()
                .filter {
                    it.id in scheduledUserCourseIds &&
                        it.source == CourseSource.OFFICIAL_JW.name &&
                        it.userModified &&
                        it.externalId?.startsWith("sjk:$targetTermId:") == true
                }
                .mapNotNull { it.externalId }
                .toSet()
            val incomingUnscheduledEntities = syncData.unscheduledCourses.toUnscheduledEntities(targetTermId)
            val remainingUnscheduledEntities = if (overwriteUserConflicts) {
                incomingUnscheduledEntities
            } else {
                incomingUnscheduledEntities.filter {
                    "sjk:$targetTermId:${it.sourceKey}" !in scheduledPracticalExternalIds
                }
            }
            dao.clearUnscheduledCoursesForTerm(targetTermId)
            dao.upsertUnscheduledCourses(remainingUnscheduledEntities)
            }
        }
        val seasonalTermId = AcademicTermResolver.defaultTermId(LocalDate.now(timeZone))
        val targetHasAnyAcademicContent = syncData.timetableCandidates.isNotEmpty() ||
            syncData.unscheduledCourses.isNotEmpty() ||
            syncData.examItems.any { it.termId == targetTermId } ||
            targetTermId == seasonalTermId
        val fallbackPopulatedSiblingTermId = if (!targetHasAnyAcademicContent) {
            syncData.additionalTermTimetables.entries.firstOrNull { (sibId, cands) ->
                sibId != targetTermId && cands.isNotEmpty() && !termRepository.isTermOlderThan(sibId, targetTermId)
            }?.key
        } else null
        val activateTargetTerm = fallbackPopulatedSiblingTermId == null

        ensureTermExistsAndUpdateCalendar(
            termId = targetTermId,
            semesterStart = syncData.semesterStart,
            totalWeeks = syncData.totalWeeks,
            setCurrent = activateTargetTerm
        )
        val diff = previewCourseImportDiff(
            termId = targetTermId,
            source = CourseSource.OFFICIAL_JW,
            candidates = syncData.timetableCandidates
        )
        commitCourseImportDiff(
            preview = diff,
            overwriteUserConflicts = overwriteUserConflicts,
            setAsCurrentTerm = activateTargetTerm
        )
        if (fallbackPopulatedSiblingTermId != null) {
            ensureTermExistsAndUpdateCalendar(termId = fallbackPopulatedSiblingTermId, setCurrent = true)
        }
        val (syncXnm, syncXqm) = HautAcademicEndpoints.fromTermId(targetTermId)
        preferences.edit { prefs ->
            prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] = syncXnm
            prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] = syncXqm
            prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] = System.currentTimeMillis()
        }
    }

    /**
     * 后台或一键动态刷新：使用本地已授权保存的教务凭据，从正方教务系统拉取最新课表、期初/期中/期末考试安排与成绩。
     * @param force 若为 false，则距离上次自动同步不足 15 分钟时跳过，避免频繁请求。
     */
    suspend fun performDynamicAcademicSync(force: Boolean = false): Result<HautLiveSyncResult>? = withContext(Dispatchers.IO) {
        requireActiveSession()
        if (!supportsHautServices) return@withContext Result.failure(IllegalStateException("当前学校教务登录尚未适配，可使用离线导入"))
        migrateLegacyAcademicCredentialsIfNeeded()
        ensureSeasonalTermRolloverIfNeeded()
        academicCredentialMutex.withLock {
        val prefs = preferences.data.first()
        val autoEnabled = prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] ?: true
        if (!force && !autoEnabled) return@withLock null

        val lastSyncMs = prefs[CampusRepositoryPrefKeys.LAST_AUTO_SYNC_MS] ?: 0L
        val nowMs = System.currentTimeMillis()
        if (!force && (nowMs - lastSyncMs) < 15 * 60 * 1000L) {
            return@withLock null
        }

        val accountId = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
        val credentialResult = academicCredentialVault.read(academicCredentialScope(accountId))
        if (credentialResult !is AcademicCredentialReadResult.Available) {
            preferences.edit {
                it[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = true
                it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
            }
            return@withLock Result.failure(IllegalStateException("安全凭据已失效，请重新登录；本地课表和成绩已保留"))
        }

        val currentTermEntity = dao.getCurrentTerm()
        val defaultPair = currentTermEntity?.id?.let { HautAcademicEndpoints.fromTermId(it) }
            ?: (AcademicTermResolver.defaultAcademicYearCode() to AcademicTermResolver.defaultSemesterCode())
        val xnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] ?: defaultPair.first
        val xqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] ?: defaultPair.second

        credentialResult.credentials.use { credentials ->
            val result = HautLiveAcademicClient.fetchLiveAcademicData(
                studentId = credentials.studentId,
                passwordPlain = credentials.passwordPlain,
                xnm = xnm,
                xqm = xqm
            )
            val failure = result.exceptionOrNull()
            if (failure is AcademicReadException && failure.code in setOf(
                    "AUTH_REJECTED", "CAPTCHA_REQUIRED", "SESSION_EXPIRED", "MANUAL_VERIFICATION"
                )) {
                preferences.edit {
                    it[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED] = false
                    it[CampusRepositoryPrefKeys.ACADEMIC_REAUTH_REQUIRED] = true
                }
            }
            result.fold(
                onSuccess = { syncData ->
                    try {
                        applyLiveSyncData(syncData, overwriteUserConflicts = false)
                        Result.success(syncData)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Result.failure(IllegalStateException("教务数据保存失败，请稍后重试；已保留尚未完成更新的数据"))
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Result.failure(error)
                }
            )
        }
        }
    }

}
