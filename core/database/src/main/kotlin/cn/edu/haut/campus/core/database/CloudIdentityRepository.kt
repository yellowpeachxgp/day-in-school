package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.CloudSyncSnapshotResult
import cn.edu.haut.campus.core.campusservices.CloudTokenSecureVault
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** W4-12 窄令牌端口：仅供 E2EE 同步 HTTPS client 当前请求使用；不得持久化、写日志或暴露给插件。 */
fun interface CloudIdentityE2eeAccessTokenProvider {
    suspend fun resolve(forceRefresh: Boolean, failedAccessToken: String?): String
}

/** 按 Profile 隔离的进程内令牌端口注册表；由 CloudIdentityRepository 构造时自注册，避免扩大 CampusRepository 外观。 */
object CloudIdentityE2eeAccessTokenProviderRegistry {
    private val providers = ConcurrentHashMap<String, CloudIdentityE2eeAccessTokenProvider>()

    internal fun register(profileId: String, provider: CloudIdentityE2eeAccessTokenProvider) {
        providers[profileId] = provider
    }

    fun forProfile(profileId: String): CloudIdentityE2eeAccessTokenProvider? = providers[profileId]
}

/**
 * CloudIdentity 拆分期运行时端口。
 * 云同步编排仍由兼容外观的 Sync 后续批次所有者提供；本批只保持原有触发与传输读取时序。
 */
internal interface CloudIdentityRuntimePort {
    /** 门面 preferencesFlow 惰性读取（与 MomentsRuntimePort 同一模式），避免构造期属性初始化环。 */
    val preferencesFlow: Flow<AppPreferencesState>

    /** 登录/换号完成后触发一次后台云同步（火后即忘），语义与门面原 repoScope.launch 一致。 */
    fun launchCloudSync()

    /** 钱包刷新优先消费的整量云同步；实现仍归门面 Sync 簇。 */
    suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult>

    /** 测试传输钩子逐次现读（生产恒 null），语义与门面 @Volatile var 一致。 */
    fun cloudHttpTransportForTest(): CloudHttpTransportForTest?

    /**
     * 云身份登录、切号、同号重登或解绑提交前清理 community 群组快照缓存。
     * 该钩子必须 fail-closed：清理抛错时身份变更不得继续生效。
     */
    suspend fun wipeCommunityCacheForCloudIdentityChange()
}

/**
 * 云身份会话、世代守卫、学籍锁定与绑定生命周期。
 * 实现从 CampusRepository 兼容门面逐字迁入；锁序（bookingPlanStateMutex 先于 cloudIdentityMutex）、
 * 世代推进、隔离时序与凭据写回顺序保持不变——三把锁仍为门面同一实例，经构造器传入。
 */
class CloudIdentityRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val appContext: Context,
    private val cloudTokenVault: CloudTokenSecureVault,
    private val academicCredentialVault: AcademicCredentialVault,
    private val academicCredentialMutex: Mutex,
    private val cloudIdentityMutex: Mutex,
    private val bookingPlanStateMutex: Mutex,
    private val cloudTokenMutex: Mutex,
    private val librarySeatRepository: LibrarySeatRepository,
    private val runtimePort: CloudIdentityRuntimePort
) {
    private val preferences = environment.preferences
    private val database = environment.database
    private val cloudAndParcelDao = database.cloudAndParcelDao()

    /** 惰性读取门面 preferencesFlow（实例固定），避免构造期属性初始化环。 */
    private val preferencesFlow: Flow<AppPreferencesState>
        get() = runtimePort.preferencesFlow

    private val profileId: String
        get() = environment.accountProfile.id

    private val supportsHautServices: Boolean
        get() = environment.supportsHautServices

    private val schoolId: String
        get() = environment.accountProfile.schoolId

    /** 逐次现读门面测试传输钩子；生产恒 null。 */
    private val cloudHttpTransportForTest: CloudHttpTransportForTest?
        get() = runtimePort.cloudHttpTransportForTest()

    init {
        CloudIdentityE2eeAccessTokenProviderRegistry.register(profileId) { forceRefresh, failedAccessToken ->
            resolveValidCloudAccessToken(
                forceRefresh = forceRefresh,
                failedAccessToken = failedAccessToken
            )
        }
    }

    private fun academicCredentialScope(accountId: String) = AcademicCredentialScope(
        schoolId = schoolId,
        accountId = accountId,
        providerId = if (supportsHautServices) "haut-zf-modern" else "offline"
    )

    /** 保持原门面内联读取路径：图书馆座位会话仍归 c3 批次所有者。 */
    private fun readCachedLibrarySeatSession() =
        librarySeatRepository.readCachedLibrarySeatSession()

    /** 保持原门面内联调用路径：云同步编排仍归 Sync 后续批次所有者。 */
    private suspend fun syncWithCampusCloud(): Result<CloudSyncSnapshotResult> =
        runtimePort.syncWithCampusCloud()

    /** 必须在 cloudTokenMutex 内调用：全量擦除路径清零内存令牌缓存，语义与搬迁前门面内联一致。 */
    internal fun clearCachedCloudTokensLocked() {
        cachedCloudAccessToken = ""
        cachedCloudRefreshToken = ""
        lastCloudTokenRefreshAtMs = 0L
    }

    private suspend fun readCloudIdentityGeneration(): Long =
        preferences.data.first()[CampusRepositoryPrefKeys.CLOUD_IDENTITY_GENERATION] ?: 0L

    /** 必须在 cloudIdentityMutex 内调用：世代严格单调递增，同账号重新登录也推进世代。 */
    internal suspend fun bumpCloudIdentityGenerationLocked(
        cloudUserId: String?,
        markUnlinkTombstone: Boolean
    ): Long {
        val next = readCloudIdentityGeneration() + 1L
        preferences.edit {
            it[CampusRepositoryPrefKeys.CLOUD_IDENTITY_GENERATION] = next
            if (cloudUserId.isNullOrBlank()) {
                it.remove(CampusRepositoryPrefKeys.CLOUD_IDENTITY_GENERATION_OWNER)
            } else {
                it[CampusRepositoryPrefKeys.CLOUD_IDENTITY_GENERATION_OWNER] = cloudUserId.trim()
            }
            if (markUnlinkTombstone) {
                it[CampusRepositoryPrefKeys.CLOUD_IDENTITY_UNLINK_TOMBSTONE] =
                    "${cloudUserId.orEmpty().trim()}:$next:${System.currentTimeMillis()}"
            }
        }
        return next
    }

    /** 统一快照读取入口：身份锁内原子读取 (binding, generation, apiBase)。 */
    suspend fun snapshotCloudIdentitySession(): CampusRepository.CloudIdentitySessionSnapshot? =
        cloudIdentityMutex.withLock {
            val binding = cloudAndParcelDao.getCloudIdentityBindingSnapshot() ?: return@withLock null
            val prefsSnap = preferencesFlow.first()
            CampusRepository.CloudIdentitySessionSnapshot(
                profileId = binding.profileId,
                cloudUserId = binding.cloudUserId,
                generation = readCloudIdentityGeneration(),
                apiBaseUrl = NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    prefsSnap.networkProxyConfig.customCloudApiBaseUrl
                ),
                ssoSubject = binding.ssoSubject
            )
        }

    /** 必须在 cloudIdentityMutex 内调用 */
    private suspend fun isCloudIdentitySnapshotCurrentLocked(
        snapshot: CampusRepository.CloudIdentitySessionSnapshot
    ): Boolean {
        val live = cloudAndParcelDao.getCloudIdentityBindingSnapshot() ?: return false
        val liveBase = runCatching {
            NetworkProxyConfig.normalizeCloudApiBaseUrl(
                preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
            )
        }.getOrNull()
        return live.profileId == snapshot.profileId &&
            live.cloudUserId.trim() == snapshot.cloudUserId.trim() &&
            live.ssoSubject.trim() == snapshot.ssoSubject.trim() &&
            readCloudIdentityGeneration() == snapshot.generation &&
            liveBase == snapshot.apiBaseUrl
    }

    /** 提交云委托/写回前的即时复核：切号、解绑、同号重登、换 base-url 一律视为已失效。 */
    suspend fun isCloudIdentitySnapshotCurrent(snapshot: CampusRepository.CloudIdentitySessionSnapshot): Boolean =
        cloudIdentityMutex.withLock { isCloudIdentitySnapshotCurrentLocked(snapshot) }

    /**
     * 网络响应写回：身份锁内复核快照仍有效后执行条件 UPDATE（rows=0 即丢弃），
     * 绝不从网络响应 INSERT 过期绑定行。
     */
    internal suspend fun writeBackCloudBindingIfCurrent(
        snapshot: CampusRepository.CloudIdentitySessionSnapshot,
        newPeachBalanceMicropeach: Long? = null,
        newDisplayName: String? = null
    ): Boolean = cloudIdentityMutex.withLock {
        if (!isCloudIdentitySnapshotCurrentLocked(snapshot)) return@withLock false
        val live = cloudAndParcelDao.getCloudIdentityBindingSnapshot() ?: return@withLock false
        cloudAndParcelDao.updateCloudIdentityBindingMutableFieldsIfIdentityMatches(
            profileId = snapshot.profileId,
            cloudUserId = snapshot.cloudUserId,
            displayName = newDisplayName?.takeIf { it.isNotBlank() } ?: live.displayName,
            peachBalanceMicropeach = newPeachBalanceMicropeach ?: live.peachBalanceMicropeach,
            updatedAtEpochMillis = System.currentTimeMillis()
        ) > 0
    }

    @Volatile
    private var cachedCloudAccessToken: String = ""

    @Volatile
    private var cachedCloudRefreshToken: String = ""

    @Volatile
    private var lastCloudTokenRefreshAtMs: Long = 0L

    private fun isJwtNotExpired(token: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) return false
        return runCatching {
            val payloadJson = String(
                java.util.Base64.getUrlDecoder().decode(parts[1]),
                Charsets.UTF_8
            )
            val expSec = org.json.JSONObject(payloadJson).optLong("exp", 0L)
            expSec <= 0L || expSec * 1000L > nowMs + 60_000L
        }.getOrDefault(false)
    }

    /**
     * 业务提交携带的期望身份（[expectedIdentity]=提交开始时的会话快照 {cloudUserId,
     * generation, base, ssoSubject}）与期望学号 [expectedStudentNo]：
     * 刷新发起前 / 网络返回后 / 凭据写回前三点复验——任一不符即返回 "" 并丢弃刷新结果，
     * 切号后旧提交绝不得携新 token 重试，旧 refresh 结果绝不写入当前凭据；
     * 刷新网络地址一律沿用快照中已验证的 apiBaseUrl。
     */
    internal suspend fun resolveValidCloudAccessToken(
        forceRefresh: Boolean = false,
        failedAccessToken: String? = null,
        expectedIdentity: CampusRepository.CloudIdentitySessionSnapshot? = null,
        expectedStudentNo: String? = null
    ): String =
        cloudTokenMutex.withLock {
            val nowMs = System.currentTimeMillis()
            // 自动同步、余额等旧调用同样使用当前身份和已配置地址，不能回到默认云端刷新。
            val identitySnapshot = expectedIdentity ?: snapshotCloudIdentitySession()

            suspend fun studentNoStillExpected(): Boolean {
                val expected = expectedStudentNo?.trim() ?: return true
                val live = (buildDelegatedCredentialBundle().first
                    ?: preferencesFlow.first().savedStudentId.orEmpty()).trim()
                return live == expected
            }

            // 复验①（刷新发起前/快路径前）：旧身份提交一律不再碰令牌体系
            if (identitySnapshot != null && !isCloudIdentitySnapshotCurrent(identitySnapshot)) {
                return@withLock ""
            }
            if (!studentNoStillExpected()) {
                return@withLock ""
            }
            if (!forceRefresh && cachedCloudAccessToken.isNotBlank() && isJwtNotExpired(cachedCloudAccessToken, nowMs)) {
                return@withLock cachedCloudAccessToken
            }
            if (forceRefresh &&
                !failedAccessToken.isNullOrBlank() &&
                cachedCloudAccessToken.isNotBlank() &&
                cachedCloudAccessToken != failedAccessToken &&
                isJwtNotExpired(cachedCloudAccessToken, nowMs)
            ) {
                return@withLock cachedCloudAccessToken
            }
            if (forceRefresh &&
                cachedCloudAccessToken.isNotBlank() &&
                isJwtNotExpired(cachedCloudAccessToken, nowMs) &&
                nowMs - lastCloudTokenRefreshAtMs < 10_000L
            ) {
                return@withLock cachedCloudAccessToken
            }
            var prefs = preferences.data.first()
            val legacyAt = prefs[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN].orEmpty()
            val legacyRt = prefs[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN].orEmpty()
            // v0.20.2 前所有档案都可能保存原文令牌。仅在原账号归属一致时一次性密封，
            // 不能把另一账号的旧凭据带入当前会话，也不能在加密失败时发送原文。
            if (identitySnapshot != null &&
                legacyAt.isNotBlank() && legacyRt.isNotBlank() &&
                !legacyAt.startsWith(CloudTokenSecureVault.SEALED_PREFIX) &&
                !legacyRt.startsWith(CloudTokenSecureVault.SEALED_PREFIX)
            ) {
                val legacyOwner = runCatching {
                    val payload = java.util.Base64.getUrlDecoder().decode(legacyAt.split(".")[1])
                    org.json.JSONObject(String(payload, Charsets.UTF_8)).optString("sub")
                }.getOrNull()
                if (legacyOwner == identitySnapshot.cloudUserId) {
                    val sealedPair = runCatching {
                        cloudTokenVault.seal(legacyAt) to cloudTokenVault.seal(legacyRt)
                    }.getOrNull()
                    if (sealedPair != null) {
                        cloudIdentityMutex.withLock {
                            if (isCloudIdentitySnapshotCurrentLocked(identitySnapshot)) {
                                preferences.edit {
                                    if (it[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN] == legacyAt &&
                                        it[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN] == legacyRt
                                    ) {
                                        it[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN] = sealedPair.first
                                        it[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN] = sealedPair.second
                                    }
                                }
                            }
                        }
                        prefs = preferences.data.first()
                    }
                }
            }
            val storedAt = prefs[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN].orEmpty().let { cloudTokenVault.unseal(it) }
            val storedRt = cachedCloudRefreshToken.ifBlank {
                prefs[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN].orEmpty().let { cloudTokenVault.unseal(it) }
            }
            if (!forceRefresh && storedAt.isNotBlank() && isJwtNotExpired(storedAt, nowMs)) {
                cachedCloudAccessToken = storedAt
                cachedCloudRefreshToken = storedRt
                return@withLock storedAt
            }
            if (storedRt.isNotBlank()) {
                // 刷新发起前再做一次即时复验（持锁期间身份仍可能被世代推进失效）
                if (identitySnapshot != null && !isCloudIdentitySnapshotCurrent(identitySnapshot)) {
                    return@withLock ""
                }
                if (!studentNoStillExpected()) {
                    return@withLock ""
                }
                // 网络地址沿已验证 base：快照里的 apiBaseUrl 才是本次会话校验过的云端地址
                val verifiedBase = identitySnapshot?.apiBaseUrl?.takeIf { it.isNotBlank() }
                    ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(prefs[CampusRepositoryPrefKeys.CLOUD_API_BASE_URL])
                val refreshed = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    val pair = CampusCloudOidcHelper.refreshCloudSession(
                        storedRt,
                        cloudBaseUrl = verifiedBase,
                        httpTransport = cloudHttpTransportForTest
                    )
                    if (pair != null) {
                        // 复验②（凭据写回前）：刷新往返期间切号/换址/换学号的，
                        // 旧会话 refresh 结果绝不写回当前凭据、绝不串 cache
                        val writeBackAllowed =
                            (identitySnapshot == null || isCloudIdentitySnapshotCurrent(identitySnapshot)) &&
                                studentNoStillExpected()
                        if (writeBackAllowed) {
                            cachedCloudAccessToken = pair.first
                            cachedCloudRefreshToken = pair.second
                            lastCloudTokenRefreshAtMs = System.currentTimeMillis()
                            val sealedAt = runCatching { cloudTokenVault.seal(pair.first) }.getOrNull()
                            val sealedRt = runCatching { cloudTokenVault.seal(pair.second) }.getOrNull()
                            if (sealedAt != null && sealedRt != null) {
                                preferences.edit {
                                    it[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN] = sealedAt
                                    it[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN] = sealedRt
                                }
                            }
                        }
                    }
                    pair
                }
                if (refreshed != null) {
                    // 复验③（返回后/重提前）：身份已变则令牌绝不交给旧会话调用方
                    if (identitySnapshot != null && !isCloudIdentitySnapshotCurrent(identitySnapshot)) {
                        return@withLock ""
                    }
                    if (!studentNoStillExpected()) {
                        return@withLock ""
                    }
                    return@withLock refreshed.first
                }
            }
            val candidate = cachedCloudAccessToken.ifBlank { storedAt }
            return@withLock if (candidate.isNotBlank() && isJwtNotExpired(candidate, nowMs)) candidate else ""
        }

    internal suspend fun isolateBookingPlansForStudentSwitch(previousStudentId: String, nowMs: Long) {
        val cleanPrevStu = previousStudentId.trim()
        if (cleanPrevStu.isEmpty()) return
        bookingPlanStateMutex.withLock {
            database.withTransaction {
                val all = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                for (plan in all) {
                    if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                        if (plan.ownerStudentId?.trim() == cleanPrevStu) {
                            LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                        }
                        continue
                    }
                    val stampedOwner = plan.ownerStudentId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    if (stampedOwner != cleanPrevStu) continue
                    LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                    when {
                        plan.status in setOf(
                            "CONFIRMED",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            "SCHOOL_CANCELLED",
                            "CANCEL_RESULT_UNKNOWN",
                            "RESULT_UNKNOWN",
                            "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            "FAILED",
                            "CANCELLED",
                            "EXPIRED",
                            "REMINDER_SENT"
                        ) -> {
                            if (plan.ownerStudentId != stampedOwner) {
                                cloudAndParcelDao.upsertLibraryBookingPlan(plan.copy(ownerStudentId = stampedOwner))
                            }
                        }
                        plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                plan.copy(
                                    ownerStudentId = stampedOwner,
                                    status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                    fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                    generation = plan.generation + 1,
                                    stateVersion = plan.stateVersion + 1,
                                    remainingDays = 0,
                                    lastDiagnosticMessage = "已在任务派发期间切换学号（$stampedOwner），原任务已停止并保留待核对证据",
                                    updatedAtEpochMillis = nowMs
                                )
                            )
                        }
                        else -> {
                            cloudAndParcelDao.upsertLibraryBookingPlan(
                                plan.copy(
                                    ownerStudentId = stampedOwner,
                                    status = "CANCELLED",
                                    fireStatus = "CANCELLED",
                                    generation = plan.generation + 1,
                                    stateVersion = plan.stateVersion + 1,
                                    remainingDays = 0,
                                    lastDiagnosticMessage = "已切换学校学号，原学号（$stampedOwner）未派发的本机排队计划已安全停止",
                                    updatedAtEpochMillis = nowMs
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun saveVerifiedXpeachCloudAccount(
        profile: cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile
    ): CloudIdentityBindingEntity {
        val now = System.currentTimeMillis()
        val previousBinding = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        // 在任何本地 token/binding 变化前完成缓存清理；失败则登录/切号/同号重登整体不生效。
        runtimePort.wipeCommunityCacheForCloudIdentityChange()
        val isSwitchingIdentity =
            previousBinding == null || previousBinding.cloudUserId != profile.cloudUserId
        val tokenBeforeSwitch = if (previousBinding != null && previousBinding.cloudUserId != profile.cloudUserId) {
            resolveValidCloudAccessToken(forceRefresh = false)
        } else ""
        val cloudBaseUrlForSwitch = if (tokenBeforeSwitch.isNotBlank()) {
            runCatching {
                NetworkProxyConfig.normalizeCloudApiBaseUrl(
                    preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
                )
            }.getOrDefault(CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL)
        } else CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL

        val previousCloudLogoutConfirmed = if (tokenBeforeSwitch.isNotBlank() && cloudHttpTransportForTest == null) {
            withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.logoutAllCloudSessions(
                    accessToken = tokenBeforeSwitch,
                    unlinkXpeach = false,
                    cloudBaseUrl = cloudBaseUrlForSwitch,
                    httpTransport = cloudHttpTransportForTest
                )
            }
        } else false

        cloudTokenMutex.withLock {
            if (profile.accessToken.isNotBlank()) {
                cachedCloudAccessToken = profile.accessToken
                lastCloudTokenRefreshAtMs = now
            }
            if (profile.refreshToken.isNotBlank()) {
                cachedCloudRefreshToken = profile.refreshToken
            }
            preferences.edit {
                if (isSwitchingIdentity) {
                    // 切换云端账号时立即脱敏清除上一账号曾获批的受限联系方式、防丢码与个人关联集合
                    it.remove(CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS)
                    it.remove(CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS)
                    it.remove(CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES)
                    it.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS)
                    it.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                    val rawMoments = it[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                    if (!rawMoments.isNullOrBlank()) {
                        val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                        if (parsed != null) {
                            val scrubbed = CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(parsed)
                            it[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                                CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(scrubbed)
                        }
                    }
                }
                if (profile.accessToken.isNotBlank()) {
                    it[CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN] = cloudTokenVault.seal(profile.accessToken)
                }
                if (profile.refreshToken.isNotBlank()) {
                    it[CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN] = cloudTokenVault.seal(profile.refreshToken)
                }
            }
        }
        if (previousBinding != null && previousBinding.cloudUserId != profile.cloudUserId) {
            val prevCloudUserId = previousBinding.cloudUserId
            bookingPlanStateMutex.withLock {
                database.withTransaction {
                    val oldPlans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                    for (plan in oldPlans) {
                        if (plan.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName) continue
                        val planOwner = plan.ownerCloudUserId?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        if (planOwner != prevCloudUserId) continue
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                        // 严禁在换号时 deleteLibraryBookingPlan！必须按原账号隔离保留全部已派发/待核对/已成功记录与预约凭据
                        when {
                            plan.status in setOf(
                                "CONFIRMED",
                                "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                "SCHOOL_CANCELLED",
                                "CANCEL_RESULT_UNKNOWN",
                                "RESULT_UNKNOWN",
                                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                "FAILED",
                                "CANCELLED",
                                "EXPIRED"
                            ) -> {
                                if (plan.ownerCloudUserId != planOwner) {
                                    cloudAndParcelDao.upsertLibraryBookingPlan(plan.copy(ownerCloudUserId = planOwner))
                                }
                            }
                            plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = planOwner,
                                        status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "切换云端账号时该任务正在派发或待核对，已保留原账号（$planOwner）证据供追溯",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            !plan.cloudJobId.isNullOrBlank() && !plan.cloudJobId.startsWith("cjob_") && !previousCloudLogoutConfirmed -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = planOwner,
                                        status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "离线切换云端账号：未能联网确认原账号排队任务是否已停止，已隔离保留待核对记录",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            else -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = planOwner,
                                        status = "CANCELLED",
                                        fireStatus = "CANCELLED",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "已切换云端账号，原账号未派发的排队计划已停止",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        val entity = CloudIdentityBindingEntity(
            profileId = profileId,
            cloudUserId = profile.cloudUserId,
            ssoIssuer = profile.ssoIssuer,
            ssoSubject = profile.ssoSubject,
            displayName = profile.displayName.trim().ifBlank { profile.ssoSubject },
            peachBalanceMicropeach = profile.peachBalanceMicropeach,
            holdSupported = profile.holdSupported,
            linkedAtEpochMillis = profile.linkedAtEpochMillis,
            updatedAtEpochMillis = now
        )
        // 身份变更必须先过提交屏障（全局锁序 booking→identity）：
        // 已进临界区的同步写入整体先于本次登录生效，之后的同步在复核处必然失败
        bookingPlanStateMutex.withLock {
            cloudIdentityMutex.withLock {
                // 登录、切换云账号、同账号重新登录一律推进世代：
                // 任何在途网络响应（含旧会话余额/计划写回）自此一律失效
                bumpCloudIdentityGenerationLocked(profile.cloudUserId, markUnlinkTombstone = false)
                cloudAndParcelDao.upsertCloudIdentityBinding(entity)
            }
        }
        runtimePort.launchCloudSync()
        return entity
    }

    suspend fun refreshXpeachWalletBalance(): Double? = withContext(Dispatchers.IO) {
        val syncRes = syncWithCampusCloud().getOrNull()
        if (syncRes != null) {
            return@withContext syncRes.peachBalance
        }
        val identitySession = snapshotCloudIdentitySession() ?: return@withContext null
        val cloudBaseUrl = identitySession.apiBaseUrl
        var token = resolveValidCloudAccessToken(forceRefresh = false)
        if (token.isBlank()) return@withContext null
        var livePeach = CampusCloudOidcHelper.fetchLivePeachBalance(
            accessToken = token,
            cloudBaseUrl = cloudBaseUrl,
            httpTransport = cloudHttpTransportForTest
        )
        if (livePeach == null) {
            token = resolveValidCloudAccessToken(forceRefresh = true, failedAccessToken = token)
            if (token.isNotBlank()) {
                livePeach = CampusCloudOidcHelper.fetchLivePeachBalance(
                    accessToken = token,
                    cloudBaseUrl = cloudBaseUrl,
                    httpTransport = cloudHttpTransportForTest
                )
            }
        }
        if (livePeach == null) return@withContext null
        // 世代守卫写回：请求期间解绑/换号/同号重登/换 base-url 一律丢弃响应，
        // 绝不 upsert 旧 binding.copy 行（那会覆盖新账号绑定或复活已解绑账号）
        writeBackCloudBindingIfCurrent(
            snapshot = identitySession,
            newPeachBalanceMicropeach = kotlin.math.round(livePeach * 10_000.0).toLong()
        )
        livePeach
    }

    internal suspend fun buildDelegatedCredentialBundle(): Pair<String?, String?> {
        val cachedLibSession = readCachedLibrarySeatSession()
        val credentialPair: Pair<String, String>? = academicCredentialMutex.withLock {
            val prefs = preferences.data.first()
            val accountId = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
            val readResult = academicCredentialVault.read(academicCredentialScope(accountId))
            if (readResult !is AcademicCredentialReadResult.Available) return@withLock null
            readResult.credentials.use { creds ->
                creds.studentId to creds.passwordPlain
            }
        }
        val studentNo = cachedLibSession?.userInfo?.userNo?.takeIf { it.isNotBlank() }
            ?: credentialPair?.first?.takeIf { it.isNotBlank() }
            ?: preferencesFlow.first().savedStudentId?.takeIf { it.isNotBlank() }
        if (studentNo.isNullOrBlank() && cachedLibSession == null && credentialPair == null) {
            return null to null
        }
        val bundleJson = org.json.JSONObject()
            .put("student_no", studentNo.orEmpty())
            .put("password", credentialPair?.second.orEmpty())
            .put("raw_token", cachedLibSession?.rawToken.orEmpty())
            .put("cas_tgc", cachedLibSession?.casTgcCookie.orEmpty())
            .put("cas_jsessionid", cachedLibSession?.casJsessionId.orEmpty())
            .toString()
        return studentNo to bundleJson
    }

    suspend fun unlinkXpeachCloudAccountAndRevokeCloudPlans() {
        val now = System.currentTimeMillis()
        // 在远端登出与本地解绑之前先清理 community 快照；失败时不得继续宣称解绑/擦除完成。
        runtimePort.wipeCommunityCacheForCloudIdentityChange()
        val bindingBeforeUnlink = cloudAndParcelDao.getCloudIdentityBindingSnapshot()
        val prevCloudUserId = bindingBeforeUnlink?.cloudUserId
        val tokenBeforeUnlink = resolveValidCloudAccessToken(forceRefresh = false)
        val cloudBaseUrl = runCatching {
            NetworkProxyConfig.normalizeCloudApiBaseUrl(
                preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl
            )
        }.getOrDefault(CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL)
        val cloudRevokeSucceeded = if (tokenBeforeUnlink.isNotBlank()) {
            withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.logoutAllCloudSessions(
                    accessToken = tokenBeforeUnlink,
                    unlinkXpeach = true,
                    cloudBaseUrl = cloudBaseUrl,
                    httpTransport = cloudHttpTransportForTest
                )
            }
        } else {
            false
        }
        cloudTokenMutex.withLock {
            cachedCloudAccessToken = ""
            cachedCloudRefreshToken = ""
            lastCloudTokenRefreshAtMs = 0L
            preferences.edit {
                it.remove(CampusRepositoryPrefKeys.CLOUD_ACCESS_TOKEN)
                it.remove(CampusRepositoryPrefKeys.CLOUD_REFRESH_TOKEN)
                it.remove(CampusRepositoryPrefKeys.MOMENTS_MY_POST_IDS)
                it.remove(CampusRepositoryPrefKeys.MOMENTS_MY_BOOK_IDS)
                it.remove(CampusRepositoryPrefKeys.MOMENTS_MY_TAG_CODES)
                it.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_POST_IDS)
                it.remove(CampusRepositoryPrefKeys.MOMENTS_FAVORITE_BOOK_IDS)
                val rawMoments = it[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                if (!rawMoments.isNullOrBlank()) {
                    val parsed = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                    if (parsed != null) {
                        val scrubbed = CampusCloudOidcHelper.sanitizeMomentsSnapshotForViewerReset(parsed)
                        it[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                            CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(scrubbed)
                    }
                }
            }
        }
        // 身份变更必须先过提交屏障（全局锁序 booking→identity）：
        // 世代推进/绑定清除与计划隔离同属一个提交临界区，同步写入要么整体先于解绑、要么在复核处失败
        bookingPlanStateMutex.withLock {
            cloudIdentityMutex.withLock {
                // 解绑推进世代并留存 tombstone：在途响应写回失效，A→解绑→重绑(ABA) 场景旧会话响应亦不复活
                bumpCloudIdentityGenerationLocked(prevCloudUserId, markUnlinkTombstone = true)
                cloudAndParcelDao.clearCloudIdentityBinding()
            }
            database.withTransaction {
                val plans = cloudAndParcelDao.getLibraryBookingPlansSnapshot()
                for (plan in plans) {
                    if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
                        val explicitCloudOwner = plan.ownerCloudUserId?.trim()?.takeIf { it.isNotEmpty() }
                        if (prevCloudUserId != null && explicitCloudOwner != prevCloudUserId) {
                            continue
                        }
                        val stampedCloudOwner = explicitCloudOwner
                        LibrarySeatAlarmSubsystem.cancelPlanAlarms(appContext, plan.id)
                        when {
                            // 严禁把已确认、在途或结果未知的状态覆盖为 CANCELLED（否则会丢失预约凭据或放行同日重复抢座！）
                            plan.status in setOf(
                                "CONFIRMED",
                                "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                                "SCHOOL_CANCELLED",
                                "CANCEL_RESULT_UNKNOWN",
                                "RESULT_UNKNOWN",
                                "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                "FAILED",
                                "CANCELLED",
                                "EXPIRED"
                            ) -> {
                                if (stampedCloudOwner != null && plan.ownerCloudUserId != stampedCloudOwner) {
                                    cloudAndParcelDao.upsertLibraryBookingPlan(plan.copy(ownerCloudUserId = stampedCloudOwner))
                                }
                            }
                            plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = stampedCloudOwner,
                                        status = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        fireStatus = "DELEGATION_STOPPED_RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "已在任务派发后退出云端账号，结果待核对（核实前禁止同日重复提交）",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            !plan.cloudJobId.isNullOrBlank() && !plan.cloudJobId.startsWith("cjob_") && !cloudRevokeSucceeded -> {
                                // 离线解绑且云端已有真实排队任务：无法协调停止云端，标为待核对以阻止同日本机盲目重复预约
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = stampedCloudOwner,
                                        status = "RESULT_UNKNOWN",
                                        fireStatus = "RESULT_UNKNOWN",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "离线退出云端账号：无法联网确认云端排队任务是否已停止，请核实前勿重复提交同日抢座",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                            else -> {
                                cloudAndParcelDao.upsertLibraryBookingPlan(
                                    plan.copy(
                                        ownerCloudUserId = stampedCloudOwner,
                                        status = "CANCELLED",
                                        fireStatus = "CANCELLED",
                                        generation = plan.generation + 1,
                                        stateVersion = plan.stateVersion + 1,
                                        remainingDays = 0,
                                        lastDiagnosticMessage = "已解绑 XPEACH 账号并撤回未执行的云端托管授权",
                                        updatedAtEpochMillis = now
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    internal fun doesCloudPlanBelongToActiveAccount(
        plan: LibraryBookingPlanEntity,
        activeCloudUserId: String?,
        activeStudentNo: String?
    ): Boolean {
        val cleanActiveCloud = activeCloudUserId?.trim().orEmpty()
        if (cleanActiveCloud.isEmpty()) return false
        val planCloud = plan.ownerCloudUserId?.trim().orEmpty()
        if (planCloud.isEmpty() || planCloud != cleanActiveCloud) return false
        val cleanActiveStu = activeStudentNo?.trim().orEmpty()
        val planStu = plan.ownerStudentId?.trim().orEmpty()
        if (cleanActiveStu.isNotEmpty()) {
            // 当前已明确登录某学号 B：仅明确归属学号 B 的云任务属于当前活跃身份；A 的任务与未知学号归属任务一律隔离！
            return planStu.isNotEmpty() && planStu == cleanActiveStu
        }
        return planStu.isEmpty()
    }

    internal fun isUnattributedLegacyBookingPlan(plan: LibraryBookingPlanEntity): Boolean {
        return if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            plan.ownerCloudUserId.isNullOrBlank() || plan.ownerStudentId.isNullOrBlank()
        } else {
            plan.ownerStudentId.isNullOrBlank()
        }
    }

    internal fun isBookingPlanVisibleToIdentity(
        plan: LibraryBookingPlanEntity,
        activeCloudUserId: String?,
        activeStudentId: String?
    ): Boolean {
        val cleanActiveCloud = activeCloudUserId?.trim().orEmpty()
        val cleanActiveStudent = activeStudentId?.trim().orEmpty()
        if (plan.executionMode == BookingExecutionMode.CLOUD_HOSTED.wireName) {
            val planOwnerCloud = plan.ownerCloudUserId?.trim().orEmpty()
            val planOwnerStu = plan.ownerStudentId?.trim().orEmpty()
            return when {
                cleanActiveCloud.isNotEmpty() -> {
                    // 已登录当前云端账号：若同时已登录学号 B，严禁将未知学号归属（planOwnerStu 为空）或学号 A 的记录展示为 B 的计划！
                    planOwnerCloud.isNotEmpty() &&
                        planOwnerCloud == cleanActiveCloud &&
                        (cleanActiveStudent.isEmpty() || (planOwnerStu.isNotEmpty() && planOwnerStu == cleanActiveStudent))
                }
                // 未登录云端账号时：若已登录某学号则仅展示明确匹配该学号的证据记录；若完全未登录则保留展示本机待核对/已确认证据
                else -> {
                    val studentMatchesWhenLoggedOutOfCloud = if (cleanActiveStudent.isNotEmpty()) {
                        planOwnerStu.isNotEmpty() && planOwnerStu == cleanActiveStudent
                    } else {
                        true
                    }
                    studentMatchesWhenLoggedOutOfCloud && (
                        plan.status in setOf(
                            "CONFIRMED",
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                            "RESULT_UNKNOWN",
                            "DELEGATION_STOPPED_RESULT_UNKNOWN",
                            "CANCEL_RESULT_UNKNOWN",
                            "SCHOOL_CANCELLED"
                        ) || !plan.externalBookingId.isNullOrBlank() || !plan.evidenceSha256.isNullOrBlank()
                    )
                }
            }
        }
        val planOwnerStu = plan.ownerStudentId?.trim().orEmpty()
        return if (cleanActiveStudent.isNotEmpty()) {
            // 已登录本地学号 B：严禁将未知归属（空 ownerStudentId）旧记录展示为 B 的计划！
            planOwnerStu.isNotEmpty() && planOwnerStu == cleanActiveStudent
        } else {
            planOwnerStu.isEmpty() ||
                plan.status in setOf(
                    "CONFIRMED",
                    "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                    "RESULT_UNKNOWN",
                    "DELEGATION_STOPPED_RESULT_UNKNOWN",
                    "CANCEL_RESULT_UNKNOWN",
                    "SCHOOL_CANCELLED"
                ) || !plan.externalBookingId.isNullOrBlank() || !plan.evidenceSha256.isNullOrBlank()
        }
    }

    internal fun isSamePersonBookingConflictCandidate(
        candidate: LibraryBookingPlanEntity,
        targetLocalDate: String,
        activeCloudUserId: String?,
        activeStudentNo: String?,
        excludePlanId: String? = null
    ): Boolean {
        if (excludePlanId != null && candidate.id == excludePlanId) return false
        if (candidate.targetLocalDate != targetLocalDate) return false
        if (candidate.status in setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED", "REMINDER_SENT")) {
            return false
        }
        val cleanActiveStu = activeStudentNo?.trim().orEmpty()
        val candStu = candidate.ownerStudentId?.trim().orEmpty()
        val cleanActiveCloud = activeCloudUserId?.trim().orEmpty()
        val candCloud = candidate.ownerCloudUserId?.trim().orEmpty()

        // 1. 两侧均有明确学号：同一学号（哪怕跨不同云账号）严格防重；不同学号严格隔离不误阻！
        if (cleanActiveStu.isNotEmpty() && candStu.isNotEmpty()) {
            return cleanActiveStu == candStu
        }
        // 2. 两侧均有明确云账号：同一云账号防重；不同云账号隔离不误阻！
        if (cleanActiveCloud.isNotEmpty() && candCloud.isNotEmpty()) {
            return cleanActiveCloud == candCloud
        }
        // 3. 当前与候选记录均未写入学号，且候选为本机非云端任务（同设备未绑学号时的在途/排队本机计划）：必须防重阻断！
        if (cleanActiveStu.isEmpty() &&
            candStu.isEmpty() &&
            candCloud.isEmpty() &&
            candidate.executionMode != BookingExecutionMode.CLOUD_HOSTED.wireName
        ) {
            return true
        }
        // 4. 完全退出云账号且未切换为其他学号时（例如仅解绑 XPeach 云账号后尝试同日改建本机抢座）：保留对本机可见已确认/待核对记录的同日防重！
        if (cleanActiveCloud.isEmpty() && cleanActiveStu.isEmpty()) {
            return isBookingPlanVisibleToIdentity(candidate, null, "")
        }
        return false
    }

}
