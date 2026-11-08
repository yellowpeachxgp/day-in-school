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
    val endLocalDate: String = targetLocalDate
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
    val allowAlternativeSeat: Boolean = true
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
    val campusContentRawJson: String? = null
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

    @Volatile
    private var latestDeepLinkCallbackPair: Pair<String, String>? = null

    @Volatile
    private var latestBrowserTxn: MobileAuthTransactionStart? = null

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
            val bindInitRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/school-bindings",
                bodyJson = JSONObject()
                    .put("school_id", "haut")
                    .put("student_no", studentNo.trim())
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
                        .put("delegated_secret", delegatedSecret.ifBlank { "delegated_android_session" })
                        .toString(),
                    bearerToken = accessToken
                )
            }
            val consentRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/me/consents",
                bodyJson = JSONObject().put("school_id", "haut").toString(),
                bearerToken = accessToken
            )
            val consentId = JSONObject(consentRaw).optJSONObject("data")?.optString("id").orEmpty()
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
                bookingId = jobId,
                seatTitle = "",
                evidenceSha256 = "",
                diagnosticMessage = "云端抢座凭证或任务号缺失，状态未知，已停止执行",
                outcomeState = "RESULT_UNKNOWN"
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
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) {
                return@runCatching AutomatedSeatBookingResult(
                    confirmed = false,
                    bookingId = jobId,
                    seatTitle = "",
                    evidenceSha256 = "",
                    diagnosticMessage = "云端抢座返回异常响应，状态未知，已停止重试",
                    outcomeState = "RESULT_UNKNOWN"
                )
            }
            val data = root.optJSONObject("data")
            val job = data?.optJSONObject("job")
            if (data == null || job == null) {
                return@runCatching AutomatedSeatBookingResult(
                    confirmed = false,
                    bookingId = jobId,
                    seatTitle = "",
                    evidenceSha256 = "",
                    diagnosticMessage = "云端抢座响应数据缺失，状态未知，已停止重试",
                    outcomeState = "RESULT_UNKNOWN"
                )
            }
            val sr = data.optJSONObject("schoolReservation") ?: data.optJSONObject("school_reservation")
            val rawState = job.optCleanString("state", "RESULT_UNKNOWN")
            val rawBookingId = sr?.optCleanString("external_booking_id").orEmpty()
            val seatAlias = sr?.optCleanString("seat_alias").orEmpty()
            val regionAlias = sr?.optCleanString("region_alias").orEmpty()
            val evidenceHash = sr?.optCleanString("evidence_sha256").orEmpty()
            val hasCompleteConfirmedData =
                rawState == "CONFIRMED" &&
                    sr != null &&
                    rawBookingId.isNotBlank() &&
                    seatAlias.isNotBlank() &&
                    regionAlias.isNotBlank()
            val outcomeState = when {
                hasCompleteConfirmedData -> "CONFIRMED"
                rawState == "CONFIRMED" -> "RESULT_UNKNOWN"
                rawState in setOf("FAILED", "CANCELLED", "EXPIRED") -> "FAILED"
                else -> "RESULT_UNKNOWN"
            }
            val extId = rawBookingId.ifBlank { jobId }
            AutomatedSeatBookingResult(
                confirmed = hasCompleteConfirmedData,
                bookingId = extId,
                seatTitle = seatAlias,
                evidenceSha256 = evidenceHash,
                diagnosticMessage = when {
                    hasCompleteConfirmedData ->
                        "云端抢座（测试版）成功！已锁定 $seatAlias（预约号 $extId），已消耗 1 🍑 内测防滥用配额"
                    rawState == "CONFIRMED" ->
                        "云端返回已确认但缺少完整座位核验凭据，状态未知，已停止重试"
                    else ->
                        "云端抢座（测试版）未锁定座位（状态：$rawState），未消耗内测配额"
                },
                outcomeState = outcomeState
            )
        }.getOrElse {
            AutomatedSeatBookingResult(
                confirmed = false,
                bookingId = jobId,
                seatTitle = "",
                evidenceSha256 = "",
                diagnosticMessage = "云端抢座执行状态未知，已停止本地重复提交，请稍后刷新查看核验结果",
                outcomeState = "RESULT_UNKNOWN"
            )
        }
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
            JSONObject(raw).optBoolean("ok", false)
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
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val bodyObj = JSONObject()
            if (!studentNo.isNullOrBlank()) {
                val bindingObj = JSONObject()
                    .put("school_id", "haut")
                    .put("student_no", studentNo.trim())
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
            if (localActiveCloudPlans.isNotEmpty()) {
                val planUpsertsArr = JSONArray()
                for (lp in localActiveCloudPlans) {
                    val seatsArr = JSONArray()
                    lp.candidateSeats.forEach { seatsArr.put(it) }
                    planUpsertsArr.put(
                        JSONObject()
                            .put("plan_id", lp.planId)
                            .put("target_local_date", lp.targetLocalDate)
                            .put("target_local_time", lp.targetLocalTime)
                            .put("area_code", lp.areaName)
                            .put("area_name", lp.areaName)
                            .put("candidate_seats", seatsArr)
                            .put("repeat_days", lp.repeatDays.coerceIn(1, 30))
                            .put("remaining_days", lp.remainingDays.coerceIn(1, 30))
                            .put("allow_alternative_seat", lp.allowAlternativeSeat)
                    )
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
            val userObj = data.optJSONObject("user") ?: JSONObject()
            val walletObj = data.optJSONObject("wallet") ?: JSONObject()
            val bindingsArr = data.optJSONArray("school_bindings") ?: JSONArray()
            val jobsArr = data.optJSONArray("reservation_jobs") ?: JSONArray()
            val ordersArr = data.optJSONArray("orders") ?: JSONArray()
            val parcelsArr = data.optJSONArray("parcels") ?: JSONArray()

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
                    val sr = j.optJSONObject("school_reservation")
                    val bookingDate = j.optCleanString("booking_local_date")
                    val repDays = j.optInt("repeat_days", 1).coerceIn(1, 30)
                    val remDays = j.optInt("remaining_days", repDays).coerceIn(0, 30)
                    add(
                        CloudSyncedReservationJobDto(
                            jobId = j.optCleanString("id"),
                            planId = j.optCleanString("plan_id"),
                            areaCode = j.optCleanString("area_code", "4F_NORTH"),
                            areaName = j.optCleanString("area_name", "4楼北自习区"),
                            candidateSeats = seats,
                            slotWindow = j.optCleanString("slot_window", "08:00-22:00"),
                            targetLocalDate = bookingDate,
                            targetLocalTime = j.optCleanString("target_local_time", "07:00:00"),
                            state = j.optCleanString("state", "ARMED"),
                            externalBookingId = sr?.optNullableCleanString("external_booking_id"),
                            evidenceSha256 = sr?.optNullableCleanString("evidence_sha256"),
                            bookedSeatAlias = sr?.optNullableCleanString("seat_alias"),
                            bookedRegionAlias = sr?.optNullableCleanString("region_alias"),
                            repeatDays = repDays,
                            remainingDays = remDays,
                            endLocalDate = j.optCleanString("end_local_date", bookingDate).ifBlank { bookingDate }
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
                campusContentRawJson = campusContentObj?.toString()
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
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Pair<CampusMomentsSnapshot, String>? = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val raw = httpJsonRequest(
            method = "GET",
            url = "$cleanBase/api/v1/moments/snapshot",
            bodyJson = null,
            bearerToken = null
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        val data = root.optJSONObject("data") ?: return null
        val snap = parseMomentsSnapshotJson(data) ?: return null
        snap to data.toString()
    }.getOrNull()

    fun publishMomentsPostToCloud(
        post: MomentsPostItem,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
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
            .toString()
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts",
            bodyJson = body,
            bearerToken = null
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
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
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
            bearerToken = null
        )
        val root = JSONObject(raw)
        if (!root.optBoolean("ok", false)) return null
        parseMomentsPostJson(root.optJSONObject("data") ?: return null)
    }.getOrNull()

    fun mutateMomentsPostOnCloud(
        postId: String,
        action: String,
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): Boolean = runCatching {
        val cleanBase = cloudBaseUrl.trimEnd('/')
        val raw = httpJsonRequest(
            method = "POST",
            url = "$cleanBase/api/v1/moments/posts/${Uri.encode(postId)}/action",
            bodyJson = JSONObject().put("action", action).toString(),
            bearerToken = null
        )
        JSONObject(raw).optBoolean("ok", false)
    }.getOrDefault(false)

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

    fun encodeMomentsSnapshotJsonString(snapshot: CampusMomentsSnapshot): String {
        val root = JSONObject()
        root.put("updated_at", snapshot.updatedAt)
        val postsArr = JSONArray()
        for (p in snapshot.posts) {
            val commentsArr = JSONArray()
            for (c in p.comments) {
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

    private fun parseMomentsPostJson(obj: JSONObject): MomentsPostItem? {
        val id = obj.optCleanString("id")
        val title = obj.optCleanString("title")
        if (id.isEmpty() || title.isEmpty()) return null
        val commentsArr = obj.optJSONArray("comments")
        val comments = buildList {
            if (commentsArr != null) {
                for (i in 0 until commentsArr.length()) {
                    val c = commentsArr.optJSONObject(i) ?: continue
                    val content = c.optCleanString("content")
                    if (content.isEmpty()) continue
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
            updatedAt = obj.optCleanString("updated_at")
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
