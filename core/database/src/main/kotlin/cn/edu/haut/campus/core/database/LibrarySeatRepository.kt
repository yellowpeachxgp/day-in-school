package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.campusservices.HautLibrarySeatAuthClient
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionOutcome
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatAuthException
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatRefreshSource
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionExpiredException
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionVault
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import cn.edu.haut.campus.core.model.AcademicTermResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * LibrarySeat 拆分期运行时端口。
 * 教务凭据保存仍由兼容外观的 Sync 后续批次所有者提供；本批只保持原有凭据写入与学生切换隔离时序。
 */
internal interface LibrarySeatRuntimePort {
    val isSessionActive: Boolean

    fun requireActiveSession()

    suspend fun migrateLegacyAcademicCredentialsIfNeeded()

    suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ): Result<Unit>?
}

/**
 * 图书馆座位会话、三级保活、座位控制动作与规划目录查询。
 * 实现从 CampusRepository 兼容门面逐字迁入；Room schema、网络重试、锁序与凭据保存顺序保持不变。
 */
class LibrarySeatRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val librarySeatSessionVault: LibrarySeatSessionVault,
    private val academicCredentialVault: AcademicCredentialVault,
    private val academicCredentialMutex: Mutex,
    private val runtimePort: LibrarySeatRuntimePort
) {
    private val preferences = environment.preferences
    private val dao = environment.dao
    private val librarySeatAuthMutex = Mutex()

    private val isSessionActive: Boolean
        get() = runtimePort.isSessionActive

    private val supportsHautServices: Boolean
        get() = environment.supportsHautServices

    private val schoolId: String
        get() = environment.accountProfile.schoolId

    private fun requireActiveSession() = runtimePort.requireActiveSession()

    private suspend fun migrateLegacyAcademicCredentialsIfNeeded() =
        runtimePort.migrateLegacyAcademicCredentialsIfNeeded()

    private suspend fun saveAuthorizedSyncCredentials(
        studentId: String,
        passwordPlain: String,
        xnm: String,
        xqm: String
    ) = runtimePort.saveAuthorizedSyncCredentials(
        studentId = studentId,
        passwordPlain = passwordPlain,
        xnm = xnm,
        xqm = xqm
    )

    private fun academicCredentialScope(accountId: String) = AcademicCredentialScope(
        schoolId = schoolId,
        accountId = accountId,
        providerId = if (supportsHautServices) "haut-zf-modern" else "offline"
    )

    /**
     * 读取本地加密持久化的图书馆座位预约会话快照（不发起网络请求）。
     */
    fun readCachedLibrarySeatSession(): LibrarySeatSessionSnapshot? =
        librarySeatSessionVault.readSnapshot()

    /**
     * 清理本地加密持久化的图书馆座位预约会话快照。
     */
    suspend fun clearLibrarySeatSession() = withContext(Dispatchers.IO) {
        librarySeatSessionVault.clear()
    }

    /**
     * 当用户在 WebView 内手动完成统一身份认证（如输入验证码后）跳转回 `wslib.haut.edu.cn/h5/index.html?token=...` 时，
     * 自动捕获并持久化最新 Token 与 `CASTGC` Cookie 到本地 AES-256-GCM 加密仓。
     */
    suspend fun captureLibrarySeatWebToken(rawToken: String): LibrarySeatSessionSnapshot? = withContext(Dispatchers.IO) {
        if (!isSessionActive || !supportsHautServices) return@withContext null
        val clean = HautLibrarySeatAuthClient.normalizeRawToken(rawToken)
        if (clean.isBlank()) return@withContext null
        librarySeatAuthMutex.withLock {
            val userInfo = HautLibrarySeatAuthClient.validateTokenOnline(clean) ?: return@withLock null
            val casTgc = HautLibrarySeatAuthClient.extractCastgcFromWebView()
                ?: librarySeatSessionVault.readSnapshot()?.casTgcCookie
            val snapshot = LibrarySeatSessionSnapshot(
                rawToken = clean,
                casTgcCookie = casTgc,
                casJsessionId = librarySeatSessionVault.readSnapshot()?.casJsessionId,
                userInfo = userInfo,
                updatedAtEpochMs = System.currentTimeMillis(),
                refreshSource = LibrarySeatRefreshSource.WEBVIEW_CAPTURE
            )
            librarySeatSessionVault.saveSnapshot(snapshot)
            snapshot
        }
    }

    /**
     * 确保图书馆座位预约拥有有效的持久化会话（三级保活）：
     * 1. 若持久化 Token 在线校验有效（且 `forceRefresh == false`），直接复用并同步 Cookie；
     * 2. 若 Token 失效，尝试使用持久化的 `CASTGC` Cookie 静默换取新 Token；
     * 3. 若 `CASTGC` Cookie 亦失效，自动从加密数据库 (`AcademicCredentialVault`) 读取学号与密码执行后台登录，
     *    并持久化新 Cookie 与 Token。
     */
    suspend fun ensureLibrarySeatSession(
        forceRefresh: Boolean = false
    ): LibrarySeatSessionState = withContext(Dispatchers.IO) {
        requireActiveSession()
        if (!supportsHautServices) {
            return@withContext LibrarySeatSessionState.AuthError("当前学校暂不支持工大图书馆空间预约服务")
        }
        migrateLegacyAcademicCredentialsIfNeeded()
        librarySeatAuthMutex.withLock {
            val cached = librarySeatSessionVault.readSnapshot()

            // Level 1: 校验已持久化的 wslib Token
            if (!forceRefresh && cached != null) {
                val verifiedUser = HautLibrarySeatAuthClient.validateTokenOnline(cached.rawToken)
                if (verifiedUser != null) {
                    HautLibrarySeatAuthClient.syncCasCookiesToWebView(
                        casTgcCookie = cached.casTgcCookie,
                        casJsessionId = cached.casJsessionId
                    )
                    val refreshed = cached.copy(
                        userInfo = verifiedUser,
                        updatedAtEpochMs = System.currentTimeMillis(),
                        refreshSource = LibrarySeatRefreshSource.CACHED_TOKEN
                    )
                    librarySeatSessionVault.saveSnapshot(refreshed)
                    return@withLock LibrarySeatSessionState.Authenticated(refreshed)
                }
            }

            // Level 2: 使用持久化的 CASTGC Cookie 静默换取新 Token
            val candidateCastgc = cached?.casTgcCookie ?: HautLibrarySeatAuthClient.extractCastgcFromWebView()
            if (!candidateCastgc.isNullOrBlank()) {
                val exchanged = HautLibrarySeatAuthClient.tryExchangeTokenWithPersistedCookie(
                    casTgcCookie = candidateCastgc,
                    casJsessionId = cached?.casJsessionId
                )
                if (exchanged != null) {
                    librarySeatSessionVault.saveSnapshot(exchanged)
                    return@withLock LibrarySeatSessionState.Authenticated(exchanged)
                }
            }

            // Level 3: 从加密凭据仓读取用户学号与统一认证密码执行后台自动重登
            val credentialPair: Pair<String, String>? = academicCredentialMutex.withLock {
                val prefs = preferences.data.first()
                val accountId = prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID] ?: return@withLock null
                val readResult = academicCredentialVault.read(academicCredentialScope(accountId))
                if (readResult !is AcademicCredentialReadResult.Available) {
                    return@withLock null
                }
                readResult.credentials.use { creds ->
                    creds.studentId to creds.passwordPlain
                }
            }

            if (credentialPair == null) {
                return@withLock LibrarySeatSessionState.CredentialsRequired(
                    "尚未保存统一身份认证凭据；绑定后将加密保存在本地数据库中，Cookie 失效时自动后台登录"
                )
            }

            val loginResult = HautLibrarySeatAuthClient.loginWithCredentials(
                studentId = credentialPair.first,
                passwordPlain = credentialPair.second
            )
            loginResult.fold(
                onSuccess = { snapshot ->
                    librarySeatSessionVault.saveSnapshot(snapshot)
                    LibrarySeatSessionState.Authenticated(snapshot)
                },
                onFailure = { error ->
                    val authError = error as? LibrarySeatAuthException
                    LibrarySeatSessionState.AuthError(
                        message = error.message ?: "自动后台登录图书馆预约系统未完成",
                        requiresManualVerification = authError?.requiresManualVerification == true
                    )
                }
            )
        }
    }

    /**
     * 在图书馆座位预约页绑定/更新统一身份认证账号密码（加密存入 `AcademicCredentialVault`），并立即执行后台登录与 Cookie 持久化。
     */
    suspend fun saveLibrarySeatCredentialsAndLogin(
        studentId: String,
        passwordPlain: String
    ): LibrarySeatSessionState = withContext(Dispatchers.IO) {
        requireActiveSession()
        val cleanId = studentId.trim()
        if (cleanId.isBlank() || passwordPlain.isBlank()) {
            return@withContext LibrarySeatSessionState.AuthError("请输入学号和统一身份认证密码")
        }
        val prefs = preferences.data.first()
        val currentTermEntity = dao.getCurrentTerm()
        val defaultPair = currentTermEntity?.id?.let { HautAcademicEndpoints.fromTermId(it) }
            ?: (AcademicTermResolver.defaultAcademicYearCode() to AcademicTermResolver.defaultSemesterCode())
        val xnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM] ?: defaultPair.first
        val xqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM] ?: defaultPair.second
        saveAuthorizedSyncCredentials(
            studentId = cleanId,
            passwordPlain = passwordPlain,
            xnm = xnm,
            xqm = xqm
        )
        librarySeatAuthMutex.withLock {
            HautLibrarySeatAuthClient.loginWithCredentials(
                studentId = cleanId,
                passwordPlain = passwordPlain
            ).fold(
                onSuccess = { snapshot ->
                    librarySeatSessionVault.saveSnapshot(snapshot)
                    LibrarySeatSessionState.Authenticated(snapshot)
                },
                onFailure = { error ->
                    val authError = error as? LibrarySeatAuthException
                    LibrarySeatSessionState.AuthError(
                        message = error.message ?: "统一身份认证登录失败，请核对学号和密码",
                        requiresManualVerification = authError?.requiresManualVerification == true
                    )
                }
            )
        }
    }

    /**
     * 拉取用户当前有效的图书馆座位预约、进行中预约列表、历史预约记录与暂离规则时长。
     * 若 Token 过期（`401`），会自动使用持久化 Cookie 或本地加密数据库凭据后台重登并无感重试。
     */
    suspend fun fetchLibrarySeatOverview(): Result<LibrarySeatOverviewSnapshot> = withContext(Dispatchers.IO) {
        val state0 = ensureLibrarySeatSession(forceRefresh = false)
        val token0 = (state0 as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(
                IllegalStateException(
                    when (state0) {
                        is LibrarySeatSessionState.CredentialsRequired -> state0.message
                        is LibrarySeatSessionState.AuthError -> state0.message
                        else -> "尚未建立有效的图书馆选座登录会话"
                    }
                )
            )
        val firstTry = HautLibrarySeatAuthClient.fetchSeatBookingOverview(token0)
        if (firstTry.isSuccess || firstTry.exceptionOrNull() !is LibrarySeatSessionExpiredException) {
            return@withContext firstTry
        }
        val stateRetry = ensureLibrarySeatSession(forceRefresh = true)
        val tokenRetry = (stateRetry as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(IllegalStateException("自动后台重登失败，请检查账号或网络"))
        HautLibrarySeatAuthClient.fetchSeatBookingOverview(tokenRetry)
    }

    /**
     * 执行图书馆座位控制动作（签到、快速签到、取消预约、短暂离、长暂离、暂离返回、续座、签退释放）。
     * 若执行时遇到 `401` 会话过期，自动触发加密凭据后台重登并重试一次。
     */
    suspend fun executeLibrarySeatAction(
        action: LibrarySeatActionType,
        bookingId: Long? = null
    ): Result<LibrarySeatActionOutcome> = withContext(Dispatchers.IO) {
        val state0 = ensureLibrarySeatSession(forceRefresh = false)
        val token0 = (state0 as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(
                IllegalStateException(
                    when (state0) {
                        is LibrarySeatSessionState.CredentialsRequired -> state0.message
                        is LibrarySeatSessionState.AuthError -> state0.message
                        else -> "请先完成统一身份认证登录"
                    }
                )
            )
        val firstTry = HautLibrarySeatAuthClient.performSeatAction(
            rawToken = token0,
            action = action,
            bookingId = bookingId
        )
        if (firstTry.isSuccess || firstTry.exceptionOrNull() !is LibrarySeatSessionExpiredException) {
            return@withContext firstTry
        }
        val stateRetry = ensureLibrarySeatSession(forceRefresh = true)
        val tokenRetry = (stateRetry as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return@withContext Result.failure(IllegalStateException("自动后台重登失败，请重试"))
        HautLibrarySeatAuthClient.performSeatAction(
            rawToken = tokenRetry,
            action = action,
            bookingId = bookingId
        )
    }

    private val librarySeatPlanningCatalog by lazy {
        LibrarySeatPlanningCatalog(preferences, ::fetchLibraryStudyRegions, ::fetchLibraryRegionSeats)
    }

    suspend fun loadLibraryPlanningRegions(date: String, refresh: Boolean = false): Result<List<LibraryStudyRegionItem>> =
        librarySeatPlanningCatalog.loadRegions(date, refresh)

    suspend fun loadLibraryPlanningSeats(region: LibraryStudyRegionItem, date: String, refresh: Boolean = false): Result<LibraryRegionSeatsSnapshot> =
        librarySeatPlanningCatalog.loadSeats(region, date, refresh)

    suspend fun fetchLibraryStudyRegions(
        bookingDate: String
    ): Result<List<LibraryStudyRegionItem>> {
        val currentToken = readCachedLibrarySeatSession()?.rawToken
            ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return Result.failure(IllegalStateException("请先登录图书馆账号"))
        val firstAttempt = HautLibrarySeatAuthClient.fetchLibraryStudyRegions(currentToken, bookingDate)
        if (firstAttempt.isSuccess) return firstAttempt
        val err = firstAttempt.exceptionOrNull()
        if (err is LibrarySeatSessionExpiredException) {
            val refreshed = ensureLibrarySeatSession(forceRefresh = true)
            if (refreshed is LibrarySeatSessionState.Authenticated) {
                return HautLibrarySeatAuthClient.fetchLibraryStudyRegions(refreshed.snapshot.rawToken, bookingDate)
            }
        }
        return firstAttempt
    }

    suspend fun fetchLibraryRegionSeats(
        regionId: Long,
        bookingDate: String
    ): Result<LibraryRegionSeatsSnapshot> {
        val currentToken = readCachedLibrarySeatSession()?.rawToken
            ?: (ensureLibrarySeatSession(forceRefresh = false) as? LibrarySeatSessionState.Authenticated)?.snapshot?.rawToken
            ?: return Result.failure(IllegalStateException("请先登录图书馆账号"))
        val firstAttempt = HautLibrarySeatAuthClient.fetchLibraryRegionSeats(currentToken, regionId, bookingDate)
        if (firstAttempt.isSuccess) return firstAttempt
        val err = firstAttempt.exceptionOrNull()
        if (err is LibrarySeatSessionExpiredException) {
            val refreshed = ensureLibrarySeatSession(forceRefresh = true)
            if (refreshed is LibrarySeatSessionState.Authenticated) {
                return HautLibrarySeatAuthClient.fetchLibraryRegionSeats(refreshed.snapshot.rawToken, regionId, bookingDate)
            }
        }
        return firstAttempt
    }
}
