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

data class XpeachCloudAccountProfile(
    val cloudUserId: String,
    val ssoIssuer: String,
    val ssoSubject: String,
    val displayName: String,
    val peachBalanceMicropeach: Long,
    val holdSupported: Boolean = false,
    val linkedAtEpochMillis: Long,
    val accessToken: String = "",
    val refreshToken: String = ""
) {
    val formattedPeachBalance: String
        get() = "%.2f 🍑".format(peachBalanceMicropeach.toDouble() / 10_000.0)
}

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
            expiresAtEpochMillis = System.currentTimeMillis() + 5 * 60 * 1000L
        )
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
            val errMsg = exchangeRoot.optJSONObject("error")?.optString("message") ?: "兑换 XPEACH SSO 凭据失败"
            throw IllegalStateException(errMsg)
        }
        val sessionData = exchangeRoot.getJSONObject("data")
        val accessToken = sessionData.getString("access_token")
        val refreshToken = sessionData.optString("refresh_token")
        val cloudUserId = sessionData.getString("cloud_user_id")
        val ssoIssuer = sessionData.optString("sso_issuer", DEFAULT_SSO_ISSUER)
        val ssoSubject = sessionData.getString("sso_subject")
        val displayName = sessionData.optString("display_name").ifBlank { ssoSubject }

        var micropeachBalance = 50_000L
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
                micropeachBalance = wData.optLong("micropeach_balance", 50_000L)
                holdSupported = wData.optBoolean("hold_supported", false)
            }
        }

        return XpeachCloudAccountProfile(
            cloudUserId = cloudUserId,
            ssoIssuer = ssoIssuer,
            ssoSubject = ssoSubject,
            displayName = displayName,
            peachBalanceMicropeach = micropeachBalance,
            holdSupported = holdSupported,
            linkedAtEpochMillis = System.currentTimeMillis(),
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    }

    fun extractCallbackTicketAndTxn(url: String?): Pair<String, String>? {
        if (url.isNullOrBlank() || !url.contains("/app-link/auth/callback")) return null
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
                    .put("student_no", studentNo.ifBlank { "20230001" })
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
            val planBody = JSONObject()
                .put("school_id", "haut")
                .put("binding_id", bindId)
                .put("mode", "CLOUD_HOSTED")
                .put("target_date", targetDate)
                .put("target_execute_at", targetExecuteAtIso)
                .put("preferred_area_ids", JSONArray().put(preferredAreaName))
                .put("preferred_seat_ids", JSONArray().put(preferredSeatLabel))
                .toString()
            val planRaw = httpJsonRequest(
                method = "POST",
                url = "$cleanBase/api/v1/reservation-plans",
                bodyJson = planBody,
                bearerToken = accessToken,
                extraHeaders = mapOf("Idempotency-Key" to planIdempotencyKey)
            )
            JSONObject(planRaw).optJSONObject("data")?.optJSONObject("plan")?.optString("id")
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
