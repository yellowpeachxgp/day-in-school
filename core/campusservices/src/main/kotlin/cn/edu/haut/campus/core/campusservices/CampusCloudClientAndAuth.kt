package cn.edu.haut.campus.core.campusservices

import android.net.Uri
import cn.edu.haut.campus.core.model.AntiLostTagItem
import cn.edu.haut.campus.core.model.AntiLostTagReport
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CampusCommunityBulletin
import cn.edu.haut.campus.core.model.CampusCommunityCloudCatalog
import cn.edu.haut.campus.core.model.CampusCompetitionCategory
import cn.edu.haut.campus.core.model.CampusCompetitionItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.CampusMomentsSnapshot
import cn.edu.haut.campus.core.model.MomentsIsbnCatalogEntry
import cn.edu.haut.campus.core.model.MomentsMatchedClue
import cn.edu.haut.campus.core.model.MomentsPostComment
import cn.edu.haut.campus.core.model.MomentsPostItem
import cn.edu.haut.campus.core.model.MomentsPostType
import cn.edu.haut.campus.core.model.MomentsTextbookListing
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.PresetCampusMomentsCatalog
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun String?.cleanNullLiteral(fallback: String = ""): String {
    if (this == null) return fallback
    val trimmed = this.trim()
    if (trimmed.isEmpty()) return fallback
    val lower = trimmed.lowercase()
    if (lower == "null" || lower == "undefined" || lower == "none" || lower == "nil") {
        return fallback
    }
    val cleaned = trimmed
        .replace(Regex("\\s*·\\s*null\\b", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\bnull\\s*·\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
    return cleaned.ifEmpty { fallback }
}

fun String?.cleanNullableLiteral(): String? =
    this.cleanNullLiteral("").takeIf { it.isNotEmpty() }

fun JSONObject.optCleanString(key: String, fallback: String = ""): String {
    if (!has(key) || isNull(key)) return fallback
    return optString(key, fallback).cleanNullLiteral(fallback)
}

fun JSONObject.optNullableCleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").cleanNullableLiteral()
}

data class XpeachCloudAccountProfile(
    val cloudUserId: String,
    val ssoIssuer: String,
    val ssoSubject: String,
    val displayName: String,
    val peachBalanceMicropeach: Long,
    val holdSupported: Boolean = false,
    val linkedAtEpochMillis: Long,
    val accessToken: String = "",
    val refreshToken: String = "",
    val group: String = "默认分组",
    val affCode: String = ""
) {
    val peachBalance: Double
        get() = peachBalanceMicropeach.toDouble() / 10_000.0

    val formattedPeachBalance: String
        get() = "%.2f 🍑".format(peachBalance)
}

sealed interface NativeOidcLoginResult {
    data class Success(val profile: XpeachCloudAccountProfile) : NativeOidcLoginResult
    data class TotpRequired(val message: String) : NativeOidcLoginResult
    data class Failure(val message: String) : NativeOidcLoginResult
}

data class CloudSyncedParcelDto(
    val id: String,
    val trackingNumber: String,
    val carrierCode: String,
    val carrierName: String,
    val status: String,
    val pickupCode: String?,
    val stationName: String?,
    val itemTitle: String?,
    val rawNotificationText: String?
)

data class CloudSyncedReservationJobDto(
    val jobId: String,
    val planId: String,
    val areaCode: String,
    val areaName: String,
    val candidateSeats: List<String>,
    val slotWindow: String,
    val targetLocalDate: String,
    val targetLocalTime: String,
    val state: String,
    val externalBookingId: String?,
    val evidenceSha256: String?,
    val bookedSeatAlias: String? = null,
    val bookedRegionAlias: String? = null,
    val repeatDays: Int = 1,
    val remainingDays: Int = 1,
    val endLocalDate: String = targetLocalDate,
    val userIntentState: String = "ACTIVE",
    val externalExecutionState: String = "NOT_DISPATCHED",
    val schoolCancellationState: String = "NOT_REQUESTED",
    val displayStatus: String = state,
    val statusLabel: String = "",
    val billingState: String = "NOT_BILLED"
)

data class CloudSyncedOrderDto(
    val orderId: String,
    val jobId: String,
    val pricePeach: Double,
    val paymentState: String,
    val paidAtIso: String?,
    val createdAtIso: String
)

data class CloudUnsyncedPlanUpsertDto(
    val planId: String,
    val targetLocalDate: String,
    val targetLocalTime: String,
    val areaName: String,
    val candidateSeats: List<String>,
    val repeatDays: Int = 1,
    val remainingDays: Int = 1,
    val allowAlternativeSeat: Boolean = true,
    val studentNo: String? = null,
    val boundBindingId: String? = null,
    val boundConsentId: String? = null,
    val createdAuthVersion: Int = 0
)

data class CloudSyncItemOutcome(
    val itemType: String,
    val itemId: String,
    val status: String,
    val reason: String? = null
)

data class CloudSyncSnapshotResult(
    val cloudUserId: String,
    val ssoSubject: String,
    val displayName: String,
    val peachBalance: Double,
    val peachUsed: Double,
    val group: String,
    val affCode: String,
    val boundStudentNo: String?,
    val jobs: List<CloudSyncedReservationJobDto>,
    val orders: List<CloudSyncedOrderDto>,
    val parcels: List<CloudSyncedParcelDto>,
    val campusContent: CampusCommunityCloudCatalog? = null,
    val campusContentRawJson: String? = null,
    val authVersion: Int = 1,
    val syncStatus: String = "FULLY_SYNCED",
    val syncSummaryMessage: String = "云端数据已完整同步",
    val needsReconfirmationBindingStudentNos: List<String> = emptyList(),
    val needsReconfirmationPlanIds: List<String> = emptyList(),
    val itemOutcomes: List<CloudSyncItemOutcome> = emptyList()
)

data class PkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256"
)

data class MobileAuthTransactionStart(
    val loginTxn: String,
    val authorizeUrl: String,
    val pkce: PkcePair,
    val expiresAtEpochMillis: Long
)

object CampusCloudOidcHelper {
    const val DEFAULT_CLOUD_BASE_URL = "https://campus.yellowpeachxgp.com"
    const val DEFAULT_SSO_ISSUER = "https://sso.xpeach.codes/t/acme"
    const val APP_LINK_CALLBACK_PREFIX = "https://campus.yellowpeachxgp.com/app-link/auth/callback"

    private val secureRandom = SecureRandom()

    fun generatePkcePair(): PkcePair {
        val raw = ByteArray(32)
        secureRandom.nextBytes(raw)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        return PkcePair(
            codeVerifier = verifier,
            codeChallenge = computeS256Challenge(verifier),
            codeChallengeMethod = "S256"
        )
    }

    fun computeS256Challenge(codeVerifier: String): String {
        require(codeVerifier.length in 43..128) {
            "PKCE code_verifier 长度必须在 43..128 之间"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildLocalOrRemoteTransaction(
        installEphemeralId: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): MobileAuthTransactionStart {
        require(installEphemeralId.length >= 8) { "install_ephemeral_id 长度不足" }
        val pkce = generatePkcePair()
        val txnBytes = ByteArray(16)
        secureRandom.nextBytes(txnBytes)
        val loginTxn = "txn_" + Base64.getUrlEncoder().withoutPadding().encodeToString(txnBytes)
        val cleanBase = cloudBaseUrl.trimEnd('/')
        return MobileAuthTransactionStart(
            loginTxn = loginTxn,
            authorizeUrl = "$cleanBase/api/v1/auth/oidc/start?txn=$loginTxn&code_challenge=${pkce.codeChallenge}&code_challenge_method=S256",
            pkce = pkce,
            expiresAtEpochMillis = nowEpochMillis + 5 * 60 * 1000L
        )
    }

    /**
     * 向 https://campus.yellowpeachxgp.com/api/v1/auth/mobile/start 发起真实 OIDC PKCE 登录事务，
     * 返回服务端签发的 loginTxn 与跳转到 sso.xpeach.codes 的 authorizeUrl。
     */
    fun startRemoteMobileAuthTransaction(
        installEphemeralId: String,
        deviceLabel: String = "DayInSchool Android",
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): MobileAuthTransactionStart {
        val safeEphId = if (installEphemeralId.length >= 8) installEphemeralId else "eph_${installEphemeralId}_android"
        val pkce = generatePkcePair()
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val reqJson = JSONObject()
            .put("install_ephemeral_id", safeEphId)
            .put("code_challenge", pkce.codeChallenge)
            .put("code_challenge_method", "S256")
            .put("device_label", deviceLabel)
            .toString()

        val respText = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/auth/mobile/start",
            bodyJson = reqJson,
            bearerToken = null
        )
        val root = JSONObject(respText)
        if (!root.optBoolean("ok", false)) {
            val errMsg = root.optJSONObject("error")?.optString("message") ?: "启动 SSO 登录事务失败"
            throw IllegalStateException(errMsg)
        }
        val data = root.getJSONObject("data")
        val loginTxn = data.getString("login_txn")
        val authorizeUrl = validateAllowedCloudUrl(data.getString("authorize_url")).toString()
        return MobileAuthTransactionStart(
            loginTxn = loginTxn,
            authorizeUrl = authorizeUrl,
            pkce = pkce,
            expiresAtEpochMillis = System.currentTimeMillis() + 15 * 60 * 1000L
        ).also { latestBrowserTxn = it }
    }

    /**
     * 原生 OIDC 直连登录：在 App 原生表单中输入 XPeach 用户名/邮箱、密码（及可选 6 位 2FA TOTP 验证码），
     * 由 Campus Cloud BFF 与 XPeach SSO 主站直接完成认证、签发长期会话并同步最新 🍑 余额。
     */
    fun loginWithNativeOidcCredentials(
        identifier: String,
        password: String,
        totpCode: String? = null,
        installEphemeralId: String,
        deviceLabel: String = "HautCampus Android Native OIDC",
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): NativeOidcLoginResult {
        val cleanId = identifier.trim()
        if (cleanId.isEmpty() || password.isEmpty()) {
            return NativeOidcLoginResult.Failure("请输入 XPeach 用户名（或邮箱）与登录密码")
        }
        val safeEphId = if (installEphemeralId.length >= 8) installEphemeralId else "eph_${installEphemeralId}_android"
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val reqObj = JSONObject()
            .put("identifier", cleanId)
            .put("password", password)
            .put("install_ephemeral_id", safeEphId)
            .put("device_label", deviceLabel)
        if (!totpCode.isNullOrBlank()) {
            reqObj.put("totp_code", totpCode.trim())
        }

        val respRaw = runCatching {
            httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/auth/oidc/native-login",
                bodyJson = reqObj.toString(),
                bearerToken = null
            )
        }.getOrElse { err ->
            return NativeOidcLoginResult.Failure(err.message ?: "连接 XPeach 认证中心失败")
        }
        val root = runCatching { JSONObject(respRaw) }.getOrElse {
            return NativeOidcLoginResult.Failure("认证响应格式异常")
        }
        if (!root.optBoolean("ok", false)) {
            val errMsg = root.optJSONObject("error")?.optString("message") ?: "XPeach 用户名/邮箱或密码不正确"
            return NativeOidcLoginResult.Failure(errMsg)
        }
        val code = root.optString("code")
        val data = root.optJSONObject("data") ?: JSONObject()
        if (code == "TOTP_REQUIRED" || (data.optBoolean("totp_required", false) && !data.has("access_token"))) {
            val msg = data.optString("message").ifBlank { "该账号已开启两步验证 (2FA)，请输入 6 位动态验证码。" }
            return NativeOidcLoginResult.TotpRequired(msg)
        }

        val accessToken = data.getString("access_token")
        val refreshToken = data.optCleanString("refresh_token")
        val cloudUserId = data.getString("cloud_user_id")
        val ssoIssuer = data.optCleanString("sso_issuer", DEFAULT_SSO_ISSUER)
        val ssoSubject = data.getString("sso_subject")
        val displayName = data.optCleanString("username").ifBlank {
            data.optCleanString("display_name").ifBlank { ssoSubject }
        }

        var peachBalance = data.optDouble("peach_balance", 0.0)
        if (peachBalance.isNaN()) peachBalance = 0.0
        var holdSupported = false
        runCatching {
            val walletRaw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/me/wallet",
                bodyJson = null,
                bearerToken = accessToken
            )
            val walletRoot = JSONObject(walletRaw)
            if (walletRoot.optBoolean("ok", false)) {
                val wData = walletRoot.getJSONObject("data")
                if (wData.has("peach_balance")) {
                    val livePeach = wData.optDouble("peach_balance", peachBalance)
                    if (!livePeach.isNaN()) peachBalance = livePeach
                }
                holdSupported = wData.optBoolean("hold_supported", false)
            }
        }
        val storedBalanceUnits = kotlin.math.round(peachBalance * 10_000.0).toLong()

