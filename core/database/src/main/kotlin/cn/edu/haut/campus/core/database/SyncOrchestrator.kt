package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.CloudSyncedParcelDto
import cn.edu.haut.campus.core.campusservices.DualEngineReservationPlanner
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import cn.edu.haut.campus.core.model.NetworkProxyConfig
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
    private val cloudSyncMutex: Mutex,
    private val bookingPlanStateMutex: Mutex,
    private val runtimePort: SyncOrchestratorRuntimePort
) {
    private val database = environment.database
    private val preferences = environment.preferences
    private val cloudAndParcelDao = database.cloudAndParcelDao()

    private val schoolId: String
        get() = environment.accountProfile.schoolId

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

}
