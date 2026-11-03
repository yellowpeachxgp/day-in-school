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
    val refreshToken: String = "",
    val group: String = "默认分组",
    val affCode: String = ""
) {
    val peachBalance: Double
        get() = peachBalanceMicropeach.toDouble() / 10_000.0

    val formattedPeachBalance: String
        get() = "%.2f 🍑".format(peachBalance)
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
    val evidenceSha256: String?
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
        val displayName = sessionData.optString("username").ifBlank {
            sessionData.optString("display_name").ifBlank { ssoSubject }
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
            val confirmed = job?.optString("state") == "CONFIRMED"
            val extId = sr?.optString("external_booking_id").orEmpty().ifBlank { jobId }
            val seatAlias = sr?.optString("seat_alias").orEmpty()
            val evidenceHash = sr?.optString("evidence_sha256").orEmpty()
            AutomatedSeatBookingResult(
                confirmed = confirmed,
                bookingId = extId,
                seatTitle = seatAlias,
                evidenceSha256 = evidenceHash,
                diagnosticMessage = if (confirmed) {
                    "云端抢座成功！已锁定 $seatAlias（预约号 $extId），扣除 0.1 🍑"
                } else {
                    "云端抢座未锁定座位（状态：${job?.optString("state") ?: "UNKNOWN"}），未扣费"
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
                if (p.trackingNumber.isBlank()) continue
                parcelUpserts.put(
                    JSONObject()
                        .put("tracking_number", p.trackingNumber)
                        .put("carrier_code", p.carrierCode.ifBlank { "cainiao" })
                        .put("carrier_name", p.carrierName.ifBlank { "校园快递" })
                        .put("status", p.status)
                        .put("pickup_code", p.pickupCode.orEmpty())
                        .put("station_name", p.stationName.orEmpty())
                        .put("item_title", p.itemTitle.orEmpty())
                        .put("raw_notification_text", p.rawNotificationText.orEmpty())
                )
                if (p.status == "PICKED_UP") {
                    parcelStatusUpdates.put(
                        JSONObject()
                            .put("tracking_number", p.trackingNumber)
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
                b0?.optString("student_no")?.takeIf { it.isNotBlank() && !it.contains("***") }
            } else null

            val jobs = buildList {
                for (i in 0 until jobsArr.length()) {
                    val j = jobsArr.optJSONObject(i) ?: continue
                    val seatsArr = j.optJSONArray("candidate_seats") ?: JSONArray()
                    val seats = buildList {
                        for (k in 0 until seatsArr.length()) {
                            val s = seatsArr.optString(k).trim()
                            if (s.isNotEmpty()) add(s)
                        }
                    }
                    val sr = j.optJSONObject("school_reservation")
                    add(
                        CloudSyncedReservationJobDto(
                            jobId = j.optString("id"),
                            planId = j.optString("plan_id"),
                            areaCode = j.optString("area_code", "4F_NORTH"),
                            areaName = j.optString("area_name", "4楼北自习区"),
                            candidateSeats = seats,
                            slotWindow = j.optString("slot_window", "08:00-22:00"),
                            targetLocalDate = j.optString("booking_local_date"),
                            targetLocalTime = j.optString("target_local_time", "07:00:00"),
                            state = j.optString("state", "ARMED"),
                            externalBookingId = sr?.optString("external_booking_id")?.takeIf { it.isNotBlank() },
                            evidenceSha256 = sr?.optString("evidence_sha256")?.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }

            val orders = buildList {
                for (i in 0 until ordersArr.length()) {
                    val o = ordersArr.optJSONObject(i) ?: continue
                    add(
                        CloudSyncedOrderDto(
                            orderId = o.optString("id"),
                            jobId = o.optString("job_id"),
                            pricePeach = o.optDouble("price_peach", 0.1),
                            paymentState = o.optString("payment_state", "PAID"),
                            paidAtIso = o.optString("paid_at").takeIf { it.isNotBlank() },
                            createdAtIso = o.optString("created_at")
                        )
                    )
                }
            }

            val parcels = buildList {
                for (i in 0 until parcelsArr.length()) {
                    val p = parcelsArr.optJSONObject(i) ?: continue
                    add(
                        CloudSyncedParcelDto(
                            id = p.optString("id"),
                            trackingNumber = p.optString("tracking_number"),
                            carrierCode = p.optString("carrier_code", "cainiao"),
                            carrierName = p.optString("carrier_name", "校园快递"),
                            status = p.optString("status", "IN_TRANSIT"),
                            pickupCode = p.optString("pickup_code").takeIf { it.isNotBlank() },
                            stationName = p.optString("station_name").takeIf { it.isNotBlank() },
                            itemTitle = p.optString("item_title").takeIf { it.isNotBlank() },
                            rawNotificationText = p.optString("raw_notification_text").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }

            CloudSyncSnapshotResult(
                cloudUserId = userObj.optString("cloud_user_id"),
                ssoSubject = userObj.optString("sso_subject"),
                displayName = userObj.optString("username").ifBlank { userObj.optString("display_name") },
                peachBalance = walletObj.optDouble("peach_balance", 0.0).let { if (it.isNaN()) 0.0 else it },
                peachUsed = walletObj.optDouble("peach_used", 0.0).let { if (it.isNaN()) 0.0 else it },
                group = walletObj.optString("group", "默认分组"),
                affCode = walletObj.optString("aff_code", ""),
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