        return NativeOidcLoginResult.Success(
            XpeachCloudAccountProfile(
                cloudUserId = cloudUserId,
                ssoIssuer = ssoIssuer,
                ssoSubject = ssoSubject,
                displayName = displayName,
                peachBalanceMicropeach = storedBalanceUnits,
                holdSupported = holdSupported,
                linkedAtEpochMillis = System.currentTimeMillis(),
                accessToken = accessToken,
                refreshToken = refreshToken
            )
        )
    }

    /**
     * 查询移动端 OIDC 授权事务状态：当用户在系统浏览器完成一键授权切回 App 时，
     * 自动读取已验证的 app_ticket 并使用本地 PKCE code_verifier 完成会话绑定，无需手动粘贴凭据。
     */
    fun pollMobileAuthTransactionTicket(
        loginTxn: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): String? {
        if (loginTxn.isBlank()) return null
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/auth/mobile/status?txn=${Uri.encode(loginTxn.trim())}",
                bodyJson = null,
                bearerToken = null
            )
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return null
            val data = root.optJSONObject("data") ?: return null
            val ticket = data.optCleanString("app_ticket")
            if (ticket.startsWith("tkt_")) ticket else null
        }.getOrNull()
    }

    /**
     * 当 WebView / App Link 捕获到 /app-link/auth/callback?ticket=tkt_...&txn=txn_... 后，
     * 使用单次 app_ticket 与 PKCE code_verifier 向 Campus Cloud 兑换会话并拉取真实用户信息与 🍑 钱包余额。
     */
    fun exchangeMobileTicketForAccount(
        loginTxn: String,
        appTicket: String,
        codeVerifier: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): XpeachCloudAccountProfile {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val reqJson = JSONObject()
            .put("login_txn", loginTxn)
            .put("app_ticket", appTicket)
            .put("code_verifier", codeVerifier)
            .toString()

        val exchangeRaw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/auth/mobile/exchange",
            bodyJson = reqJson,
            bearerToken = null
        )
        val exchangeRoot = JSONObject(exchangeRaw)
        if (!exchangeRoot.optBoolean("ok", false)) {
            val errMsg = exchangeRoot.optJSONObject("error")?.optCleanString("message")?.ifBlank { null } ?: "兑换 XPEACH SSO 凭据失败"
            throw IllegalStateException(errMsg)
        }
        val sessionData = exchangeRoot.getJSONObject("data")
        val accessToken = sessionData.getString("access_token")
        val refreshToken = sessionData.optCleanString("refresh_token")
        val cloudUserId = sessionData.getString("cloud_user_id")
        val ssoIssuer = sessionData.optCleanString("sso_issuer", DEFAULT_SSO_ISSUER)
        val ssoSubject = sessionData.getString("sso_subject")
        val displayName = sessionData.optCleanString("username").ifBlank {
            sessionData.optCleanString("display_name").ifBlank { ssoSubject }
        }

        var peachBalance = sessionData.optDouble("peach_balance", 0.0)
        if (peachBalance.isNaN()) peachBalance = 0.0
        var holdSupported = false
        runCatching {
            val walletRaw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/me/wallet",
                bodyJson = null,
                bearerToken = accessToken
            )
            val walletRoot = JSONObject(walletRaw)
            if (walletRoot.optBoolean("ok", false)) {
                val wData = walletRoot.getJSONObject("data")
                if (wData.has("peach_balance")) {
                    val livePeach = wData.optDouble("peach_balance", peachBalance)
                    if (!livePeach.isNaN()) peachBalance = livePeach
                }
                holdSupported = wData.optBoolean("hold_supported", false)
            }
        }
        val storedBalanceUnits = kotlin.math.round(peachBalance * 10_000.0).toLong()

        return XpeachCloudAccountProfile(
            cloudUserId = cloudUserId,
            ssoIssuer = ssoIssuer,
            ssoSubject = ssoSubject,
            displayName = displayName,
            peachBalanceMicropeach = storedBalanceUnits,
            holdSupported = holdSupported,
            linkedAtEpochMillis = System.currentTimeMillis(),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    /**
     * 从 Campus Cloud 实时拉取 XPeach 主站最新 🍑 余额
     */
    fun fetchLivePeachBalance(
        accessToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Double? {
        if (accessToken.isBlank()) return null
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val walletRaw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/me/wallet",
                bodyJson = null,
                bearerToken = accessToken
            )
            val walletRoot = JSONObject(walletRaw)
            if (!walletRoot.optBoolean("ok", false)) return null
            val wData = walletRoot.getJSONObject("data")
            val v = wData.optDouble("peach_balance", Double.NaN)
            if (v.isNaN()) null else v
        }.getOrNull()
    }

    internal data class ScopedCloudAuthMetadata(
        val authVersion: Int,
        val bindingId: String?,
        val consentId: String?,
        val confirmedByClientAction: Boolean = false
    )

    internal data class ScopedCloudPlanProvenance(
        val planId: String,
        val studentNo: String,
        val authVersion: Int,
        val bindingId: String,
        val consentId: String
    )

    @Volatile
    private var latestDeepLinkCallbackPair: Pair<String, String>? = null

    @Volatile
    private var latestBrowserTxn: MobileAuthTransactionStart? = null

    private val scopedAuthMetadataCache =
        java.util.concurrent.ConcurrentHashMap<String, ScopedCloudAuthMetadata>()

    private val confirmedAuthMetadataStore =
        java.util.concurrent.ConcurrentHashMap<String, ScopedCloudAuthMetadata>()

    private val confirmedPlanProvenanceStore =
        java.util.concurrent.ConcurrentHashMap<String, ScopedCloudPlanProvenance>()

    @Volatile
    private var persistentAuthStoreFile: java.io.File =
        java.io.File(
            java.io.File(System.getProperty("user.dir", "."), ".haut_cloud_auth_store"),
            "haut_campus_cloud_auth_confirmations_v1.json"
        )

    @Volatile
    private var persistentStoreLoaded = false

    private val persistenceLock = Any()

    fun configurePersistentAuthStorageDir(storageDir: java.io.File) {
        synchronized(persistenceLock) {
            val targetFile = java.io.File(storageDir, "haut_campus_cloud_auth_confirmations_v1.json")
            if (persistentAuthStoreFile.absolutePath != targetFile.absolutePath) {
                persistentAuthStoreFile = targetFile
                persistentStoreLoaded = false
                reloadPersistedAuthStoreLocked()
                persistentStoreLoaded = true
            }
        }
    }

    internal fun configurePersistentAuthStoreFileForTest(targetFile: java.io.File) {
        synchronized(persistenceLock) {
            persistentAuthStoreFile = targetFile
            persistentStoreLoaded = false
        }
    }

    private fun computeStorePayloadSha256(confirmedJson: String, plansJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$confirmedJson|$plansJson".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun ensurePersistentAuthStoreLoaded() {
        if (persistentStoreLoaded) return
        synchronized(persistenceLock) {
            if (persistentStoreLoaded) return
            reloadPersistedAuthStoreLocked()
            persistentStoreLoaded = true
        }
    }

    private fun reloadPersistedAuthStoreLocked() {
        try {
            val file = persistentAuthStoreFile
            if (!file.exists()) return
            val raw = file.readText(StandardCharsets.UTF_8)
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val confirmedObj = root.optJSONObject("confirmed_auth") ?: JSONObject()
            val plansObj = root.optJSONObject("confirmed_plans") ?: JSONObject()
            val recordedSha = root.optCleanString("sha256")
            if (recordedSha.isNotEmpty()) {
                val expectedSha = computeStorePayloadSha256(confirmedObj.toString(), plansObj.toString())
                if (recordedSha != expectedSha) {
                    // Corrupted or tampered persistence file -> fail-closed!
                    confirmedAuthMetadataStore.clear()
                    confirmedPlanProvenanceStore.clear()
                    return
                }
            }
            val keys = confirmedObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = confirmedObj.optJSONObject(k) ?: continue
                val meta = ScopedCloudAuthMetadata(
                    authVersion = v.optInt("authVersion", 0),
                    bindingId = v.optCleanString("bindingId").ifBlank { null },
                    consentId = v.optCleanString("consentId").ifBlank { null },
                    confirmedByClientAction = v.optBoolean("confirmedByClientAction", true)
                )
                confirmedAuthMetadataStore[k] = meta
                if (!scopedAuthMetadataCache.containsKey(k)) {
                    scopedAuthMetadataCache[k] = meta
                }
            }
            val pKeys = plansObj.keys()
            while (pKeys.hasNext()) {
                val k = pKeys.next()
                val v = plansObj.optJSONObject(k) ?: continue
                val prov = ScopedCloudPlanProvenance(
                    planId = v.optCleanString("planId"),
                    studentNo = v.optCleanString("studentNo"),
                    authVersion = v.optInt("authVersion", 0),
                    bindingId = v.optCleanString("bindingId"),
                    consentId = v.optCleanString("consentId")
                )
                if (prov.planId.isNotEmpty()) {
                    confirmedPlanProvenanceStore[k] = prov
                }
            }
        } catch (_: Exception) {
            // Corrupted JSON -> fail-closed: clear unverified confirmations
            confirmedAuthMetadataStore.clear()
            confirmedPlanProvenanceStore.clear()
        }
    }

    private fun flushPersistedAuthStoreStrict() {
        synchronized(persistenceLock) {
            try {
                val confirmedObj = JSONObject()
                for ((k, v) in confirmedAuthMetadataStore.entries.sortedBy { it.key }) {
                    confirmedObj.put(
                        k,
                        JSONObject()
                            .put("authVersion", v.authVersion)
                            .put("bindingId", v.bindingId.orEmpty())
                            .put("consentId", v.consentId.orEmpty())
                            .put("confirmedByClientAction", v.confirmedByClientAction)
                    )
                }
                val plansObj = JSONObject()
                for ((k, v) in confirmedPlanProvenanceStore.entries.sortedBy { it.key }) {
                    plansObj.put(
                        k,
                        JSONObject()
                            .put("planId", v.planId)
                            .put("studentNo", v.studentNo)
                            .put("authVersion", v.authVersion)
                            .put("bindingId", v.bindingId)
                            .put("consentId", v.consentId)
                    )
                }
                val sha256 = computeStorePayloadSha256(confirmedObj.toString(), plansObj.toString())
                val root = JSONObject()
                    .put("confirmed_auth", confirmedObj)
                    .put("confirmed_plans", plansObj)
                    .put("sha256", sha256)
                val targetFile = persistentAuthStoreFile
                val parentDir = targetFile.parentFile
                if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                    throw java.io.IOException("无法创建授权确认存储目录: ${parentDir.absolutePath}")
                }
                val tmpFile = java.io.File(targetFile.parentFile, "${targetFile.name}.tmp")
                val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
                java.io.FileOutputStream(tmpFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    fos.fd.sync()
                }
                try {
                    java.nio.file.Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Exception) {
                    if (!tmpFile.renameTo(targetFile)) {
                        throw java.io.IOException("原子重命名授权确认存储文件失败: ${targetFile.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("授权确认信息持久化落盘失败，已拒绝生效", e)
            }
        }
    }

    internal fun extractTokenAccountScope(accessToken: String): String {
        val clean = accessToken.trim()
        if (clean.isEmpty()) return "anon"
        val parts = clean.split('.')
        if (parts.size >= 2) {
            val payloadJson = runCatching {
                String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            }.getOrNull()
            val sub = payloadJson?.let { runCatching { JSONObject(it).optCleanString("sub") }.getOrNull() }
            if (!sub.isNullOrBlank()) return "sub:$sub"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(clean.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "tok:$digest"
    }

    internal fun buildAuthScopeKey(
        cloudBaseUrl: String,
        accessToken: String,
        schoolId: String = "haut",
        studentNo: String? = null
    ): String {
        val cleanBase = cloudBaseUrl.trim().trimEnd('/').lowercase()
        val accountScope = extractTokenAccountScope(accessToken)
        val cleanSchool = schoolId.trim().lowercase().ifEmpty { "haut" }
        val cleanSno = studentNo?.trim().orEmpty().ifEmpty { "*" }
        return "$cleanBase|$accountScope|$cleanSchool|$cleanSno"
    }

    internal fun buildPlanProvenanceKey(
        cloudBaseUrl: String,
        accessToken: String,
        planId: String
    ): String {
        val cleanBase = cloudBaseUrl.trim().trimEnd('/').lowercase()
        val accountScope = extractTokenAccountScope(accessToken)
        return "$cleanBase|$accountScope|plan:${planId.trim()}"
    }

    internal fun recordScopedPlanProvenance(
        cloudBaseUrl: String,
        accessToken: String,
        planId: String,
        studentNo: String,
        authVersion: Int,
        bindingId: String?,
        consentId: String?
    ) {
        if (accessToken.isBlank() || planId.isBlank()) return
        ensurePersistentAuthStoreLoaded()
        val cleanBind = bindingId?.trim().orEmpty()
        val cleanConsent = consentId?.trim().orEmpty()
        if (cleanBind.isEmpty() || cleanConsent.isEmpty() || authVersion <= 0) return
        val key = buildPlanProvenanceKey(cloudBaseUrl, accessToken, planId)
        val prev = confirmedPlanProvenanceStore[key]
        confirmedPlanProvenanceStore[key] = ScopedCloudPlanProvenance(
            planId = planId.trim(),
            studentNo = studentNo.trim(),
            authVersion = authVersion,
            bindingId = cleanBind,
            consentId = cleanConsent
        )
        try {
            flushPersistedAuthStoreStrict()
        } catch (e: Exception) {
            if (prev != null) {
                confirmedPlanProvenanceStore[key] = prev
            } else {
                confirmedPlanProvenanceStore.remove(key)
            }
            throw e
        }
    }

    internal fun resolveScopedPlanProvenance(
        cloudBaseUrl: String,
        accessToken: String,
        planId: String
    ): ScopedCloudPlanProvenance? {
        if (accessToken.isBlank() || planId.isBlank()) return null
        ensurePersistentAuthStoreLoaded()
        return confirmedPlanProvenanceStore[buildPlanProvenanceKey(cloudBaseUrl, accessToken, planId)]
    }

    internal fun recordScopedAuthMetadata(
        cloudBaseUrl: String,
        accessToken: String,
        schoolId: String = "haut",
        studentNo: String?,
        authVersion: Int,
        bindingId: String?,
        consentId: String?,
        confirmedByClientAction: Boolean = true
    ) {
        if (accessToken.isBlank()) return
        ensurePersistentAuthStoreLoaded()
        val cleanSno = studentNo?.trim().orEmpty()
        val meta = ScopedCloudAuthMetadata(
            authVersion = authVersion.coerceAtLeast(0),
            bindingId = bindingId?.trim()?.takeIf { it.isNotEmpty() },
            consentId = consentId?.trim()?.takeIf { it.isNotEmpty() },
            confirmedByClientAction = confirmedByClientAction
        )
        val snoKey = if (cleanSno.isNotEmpty() && !cleanSno.contains("***")) {
            buildAuthScopeKey(cloudBaseUrl, accessToken, schoolId, cleanSno)
        } else {
            null
        }
        val wildcardKey = buildAuthScopeKey(cloudBaseUrl, accessToken, schoolId, null)
        val prevSnoCache = snoKey?.let { scopedAuthMetadataCache[it] }
        val prevSnoConfirmed = snoKey?.let { confirmedAuthMetadataStore[it] }
        val prevWildcardCache = scopedAuthMetadataCache[wildcardKey]
        val prevWildcardConfirmed = confirmedAuthMetadataStore[wildcardKey]

        if (snoKey != null) {
            scopedAuthMetadataCache[snoKey] = meta
            if (confirmedByClientAction && meta.authVersion > 0 && meta.bindingId != null && meta.consentId != null) {
                confirmedAuthMetadataStore[snoKey] = meta
            }
        }
        val existingWildcard = prevWildcardCache
        if (existingWildcard == null || cleanSno.isEmpty()) {
            scopedAuthMetadataCache[wildcardKey] = meta
            if (confirmedByClientAction && meta.authVersion > 0 && meta.bindingId != null && meta.consentId != null) {
                confirmedAuthMetadataStore[wildcardKey] = meta
            }
        } else if (existingWildcard.bindingId != null && existingWildcard.bindingId != meta.bindingId) {
            // Multiple bindings under the same account+school: do not guess bindingId in wildcard entry
            val multiWildcard = ScopedCloudAuthMetadata(
                authVersion = maxOf(existingWildcard.authVersion, meta.authVersion),
                bindingId = null,
                consentId = meta.consentId ?: existingWildcard.consentId,
                confirmedByClientAction = false
            )
            scopedAuthMetadataCache[wildcardKey] = multiWildcard
            confirmedAuthMetadataStore.remove(wildcardKey)
        } else {
            scopedAuthMetadataCache[wildcardKey] = meta
            if (confirmedByClientAction && meta.authVersion > 0 && meta.bindingId != null && meta.consentId != null) {
                confirmedAuthMetadataStore[wildcardKey] = meta
            }
        }
        if (confirmedByClientAction) {
            try {
                flushPersistedAuthStoreStrict()
            } catch (e: Exception) {
                // Fail-closed: rollback in-memory confirmed state if disk write fails!
                if (snoKey != null) {
                    if (prevSnoCache != null) scopedAuthMetadataCache[snoKey] = prevSnoCache else scopedAuthMetadataCache.remove(snoKey)
                    if (prevSnoConfirmed != null) confirmedAuthMetadataStore[snoKey] = prevSnoConfirmed else confirmedAuthMetadataStore.remove(snoKey)
                }
                if (prevWildcardCache != null) scopedAuthMetadataCache[wildcardKey] = prevWildcardCache else scopedAuthMetadataCache.remove(wildcardKey)
                if (prevWildcardConfirmed != null) confirmedAuthMetadataStore[wildcardKey] = prevWildcardConfirmed else confirmedAuthMetadataStore.remove(wildcardKey)
                throw e
            }
        }
    }

    internal fun resolveScopedAuthMetadata(
        cloudBaseUrl: String,
        accessToken: String,
        schoolId: String = "haut",
        studentNo: String? = null
    ): ScopedCloudAuthMetadata? {
        if (accessToken.isBlank()) return null
        ensurePersistentAuthStoreLoaded()
        val cleanSno = studentNo?.trim().orEmpty()
        if (cleanSno.isNotEmpty() && !cleanSno.contains("***")) {
            val key = buildAuthScopeKey(cloudBaseUrl, accessToken, schoolId, cleanSno)
            return scopedAuthMetadataCache[key] ?: confirmedAuthMetadataStore[key]
        }
        val wildcardKey = buildAuthScopeKey(cloudBaseUrl, accessToken, schoolId, null)
        return scopedAuthMetadataCache[wildcardKey] ?: confirmedAuthMetadataStore[wildcardKey]
    }

    internal fun simulateAppRestartKeepPersistedAuthForTest() {
        synchronized(persistenceLock) {
            scopedAuthMetadataCache.clear()
            confirmedAuthMetadataStore.clear()
            confirmedPlanProvenanceStore.clear()
            persistentStoreLoaded = false
            reloadPersistedAuthStoreLocked()
            persistentStoreLoaded = true
        }
    }

    internal fun clearScopedAuthMetadataForTest() {
        synchronized(persistenceLock) {
            scopedAuthMetadataCache.clear()
            confirmedAuthMetadataStore.clear()
            confirmedPlanProvenanceStore.clear()
            runCatching { if (persistentAuthStoreFile.exists()) persistentAuthStoreFile.delete() }
            persistentStoreLoaded = true
        }
    }

    private fun updateScopedAuthMetadataFromSnapshotData(
        cloudBaseUrl: String,
        accessToken: String,
        data: JSONObject?
    ) {
        if (data == null || accessToken.isBlank()) return
        ensurePersistentAuthStoreLoaded()
        val serverAuthVer = data.optInt("auth_version", 0)
        val consentsArr = data.optJSONArray("consents") ?: JSONArray()
        val latestConsentBySchool = mutableMapOf<String, String>()
        for (i in 0 until consentsArr.length()) {
            val c = consentsArr.optJSONObject(i) ?: continue
            if (!c.optCleanString("withdrawn_at").isEmpty()) continue
            val sId = c.optCleanString("school_id", "haut").lowercase()
            val cId = c.optCleanString("id")
            if (cId.isNotEmpty()) {
                latestConsentBySchool[sId] = cId
            }
        }
        val bindingsArr = data.optJSONArray("school_bindings") ?: JSONArray()
        val activeBindingsBySchool = mutableMapOf<String, MutableList<Pair<String, ScopedCloudAuthMetadata>>>()
        for (i in 0 until bindingsArr.length()) {
            val b = bindingsArr.optJSONObject(i) ?: continue
            val sId = b.optCleanString("school_id", "haut").lowercase()
            val bId = b.optCleanString("id")
            val sno = b.optCleanString("student_no")
            val cId = b.optCleanString("consent_id").ifEmpty { latestConsentBySchool[sId].orEmpty() }
            val bVer = b.optInt("auth_version", serverAuthVer).coerceAtLeast(serverAuthVer)
            val bindKey = if (sno.isNotEmpty() && !sno.contains("***")) {
                buildAuthScopeKey(cloudBaseUrl, accessToken, sId, sno)
            } else {
                null
            }
            val prevMeta = bindKey?.let { confirmedAuthMetadataStore[it] ?: scopedAuthMetadataCache[it] }
            val keepConfirmed = prevMeta != null &&
                prevMeta.confirmedByClientAction &&
                prevMeta.authVersion == bVer &&
                prevMeta.bindingId == bId.takeIf { it.isNotEmpty() } &&
                prevMeta.consentId == cId.takeIf { it.isNotEmpty() }
            val meta = ScopedCloudAuthMetadata(
                authVersion = bVer,
                bindingId = bId.takeIf { it.isNotEmpty() },
                consentId = cId.takeIf { it.isNotEmpty() },
                confirmedByClientAction = keepConfirmed
            )
            if (bindKey != null) {
                scopedAuthMetadataCache[bindKey] = meta
            }
            activeBindingsBySchool.getOrPut(sId) { mutableListOf() }.add(sno to meta)
        }
        for ((sId, list) in activeBindingsBySchool) {
            val distinctBindIds = list.mapNotNull { it.second.bindingId }.distinct()
            val distinctStudentNos = list.map { it.first }.filter { it.isNotEmpty() && !it.contains("***") }.distinct()
            val isSingleStudentScope =
                distinctBindIds.size == 1 || (distinctStudentNos.size == 1 && list.all { it.first == distinctStudentNos.first() })
            val wildcardMeta = if (isSingleStudentScope) {
                list.last().second
            } else {
                ScopedCloudAuthMetadata(
                    authVersion = serverAuthVer,
                    bindingId = null,
                    consentId = latestConsentBySchool[sId],
                    confirmedByClientAction = false
                )
            }
            scopedAuthMetadataCache[buildAuthScopeKey(cloudBaseUrl, accessToken, sId, null)] = wildcardMeta
        }
        if (activeBindingsBySchool.isEmpty() && serverAuthVer > 0) {
            val wildcardKey = buildAuthScopeKey(cloudBaseUrl, accessToken, "haut", null)
            scopedAuthMetadataCache[wildcardKey] = ScopedCloudAuthMetadata(
                authVersion = serverAuthVer,
                bindingId = null,
                consentId = latestConsentBySchool["haut"],
                confirmedByClientAction = false
            )
        }
    }

    private fun prefetchScopedAuthMetadata(
        cloudBaseUrl: String,
        accessToken: String
    ) {
        runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/sync/snapshot",
                bodyJson = null,
                bearerToken = accessToken
            )
            val root = JSONObject(raw)
            if (root.optBoolean("ok", false)) {
                updateScopedAuthMetadataFromSnapshotData(cleanBase, accessToken, root.optJSONObject("data"))
            }
        }
    }

    fun getLatestBrowserTxn(): MobileAuthTransactionStart? = latestBrowserTxn

    fun clearLatestBrowserTxn() {
        latestBrowserTxn = null
    }

    fun notifyDeepLinkCallback(url: String?): Pair<String, String>? {
        val pair = extractCallbackTicketAndTxn(url) ?: return null
        val activeTxn = latestBrowserTxn ?: return null
        if (System.currentTimeMillis() > activeTxn.expiresAtEpochMillis) {
            latestBrowserTxn = null
            return null
        }
        if (pair.second != activeTxn.loginTxn) {
            return null
        }
        latestDeepLinkCallbackPair = pair
        return pair
    }

    fun consumeDeepLinkCallback(expectedTxn: String? = null): Pair<String, String>? {
        val pair = latestDeepLinkCallbackPair ?: return null
        latestDeepLinkCallbackPair = null
        if (!expectedTxn.isNullOrBlank() && pair.second != expectedTxn) {
            return null
        }
        return pair
    }

    fun extractCallbackTicketAndTxn(url: String?): Pair<String, String>? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val uri = java.net.URI(url.trim())
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.rawPath.orEmpty()
            val isCustomScheme = scheme == "dayinschool" && host == "auth" && path.startsWith("/callback")
            val isHttpsAppLink = scheme == "https" &&
                host == "campus.yellowpeachxgp.com" &&
                path.startsWith("/app-link/auth/callback")
            if (!isCustomScheme && !isHttpsAppLink) return null
            val query = uri.rawQuery.orEmpty()
            var ticket = ""
            var txn = ""
            for (pair in query.split("&")) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val k = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()).trim()
                val v = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()).trim()
                if (k == "ticket") ticket = v
                if (k == "txn") txn = v
            }
            if (ticket.startsWith("tkt_") && txn.startsWith("txn_")) ticket to txn else null
        }.getOrNull()
    }

    /**
     * 将云端托管预约计划同步提交至 https://campus.yellowpeachxgp.com/api/v1/reservation-plans
     */
    fun syncCloudHostedReservationPlan(
        accessToken: String,
        studentNo: String,
        delegatedSecret: String,
        targetDate: String,
        targetExecuteAtIso: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        planIdempotencyKey: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        repeatDays: Int = 1
    ): String? {
        if (accessToken.isBlank()) return null
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val consentRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/me/consents",
                bodyJson = JSONObject().put("school_id", "haut").toString(),
                bearerToken = accessToken
            )
            val consentData = JSONObject(consentRaw).optJSONObject("data")
            val consentId = consentData?.optString("id").orEmpty()
            val consentAuthVer = consentData?.optInt("auth_version", 0) ?: 0
            val bindInitRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/school-bindings",
                bodyJson = JSONObject()
                    .put("school_id", "haut")
                    .put("student_no", studentNo.trim())
                    .put("consent_id", consentId)
                    .toString(),
                bearerToken = accessToken
            )
            val bindId = JSONObject(bindInitRaw).optJSONObject("data")?.optString("id").orEmpty()
            if (bindId.isNotEmpty()) {
                httpJsonRequest(
                    method = "POST",
                    url = "$cleanBase/api/v1/school-bindings/$bindId/complete",
                    bodyJson = JSONObject()
                        .put("credential_kind", "DELEGATED_SESSION_BUNDLE")
                        .put("consent_id", consentId)
                        .put("delegated_secret", delegatedSecret.ifBlank { "delegated_android_session" })
                        .toString(),
                    bearerToken = accessToken
                )
            }
            recordScopedAuthMetadata(
                cloudBaseUrl = cleanBase,
                accessToken = accessToken,
                schoolId = "haut",
                studentNo = studentNo,
                authVersion = consentAuthVer,
                bindingId = bindId,
                consentId = consentId,
                confirmedByClientAction = true
            )
            recordScopedPlanProvenance(
                cloudBaseUrl = cleanBase,
                accessToken = accessToken,
                planId = planIdempotencyKey,
                studentNo = studentNo,
                authVersion = consentAuthVer.coerceAtLeast(1),
                bindingId = bindId,
                consentId = consentId
            )
            val localTime = targetExecuteAtIso.trim().let { raw ->
                when {
                    raw.matches(Regex("^\\d{2}:\\d{2}:\\d{2}$")) -> raw
                    raw.matches(Regex("^\\d{2}:\\d{2}$")) -> "$raw:00"
                    raw.endsWith("Z") -> runCatching {
                        java.time.Instant.parse(raw)
                            .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .toLocalTime()
                            .let { "%02d:%02d:%02d".format(it.hour, it.minute, it.second) }
                    }.getOrDefault("07:00:00")
                    else -> raw.substringAfter('T', "07:00:00").take(8).let {
                        if (it.length == 5) "$it:00" else if (it.length == 8) it else "07:00:00"
                    }
                }
            }
            val candidateSeatsArr = JSONArray()
            preferredSeatLabel
                .split(",", "，", "、", "/", "|", ";", "；")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(preferredSeatLabel.trim()) }
                .forEach { candidateSeatsArr.put(it) }

            val normalizedRepeatDays = repeatDays.coerceIn(1, 30)
            val planBody = JSONObject()
                .put("school_binding_id", bindId)
                .put("consent_id", consentId)
                .put("area_code", preferredAreaName)
                .put("area_name", preferredAreaName)
                .put("candidate_seats", candidateSeatsArr)
                .put("preferred_area_ids", JSONArray().put(preferredAreaName))
                .put("preferred_seat_ids", candidateSeatsArr)
                .put("target_local_date", targetDate)
                .put("target_local_time", localTime)
                .put("repeat_days", normalizedRepeatDays)
                .put("remaining_days", normalizedRepeatDays)
                .put("replace_existing", true)
                .toString()
            val planRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/reservation-plans",
                bodyJson = planBody,
                bearerToken = accessToken,
                extraHeaders = mapOf("Idempotency-Key" to planIdempotencyKey)
            )
            val planData = JSONObject(planRaw).optJSONObject("data")
            planData?.optString("job_id")?.takeIf { it.isNotBlank() }
                ?: planData?.optString("plan_id")
        }.getOrNull()
    }

    /**
     * 显式重新确认学校绑定与待恢复的云端预约计划，生成新授权世代确认记录并恢复同步（保留旧记录审计，不自动篡改旧快照）
     */
    fun reconfirmSchoolBindingAndPlans(
        accessToken: String,
        studentNo: String,
        delegatedSecret: String,
        plansToReconfirm: List<CloudUnsyncedPlanUpsertDto> = emptyList(),
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Boolean {
        if (accessToken.isBlank() || studentNo.isBlank()) return false
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val consentRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/me/consents",
                bodyJson = JSONObject().put("school_id", "haut").toString(),
                bearerToken = accessToken
            )
            val consentData = JSONObject(consentRaw).optJSONObject("data") ?: return false
            val consentId = consentData.optString("id").trim()
            val consentAuthVer = consentData.optInt("auth_version", 1).coerceAtLeast(1)
            if (consentId.isEmpty()) return false

            val bindInitRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/school-bindings",
                bodyJson = JSONObject()
                    .put("school_id", "haut")
                    .put("student_no", studentNo.trim())
                    .put("consent_id", consentId)
                    .toString(),
                bearerToken = accessToken
            )
            val bindId = JSONObject(bindInitRaw).optJSONObject("data")?.optString("id")?.trim().orEmpty()
            if (bindId.isEmpty()) return false

            httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/school-bindings/$bindId/complete",
                bodyJson = JSONObject()
                    .put("credential_kind", "DELEGATED_SESSION_BUNDLE")
                    .put("consent_id", consentId)
                    .put("delegated_secret", delegatedSecret.ifBlank { "delegated_android_session" })
                    .toString(),
                bearerToken = accessToken
            )

            recordScopedAuthMetadata(
                cloudBaseUrl = cleanBase,
                accessToken = accessToken,
                schoolId = "haut",
                studentNo = studentNo.trim(),
                authVersion = consentAuthVer,
                bindingId = bindId,
                consentId = consentId,
                confirmedByClientAction = true
            )

            for (p in plansToReconfirm) {
                recordScopedPlanProvenance(
                    cloudBaseUrl = cleanBase,
                    accessToken = accessToken,
                    planId = p.planId,
                    studentNo = (p.studentNo ?: studentNo).trim(),
                    authVersion = consentAuthVer,
                    bindingId = bindId,
                    consentId = consentId
                )
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 立即在云端执行指定预约任务 (`/api/v1/reservation-jobs/{jobId}/execute-now`)
     */
    fun executeCloudHostedReservationJobNow(
        accessToken: String,
        jobId: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): AutomatedSeatBookingResult {
        if (accessToken.isBlank() || jobId.isBlank()) {
            return AutomatedSeatBookingResult(
                confirmed = false,
                bookingId = "",
                seatTitle = "",
                evidenceSha256 = "",
                diagnosticMessage = "云端抢座凭证或任务号缺失，状态未知，已停止执行",
                outcomeState = "RESULT_UNKNOWN",
                externalExecutionState = "RESULT_UNKNOWN"
            )
        }
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/reservation-jobs/$jobId/execute-now",
                bodyJson = "{}",
                bearerToken = accessToken
            )
            parseExecuteNowBookingResult(jobId = jobId, raw = raw)
        }.getOrElse {
            AutomatedSeatBookingResult(
                confirmed = false,
                bookingId = "",
                seatTitle = "",
                evidenceSha256 = "",
                diagnosticMessage = "云端抢座执行状态未知，已停止本地重复提交，请稍后刷新查看核验结果",
                outcomeState = "RESULT_UNKNOWN",
                externalExecutionState = "RESULT_UNKNOWN"
            )
        }
    }

    internal data class NormalizedCloudJobConfirmation(
        val state: String,
        val displayStatus: String,
        val userIntentState: String,
        val externalExecutionState: String,
        val schoolCancellationState: String,
        val billingState: String,
        val hasCompleteConfirmedData: Boolean,
        val isDelegationStopped: Boolean,
        val verifiedBookingId: String,
        val seatAlias: String,
        val regionAlias: String,
        val evidenceSha256: String
    )

    internal fun normalizeCloudJobConfirmation(
        jobId: String,
        job: JSONObject,
        sr: JSONObject?,
        orderObj: JSONObject? = null,
        defaultStateWhenMissing: String = "RESULT_UNKNOWN"
    ): NormalizedCloudJobConfirmation {
        val rawState = job.optCleanString("state", defaultStateWhenMissing)
        val rawDisplay = job.optCleanString("display_status", rawState)
        val userIntentState = job.optCleanString(
            "user_intent_state",
            if (rawState == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" || rawDisplay == "DELEGATION_STOPPED_SCHOOL_CONFIRMED") {
                "REVOKED"
            } else {
                "ACTIVE"
            }
        )
        val rawExternalExecState = job.optCleanString(
            "external_execution_state",
            when {
                rawState in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") -> "SCHOOL_CONFIRMED"
                rawState in setOf("FAILED", "EXPIRED") -> "SCHOOL_REJECTED"
                rawState in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "CANCELLED", "NEEDS_RECONFIRMATION") -> "NOT_DISPATCHED"
                else -> "RESULT_UNKNOWN"
            }
        )
        val schoolCancelState = job.optCleanString(
            "school_cancellation_state",
            if (rawState == "DELEGATION_STOPPED_SCHOOL_CONFIRMED") "MANUAL_ACTION_REQUIRED" else "NOT_REQUESTED"
        )
        val rawBookingId = sr?.optCleanString("external_booking_id")
            ?.ifBlank { job.optCleanString("external_booking_id") }
            .orEmpty()
        val seatAlias = sr?.optCleanString("seat_alias")
            ?.ifBlank { job.optCleanString("confirmed_seat_name") }
            .orEmpty()
        val regionAlias = sr?.optCleanString("region_alias")
            ?.ifBlank { job.optCleanString("confirmed_region_name") }
            .orEmpty()
        val evidenceHash = sr?.optCleanString("evidence_sha256").orEmpty()

        val isRealBookingId = rawBookingId.isNotBlank() &&
            rawBookingId != jobId &&
            !rawBookingId.startsWith("cjob_") &&
            !rawBookingId.startsWith("plan_") &&
            !Regex("""^haut_\d{4}-\d{2}-\d{2}_""").containsMatchIn(rawBookingId)

        val claimsConfirmed =
            rawState in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") ||
                rawDisplay in setOf("CONFIRMED", "SCHOOL_CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") ||
                rawExternalExecState == "SCHOOL_CONFIRMED"

        val isDelegationStopped =
            userIntentState in setOf("REVOKED", "STOP_REQUESTED") ||
                rawState == "DELEGATION_STOPPED_SCHOOL_CONFIRMED" ||
                rawDisplay in setOf("DELEGATION_STOPPED_SCHOOL_CONFIRMED", "DELEGATION_STOPPED_RESULT_UNKNOWN")

        val hasCompleteConfirmedData =
            claimsConfirmed &&
                sr != null &&
                isRealBookingId &&
                seatAlias.isNotBlank() &&
                regionAlias.isNotBlank() &&
                evidenceHash.isNotBlank()

        val safeState = when {
            claimsConfirmed && !hasCompleteConfirmedData ->
                if (isDelegationStopped) "DELEGATION_STOPPED_RESULT_UNKNOWN" else "RESULT_UNKNOWN"
            hasCompleteConfirmedData && isDelegationStopped -> "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
            hasCompleteConfirmedData -> "CONFIRMED"
            else -> rawState
        }
        val safeDisplayStatus = if (claimsConfirmed && !hasCompleteConfirmedData) {
            safeState
        } else {
            rawDisplay
        }
        val safeExecState = when {
            hasCompleteConfirmedData -> "SCHOOL_CONFIRMED"
            claimsConfirmed && !hasCompleteConfirmedData -> "RESULT_UNKNOWN"
            else -> rawExternalExecState
        }
        val safeBillingState = when {
            claimsConfirmed && !hasCompleteConfirmedData -> "HELD_PENDING_REVIEW"
            orderObj?.optCleanString("payment_state")?.isNotBlank() == true ->
                orderObj.optCleanString("payment_state")
            else -> job.optCleanString(
                "billing_state",
                when {
                    hasCompleteConfirmedData && isDelegationStopped -> "HELD_PENDING_REVIEW"
                    hasCompleteConfirmedData -> "PAID"
                    else -> "NOT_BILLED"
                }
            )
        }
        return NormalizedCloudJobConfirmation(
            state = safeState,
            displayStatus = safeDisplayStatus,
            userIntentState = userIntentState,
            externalExecutionState = safeExecState,
            schoolCancellationState = schoolCancelState,
            billingState = safeBillingState,
            hasCompleteConfirmedData = hasCompleteConfirmedData,
            isDelegationStopped = isDelegationStopped,
            verifiedBookingId = if (hasCompleteConfirmedData) rawBookingId else "",
            seatAlias = if (hasCompleteConfirmedData) seatAlias else "",
            regionAlias = if (hasCompleteConfirmedData) regionAlias else "",
            evidenceSha256 = if (hasCompleteConfirmedData) evidenceHash else ""
        )
    }

    internal fun parseExecuteNowBookingResult(
        jobId: String,
        raw: String
    ): AutomatedSeatBookingResult {
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) {
            val errObj = root.optJSONObject("error")
            val errCode = errObj?.optCleanString("code").orEmpty()
            val msg = errObj?.optCleanString("message")?.ifBlank { "云端抢座请求失败" } ?: "云端抢座请求失败"
            val isExplicitPreDispatchReject = errCode in setOf(
                "BAD_REQUEST",
                "UNPROCESSABLE_ENTITY",
                "CAMPUS_BINDING_REQUIRED",
                "CONSENT_REQUIRED",
                "FORBIDDEN",
                "NOT_FOUND"
            )
            return AutomatedSeatBookingResult(
                confirmed = false,
                bookingId = "",
                seatTitle = "",
                evidenceSha256 = "",
                diagnosticMessage = msg,
                outcomeState = if (isExplicitPreDispatchReject) "FAILED" else "RESULT_UNKNOWN",
                externalExecutionState = if (isExplicitPreDispatchReject) "SCHOOL_REJECTED" else "RESULT_UNKNOWN"
            )
        }
        val data = root.optJSONObject("data")
        val job = data?.optJSONObject("job")
        if (data == null || job == null) {
            return AutomatedSeatBookingResult(
                confirmed = false,
                bookingId = "",
                seatTitle = "",
                evidenceSha256 = "",
                diagnosticMessage = "云端抢座响应数据缺失，状态未知，已停止重试",
                outcomeState = "RESULT_UNKNOWN",
                externalExecutionState = "RESULT_UNKNOWN"
            )
        }
        val sr = data.optJSONObject("schoolReservation") ?: data.optJSONObject("school_reservation")
        val orderObj = data.optJSONObject("order")
        val norm = normalizeCloudJobConfirmation(
            jobId = jobId,
            job = job,
            sr = sr,
            orderObj = orderObj,
            defaultStateWhenMissing = "RESULT_UNKNOWN"
        )
        val outcomeState = when {
            norm.hasCompleteConfirmedData && norm.isDelegationStopped -> "DELEGATION_STOPPED_SCHOOL_CONFIRMED"
            norm.hasCompleteConfirmedData -> "CONFIRMED"
            norm.state == "DELEGATION_STOPPED_RESULT_UNKNOWN" || norm.displayStatus == "DELEGATION_STOPPED_RESULT_UNKNOWN" ->
                "DELEGATION_STOPPED_RESULT_UNKNOWN"
            norm.state == "RESULT_UNKNOWN" -> "RESULT_UNKNOWN"
            norm.state in setOf("FAILED", "CANCELLED", "EXPIRED", "SCHOOL_CANCELLED") -> "FAILED"
            else -> "RESULT_UNKNOWN"
        }
        return AutomatedSeatBookingResult(
            confirmed = norm.hasCompleteConfirmedData && !norm.isDelegationStopped && norm.userIntentState == "ACTIVE",
            bookingId = norm.verifiedBookingId,
            seatTitle = norm.seatAlias,
            evidenceSha256 = norm.evidenceSha256,
            diagnosticMessage = when {
                norm.hasCompleteConfirmedData && norm.isDelegationStopped ->
                    "委托已停止，但学校预约已成功（座位：${norm.regionAlias} ${norm.seatAlias}，预约号：${norm.verifiedBookingId}）。系统未自动扣费且已停止后续计划，请核对或按需退座"
                norm.hasCompleteConfirmedData ->
                    "云端抢座（测试版）成功！已锁定 ${norm.seatAlias}（预约号 ${norm.verifiedBookingId}），已消耗 1 🍑 内测防滥用配额"
                outcomeState == "DELEGATION_STOPPED_RESULT_UNKNOWN" ->
                    "委托已停止，但此前已发出的请求或确认凭据不完整，结果待核对（禁止盲目重试）"
                outcomeState == "RESULT_UNKNOWN" ->
                    "云端返回结果待核对或缺少完整学校预约核验凭据，状态未知，已停止重试"
                else ->
                    "云端抢座（测试版）未锁定座位（状态：${norm.state}），未消耗内测配额"
            },
            outcomeState = outcomeState,
            userIntentState = norm.userIntentState,
            externalExecutionState = norm.externalExecutionState,
            schoolCancellationState = norm.schoolCancellationState,
            billingState = norm.billingState
        )
    }

    enum class EngineSwitchCloudCheckOutcome {
        SAFE_TO_SWITCH_STOPPED,
        BLOCKED_ALREADY_DISPATCHED_OR_UNKNOWN,
        BLOCKED_ALREADY_CONFIRMED,
        BLOCKED_OFFLINE_UNCOORDINATED
    }

    /**
     * 在从云端抢座切换为本机抢座前，先向云端停止旧计划/任务并核实是否已有在途或已完成的学校预约请求。
     * 若离线无法与云端协调，或云端任务已进入派发/待核对/已确认状态，则拒绝切换以防止本机与云端重复预约。
     */
    fun coordinateStopCloudJobBeforeEngineSwitch(
        accessToken: String,
        cloudJobId: String?,
        planId: String,
        targetLocalDate: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Pair<EngineSwitchCloudCheckOutcome, String> {
        if (accessToken.isBlank()) {
            return EngineSwitchCloudCheckOutcome.BLOCKED_OFFLINE_UNCOORDINATED to
                "当前未连接云端会话，无法核实并停止已排队的云端任务；为避免本机与云端重复预约，已阻止自动切换，请联网核实后再操作"
        }
        return try {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val cleanJobId = cloudJobId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("cjob_") }
            if (cleanJobId != null) {
                runCatching {
                    httpJsonRequest(
                        method = "POST",
                        url = "$cleanBase/api/v1/reservation-jobs/$cleanJobId/cancel",
                        bodyJson = "{}",
                        bearerToken = accessToken
                    )
                }
            }
            if (planId.isNotBlank()) {
                runCatching {
                    httpJsonRequest(
                        method = "DELETE",
                        url = "$cleanBase/api/v1/reservation-plans/${planId.removePrefix("plan_")}",
                        bodyJson = null,
                        bearerToken = accessToken
                    )
                }
            }
            val snapRaw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/sync/snapshot",
                bodyJson = null,
                bearerToken = accessToken
            )
            val root = JSONObject(snapRaw)
            if (!root.optBoolean("ok", false)) {
                return EngineSwitchCloudCheckOutcome.BLOCKED_OFFLINE_UNCOORDINATED to
                    "无法从云端核实当前任务状态，不能确保云端未发起预约，已阻止切换为本机抢座"
            }
            val data = root.optJSONObject("data") ?: JSONObject()
            val jobsArr = data.optJSONArray("reservation_jobs") ?: JSONArray()
            for (i in 0 until jobsArr.length()) {
                val j = jobsArr.optJSONObject(i) ?: continue
                val jId = j.optCleanString("id")
                val jDate = j.optCleanString("booking_local_date")
                if ((cleanJobId != null && jId == cleanJobId) || jDate == targetLocalDate) {
                    val st = j.optCleanString("state", "ARMED")
                    val extExec = j.optCleanString("external_execution_state", "NOT_DISPATCHED")
                    if (extExec == "SCHOOL_CONFIRMED" || st in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED")) {
                        return EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_CONFIRMED to
                            "云端或另一台设备已完成该日期的学校座位预约，无需再切换为本机重复预约"
                    }
                    if (extExec in setOf("DISPATCH_INTENT", "RESULT_UNKNOWN") ||
                        st in setOf("EXECUTING", "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN")
                    ) {
                        return EngineSwitchCloudCheckOutcome.BLOCKED_ALREADY_DISPATCHED_OR_UNKNOWN to
                            "云端抢座请求已发出或结果待核对，为避免本机与云端重复预约，已阻止切换至本机引擎"
                    }
                    if (st !in setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED")) {
                        return EngineSwitchCloudCheckOutcome.BLOCKED_OFFLINE_UNCOORDINATED to
                            "云端排队任务尚未确认停止（状态：$st），已阻止切换以避免重复预约"
                    }
                }
            }
            EngineSwitchCloudCheckOutcome.SAFE_TO_SWITCH_STOPPED to "已停止云端排队任务并核实无在途请求"
        } catch (_: Exception) {
            EngineSwitchCloudCheckOutcome.BLOCKED_OFFLINE_UNCOORDINATED to
                "当前网络离线或无法与云端协调停止旧任务，不能承诺避免重复预约，已阻止自动切换；请联网核实后再操作"
        }
    }

    internal fun isCloudSeatCancelConfirmed(raw: String): Boolean {
        return runCatching {
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return false
            val data = root.optJSONObject("data") ?: return false
            val liveCancel = data.optJSONObject("live_cancel")
            val jobObj = data.optJSONObject("job")
            if (liveCancel == null && jobObj == null) return false

            val liveConfirmed = liveCancel != null &&
                liveCancel.optBoolean("cancelled", false) &&
                liveCancel.optCleanString("outcome", "SCHOOL_CANCELLED") == "SCHOOL_CANCELLED" &&
                liveCancel.optCleanString("school_cancellation_state", "SCHOOL_CANCELLED") == "SCHOOL_CANCELLED"

            // 严禁把 job.state == "CANCELLED"（仅代表停止云端委托、未曾发起预约）当作学校端退座成功！
            val jobConfirmed = jobObj != null &&
                jobObj.optCleanString("school_cancellation_state") == "SCHOOL_CANCELLED"

            if (liveCancel != null && jobObj != null) {
                liveConfirmed && jobConfirmed
            } else {
                liveConfirmed || jobConfirmed
            }
        }.getOrDefault(false)
    }

    /**
     * 在云端取消并释放已占座位 (`/api/v1/reservation-jobs/{jobId}/cancel-seat`)
     */
    fun cancelCloudHostedReservationSeat(
        accessToken: String,
        jobId: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Boolean {
        if (accessToken.isBlank() || jobId.isBlank()) return false
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/reservation-jobs/$jobId/cancel-seat",
                bodyJson = "{}",
                bearerToken = accessToken
            )
            isCloudSeatCancelConfirmed(raw)
        }.getOrDefault(false)
    }

    /**
     * 使用 refresh_token 自动续期云端会话，返回 (newAccessToken, newRefreshToken)
     */
    fun refreshCloudSession(
        refreshToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Pair<String, String>? {
        if (refreshToken.isBlank()) return null
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/auth/refresh",
                bodyJson = JSONObject().put("refresh_token", refreshToken.trim()).toString(),
                bearerToken = null
            )
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return null
            val data = root.getJSONObject("data")
            val nextAt = data.optString("access_token").trim()
            val nextRt = data.optString("refresh_token").trim()
            if (nextAt.isNotEmpty() && nextRt.isNotEmpty()) nextAt to nextRt else null
        }.getOrNull()
    }

    /**
     * 与 https://campus.yellowpeachxgp.com/api/v1/sync/snapshot 进行双向同步：
     * 1. 上推本地绑定的学号、本地导入/小米同步的快递包裹与已取件状态、已取消的预约任务
     * 2. 拉回 XPeach 最新 🍑 余额、网页端创建的占座预约计划与账单、网页端录入的快递包裹
     */
    fun syncCloudSnapshot(
        accessToken: String,
        studentNo: String?,
        delegatedSecret: String?,
        localParcels: List<CloudSyncedParcelDto>,
        cancelledJobOrPlanIds: List<String>,
        localActiveCloudPlans: List<CloudUnsyncedPlanUpsertDto> = emptyList(),
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): CloudSyncSnapshotResult? {
        if (accessToken.isBlank()) return null
        return runCatching {
            ensurePersistentAuthStoreLoaded()
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val cleanStudentNo = studentNo?.trim().orEmpty()
            val hasBindingOrPlanPush = cleanStudentNo.isNotEmpty() || localActiveCloudPlans.isNotEmpty()
            val priorLocalMeta = resolveScopedAuthMetadata(cleanBase, accessToken, "haut", cleanStudentNo.ifEmpty { null })
            if (hasBindingOrPlanPush &&
                (priorLocalMeta == null || priorLocalMeta.authVersion <= 0 || priorLocalMeta.bindingId.isNullOrBlank() || priorLocalMeta.consentId.isNullOrBlank())
            ) {
                prefetchScopedAuthMetadata(cleanBase, accessToken)
            }
            val currentMeta = resolveScopedAuthMetadata(cleanBase, accessToken, "haut", cleanStudentNo.ifEmpty { null })
            val knownAuthVer = currentMeta?.authVersion ?: 0
            val knownBindId = currentMeta?.bindingId
            val knownConsentId = currentMeta?.consentId

            val itemOutcomes = mutableListOf<CloudSyncItemOutcome>()
            val needsReconfirmationBindings = mutableListOf<String>()
            val needsReconfirmationPlans = mutableListOf<String>()

            // 1. 判定当前学号绑定是否具备当前授权世代的显式确认
            val canPushBinding = when {
                cleanStudentNo.isEmpty() -> false
                knownAuthVer <= 1 -> true
                else -> currentMeta?.confirmedByClientAction == true &&
                    !knownBindId.isNullOrBlank() &&
                    !knownConsentId.isNullOrBlank()
            }
            if (cleanStudentNo.isNotEmpty()) {
                if (canPushBinding) {
                    itemOutcomes.add(
                        CloudSyncItemOutcome(
                            itemType = "BINDING",
                            itemId = cleanStudentNo,
                            status = "SYNCED"
                        )
                    )
                } else {
                    needsReconfirmationBindings.add(cleanStudentNo)
                    itemOutcomes.add(
                        CloudSyncItemOutcome(
                            itemType = "BINDING",
                            itemId = cleanStudentNo,
                            status = "NEEDS_RECONFIRMATION",
                            reason = "学校账号授权世代已更新（当前 v$knownAuthVer），需重新确认授权后方可同步凭据"
                        )
                    )
                }
            }

            // 2. 逐笔校验本地待上推预约计划的授权版本、binding_id 与 consent_id 对应关系（严禁无来源旧计划借绑定级确认通过！）
            val eligiblePlanUpserts = mutableListOf<Pair<CloudUnsyncedPlanUpsertDto, ScopedCloudAuthMetadata>>()
            for (lp in localActiveCloudPlans) {
                val planSno = lp.studentNo?.trim()?.takeIf { it.isNotEmpty() } ?: cleanStudentNo
                val planScopeMeta = resolveScopedAuthMetadata(cleanBase, accessToken, "haut", planSno.ifEmpty { null })
                    ?: currentMeta
                val serverVer = planScopeMeta?.authVersion ?: knownAuthVer
                val serverBindId = planScopeMeta?.bindingId
                val serverConsentId = planScopeMeta?.consentId

                val persistedProv = resolveScopedPlanProvenance(cleanBase, accessToken, lp.planId)
                val recordedAuthVer = if (lp.createdAuthVersion > 0) lp.createdAuthVersion else (persistedProv?.authVersion ?: 0)
                val recordedBindId = lp.boundBindingId?.trim()?.takeIf { it.isNotEmpty() } ?: persistedProv?.bindingId
                val recordedConsentId = lp.boundConsentId?.trim()?.takeIf { it.isNotEmpty() } ?: persistedProv?.consentId

                val isEligible = when {
                    // 无可验证授权来源（recordedAuthVer <= 0 或缺少明确 binding/consent 归属）的计划：
                    // 一律暂停并要求显式重新确认，绝不通过当前绑定或默认版本猜测授权！
                    recordedAuthVer <= 0 || recordedBindId.isNullOrBlank() || recordedConsentId.isNullOrBlank() -> false
                    recordedAuthVer != serverVer.coerceAtLeast(1) -> false
                    !serverBindId.isNullOrBlank() && recordedBindId != serverBindId -> false
                    !serverConsentId.isNullOrBlank() && recordedConsentId != serverConsentId -> false
                    serverVer <= 1 -> true
                    planScopeMeta?.confirmedByClientAction == true -> true
                    else -> false
                }

                if (isEligible) {
                    val effectiveMeta = ScopedCloudAuthMetadata(
                        authVersion = serverVer.coerceAtLeast(1),
                        bindingId = serverBindId ?: recordedBindId,
                        consentId = serverConsentId ?: recordedConsentId,
                        confirmedByClientAction = true
                    )
                    eligiblePlanUpserts.add(lp to effectiveMeta)
                    itemOutcomes.add(
                        CloudSyncItemOutcome(
                            itemType = "PLAN",
                            itemId = lp.planId,
                            status = "SYNCED"
                        )
                    )
                } else {
                    needsReconfirmationPlans.add(lp.planId)
                    itemOutcomes.add(
                        CloudSyncItemOutcome(
                            itemType = "PLAN",
                            itemId = lp.planId,
                            status = "NEEDS_RECONFIRMATION",
                            reason = "预约计划缺少可验证的授权版本来源或未经当前授权世代（v${serverVer.coerceAtLeast(1)}）确认，需重新确认后恢复同步"
                        )
                    )
                }
            }

            val bodyObj = JSONObject()
            val effectiveTopAuthVer = when {
                canPushBinding && knownAuthVer > 0 -> knownAuthVer
                eligiblePlanUpserts.isNotEmpty() -> eligiblePlanUpserts.first().second.authVersion
                else -> 0
            }
            if (effectiveTopAuthVer > 0) {
                bodyObj.put("auth_version", effectiveTopAuthVer)
            }
            if (canPushBinding && cleanStudentNo.isNotEmpty()) {
                val bindingObj = JSONObject()
                    .put("school_id", "haut")
                    .put("student_no", cleanStudentNo)
                if (!knownBindId.isNullOrBlank()) {
                    bindingObj.put("binding_id", knownBindId)
                }
                if (!knownConsentId.isNullOrBlank()) {
                    bindingObj.put("consent_id", knownConsentId)
                }
                if (knownAuthVer > 0) {
                    bindingObj.put("auth_version", knownAuthVer)
                }
                if (!delegatedSecret.isNullOrBlank()) {
                    bindingObj.put("delegated_secret", delegatedSecret)
                }
                bodyObj.put(
                    "school_binding_upserts",
                    JSONArray().put(bindingObj)
                )
            }
            val parcelUpserts = JSONArray()
            val parcelStatusUpdates = JSONArray()
            for (p in localParcels) {
                val cleanTracking = p.trackingNumber.cleanNullLiteral()
                if (cleanTracking.isBlank()) continue
                parcelUpserts.put(
                    JSONObject()
                        .put("tracking_number", cleanTracking)
                        .put("carrier_code", p.carrierCode.cleanNullLiteral("cainiao"))
                        .put("carrier_name", p.carrierName.cleanNullLiteral("校园快递"))
                        .put("status", p.status.cleanNullLiteral("IN_TRANSIT"))
                        .put("pickup_code", p.pickupCode.cleanNullLiteral(""))
                        .put("station_name", p.stationName.cleanNullLiteral(""))
                        .put("item_title", p.itemTitle.cleanNullLiteral(""))
                        .put("raw_notification_text", p.rawNotificationText.cleanNullLiteral(""))
                )
                if (p.status == "PICKED_UP") {
                    parcelStatusUpdates.put(
                        JSONObject()
                            .put("tracking_number", cleanTracking)
                            .put("status", "PICKED_UP")
                    )
                }
            }
            bodyObj.put("parcel_upserts", parcelUpserts)
            bodyObj.put("parcel_status_updates", parcelStatusUpdates)
            if (cancelledJobOrPlanIds.isNotEmpty()) {
                val cancelArr = JSONArray()
                cancelledJobOrPlanIds.forEach { cancelArr.put(it) }
                bodyObj.put("reservation_cancellations", cancelArr)
            }
            if (eligiblePlanUpserts.isNotEmpty()) {
                val planUpsertsArr = JSONArray()
                for ((lp, pMeta) in eligiblePlanUpserts) {
                    val seatsArr = JSONArray()
                    lp.candidateSeats.forEach { seatsArr.put(it) }
                    val planItemObj = JSONObject()
                        .put("plan_id", lp.planId)
                        .put("target_local_date", lp.targetLocalDate)
                        .put("target_local_time", lp.targetLocalTime)
                        .put("area_code", lp.areaName)
                        .put("area_name", lp.areaName)
                        .put("candidate_seats", seatsArr)
                        .put("repeat_days", lp.repeatDays.coerceIn(1, 30))
                        .put("remaining_days", lp.remainingDays.coerceIn(1, 30))
                        .put("allow_alternative_seat", lp.allowAlternativeSeat)
                    if (!pMeta.bindingId.isNullOrBlank()) {
                        planItemObj.put("school_binding_id", pMeta.bindingId)
                    }
                    if (!pMeta.consentId.isNullOrBlank()) {
                        planItemObj.put("consent_id", pMeta.consentId)
                    }
                    if (pMeta.authVersion > 0) {
                        planItemObj.put("auth_version", pMeta.authVersion)
                    }
                    planUpsertsArr.put(planItemObj)
                }
                bodyObj.put("reservation_plan_upserts", planUpsertsArr)
            }

            val raw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/sync/snapshot",
                bodyJson = bodyObj.toString(),
                bearerToken = accessToken
            )
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return null
            val data = root.getJSONObject("data")
            updateScopedAuthMetadataFromSnapshotData(cleanBase, accessToken, data)
            val userObj = data.optJSONObject("user") ?: JSONObject()
            val walletObj = data.optJSONObject("wallet") ?: JSONObject()
            val bindingsArr = data.optJSONArray("school_bindings") ?: JSONArray()
            val jobsArr = data.optJSONArray("reservation_jobs") ?: JSONArray()
            val ordersArr = data.optJSONArray("orders") ?: JSONArray()
            val parcelsArr = data.optJSONArray("parcels") ?: JSONArray()
            val syncReportObj = data.optJSONObject("sync_report")

            val boundStudentNo = if (bindingsArr.length() > 0) {
                val b0 = bindingsArr.optJSONObject(0)
                b0?.optNullableCleanString("student_no")?.takeIf { !it.contains("***") }
            } else null

            val jobs = buildList {
                for (i in 0 until jobsArr.length()) {
                    val j = jobsArr.optJSONObject(i) ?: continue
                    val seatsArr = j.optJSONArray("candidate_seats") ?: JSONArray()
                    val seats = buildList {
                        for (k in 0 until seatsArr.length()) {
                            val s = seatsArr.optString(k).cleanNullLiteral()
                            if (s.isNotEmpty()) add(s)
                        }
                    }
                    val jId = j.optCleanString("id")
                    val sr = j.optJSONObject("school_reservation") ?: j.optJSONObject("schoolReservation")
                    val bookingDate = j.optCleanString("booking_local_date")
                    val repDays = j.optInt("repeat_days", 1).coerceIn(1, 30)
                    val remDays = j.optInt("remaining_days", repDays).coerceIn(0, 30)
                    val norm = normalizeCloudJobConfirmation(
                        jobId = jId,
                        job = j,
                        sr = sr,
                        orderObj = null,
                        defaultStateWhenMissing = "ARMED"
                    )
                    val preservePendingCancelBookingId = if (
                        norm.state == "CANCEL_RESULT_UNKNOWN" ||
                        norm.schoolCancellationState == "CANCEL_RESULT_UNKNOWN" ||
                        norm.schoolCancellationState == "MANUAL_ACTION_REQUIRED"
                    ) {
                        sr?.optNullableCleanString("external_booking_id")
                            ?: j.optNullableCleanString("external_booking_id")
                    } else {
                        null
                    }
                    add(
                        CloudSyncedReservationJobDto(
                            jobId = jId,
                            planId = j.optCleanString("plan_id"),
                            areaCode = j.optCleanString("area_code", "4F_NORTH"),
                            areaName = j.optCleanString("area_name", "4楼北自习区"),
                            candidateSeats = seats,
                            slotWindow = j.optCleanString("slot_window", "08:00-22:00"),
                            targetLocalDate = bookingDate,
                            targetLocalTime = j.optCleanString("target_local_time", "07:00:00"),
                            state = norm.state,
                            externalBookingId = norm.verifiedBookingId.takeIf { it.isNotBlank() } ?: preservePendingCancelBookingId,
                            evidenceSha256 = norm.evidenceSha256.takeIf { it.isNotBlank() },
                            bookedSeatAlias = norm.seatAlias.takeIf { it.isNotBlank() },
                            bookedRegionAlias = norm.regionAlias.takeIf { it.isNotBlank() },
                            repeatDays = repDays,
                            remainingDays = remDays,
                            endLocalDate = j.optCleanString("end_local_date", bookingDate).ifBlank { bookingDate },
                            userIntentState = norm.userIntentState,
                            externalExecutionState = norm.externalExecutionState,
                            schoolCancellationState = norm.schoolCancellationState,
                            displayStatus = norm.displayStatus,
                            statusLabel = j.optCleanString("status_label", ""),
                            billingState = norm.billingState
                        )
                    )
                }
            }

            val orders = buildList {
                for (i in 0 until ordersArr.length()) {
                    val o = ordersArr.optJSONObject(i) ?: continue
                    add(
                        CloudSyncedOrderDto(
                            orderId = o.optCleanString("id"),
                            jobId = o.optCleanString("job_id"),
                            pricePeach = o.optDouble("price_peach", 0.1),
                            paymentState = o.optCleanString("payment_state", "PAID"),
                            paidAtIso = o.optNullableCleanString("paid_at"),
                            createdAtIso = o.optCleanString("created_at")
                        )
                    )
                }
            }

            val parcels = buildList {
                for (i in 0 until parcelsArr.length()) {
                    val p = parcelsArr.optJSONObject(i) ?: continue
                    val tn = p.optCleanString("tracking_number")
                    if (tn.isBlank()) continue
                    add(
                        CloudSyncedParcelDto(
                            id = p.optCleanString("id"),
                            trackingNumber = tn,
                            carrierCode = p.optCleanString("carrier_code", "cainiao"),
                            carrierName = p.optCleanString("carrier_name", "校园快递"),
                            status = p.optCleanString("status", "IN_TRANSIT"),
                            pickupCode = p.optNullableCleanString("pickup_code"),
                            stationName = p.optNullableCleanString("station_name"),
                            itemTitle = p.optNullableCleanString("item_title"),
                            rawNotificationText = p.optNullableCleanString("raw_notification_text")
                        )
                    )
                }
            }

            val campusContentObj = data.optJSONObject("campus_content")
            val parsedCampusContent = campusContentObj?.let { parseCampusContentJson(it) }
            val serverReconfirmCount = syncReportObj?.optInt("plans_needing_reconfirmation_count", 0) ?: 0
            val totalReconfirmCount = needsReconfirmationPlans.size + needsReconfirmationBindings.size + serverReconfirmCount
            val syncStatus = if (totalReconfirmCount > 0) "PARTIAL_NEEDS_RECONFIRMATION" else "FULLY_SYNCED"
            val syncSummaryMessage = when {
                needsReconfirmationPlans.isNotEmpty() ->
                    "余额已更新，${needsReconfirmationPlans.size} 个预约计划需要重新确认授权"
                needsReconfirmationBindings.isNotEmpty() ->
                    "余额已更新，学校绑定（${needsReconfirmationBindings.joinToString(", ")}）需要重新确认授权"
                serverReconfirmCount > 0 ->
                    syncReportObj?.optCleanString("summary_message")?.ifBlank { null }
                        ?: "余额已更新，${serverReconfirmCount} 个预约计划需要重新确认授权"
                else -> "云端数据已完整同步"
            }

            CloudSyncSnapshotResult(
                cloudUserId = userObj.optCleanString("cloud_user_id"),
                ssoSubject = userObj.optCleanString("sso_subject"),
                displayName = userObj.optCleanString("username").ifBlank { userObj.optCleanString("display_name") },
                peachBalance = walletObj.optDouble("peach_balance", 0.0).let { if (it.isNaN()) 0.0 else it },
                peachUsed = walletObj.optDouble("peach_used", 0.0).let { if (it.isNaN()) 0.0 else it },
                group = walletObj.optCleanString("group", "默认分组"),
                affCode = walletObj.optCleanString("aff_code", ""),
                boundStudentNo = boundStudentNo,
                jobs = jobs,
                orders = orders,
                parcels = parcels,
                campusContent = parsedCampusContent,
                campusContentRawJson = campusContentObj?.toString(),
                authVersion = data.optInt("auth_version", 1).coerceAtLeast(1),
                syncStatus = syncStatus,
                syncSummaryMessage = syncSummaryMessage,
                needsReconfirmationBindingStudentNos = needsReconfirmationBindings.distinct(),
                needsReconfirmationPlanIds = needsReconfirmationPlans.distinct(),
                itemOutcomes = itemOutcomes
            )
        }.getOrNull()
    }

    /**
     * 拉取服务器云控下发的校园频道与学生社团配置 (`GET /api/v1/campus-content?school_id=haut`)。
     * 支持免登录公共访问，确保所有学生在打开 App 时第一时间看到云端最新下发的社团与频道内容。
     */
    fun fetchPublicCampusContent(
        schoolId: String = "haut",
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Pair<CampusCommunityCloudCatalog, String>? {
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "GET",
                url = "$cleanBase/api/v1/campus-content?school_id=$schoolId",
                bodyJson = null,
                bearerToken = null
            )
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return null
            val data = root.optJSONObject("data") ?: return null
            val catalog = parseCampusContentJson(data) ?: return null
            catalog to data.toString()
        }.getOrNull()
    }

    fun parseCampusContentJsonString(rawJson: String?): CampusCommunityCloudCatalog? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching {
            parseCampusContentJson(JSONObject(rawJson))
        }.getOrNull()
    }

    fun parseCampusContentJson(data: JSONObject): CampusCommunityCloudCatalog? {
        return runCatching {
            val schoolId = data.optCleanString("school_id", "haut").ifBlank { "haut" }
            val version = data.optLong("version", 1L).coerceAtLeast(1L)
            if (version < 2L) return@runCatching null
            val updatedAt = data.optCleanString("updated_at", "")
            val bObj = data.optJSONObject("bulletin")
            val bulletin = if (bObj != null) {
                CampusCommunityBulletin(
                    enabled = bObj.optBoolean("enabled", true),
                    title = bObj.optCleanString("title", "河工大合作社团与交流群云端直通车"),
                    content = bObj.optCleanString("content", ""),
                    tag = bObj.optCleanString("tag", "云端实时同步").ifBlank { "云端实时同步" },
                    actionUrl = bObj.optCleanString("action_url", "")
                )
            } else {
                CampusCommunityBulletin()
            }

            val chArr = data.optJSONArray("channels")
            val parsedChannels = buildList {
                if (chArr != null) {
                    for (i in 0 until chArr.length()) {
                        val ch = chArr.optJSONObject(i) ?: continue
                        if (!ch.optBoolean("enabled", true)) continue
                        val id = ch.optCleanString("id")
                        val title = ch.optCleanString("title")
                        if (id.isEmpty() || title.isEmpty()) continue
                        add(
                            PresetQqChannelItem(
                                id = id,
                                title = title,
                                subtitle = ch.optCleanString("subtitle", ""),
                                tagLabel = ch.optCleanString("tag_label", ch.optCleanString("tagLabel", "频道")).ifBlank { "频道" },
                                defaultInviteUrl = ch.optCleanString("invite_url", ch.optCleanString("defaultInviteUrl", "https://pd.qq.com/s/hautcampus")),
                                channelNumberHint = ch.optCleanString("channel_number_hint", "点击加入").ifBlank { "点击加入" },
                                enabled = true,
                                pinned = ch.optBoolean("pinned", false),
                                sortOrder = ch.optInt("sort_order", (i + 1) * 10)
                            )
                        )
                    }
                }
            }.sortedWith(compareByDescending<PresetQqChannelItem> { it.pinned }.thenBy { it.sortOrder })

            val clArr = data.optJSONArray("clubs")
            val parsedClubs = buildList {
                if (clArr != null) {
                    for (i in 0 until clArr.length()) {
                        val cl = clArr.optJSONObject(i) ?: continue
                        if (!cl.optBoolean("enabled", true)) continue
                        val id = cl.optCleanString("id")
                        val name = cl.optCleanString("name")
                        if (id.isEmpty() || name.isEmpty()) continue
                        val hlArr = cl.optJSONArray("highlights")
                        val highlights = buildList {
                            if (hlArr != null) {
                                for (k in 0 until hlArr.length()) {
                                    val h = hlArr.optString(k).cleanNullLiteral()
                                    if (h.isNotEmpty()) add(h)
                                }
                            }
                        }
                        add(
                            CampusClubItem(
                                id = id,
                                name = name,
                                category = CampusClubCategory.fromWireName(cl.optCleanString("category")),
                                affiliatedUnit = cl.optCleanString("affiliated_unit", cl.optCleanString("affiliatedUnit", "校团委")).ifBlank { "校团委" },
                                activityVenue = cl.optCleanString("activity_venue", cl.optCleanString("activityVenue", "大学生活动中心")).ifBlank { "大学生活动中心" },
                                summary = cl.optCleanString("summary", ""),
                                qqGroupNumber = cl.optCleanString("qq_group_number", cl.optCleanString("qqGroupNumber", "")),
                                highlights = highlights,
                                enabled = true,
                                pinned = cl.optBoolean("pinned", false),
                                sortOrder = cl.optInt("sort_order", (i + 1) * 10)
                            )
                        )
                    }
                }
            }.sortedWith(compareByDescending<CampusClubItem> { it.pinned }.thenBy { it.sortOrder })

            val compArr = data.optJSONArray("competitions")
            val parsedCompetitions = buildList {
                if (compArr != null) {
                    for (i in 0 until compArr.length()) {
                        val cp = compArr.optJSONObject(i) ?: continue
                        if (!cp.optBoolean("enabled", true)) continue
                        val id = cp.optCleanString("id")
                        val title = cp.optCleanString("title", cp.optCleanString("name"))
                        if (id.isEmpty() || title.isEmpty()) continue
                        val presetFallback = PresetCampusCommunityCatalog.defaultCompetitions.firstOrNull { it.id == id }
                        val majorArr = cp.optJSONArray("recommended_major_tracks")
                            ?: cp.optJSONArray("recommendedMajorTracks")
                        val majorTracks = buildList {
                            if (majorArr != null) {
                                for (k in 0 until majorArr.length()) {
                                    val m = majorArr.optString(k).cleanNullLiteral()
                                    if (m.isNotEmpty()) add(CampusMajorTrack.fromWireName(m))
                                }
                            }
                        }.distinct().ifEmpty {
                            presetFallback?.recommendedMajorTracks ?: listOf(CampusMajorTrack.ALL_MAJORS)
                        }
                        val tagsArr = cp.optJSONArray("interest_tags")
                            ?: cp.optJSONArray("interestTags")
                        val interestTags = buildList {
                            if (tagsArr != null) {
                                for (k in 0 until tagsArr.length()) {
                                    val t = tagsArr.optString(k).cleanNullLiteral()
                                    if (t.isNotEmpty()) add(t)
                                }
                            }
                        }.ifEmpty { presetFallback?.interestTags.orEmpty() }
                        val rawUrl = cp.optCleanString(
                            "official_website_url",
                            cp.optCleanString("officialWebsiteUrl", presetFallback?.officialWebsiteUrl.orEmpty())
                        )
                        val safeWebsiteUrl = if (rawUrl.startsWith("https://", ignoreCase = true)) {
                            rawUrl
                        } else {
                            presetFallback?.officialWebsiteUrl.orEmpty()
                        }
                        add(
                            CampusCompetitionItem(
                                id = id,
                                title = title,
                                shortTitle = cp.optCleanString(
                                    "short_title",
                                    cp.optCleanString("shortTitle", presetFallback?.shortTitle ?: title)
                                ).ifBlank { title },
                                tierBadge = cp.optCleanString(
                                    "tier_badge",
                                    cp.optCleanString("tierBadge", presetFallback?.tierBadge ?: "教育部白名单 A 类赛事")
                                ).ifBlank { "教育部白名单 A 类赛事" },
                                category = CampusCompetitionCategory.fromWireName(
                                    cp.optCleanString("category", presetFallback?.category?.name.orEmpty())
                                ),
                                recommendedMajorTracks = majorTracks,
                                interestTags = interestTags,
                                organizer = cp.optCleanString("organizer", presetFallback?.organizer.orEmpty()),
                                hautHostCollege = cp.optCleanString(
                                    "haut_host_college",
                                    cp.optCleanString("hautHostCollege", presetFallback?.hautHostCollege ?: "创新创业学院")
                                ),
                                registrationWindow = cp.optCleanString(
                                    "registration_window",
                                    cp.optCleanString("registrationWindow", presetFallback?.registrationWindow ?: "关注赛事官网通知")
                                ),
                                teamMode = cp.optCleanString(
                                    "team_mode",
                                    cp.optCleanString("teamMode", presetFallback?.teamMode ?: "1–5人组队")
                                ),
                                difficultyLabel = cp.optCleanString(
                                    "difficulty_label",
                                    cp.optCleanString("difficultyLabel", presetFallback?.difficultyLabel ?: "大一友好")
                                ),
                                beginnerFriendly = cp.optBoolean(
                                    "beginner_friendly",
                                    cp.optBoolean("beginnerFriendly", presetFallback?.beginnerFriendly ?: true)
                                ),
                                summary = cp.optCleanString("summary", presetFallback?.summary.orEmpty()),
                                skillRoadmap = cp.optCleanString(
                                    "skill_roadmap",
                                    cp.optCleanString("skillRoadmap", presetFallback?.skillRoadmap.orEmpty())
                                ),
                                officialWebsiteUrl = safeWebsiteUrl,
                                partnerClubId = cp.optCleanString(
                                    "partner_club_id",
                                    cp.optCleanString("partnerClubId", presetFallback?.partnerClubId.orEmpty())
                                ),
                                partnerClubName = cp.optCleanString(
                                    "partner_club_name",
                                    cp.optCleanString("partnerClubName", presetFallback?.partnerClubName.orEmpty())
                                ),
                                partnerClubQq = cp.optCleanString(
                                    "partner_club_qq",
                                    cp.optCleanString("partnerClubQq", presetFallback?.partnerClubQq.orEmpty())
                                ),
                                enabled = true,
                                pinned = cp.optBoolean("pinned", presetFallback?.pinned ?: false),
                                sortOrder = cp.optInt("sort_order", cp.optInt("sortOrder", (i + 1) * 10))
                            )
                        )
                    }
                }
            }.sortedWith(compareByDescending<CampusCompetitionItem> { it.pinned }.thenBy { it.sortOrder })

            val effectiveCompetitions = if (compArr != null && (parsedCompetitions.isNotEmpty() || version >= 3L)) {
                parsedCompetitions.ifEmpty { PresetCampusCommunityCatalog.defaultCompetitions }
            } else {
                PresetCampusCommunityCatalog.defaultCompetitions
            }

            CampusCommunityCloudCatalog(
                schoolId = schoolId,
                version = version,
                updatedAtIso = updatedAt,
                bulletin = bulletin,
                channels = if (chArr != null) parsedChannels else PresetCampusCommunityCatalog.defaultQqChannels,
                clubs = if (clArr != null) parsedClubs else PresetCampusCommunityCatalog.defaultCampusClubs,
                competitions = effectiveCompetitions,
                isFromCloud = chArr != null || clArr != null || compArr != null
            )
        }.getOrNull()
    }

    internal fun validateAllowedCloudUrl(rawUrl: String): URL {
        val endpoint = OfficialServiceRoutePolicy.parseHttpsEndpoint(rawUrl)
            ?: throw SecurityException("拒绝非 HTTPS 或含非法端口的云服务地址")
        val host = endpoint.host
        val allowed = host == "campus.yellowpeachxgp.com" ||
            host.endsWith(".yellowpeachxgp.com") ||
            host == "xpeach.codes" ||
            host.endsWith(".xpeach.codes") ||
            host == "cloud.dayinschool.haut.edu.cn"
        if (!allowed) {
            throw SecurityException("拒绝向未授权的云端域名发送请求: $host")
        }
        return URL(endpoint.urlText)
    }

    private fun httpJsonRequest(
        method: String,
        url: String,
        bodyJson: String?,
        bearerToken: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val validatedUrl = validateAllowedCloudUrl(url)
        val conn = (validatedUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (bodyJson != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (bodyJson != null) {
                conn.outputStream.use { out ->
                    out.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { input ->
                val maxBytes = 2 * 1024 * 1024
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) {
                        throw IllegalStateException("云端响应体积超出 2MB 安全上限")
                    }
                    out.write(buffer, 0, n)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }.orEmpty()
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun buildDebitIdempotencyKey(orderId: String): String =
        "dis:reservation:$orderId:debit:v1"

    fun buildRefundIdempotencyKey(orderId: String): String =
        "dis:reservation:$orderId:refund:v1"

    // --- Campus Moments (Lost & Found Plaza, Second-Hand Textbooks, Anti-Lost QR Tags) ---
    fun fetchMomentsSnapshot(
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        bearerToken: String? = null
    ): Pair<CampusMomentsSnapshot, String>? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val raw = httpJsonRequest(
            method = "GET",
            url = "$cleanBase/api/v1/moments/snapshot",
            bodyJson = null,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() }
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        val data = root.optJSONObject("data") ?: return null
        val snap = parseMomentsSnapshotJson(data) ?: return null
        snap to data.toString()
    }.getOrNull()

    fun publishMomentsPostToCloud(
        post: MomentsPostItem,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        bearerToken: String? = null,
        contactVisibility: String = "PUBLIC"
    ): MomentsPostItem? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val body = JSONObject()
            .put("id", post.id)
            .put("type", post.type.wireName)
            .put("title", post.title)
            .put("category", post.category)
            .put("campus", post.campus)
            .put("location", post.location)
            .put("description", post.description)
            .put("contact", post.contact)
            .put("reward_note", post.rewardNote)
            .put("author_name", post.authorName)
            .put("author_student_no_masked", post.authorStudentNoMasked)
            .put("contact_visibility", contactVisibility)
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts",
            bodyJson = body,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() }
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseMomentsPostJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun addMomentsCommentToCloud(
        postId: String,
        authorName: String,
        content: String,
        contactHint: String = "",
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        bearerToken: String? = null
    ): MomentsPostItem? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val body = JSONObject()
            .put("author_name", authorName)
            .put("content", content)
            .put("contact_hint", contactHint)
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/comments",
            bodyJson = body,
            bearerToken = bearerToken?.takeIf { it.isNotBlank() }
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseMomentsPostJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun mutateMomentsPostOnCloud(
        postId: String,
        action: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL,
        bearerToken: String? = null,
        reason: String? = null
    ): Boolean = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val bodyObj = JSONObject().put("action", action)
        if (!reason.isNullOrBlank()) {
            bodyObj.put("reason", reason.trim())
        }
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/action",
            bodyJson = bodyObj.toString(),
            bearerToken = bearerToken?.takeIf { it.isNotBlank() }
        )
        JSONObject(raw).optBoolean("ok", false)
    }.getOrDefault(false)

    data class MomentsContactRequestDto(
        val id: String,
        val postId: String,
        val senderUserId: String,
        val senderName: String,
        val message: String,
        val contactInfo: String,
        val status: String,
        val replyMessage: String?,
        val initiatorContact: String?,
        val createdAt: String
    )

    fun submitMomentsContactRequestToCloud(
        postId: String,
        senderName: String,
        message: String,
        contactInfo: String,
        idempotencyKey: String,
        bearerToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): MomentsContactRequestDto? = runCatching {
        if (bearerToken.isBlank()) return null
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val body = JSONObject()
            .put("sender_name", senderName)
            .put("message", message)
            .put("contact_info", contactInfo)
            .put("client_request_id", idempotencyKey)
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/contact-requests",
            bodyJson = body,
            bearerToken = bearerToken
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        val d = root.optJSONObject("data") ?: return null
        MomentsContactRequestDto(
            id = d.optCleanString("id"),
            postId = d.optCleanString("post_id", postId),
            senderUserId = d.optCleanString("sender_user_id"),
            senderName = d.optCleanString("sender_name", senderName),
            message = d.optCleanString("message", message),
            contactInfo = d.optCleanString("contact_info", contactInfo),
            status = d.optCleanString("status", "PENDING"),
            replyMessage = d.optNullableCleanString("reply_message"),
            initiatorContact = d.optNullableCleanString("initiator_contact"),
            createdAt = d.optCleanString("created_at").substringBefore("T").ifBlank { "刚刚" }
        )
    }.getOrNull()

    data class MomentsContactRequestsListResult(
        val isOwner: Boolean = false,
        val canManage: Boolean = false,
        val isLegacyUnowned: Boolean = false,
        val requests: List<MomentsContactRequestDto> = emptyList()
    )

    fun claimLegacyMomentsPostOnCloud(
        postId: String,
        claimToken: String,
        bearerToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): MomentsPostItem? = runCatching {
        val cleanToken = claimToken.trim()
        if (bearerToken.isBlank() || cleanToken.length < 32) return null
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val bodyObj = JSONObject()
            .put("action", "CLAIM_OWNERSHIP")
            .put("claim_token", cleanToken)
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/action",
            bodyJson = bodyObj.toString(),
            bearerToken = bearerToken
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseMomentsPostJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun fetchMomentsContactRequestsResultFromCloud(
        postId: String,
        bearerToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): MomentsContactRequestsListResult = runCatching {
        if (bearerToken.isBlank()) return MomentsContactRequestsListResult()
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val raw = httpJsonRequest(
            method = "GET",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/contact-requests",
            bodyJson = null,
            bearerToken = bearerToken
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return MomentsContactRequestsListResult()
        val dataObj = root.optJSONObject("data") ?: return MomentsContactRequestsListResult()
        val isOwner = dataObj.optBoolean("is_owner", false)
        val canManage = dataObj.optBoolean("can_manage", isOwner)
        val isLegacyUnowned = dataObj.optBoolean("is_legacy_unowned", false)
        val arr = dataObj.optJSONArray("requests") ?: JSONArray()
        val requests = buildList {
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                add(
                    MomentsContactRequestDto(
                        id = d.optCleanString("id"),
                        postId = d.optCleanString("post_id", postId),
                        senderUserId = d.optCleanString("sender_user_id"),
                        senderName = d.optCleanString("sender_name", "河工大同学"),
                        message = d.optCleanString("message"),
                        contactInfo = d.optCleanString("contact_info"),
                        status = d.optCleanString("status", "PENDING"),
                        replyMessage = d.optNullableCleanString("reply_message"),
                        initiatorContact = d.optNullableCleanString("initiator_contact"),
                        createdAt = d.optCleanString("created_at").substringBefore("T").ifBlank { "刚刚" }
                    )
                )
            }
        }
        MomentsContactRequestsListResult(
            isOwner = isOwner,
            canManage = canManage,
            isLegacyUnowned = isLegacyUnowned,
            requests = requests
        )
    }.getOrDefault(MomentsContactRequestsListResult())

    fun fetchMomentsContactRequestsFromCloud(
        postId: String,
        bearerToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): List<MomentsContactRequestDto> =
        fetchMomentsContactRequestsResultFromCloud(postId, bearerToken, cloudBaseUrl).requests

    fun handleMomentsContactRequestActionOnCloud(
        postId: String,
        requestId: String,
        action: String,
        replyMessage: String? = null,
        reason: String? = null,
        bearerToken: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): MomentsContactRequestDto? = runCatching {
        if (bearerToken.isBlank()) return null
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val bodyObj = JSONObject().put("action", action)
        if (!replyMessage.isNullOrBlank()) bodyObj.put("reply_message", replyMessage.trim())
        if (!reason.isNullOrBlank()) bodyObj.put("reason", reason.trim())
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/contact-requests/${Uri.encode(requestId)}/action",
            bodyJson = bodyObj.toString(),
            bearerToken = bearerToken
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        val d = root.optJSONObject("data") ?: return null
        MomentsContactRequestDto(
            id = d.optCleanString("id", requestId),
            postId = d.optCleanString("post_id", postId),
            senderUserId = d.optCleanString("sender_user_id"),
            senderName = d.optCleanString("sender_name", "河工大同学"),
            message = d.optCleanString("message"),
            contactInfo = d.optCleanString("contact_info"),
            status = d.optCleanString("status", action),
            replyMessage = d.optNullableCleanString("reply_message"),
            initiatorContact = d.optNullableCleanString("initiator_contact"),
            createdAt = d.optCleanString("created_at").substringBefore("T").ifBlank { "刚刚" }
        )
    }.getOrNull()

    fun publishMomentsBookToCloud(
        book: MomentsTextbookListing,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): MomentsTextbookListing? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val body = JSONObject()
            .put("id", book.id)
            .put("title", book.title)
            .put("isbn", book.isbn)
            .put("author_publisher", book.authorPublisher)
            .put("course_name", book.courseName)
            .put("major_track", book.majorTrack.name)
            .put("campus", book.campus)
            .put("condition_label", book.conditionLabel)
            .put("price_yuan", book.priceYuan)
            .put("original_price_yuan", book.originalPriceYuan)
            .put("delivery_mode", book.deliveryMode)
            .put("contact", book.contact)
            .put("seller_name", book.sellerName)
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/books",
            bodyJson = body,
            bearerToken = null
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseMomentsBookJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun mutateMomentsBookOnCloud(
        bookId: String,
        action: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Boolean = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/books/${Uri.encode(bookId)}/action",
            bodyJson = JSONObject().put("action", action).toString(),
            bearerToken = null
        )
        JSONObject(raw).optBoolean("ok", false)
    }.getOrDefault(false)

    fun upsertAntiLostTagOnCloud(
        tag: AntiLostTagItem,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): AntiLostTagItem? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val body = JSONObject()
            .put("tag_code", tag.tagCode)
            .put("item_name", tag.itemName)
            .put("category", tag.category)
            .put("campus", tag.campus)
            .put("owner_name", tag.ownerName)
            .put("owner_contact", tag.ownerContact)
            .put("return_note", tag.returnNote)
            .put("status", tag.status)
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/anti-lost-tags",
            bodyJson = body,
            bearerToken = null
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseAntiLostTagJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun reportAntiLostTagFoundOnCloud(
        tagCode: String,
        finderName: String,
        foundLocation: String,
        finderContact: String,
        message: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): AntiLostTagItem? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val body = JSONObject()
            .put("finder_name", finderName)
            .put("found_location", foundLocation)
            .put("finder_contact", finderContact)
            .put("message", message)
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/anti-lost-tags/${Uri.encode(tagCode.trim().uppercase())}/report-found",
            bodyJson = body,
            bearerToken = null
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseAntiLostTagJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun parseMomentsSnapshotJsonString(rawJson: String?): CampusMomentsSnapshot? {
        if (rawJson.isNullOrBlank()) return null
        return runCatching { parseMomentsSnapshotJson(JSONObject(rawJson)) }.getOrNull()
    }

    internal fun isLegacyPrivateMomentsCommentContent(rawContent: String): Boolean =
        rawContent.trim().startsWith("【私密联系发起人】")

    /**
     * 在账号退出、切换云端账号或处于未登录游客态读取缓存时，强制脱敏并清除一切与上一登录身份绑定的受限字段：
     * 1. 所有 `contact_visibility == "ON_REQUEST"` 或标记受保护的帖子重新遮罩 `contact = "需发起联系/认领申请后查看"`、`hasProtectedContact = true`；
     * 2. 清除私密前缀留言及身份权限位 (`isOwner = false`, `canManage = false`, `canClaimLegacy = false`, `viewerRole = "GUEST"`)；
     * 3. 清空本机关联的 `myPostIds`，杜绝退出或切到账号 B 后在断网状态下仍可读/可复制账号 A 曾获批的受限联系方式。
     */
    fun sanitizeMomentsSnapshotForViewerReset(snapshot: CampusMomentsSnapshot): CampusMomentsSnapshot {
        val scrubbedPosts = snapshot.posts.map { post ->
            val isProtectedContact =
                post.contactVisibility.equals("ON_REQUEST", ignoreCase = true) || post.hasProtectedContact
            val safeContact = if (isProtectedContact) {
                cn.edu.haut.campus.core.model.MASKED_ON_REQUEST_MOMENTS_CONTACT
            } else {
                post.contact
            }
            val safeComments = post.comments.filterNot { isLegacyPrivateMomentsCommentContent(it.content) }
            post.copy(
                contact = safeContact,
                hasProtectedContact = isProtectedContact,
                comments = safeComments,
                isOwner = false,
                canManage = false,
                canClaimLegacy = false,
                viewerRole = "GUEST"
            )
        }
        return snapshot.copy(
            posts = scrubbedPosts,
            myPostIds = emptySet()
        )
    }

    fun encodeMomentsSnapshotJsonString(snapshot: CampusMomentsSnapshot): String {
        val root = JSONObject()
        root.put("updated_at", snapshot.updatedAt)
        val postsArr = JSONArray()
        for (p in snapshot.posts) {
            val commentsArr = JSONArray()
            for (c in p.comments) {
                // 清理旧版客户端本地缓存中的「【私密联系发起人】」前缀评论，防止历史私密信息残留于本地公开缓存
                if (isLegacyPrivateMomentsCommentContent(c.content)) continue
                commentsArr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("author_name", c.authorName)
                        .put("content", c.content)
                        .put("contact_hint", c.contactHint)
                        .put("created_at", c.createdAt)
                )
            }
            postsArr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("type", p.type.wireName)
                    .put("title", p.title)
                    .put("category", p.category)
                    .put("campus", p.campus)
                    .put("location", p.location)
                    .put("description", p.description)
                    .put("contact", p.contact)
                    .put("reward_note", p.rewardNote)
                    .put("status", p.status)
                    .put("author_name", p.authorName)
                    .put("author_student_no_masked", p.authorStudentNoMasked)
                    .put("contact_visibility", p.contactVisibility)
                    .put("has_protected_contact", p.hasProtectedContact)
                    .put("is_owner", p.isOwner)
                    .put("can_manage", p.canManage)
                    .put("is_legacy_unowned", p.isLegacyUnowned)
                    .put("can_claim_legacy", p.canClaimLegacy)
                    .put("viewer_role", p.viewerRole)
                    .put("likes_count", p.likesCount)
                    .put("favorites_count", p.favoritesCount)
                    .put("comments", commentsArr)
                    .put("created_at", p.createdAt)
                    .put("updated_at", p.updatedAt)
            )
        }
        root.put("posts", postsArr)

        val booksArr = JSONArray()
        for (b in snapshot.bookListings) {
            booksArr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("isbn", b.isbn)
                    .put("author_publisher", b.authorPublisher)
                    .put("course_name", b.courseName)
                    .put("major_track", b.majorTrack.name)
                    .put("campus", b.campus)
                    .put("condition_label", b.conditionLabel)
                    .put("price_yuan", b.priceYuan)
                    .put("original_price_yuan", b.originalPriceYuan)
                    .put("delivery_mode", b.deliveryMode)
                    .put("contact", b.contact)
                    .put("seller_name", b.sellerName)
                    .put("status", b.status)
                    .put("likes_count", b.likesCount)
                    .put("created_at", b.createdAt)
            )
        }
        root.put("book_listings", booksArr)

        val tagsArr = JSONArray()
        for (t in snapshot.antiLostTags) {
            val repArr = JSONArray()
            for (r in t.reports) {
                repArr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("finder_name", r.finderName)
                        .put("found_location", r.foundLocation)
                        .put("finder_contact", r.finderContact)
                        .put("message", r.message)
                        .put("created_at", r.createdAt)
                )
            }
            tagsArr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("tag_code", t.tagCode)
                    .put("item_name", t.itemName)
                    .put("category", t.category)
                    .put("campus", t.campus)
                    .put("owner_name", t.ownerName)
                    .put("owner_contact", t.ownerContact)
                    .put("return_note", t.returnNote)
                    .put("status", t.status)
                    .put("reports", repArr)
                    .put("created_at", t.createdAt)
                    .put("updated_at", t.updatedAt)
            )
        }
        root.put("anti_lost_tags", tagsArr)
        return root.toString()
    }

    internal fun parseMomentsPostJson(obj: JSONObject): MomentsPostItem? {
        val id = obj.optCleanString("id")
        val title = obj.optCleanString("title")
        if (id.isEmpty() || title.isEmpty()) return null
        val isOwner = obj.optBoolean("is_owner", false)
        val canManage = obj.optBoolean("can_manage", isOwner)
        val isLegacyUnowned = if (obj.has("is_legacy_unowned")) {
            obj.optBoolean("is_legacy_unowned", false)
        } else {
            obj.optCleanString("owner_cloud_user_id").isEmpty()
        }
        val canClaimLegacy = obj.optBoolean("can_claim_legacy", false)
        val viewerRole = obj.optCleanString(
            "viewer_role",
            when {
                isOwner -> "OWNER"
                canManage -> "ADMIN"
                else -> "GUEST"
            }
        )
        val commentsArr = obj.optJSONArray("comments")
        val comments = buildList {
            if (commentsArr != null) {
                for (i in 0 until commentsArr.length()) {
                    val c = commentsArr.optJSONObject(i) ?: continue
                    val content = c.optCleanString("content")
                    if (content.isEmpty()) continue
                    val isPrivateComment =
                        c.optBoolean("is_private_to_initiator", false) ||
                            isLegacyPrivateMomentsCommentContent(content)
                    // 过滤旧客户端本地缓存或未登录投影中的历史「【私密联系发起人】」前缀评论，杜绝作为普通公开留言展示
                    if (isPrivateComment) continue
                    add(
                        MomentsPostComment(
                            id = c.optCleanString("id", "mc_$i"),
                            authorName = c.optCleanString("author_name", "热心同学"),
                            content = content,
                            contactHint = c.optCleanString("contact_hint"),
                            createdAt = c.optCleanString("created_at").substringBefore("T").ifBlank { "刚刚" }
                        )
                    )
                }
            }
        }
        val cluesArr = obj.optJSONArray("matched_clues")
        val matchedClues = buildList {
            if (cluesArr != null) {
                for (i in 0 until cluesArr.length()) {
                    val cl = cluesArr.optJSONObject(i) ?: continue
                    val cid = cl.optCleanString("id")
                    val cTitle = cl.optCleanString("title")
                    if (cid.isEmpty() || cTitle.isEmpty()) continue
                    add(
                        MomentsMatchedClue(
                            id = cid,
                            type = MomentsPostType.fromWireName(cl.optCleanString("type")),
                            title = cTitle,
                            location = cl.optCleanString("location", "校内"),
                            contact = cl.optCleanString("contact"),
                            matchScore = cl.optInt("match_score", 60)
                        )
                    )
                }
            }
        }
        return MomentsPostItem(
            id = id,
            type = MomentsPostType.fromWireName(obj.optCleanString("type")),
            title = title,
            category = obj.optCleanString("category", "其他物品"),
            campus = obj.optCleanString("campus", "莲花街校区"),
            location = obj.optCleanString("location", "校内"),
            description = obj.optCleanString("description"),
            contact = obj.optCleanString("contact"),
            rewardNote = obj.optCleanString("reward_note"),
            status = obj.optCleanString("status", "OPEN"),
            authorName = obj.optCleanString("author_name", "河工大同学"),
            authorStudentNoMasked = obj.optCleanString("author_student_no_masked"),
            likesCount = obj.optInt("likes_count", 0).coerceAtLeast(0),
            favoritesCount = obj.optInt("favorites_count", 0).coerceAtLeast(0),
            comments = comments,
            matchedClues = matchedClues,
            createdAt = obj.optCleanString("created_at").substringBefore("T").ifBlank { "今天" },
            updatedAt = obj.optCleanString("updated_at"),
            contactVisibility = obj.optCleanString("contact_visibility", "PUBLIC"),
            hasProtectedContact = obj.optBoolean(
                "has_protected_contact",
                obj.optCleanString("contact") == cn.edu.haut.campus.core.model.MASKED_ON_REQUEST_MOMENTS_CONTACT
            ),
            isOwner = isOwner,
            canManage = canManage,
            isLegacyUnowned = isLegacyUnowned,
            canClaimLegacy = canClaimLegacy,
            viewerRole = viewerRole
        )
    }

    private fun parseMomentsBookJson(obj: JSONObject): MomentsTextbookListing? {
        val id = obj.optCleanString("id")
        val title = obj.optCleanString("title")
        if (id.isEmpty() || title.isEmpty()) return null
        return MomentsTextbookListing(
            id = id,
            title = title,
            isbn = obj.optCleanString("isbn"),
            authorPublisher = obj.optCleanString("author_publisher", "高校教材"),
            courseName = obj.optCleanString("course_name"),
            majorTrack = CampusMajorTrack.fromWireName(obj.optCleanString("major_track")),
            campus = obj.optCleanString("campus", "莲花街校区"),
            conditionLabel = obj.optCleanString("condition_label", "九成新"),
            priceYuan = obj.optInt("price_yuan", 15).coerceAtLeast(0),
            originalPriceYuan = obj.optInt("original_price_yuan", 55).coerceAtLeast(0),
            deliveryMode = obj.optCleanString("delivery_mode", "莲花街自提"),
            contact = obj.optCleanString("contact"),
            sellerName = obj.optCleanString("seller_name", "河工大同学"),
            status = obj.optCleanString("status", "AVAILABLE"),
            likesCount = obj.optInt("likes_count", 0).coerceAtLeast(0),
            createdAt = obj.optCleanString("created_at").substringBefore("T").ifBlank { "今天" }
        )
    }

    private fun parseAntiLostTagJson(obj: JSONObject): AntiLostTagItem? {
        val tagCode = obj.optCleanString("tag_code").uppercase()
        val itemName = obj.optCleanString("item_name")
        if (tagCode.isEmpty() || itemName.isEmpty()) return null
        val repArr = obj.optJSONArray("reports")
        val reports = buildList {
            if (repArr != null) {
                for (i in 0 until repArr.length()) {
                    val r = repArr.optJSONObject(i) ?: continue
                    val loc = r.optCleanString("found_location")
                    if (loc.isEmpty()) continue
                    add(
                        AntiLostTagReport(
                            id = r.optCleanString("id", "rep_$i"),
                            finderName = r.optCleanString("finder_name", "热心同学"),
                            foundLocation = loc,
                            finderContact = r.optCleanString("finder_contact"),
                            message = r.optCleanString("message"),
                            createdAt = r.optCleanString("created_at").substringBefore("T").ifBlank { "刚刚" }
                        )
                    )
                }
            }
        }
        return AntiLostTagItem(
            id = obj.optCleanString("id", "tag_$tagCode"),
            tagCode = tagCode,
            itemName = itemName,
            category = obj.optCleanString("category", "水杯雨伞"),
            campus = obj.optCleanString("campus", "莲花街校区"),
            ownerName = obj.optCleanString("owner_name", "河工大同学"),
            ownerContact = obj.optCleanString("owner_contact"),
            returnNote = obj.optCleanString("return_note", "拾到请联系失主或放至就近服务台"),
            status = obj.optCleanString("status", "NORMAL"),
            reports = reports,
            createdAt = obj.optCleanString("created_at").substringBefore("T").ifBlank { "今天" },
            updatedAt = obj.optCleanString("updated_at")
        )
    }

    private fun parseMomentsSnapshotJson(data: JSONObject): CampusMomentsSnapshot? {
        return runCatching {
            val postsArr = data.optJSONArray("posts")
            val parsedPosts = buildList {
                if (postsArr != null) {
                    for (i in 0 until postsArr.length()) {
                        val pObj = postsArr.optJSONObject(i) ?: continue
                        parseMomentsPostJson(pObj)?.let { add(it) }
                    }
                }
            }
            val booksArr = data.optJSONArray("book_listings")
            val parsedBooks = buildList {
                if (booksArr != null) {
                    for (i in 0 until booksArr.length()) {
                        val bObj = booksArr.optJSONObject(i) ?: continue
                        parseMomentsBookJson(bObj)?.let { add(it) }
                    }
                }
            }
            val tagsArr = data.optJSONArray("anti_lost_tags")
            val parsedTags = buildList {
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        val tObj = tagsArr.optJSONObject(i) ?: continue
                        parseAntiLostTagJson(tObj)?.let { add(it) }
                    }
                }
            }
            val isbnArr = data.optJSONArray("isbn_catalog")
            val parsedIsbn = buildList {
                if (isbnArr != null) {
                    for (i in 0 until isbnArr.length()) {
                        val c = isbnArr.optJSONObject(i) ?: continue
                        val isbn = c.optCleanString("isbn")
                        val title = c.optCleanString("title")
                        if (isbn.isNotEmpty() && title.isNotEmpty()) {
                            add(
                                MomentsIsbnCatalogEntry(
                                    isbn = isbn,
                                    title = title,
                                    authorPublisher = c.optCleanString("author_publisher", "高等教育出版社"),
                                    courseName = c.optCleanString("course_name"),
                                    majorTrack = CampusMajorTrack.fromWireName(c.optCleanString("major_track")),
                                    originalPriceYuan = c.optInt("original_price_yuan", 50)
                                )
                            )
                        }
                    }
                }
            }
            CampusMomentsSnapshot(
                updatedAt = data.optCleanString("updated_at"),
                posts = parsedPosts.ifEmpty { PresetCampusMomentsCatalog.defaultPosts },
                bookListings = parsedBooks.ifEmpty { PresetCampusMomentsCatalog.defaultBookListings },
                antiLostTags = parsedTags.ifEmpty { PresetCampusMomentsCatalog.defaultAntiLostTags },
                isbnCatalog = parsedIsbn.ifEmpty { PresetCampusMomentsCatalog.defaultIsbnCatalog }
            )
        }.getOrNull()
    }
}
