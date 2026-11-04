package cn.edu.haut.campus.core.campusservices

import android.net.Uri
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
    val bookedRegionAlias: String? = null
)

data class CloudSyncedOrderDto(
    val orderId: String,
    val jobId: String,
    val pricePeach: Double,
    val paymentState: String,
    val paidAtIso: String?,
    val createdAtIso: String
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
    val parcels: List<CloudSyncedParcelDto>
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
        val authorizeUrl = data.getString("authorize_url")
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
        latestDeepLinkCallbackPair = pair
        return pair
    }

    fun consumeDeepLinkCallback(): Pair<String, String>? {
        val pair = latestDeepLinkCallbackPair
        latestDeepLinkCallbackPair = null
        return pair
    }

    fun extractCallbackTicketAndTxn(url: String?): Pair<String, String>? {
        if (url.isNullOrBlank()) return null
        if (!url.contains("/app-link/auth/callback") && !url.startsWith("dayinschool://auth/callback")) return null
        return runCatching {
            val uri = Uri.parse(url)
            val ticket = uri.getQueryParameter("ticket")?.trim().orEmpty()
            val txn = uri.getQueryParameter("txn")?.trim().orEmpty()
            if (ticket.isNotEmpty() && txn.isNotEmpty()) ticket to txn else null
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
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
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
    ): AutomatedSeatBookingResult? {
        if (accessToken.isBlank() || jobId.isBlank()) return null
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val raw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/reservation-jobs/$jobId/execute-now",
                bodyJson = "{}",
                bearerToken = accessToken
            )
            val root = JSONObject(raw)
            if (!root.optBoolean("ok", false)) return null
            val data = root.optJSONObject("data") ?: return null
            val job = data.optJSONObject("job")
            val sr = data.optJSONObject("schoolReservation") ?: data.optJSONObject("school_reservation")
            val confirmed = job?.optCleanString("state") == "CONFIRMED"
            val extId = sr?.optCleanString("external_booking_id").orEmpty().ifBlank { jobId }
            val seatAlias = sr?.optCleanString("seat_alias").orEmpty()
            val evidenceHash = sr?.optCleanString("evidence_sha256").orEmpty()
            AutomatedSeatBookingResult(
                confirmed = confirmed,
                bookingId = extId,
                seatTitle = seatAlias,
                evidenceSha256 = evidenceHash,
                diagnosticMessage = if (confirmed) {
                    "云端抢座成功！已锁定 ${seatAlias.ifBlank { "所选座位" }}（预约号 $extId），扣除 0.1 🍑"
                } else {
                    "云端抢座未锁定座位（状态：${job?.optCleanString("state", "未命中") ?: "未命中"}），未扣费"
                }
            )
        }.getOrNull()
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
        cloudBaseUrl: String = DEFAULT_CLOUD_BASE_URL
    ): CloudSyncSnapshotResult? {
        if (accessToken.isBlank()) return null
        return runCatching {
            val cleanBase = cloudBaseUrl.trimEnd('/')
            val bodyObj = JSONObject()
            if (!studentNo.isNullOrBlank()) {
                bodyObj.put(
                    "school_binding_upserts",
                    JSONArray().put(
                        JSONObject()
                            .put("school_id", "haut")
                            .put("student_no", studentNo.trim())
                            .put("delegated_secret", delegatedSecret?.takeIf { it.isNotBlank() } ?: "android_delegated")
                    )
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
                    add(
                        CloudSyncedReservationJobDto(
                            jobId = j.optCleanString("id"),
                            planId = j.optCleanString("plan_id"),
                            areaCode = j.optCleanString("area_code", "4F_NORTH"),
                            areaName = j.optCleanString("area_name", "4楼北自习区"),
                            candidateSeats = seats,
                            slotWindow = j.optCleanString("slot_window", "08:00-22:00"),
                            targetLocalDate = j.optCleanString("booking_local_date"),
                            targetLocalTime = j.optCleanString("target_local_time", "07:00:00"),
                            state = j.optCleanString("state", "ARMED"),
                            externalBookingId = sr?.optNullableCleanString("external_booking_id"),
                            evidenceSha256 = sr?.optNullableCleanString("evidence_sha256"),
                            bookedSeatAlias = sr?.optNullableCleanString("seat_alias"),
                            bookedRegionAlias = sr?.optNullableCleanString("region_alias")
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
                parcels = parcels
            )
        }.getOrNull()
    }

    private fun httpJsonRequest(
        method: String,
        url: String,
        bodyJson: String?,
        bearerToken: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
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
        if (bodyJson != null) {
            conn.outputStream.use { out ->
                out.write(bodyJson.toByteArray(StandardCharsets.UTF_8))
            }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return text
    }

    fun buildDebitIdempotencyKey(orderId: String): String =
        "dis:reservation:$orderId:debit:v1"

    fun buildRefundIdempotencyKey(orderId: String): String =
        "dis:reservation:$orderId:refund:v1"
}
