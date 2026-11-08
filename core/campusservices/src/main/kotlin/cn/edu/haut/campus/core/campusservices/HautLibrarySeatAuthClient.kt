package cn.edu.haut.campus.core.campusservices

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LibrarySeatRefreshSource(val label: String) {
    CACHED_TOKEN("已自动登录"),
    PERSISTED_COOKIE("已自动续期"),
    BACKGROUND_PASSWORD_LOGIN("已自动登录"),
    WEBVIEW_CAPTURE("已登录")
}

data class LibrarySeatUserInfo(
    val userNo: String,
    val userName: String,
    val department: String,
    val cardNo: String,
    val breakNum: String
)

data class LibrarySeatSessionSnapshot(
    val rawToken: String,
    val casTgcCookie: String?,
    val casJsessionId: String?,
    val userInfo: LibrarySeatUserInfo,
    val updatedAtEpochMs: Long,
    val refreshSource: LibrarySeatRefreshSource
) {
    override fun toString(): String =
        "LibrarySeatSessionSnapshot(userNo=${userInfo.userNo}, source=${refreshSource.name}, updatedAt=$updatedAtEpochMs)"
}

sealed interface LibrarySeatSessionState {
    data object Idle : LibrarySeatSessionState
    data class Refreshing(val hint: String = "正在自动登录图书馆选座系统…") : LibrarySeatSessionState
    data class Authenticated(val snapshot: LibrarySeatSessionSnapshot) : LibrarySeatSessionState
    data class CredentialsRequired(val message: String = "请先保存一次学校账号密码，之后将自动免密登录") : LibrarySeatSessionState
    data class AuthError(val message: String, val requiresManualVerification: Boolean = false) : LibrarySeatSessionState
}

/**
 * 河南工业大学图书馆空间预约系统 (`wslib.haut.edu.cn`) 座位预约状态枚举（对应官方 `bookingStatus` 1..7）。
 */
enum class LibrarySeatBookingStatus(val code: Int, val label: String) {
    FULFILLED(1, "待履约签到"),
    LEARNING(2, "学习使用中"),
    LEAVING(3, "中途暂离中"),
    CONTINUED_LEARNING(4, "续座学习中"),
    CANCELLED(5, "已取消"),
    SIGNED_OUT(6, "已签退"),
    DEFAULTED(7, "已违约"),
    UNKNOWN(0, "未知状态");

    companion object {
        fun fromCode(raw: String?): LibrarySeatBookingStatus {
            val code = raw?.trim()?.toIntOrNull() ?: 0
            return entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}

/**
 * 图书馆座位预约单条记录模型（兼容 `/seatbook/user/my/crrentbooking`、`/seatbook/user/mybookingInProgress` 与 `/seatbook/user/mybookinghis`）。
 */
data class LibrarySeatBookingRecord(
    val bookingId: Long,
    val seatId: Long?,
    val seatName: String,
    val regionName: String,
    val buildingLayerRegionId: Long?,
    val startDate: String,
    val endDate: String,
    val status: LibrarySeatBookingStatus,
    val createTime: String? = null,
    val signInTime: String? = null,
    val signOutTime: String? = null,
    val shotLeaveTime: String? = null,
    val longShortLeave: Int? = null,
    val description: String? = null
) {
    companion object {
        private val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        private val DATE_TIME_PATTERNS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        )

        fun parseShanghaiDateTimeEpochMillis(raw: String?): Long? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            for (fmt in DATE_TIME_PATTERNS) {
                val parsed = runCatching { LocalDateTime.parse(text, fmt) }.getOrNull()
                if (parsed != null) {
                    return parsed.atZone(SHANGHAI_ZONE).toInstant().toEpochMilli()
                }
            }
            return null
        }

        /**
         * 计算河工大图书馆后台下一次整 5 分钟（`:00` / `:05` / `:10` ...）闸机刷脸自动签到同步时刻文本（如 `"20:15"`）。
         */
        fun computeNextGateSyncTimeText(nowEpochMs: Long = System.currentTimeMillis()): String {
            val zdt = Instant.ofEpochMilli(nowEpochMs).atZone(SHANGHAI_ZONE)
            val minute = zdt.minute
            val deltaMin = 5 - (minute % 5)
            val nextSync = zdt.plusMinutes(deltaMin.toLong()).withSecond(0).withNano(0)
            return "%02d:%02d".format(nextSync.hour, nextSync.minute)
        }
    }

    val startEpochMillis: Long?
        get() = parseShanghaiDateTimeEpochMillis(startDate)

    val endEpochMillis: Long?
        get() = parseShanghaiDateTimeEpochMillis(endDate)

    /**
     * 判断当前时刻是否已达到或超过预约开始时间 `startDate`。
     *
     * 河工大选座系统 (`wslib.haut.edu.cn`) 规定：当日选座默认将 `startDate` 设为创建后 5 分钟；
     * 一旦 `now >= startDate`，`/seatbook/user/cancel` 会拒绝取消并返回「当前预约已开始，不能取消预约」，
     * 此时必须通过入馆闸机自动签到（每整 5 分钟同步一次）并调用 `/seatbook/user/signoff`（签退释放）方可退座。
     */
    fun hasStarted(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val startMs = startEpochMillis ?: return false
        return nowEpochMs >= startMs
    }

    /**
     * 距离预约正式开始 (`startDate`) 剩余的可直接在线取消秒数；若已开始或无法解析则返回 `0L` / `null`。
     */
    fun remainingSecondsBeforeStart(nowEpochMs: Long = System.currentTimeMillis()): Long? {
        val startMs = startEpochMillis ?: return null
        return ((startMs - nowEpochMs) / 1000L).coerceAtLeast(0L)
    }

    val isActive: Boolean
        get() = status in setOf(
            LibrarySeatBookingStatus.FULFILLED,
            LibrarySeatBookingStatus.LEARNING,
            LibrarySeatBookingStatus.LEAVING,
            LibrarySeatBookingStatus.CONTINUED_LEARNING
        )

    val canSignIn: Boolean
        get() = status == LibrarySeatBookingStatus.FULFILLED

    val canCancel: Boolean
        get() = status == LibrarySeatBookingStatus.FULFILLED

    val canLeave: Boolean
        get() = status == LibrarySeatBookingStatus.LEARNING ||
            status == LibrarySeatBookingStatus.CONTINUED_LEARNING

    val canReturnFromLeave: Boolean
        get() = status == LibrarySeatBookingStatus.LEAVING

    val canRenew: Boolean
        get() = status == LibrarySeatBookingStatus.LEARNING ||
            status == LibrarySeatBookingStatus.CONTINUED_LEARNING

    val canSignOut: Boolean
        get() = status in setOf(
            LibrarySeatBookingStatus.LEARNING,
            LibrarySeatBookingStatus.LEAVING,
            LibrarySeatBookingStatus.CONTINUED_LEARNING
        ) || (status == LibrarySeatBookingStatus.FULFILLED && hasStarted())

    val timeWindowText: String
        get() {
            val startPart = startDate.trim()
            val endTimeOnly = endDate.trim().substringAfter(' ', endDate.trim()).removeSuffix(":00")
            val cleanStart = startPart.removeSuffix(":00")
            return if (cleanStart.isNotEmpty() && endTimeOnly.isNotEmpty()) {
                "$cleanStart - $endTimeOnly"
            } else {
                listOf(startPart, endDate.trim()).filter { it.isNotEmpty() }.joinToString(" - ")
            }
        }

    val displaySeatTitle: String
        get() {
            val cleanSeat = seatName.trim()
            val cleanRegion = regionName.trim()
            return when {
                cleanSeat.isNotEmpty() && cleanRegion.isNotEmpty() && cleanSeat.startsWith(cleanRegion) ->
                    "${cleanSeat}号座"
                cleanSeat.isNotEmpty() && cleanRegion.isNotEmpty() ->
                    "$cleanRegion · ${cleanSeat}号座"
                cleanSeat.isNotEmpty() -> "${cleanSeat}号座"
                cleanRegion.isNotEmpty() -> cleanRegion
                else -> "预约编号 #$bookingId"
            }
        }
}

/**
 * 图书馆座位预约操作类型（严格对应 `wslib.haut.edu.cn/spacelink/api` 真实端点）。
 */
enum class LibrarySeatActionType(
    val title: String,
    val confirmMessage: String,
    val requiresBookingId: Boolean = true
) {
    SIGN_IN(
        title = "签到入座",
        confirmMessage = "确认立即签到入座当前预约座位吗？（移动端签到需配合馆内蓝牙或现场核验）"
    ),
    QUICK_SIGN_IN(
        title = "快速签到",
        confirmMessage = "确认发起一键快速签到吗？",
        requiresBookingId = false
    ),
    CANCEL_BOOKING(
        title = "取消预约",
        confirmMessage = "确认取消该座位预约吗？取消后座位将立即释放给其他同学。"
    ),
    SHORT_LEAVE(
        title = "短暂离开",
        confirmMessage = "确认办理中途短暂离开（默认保留座位 30 分钟）吗？请在规定时间内点击「暂离返回」，否则将自动释放座位并记违约。"
    ),
    LONG_LEAVE(
        title = "长时暂离",
        confirmMessage = "确认办理中途长时间暂离（默认保留座位 120 分钟）吗？请在规定时间内点击「暂离返回」，否则将自动释放座位并记违约。"
    ),
    LEAVE_BACK(
        title = "暂离返回",
        confirmMessage = "确认您已返回预约座位并恢复学习状态吗？"
    ),
    RENEW_SEAT(
        title = "续约座位",
        confirmMessage = "确认续约当前正在使用的座位吗？"
    ),
    SIGN_OUT(
        title = "签退释放",
        confirmMessage = "确认结束使用并签退释放当前座位吗？签退后本次预约即告完成。"
    )
}

data class LibrarySeatActionOutcome(
    val success: Boolean,
    val message: String,
    val rawCode: Int = 200
)

data class AutomatedSeatBookingResult(
    val confirmed: Boolean,
    val bookingId: String,
    val seatTitle: String,
    val evidenceSha256: String,
    val diagnosticMessage: String,
    val outcomeState: String = if (confirmed) "CONFIRMED" else "FAILED"
) {
    val isResultUnknown: Boolean
        get() = !confirmed && outcomeState == "RESULT_UNKNOWN"
}

data class LibraryStudyRegionItem(
    val id: Long,
    val regionName: String,
    val buildingId: String = "",
    val buildingName: String = "",
    val layerId: String = "",
    val layerName: String = "",
    val openTime: String = "07:00",
    val closeTime: String = "22:00",
    val totalSeats: Int = 0,
    val availableSeats: Int = 0,
    val isOpen: Boolean = true
)

data class LibrarySeatItem(
    val id: Long,
    val seatName: String,
    val seatStatus: Int
) {
    val isAvailable: Boolean
        get() = seatStatus == 2

    val statusLabel: String
        get() = when (seatStatus) {
            2 -> "空闲"
            1 -> "使用中"
            else -> "不可选"
        }
}

data class LibraryRegionSeatsSnapshot(
    val regionId: Long,
    val regionName: String,
    val bookingDate: String,
    val startTime: String,
    val endTime: String,
    val canSetTime: Boolean,
    val seats: List<LibrarySeatItem>
)

data class LibrarySeatOverviewSnapshot(
    val currentBooking: LibrarySeatBookingRecord?,
    val inProgressBookings: List<LibrarySeatBookingRecord>,
    val recentHistory: List<LibrarySeatBookingRecord>,
    val shortLeaveMinutes: Int = 30,
    val longLeaveMinutes: Int = 120,
    val fetchedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * 基于 Android Keystore 非导出 AES-256-GCM 密钥的图书馆座位预约持久化 Cookie 与 Token 安全仓。
 *
 * 将统一身份认证 `CASTGC` Cookie 与 `wslib.haut.edu.cn` 会话 Token 加密保存在本地，
 * 确保应用重启后仍可复用持久会话，且磁盘不落任何明文凭据。
 */
class LibrarySeatSessionVault(
    context: Context,
    scopeId: String? = null
) {
    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFS_FILE_NAME = "haut_library_seat_session_vault"
        private const val KEY_ENCRYPTED_SESSION = "encrypted_seat_session_v1"
        private const val DEFAULT_KEY_ALIAS = "cn.edu.haut.campus.library.seat.session.v1"
    }

    private val lock = Any()
    private val scopeSuffix = scopeId?.takeIf { it.isNotBlank() }?.let {
        "." + MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
    }.orEmpty()
    private val keyAlias = DEFAULT_KEY_ALIAS + scopeSuffix
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME + scopeSuffix, Context.MODE_PRIVATE)

    private fun getOrCreateSecretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = runCatching { store.getKey(keyAlias, null) as? SecretKey }.getOrElse {
            runCatching { store.deleteEntry(keyAlias) }
            null
        }
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun resetCorruptedStorage() {
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (store.containsAlias(keyAlias)) {
                store.deleteEntry(keyAlias)
            }
        }
        prefs.edit().remove(KEY_ENCRYPTED_SESSION).commit()
    }

    fun readSnapshot(): LibrarySeatSessionSnapshot? = synchronized(lock) {
        val encoded = prefs.getString(KEY_ENCRYPTED_SESSION, null) ?: return null
        return try {
            val rawBytes = Base64.getDecoder().decode(encoded)
            require(rawBytes.size > GCM_IV_LENGTH_BYTES + 16)
            val iv = rawBytes.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = rawBytes.copyOfRange(GCM_IV_LENGTH_BYTES, rawBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val jsonText = cipher.doFinal(cipherBytes).toString(StandardCharsets.UTF_8)
            val obj = JSONObject(jsonText)
            val rawToken = HautLibrarySeatAuthClient.normalizeRawToken(obj.optCleanString("rawToken"))
            if (rawToken.isBlank()) return null
            val sourceName = obj.optCleanString("refreshSource", LibrarySeatRefreshSource.CACHED_TOKEN.name)
            val source = runCatching { LibrarySeatRefreshSource.valueOf(sourceName) }
                .getOrDefault(LibrarySeatRefreshSource.CACHED_TOKEN)
            val uNo = obj.optCleanString("userNo")
            val uName = obj.optCleanString("userName").ifBlank { uNo }
            LibrarySeatSessionSnapshot(
                rawToken = rawToken,
                casTgcCookie = obj.optNullableCleanString("casTgcCookie"),
                casJsessionId = obj.optNullableCleanString("casJsessionId"),
                userInfo = LibrarySeatUserInfo(
                    userNo = uNo,
                    userName = uName,
                    department = obj.optCleanString("department"),
                    cardNo = obj.optCleanString("cardNo"),
                    breakNum = obj.optCleanString("breakNum", "0")
                ),
                updatedAtEpochMs = obj.optLong("updatedAtEpochMs", System.currentTimeMillis()),
                refreshSource = source
            )
        } catch (_: Exception) {
            resetCorruptedStorage()
            null
        }
    }

    fun saveSnapshot(snapshot: LibrarySeatSessionSnapshot): Unit = synchronized(lock) {
        val cleanToken = HautLibrarySeatAuthClient.normalizeRawToken(snapshot.rawToken)
        if (cleanToken.isBlank()) return
        val cleanNo = snapshot.userInfo.userNo.cleanNullLiteral()
        val cleanName = snapshot.userInfo.userName.cleanNullLiteral(cleanNo)
        val payload = JSONObject()
            .put("rawToken", cleanToken)
            .put("casTgcCookie", snapshot.casTgcCookie.cleanNullLiteral())
            .put("casJsessionId", snapshot.casJsessionId.cleanNullLiteral())
            .put("userNo", cleanNo)
            .put("userName", cleanName)
            .put("department", snapshot.userInfo.department.cleanNullLiteral())
            .put("cardNo", snapshot.userInfo.cardNo.cleanNullLiteral())
            .put("breakNum", snapshot.userInfo.breakNum.cleanNullLiteral("0"))
            .put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
            .put("refreshSource", snapshot.refreshSource.name)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val encrypted = try {
            encryptBytes(payload)
        } catch (_: Exception) {
            return
        } finally {
            payload.fill(0)
        }
        prefs.edit().putString(KEY_ENCRYPTED_SESSION, encrypted).commit()
    }

    private fun encryptBytes(plainBytes: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        check(iv != null && iv.size == GCM_IV_LENGTH_BYTES)
        val cipherBytes = cipher.doFinal(plainBytes)
        return Base64.getEncoder().encodeToString(iv + cipherBytes)
    }

    fun clear(): Unit = synchronized(lock) {
        resetCorruptedStorage()
    }
}

/**
 * 河南工业大学图书馆空间预约系统 (`wslib.haut.edu.cn`) 持久化 Cookie 与自动后台登录客户端。
 *
 * 完整支持三级无感保活机制：
 * 1. **Level 1（持久 Token 直连校验）**：校验加密保存的 `wslib` Token (`/spacelink/api/h5user/my/userinfo`)，有效则直接注入复用；
 * 2. **Level 2（持久 `CASTGC` Cookie 静默换票）**：当 `wslib` Token 过期时，优先携带持久化的统一身份认证 `CASTGC` Cookie 请求
 *    `https://mapp.haut.edu.cn/cas/login?service=https://wslib.haut.edu.cn/stage-api/api/caslogin/ticketlogin` 静默换取新 Token；
 * 3. **Level 3（加密数据库凭据自动后台登录）**：当 `CASTGC` Cookie 也失效时，自动从本地 Android Keystore AES-256-GCM 加密凭据仓
 *    读取用户的学号与统一认证密码，在后台完成 CAS 登录并同步刷新持久化 Cookie 与 `wslib` Token。
 */
object HautLibrarySeatAuthClient {

    const val CAS_TICKET_LOGIN_SERVICE_URL = "https://wslib.haut.edu.cn/stage-api/api/caslogin/ticketlogin"
    const val CAS_LOGIN_ENTRY_URL =
        "https://mapp.haut.edu.cn/cas/login?service=https://wslib.haut.edu.cn/stage-api/api/caslogin/ticketlogin"
    const val WSLIB_API_BASE_URL = "https://wslib.haut.edu.cn/spacelink/api"
    const val WSLIB_USER_INFO_URL = "$WSLIB_API_BASE_URL/h5user/my/userinfo"
    const val WSLIB_CURRENT_BOOKING_URL = "$WSLIB_API_BASE_URL/seatbook/user/my/crrentbooking"
    const val WSLIB_IN_PROGRESS_BOOKINGS_URL = "$WSLIB_API_BASE_URL/seatbook/user/mybookingInProgress"
    const val WSLIB_HISTORY_BOOKINGS_URL = "$WSLIB_API_BASE_URL/seatbook/user/mybookinghis"
    const val WSLIB_RULE_URL = "$WSLIB_API_BASE_URL/seatbook/layout/rule"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    private val ALLOWED_AUTH_HOSTS = setOf(
        OfficialServiceRoutePolicy.MAPP_CAS_HOST,
        OfficialServiceRoutePolicy.WSLIB_HOST
    )

    /**
     * 规范化 `wslib` JWT Token（剥离可能附带的 `"Bearer "` 前缀与空白字符）。
     */
    fun normalizeRawToken(token: String?): String {
        var current = token?.trim().orEmpty()
        while (current.startsWith("Bearer ", ignoreCase = true)) {
            current = current.substring(7).trim()
        }
        return current.takeIf {
            it.length in 16..2048 && it.none { ch -> ch.isWhitespace() || ch.isISOControl() || ch == '\'' || ch == '"' || ch == '\\' }
        }.orEmpty()
    }

    /**
     * 从选座系统回调 URL（如 `https://wslib.haut.edu.cn/h5/index.html?token=eyJ...`）提取纯净 Token。
     */
    fun extractTokenFromUrl(rawUrl: String?): String? {
        val text = rawUrl?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = Regex("""[?&]token=([^&#;]+)""").find(text) ?: return null
        val decoded = runCatching {
            URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8.name())
        }.getOrDefault(match.groupValues[1])
        return normalizeRawToken(decoded).takeIf { it.isNotEmpty() }
    }

    /**
     * 解析 `/spacelink/api/h5user/my/userinfo` 响应 JSON。
     * @return 当 `code == 200` 时返回 [LibrarySeatUserInfo]；当 `code == 401` 或未认证时返回 `null`。
     */
    fun parseUserInfoResponse(rawJson: String): LibrarySeatUserInfo? {
        val root = runCatching { JSONObject(rawJson.trim()) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 200) return null
        val data = root.optJSONObject("data") ?: return null
        val userNo = data.optCleanString("userNo")
        val userName = data.optCleanString("userName")
        if (userNo.isEmpty() && userName.isEmpty()) return null
        return LibrarySeatUserInfo(
            userNo = userNo,
            userName = userName.ifBlank { userNo },
            department = data.optCleanString("department"),
            cardNo = data.optCleanString("cardNo"),
            breakNum = data.optCleanString("breakNum", "0")
        )
    }

    /**
     * 解析 `/spacelink/api/seatbook/user/my/crrentbooking` 返回的当前有效预约。
     * 若 `code == 401` 则抛出 [LibrarySeatSessionExpiredException] 以便调用方触发后台自动重登。
     */
    fun parseCurrentBookingResponse(rawJson: String): LibrarySeatBookingRecord? {
        val root = runCatching { JSONObject(rawJson.trim()) }.getOrNull() ?: return null
        val code = root.optInt("code", -1)
        if (code == 401) throw LibrarySeatSessionExpiredException()
        if (code != 200) return null
        val data = root.optJSONObject("data") ?: root.optJSONObject("booking") ?: return null
        return parseBookingRecordObject(data)
    }

    /**
     * 解析 `/spacelink/api/seatbook/user/mybookingInProgress` 或 `/spacelink/api/seatbook/user/mybookinghis` 返回的预约列表。
     */
    fun parseBookingListResponse(rawJson: String): List<LibrarySeatBookingRecord> {
        val root = runCatching { JSONObject(rawJson.trim()) }.getOrNull() ?: return emptyList()
        val code = root.optInt("code", -1)
        if (code == 401) throw LibrarySeatSessionExpiredException()
        if (code != 200) return emptyList()
        val rows = root.optJSONArray("rows") ?: return emptyList()
        val list = mutableListOf<LibrarySeatBookingRecord>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            parseBookingRecordObject(item)?.let(list::add)
        }
        return list
    }

    private fun parseBookingRecordObject(obj: JSONObject): LibrarySeatBookingRecord? {
        if (obj.isNull("id")) return null
        val id = obj.optLong("id", -1L).takeIf { it > 0L } ?: return null
        val statusRaw = obj.optCleanString("bookingStatus")
        val status = LibrarySeatBookingStatus.fromCode(statusRaw)
        val startDate = obj.optCleanString("startDate")
        val endDate = obj.optCleanString("endDate")
        val seatName = obj.optCleanString("seatName")
        val regionName = obj.optCleanString("regionName")
        return LibrarySeatBookingRecord(
            bookingId = id,
            seatId = if (obj.isNull("seatId")) null else obj.optLong("seatId", -1L).takeIf { it > 0L },
            seatName = seatName,
            regionName = regionName,
            buildingLayerRegionId = if (obj.isNull("buildingLayerRegionId")) null else obj.optLong("buildingLayerRegionId", -1L).takeIf { it > 0L },
            startDate = startDate,
            endDate = endDate,
            status = status,
            createTime = obj.optNullableCleanString("createTime"),
            signInTime = obj.optNullableCleanString("signInTime"),
            signOutTime = obj.optNullableCleanString("signOutTime"),
            shotLeaveTime = obj.optNullableCleanString("shotLeaveTime"),
            longShortLeave = if (obj.isNull("longShortLeave")) null else obj.optInt("longShortLeave", 0).takeIf { it > 0 },
            description = obj.optNullableCleanString("descr")
        )
    }

    /**
     * 解析 `/spacelink/api/seatbook/layout/rule` 中的短暂离 (`r1007`) 与长暂离 (`r1052`) 时长（分钟）。
     */
    fun parseRuleLeaveMinutes(rawJson: String): Pair<Int, Int> {
        val root = runCatching { JSONObject(rawJson.trim()) }.getOrNull() ?: return 30 to 120
        val rule = root.optJSONObject("rule") ?: return 30 to 120
        val shortMin = rule.optCleanString("r1007", "30").toIntOrNull()?.coerceIn(5, 180) ?: 30
        val longMin = rule.optCleanString("r1052", "120").toIntOrNull()?.coerceIn(15, 360) ?: 120
        return shortMin to longMin
    }

    /**
     * 解析预约操作（取消预约 / 签到 / 短暂离 / 长暂离 / 暂离返回 / 续座 / 签退）响应报文。
     */
    fun parseActionOutcome(rawJson: String, action: LibrarySeatActionType): LibrarySeatActionOutcome {
        val root = runCatching { JSONObject(rawJson.trim()) }.getOrNull()
            ?: return LibrarySeatActionOutcome(
                success = false,
                message = "${action.title}响应无法解析，请刷新确认状态",
                rawCode = -1
            )
        val code = root.optInt("code", -1)
        if (code == 401) throw LibrarySeatSessionExpiredException()
        val rawMsg = root.optCleanString("msg")
        return if (code == 200) {
            val defaultSuccess = when (action) {
                LibrarySeatActionType.SIGN_IN, LibrarySeatActionType.QUICK_SIGN_IN -> "签到成功"
                LibrarySeatActionType.CANCEL_BOOKING -> "已成功取消该座位预约"
                LibrarySeatActionType.SHORT_LEAVE -> "已办理中途短暂离开，请按时返回"
                LibrarySeatActionType.LONG_LEAVE -> "已办理中途长时暂离，请按时返回"
                LibrarySeatActionType.LEAVE_BACK -> "已成功从暂离状态返回座位"
                LibrarySeatActionType.RENEW_SEAT -> "座位续约成功"
                LibrarySeatActionType.SIGN_OUT -> "已成功签退并释放座位"
            }
            LibrarySeatActionOutcome(
                success = true,
                message = if (rawMsg.isNotEmpty() && rawMsg != "成功" && rawMsg != "操作成功") rawMsg else defaultSuccess,
                rawCode = 200
            )
        } else {
            LibrarySeatActionOutcome(
                success = false,
                message = rawMsg.ifBlank { "${action.title}未成功 (代码 $code)" },
                rawCode = code
            )
        }
    }

    /**
     * 判断取消预约失败是否因为预约已达到开始时间 (`now >= startDate`，返回「当前预约已开始，不能取消预约」)。
     */
    fun isCancelBlockedByStartedWindow(outcome: LibrarySeatActionOutcome): Boolean {
        if (outcome.success) return false
        val msg = outcome.message
        return msg.contains("已开始") || (msg.contains("不能取消") && msg.contains("预约"))
    }

    /**
     * 当取消预约遇到「当前预约已开始，不能取消预约」且尝试自动转「签退释放」时，根据签退响应生成最终结果。
     *
     * 业务机制（基于河工大 `wslib.haut.edu.cn` 真实后端规则）：
     * 1. 当日选座强制将 `startDate` 设为创建后 5 分钟；一旦到达 `startDate`，`/seatbook/user/cancel` 即拒绝取消。
     * 2. 若同学已刷脸过馆内闸机，后台每整 5 分钟（`:00` / `:05` / `:10` ...）自动将状态置为 `已签到(2)`，此时直接转调 `/seatbook/user/signoff` 即可立即释放座位。
     * 3. 若同学尚未刷脸入馆（或正处于开始时间与整 5 分钟闸机同步点之间的最多 4 分钟窗口），`/seatbook/user/signoff` 会返回「请先签到」，此时向用户明确展示原因与退座指引。
     */
    fun resolveStartedBookingCancelFallback(
        cancelOutcome: LibrarySeatActionOutcome,
        signOffOutcome: LibrarySeatActionOutcome,
        nowEpochMs: Long = System.currentTimeMillis()
    ): LibrarySeatActionOutcome {
        if (signOffOutcome.success) {
            return LibrarySeatActionOutcome(
                success = true,
                message = "当前预约已开始，已自动为您改用「签退释放」并成功退座",
                rawCode = 200
            )
        }
        val nextSync = LibrarySeatBookingRecord.computeNextGateSyncTimeText(nowEpochMs)
        return LibrarySeatActionOutcome(
            success = false,
            message = "当前预约已满 5 分钟正式开始，学校系统禁止在「开始后、未签到前」直接取消。" +
                "若您已刷脸入馆，系统将在 $nextSync 自动同步签到，届时点「签退释放」即可退座；若尚未入馆，请刷脸过闸机自动签到后再点「签退释放」。",
            rawCode = cancelOutcome.rawCode
        )
    }

    /**
     * 拉取用户当前有效预约、进行中预约、最近历史预约及暂离时长规则。
     */
    suspend fun fetchSeatBookingOverview(
        rawToken: String
    ): Result<LibrarySeatOverviewSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = normalizeRawToken(rawToken)
            if (cleanToken.isBlank()) throw LibrarySeatSessionExpiredException()

            val currentBody = executeAuthenticatedApiGet(
                url = WSLIB_CURRENT_BOOKING_URL,
                cleanToken = cleanToken
            )
            val currentRecord = parseCurrentBookingResponse(currentBody)

            val inProgressBody = runCatching {
                executeAuthenticatedApiGet(
                    url = "$WSLIB_IN_PROGRESS_BOOKINGS_URL?bookingstatus=0&pageSize=15&pageNum=1",
                    cleanToken = cleanToken
                )
            }.getOrDefault("")
            val rawInProgressList = if (inProgressBody.isNotBlank()) {
                parseBookingListResponse(inProgressBody)
            } else {
                emptyList()
            }

            val historyBody = runCatching {
                executeAuthenticatedApiGet(
                    url = "$WSLIB_HISTORY_BOOKINGS_URL?bookingstatus=0&pageSize=15&pageNum=1",
                    cleanToken = cleanToken
                )
            }.getOrDefault("")
            val rawHistoryList = if (historyBody.isNotBlank()) {
                parseBookingListResponse(historyBody)
            } else {
                emptyList()
            }

            val ruleBody = runCatching {
                executeAuthenticatedApiGet(
                    url = WSLIB_RULE_URL,
                    cleanToken = cleanToken
                )
            }.getOrDefault("")
            val (shortMin, longMin) = parseRuleLeaveMinutes(ruleBody)

            // `/seatbook/user/mybookingInProgress` 会返回当日全部预约（包含已取消 status=5 / 已签退 status=6），
            // 此处严格将活跃预约与今日已结束记录分离，保证「最近预约记录」完整展示今日已取消/已签退记录。
            val activeInProgress = rawInProgressList.filter { it.isActive }
            val finishedToday = rawInProgressList.filter { !it.isActive }
            val mergedHistory = (finishedToday + rawHistoryList)
                .distinctBy { it.bookingId }
                .sortedByDescending { it.bookingId }

            val effectiveCurrent = currentRecord?.takeIf { it.isActive }
                ?: activeInProgress.firstOrNull()

            LibrarySeatOverviewSnapshot(
                currentBooking = effectiveCurrent,
                inProgressBookings = activeInProgress,
                recentHistory = mergedHistory,
                shortLeaveMinutes = shortMin,
                longLeaveMinutes = longMin,
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    /**
     * 执行座位控制动作（取消预约、签到、快速签到、短暂离、长暂离、暂离返回、续座、签退释放）。
     *
     * 当执行 [LibrarySeatActionType.CANCEL_BOOKING] 且遇到「当前预约已开始，不能取消预约」时，
     * 自动降级尝试「签退释放 (`signoff`)」及「签到+签退」链路：
     * - 若用户已在馆内闸机刷脸自动签到，将直接通过 `signoff` 完成座位释放；
     * - 若仍处于待刷脸签到状态，返回明确的整 5 分钟同步时刻与闸机刷脸签退指引。
     */
    suspend fun performSeatAction(
        rawToken: String,
        action: LibrarySeatActionType,
        bookingId: Long? = null
    ): Result<LibrarySeatActionOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = normalizeRawToken(rawToken)
            if (cleanToken.isBlank()) throw LibrarySeatSessionExpiredException()
            if (action.requiresBookingId && (bookingId == null || bookingId <= 0L)) {
                return@runCatching LibrarySeatActionOutcome(
                    success = false,
                    message = "当前没有可操作的有效座位预约",
                    rawCode = -1
                )
            }
            val targetUrl = when (action) {
                LibrarySeatActionType.SIGN_IN ->
                    "$WSLIB_API_BASE_URL/seatbook/user/signin?bookingid=$bookingId&channelType=1003&positionType=1"
                LibrarySeatActionType.QUICK_SIGN_IN ->
                    "$WSLIB_API_BASE_URL/seatbook/user/quickSignIn?channelType=1003&positionType=1&status=1"
                LibrarySeatActionType.CANCEL_BOOKING ->
                    "$WSLIB_API_BASE_URL/seatbook/user/cancel?bookingid=$bookingId&channelType=1003"
                LibrarySeatActionType.SHORT_LEAVE ->
                    "$WSLIB_API_BASE_URL/seatbook/user/leave4short?bookingid=$bookingId&channelType=1003"
                LibrarySeatActionType.LONG_LEAVE ->
                    "$WSLIB_API_BASE_URL/seatbook/user/leave4long?bookingid=$bookingId&channelType=1003"
                LibrarySeatActionType.LEAVE_BACK ->
                    "$WSLIB_API_BASE_URL/seatbook/user/leaveBack?bookingid=$bookingId"
                LibrarySeatActionType.RENEW_SEAT ->
                    "$WSLIB_API_BASE_URL/seatbook/user/keepon?bookingid=$bookingId"
                LibrarySeatActionType.SIGN_OUT ->
                    "$WSLIB_API_BASE_URL/seatbook/user/signoff?bookingid=$bookingId"
            }
            val body = executeAuthenticatedApiGet(url = targetUrl, cleanToken = cleanToken)
            val initialOutcome = parseActionOutcome(body, action)

            if (action == LibrarySeatActionType.CANCEL_BOOKING &&
                isCancelBlockedByStartedWindow(initialOutcome) &&
                bookingId != null &&
                bookingId > 0L
            ) {
                // 1) 先尝试直接签退释放（若后台整 5 分钟闸机任务已自动签到，此调用将直接成功释放座位）
                val signOffUrl = "$WSLIB_API_BASE_URL/seatbook/user/signoff?bookingid=$bookingId"
                val firstSignOffBody = runCatching {
                    executeAuthenticatedApiGet(url = signOffUrl, cleanToken = cleanToken)
                }.getOrDefault("")
                val firstSignOffOutcome = parseActionOutcome(firstSignOffBody, LibrarySeatActionType.SIGN_OUT)
                if (firstSignOffOutcome.success) {
                    return@runCatching resolveStartedBookingCancelFallback(
                        cancelOutcome = initialOutcome,
                        signOffOutcome = firstSignOffOutcome
                    )
                }

                // 2) 若尚未签到，尝试触发在线签到 + 快速签到后再尝试签退释放
                val signInUrl = "$WSLIB_API_BASE_URL/seatbook/user/signin?bookingid=$bookingId&channelType=1003&positionType=1"
                val signInOutcome = runCatching {
                    parseActionOutcome(
                        executeAuthenticatedApiGet(url = signInUrl, cleanToken = cleanToken),
                        LibrarySeatActionType.SIGN_IN
                    )
                }.getOrNull()
                val quickSignInUrl = "$WSLIB_API_BASE_URL/seatbook/user/quickSignIn?channelType=1003&positionType=1&status=1"
                val quickSignInOutcome = if (signInOutcome?.success != true) {
                    runCatching {
                        parseActionOutcome(
                            executeAuthenticatedApiGet(url = quickSignInUrl, cleanToken = cleanToken),
                            LibrarySeatActionType.QUICK_SIGN_IN
                        )
                    }.getOrNull()
                } else {
                    null
                }
                if (signInOutcome?.success == true || quickSignInOutcome?.success == true) {
                    val secondSignOffBody = runCatching {
                        executeAuthenticatedApiGet(url = signOffUrl, cleanToken = cleanToken)
                    }.getOrDefault("")
                    val secondSignOffOutcome = parseActionOutcome(secondSignOffBody, LibrarySeatActionType.SIGN_OUT)
                    return@runCatching resolveStartedBookingCancelFallback(
                        cancelOutcome = initialOutcome,
                        signOffOutcome = secondSignOffOutcome
                    )
                }

                return@runCatching resolveStartedBookingCancelFallback(
                    cancelOutcome = initialOutcome,
                    signOffOutcome = firstSignOffOutcome
                )
            }

            initialOutcome
        }
    }

    private fun inferLayerNameFromRegion(regionName: String, rawLayerName: String): String {
        val cleaned = rawLayerName.cleanNullLiteral()
        if (cleaned.isNotEmpty()) return cleaned
        val match = Regex("^([一二三四五六七八九十]+层)").find(regionName.cleanNullLiteral())
        return match?.groupValues?.getOrNull(1) ?: "自习室"
    }

    private fun resolveDefaultTimeWindow(
        cleanToken: String,
        regionId: Long,
        bookingDate: String
    ): Triple<String, String, JSONObject?> {
        val defaultTimeUrl = "$WSLIB_API_BASE_URL/seatbook/layout/getDefaultTime?regionid=$regionId&bookingdate=${URLEncoder.encode(bookingDate, "UTF-8")}"
        val defaultTimeRaw = runCatching {
            executeAuthenticatedApiGet(url = defaultTimeUrl, cleanToken = cleanToken)
        }.getOrDefault("")
        val dtRoot = runCatching { JSONObject(defaultTimeRaw) }.getOrNull()
        if (dtRoot?.optInt("code", -1) == 401) throw LibrarySeatSessionExpiredException()

        val rawStartDate = dtRoot?.optCleanString("startDate")?.ifBlank { null } ?: bookingDate
        val rawEndDate = dtRoot?.optCleanString("endDate")?.ifBlank { null } ?: bookingDate
        val rawStartTime = dtRoot?.optCleanString("startTime")?.ifBlank { null } ?: "07:30"
        val rawEndTime = dtRoot?.optCleanString("endTime")?.ifBlank { null } ?: "22:00"

        var startFull = when {
            rawStartDate.contains(" ") -> rawStartDate
            rawStartTime.count { it == ':' } == 1 -> "$rawStartDate $rawStartTime:00"
            else -> "$rawStartDate $rawStartTime"
        }
        var endFull = when {
            rawEndDate.contains(" ") -> rawEndDate
            rawEndTime.count { it == ':' } == 1 -> "$rawEndDate $rawEndTime:00"
            else -> "$rawEndDate $rawEndTime"
        }

        // 夜间（21:50 之后）查询时，wslib 会将 startDate 设为当前时间+5分钟，导致 startFull >= endFull 并触发 seat.10058 (500)，
        // 此处自动钳制回目标日期的标准开放时段 07:30:00..22:00:00
        if (startFull >= endFull || rawStartTime >= "21:50" || !startFull.startsWith(bookingDate)) {
            startFull = "$bookingDate 07:30:00"
            endFull = "$bookingDate 22:00:00"
        }
        return Triple(startFull, endFull, dtRoot)
    }

    private fun currentShanghaiDateString(): String =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()

    /**
     * 自动识别并拉取全馆自习区域列表（依次调用 `/seatbook/layout/getDefaultTime`、`/seatbook/layout/building` 与 `/seatbook/layout/region`）。
     * 当用户在当日营业时段内选择「明天」（`bookingDate > today`）时，学校接口仅允许使用当日时间窗查询区域布局，
     * 此处自动用当日合法时间窗拉取物理区域与总座位数，并将明日预选可用座位数设为该自习室总座位数。
     */
    suspend fun fetchLibraryStudyRegions(
        rawToken: String,
        bookingDate: String
    ): Result<List<LibraryStudyRegionItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = normalizeRawToken(rawToken)
            if (cleanToken.isBlank()) throw LibrarySeatSessionExpiredException()

            val shanghaiToday = currentShanghaiDateString()
            val isFutureDate = bookingDate.isNotBlank() && bookingDate > shanghaiToday
            val queryDate = if (isFutureDate) shanghaiToday else bookingDate.ifBlank { shanghaiToday }
            val (startFull, endFull, _) = resolveDefaultTimeWindow(cleanToken, 7L, queryDate)

            val buildingRaw = executeAuthenticatedApiGet(
                url = "$WSLIB_API_BASE_URL/seatbook/layout/building",
                cleanToken = cleanToken
            )
            val buildingRoot = JSONObject(buildingRaw)
            if (buildingRoot.optInt("code", -1) == 401) throw LibrarySeatSessionExpiredException()
            val builderArr = buildingRoot.optJSONArray("builderInfo")
                ?: buildingRoot.optJSONObject("data")?.optJSONArray("builderInfo")

            val resultList = mutableListOf<LibraryStudyRegionItem>()
            val startTimeParam = URLEncoder.encode(startFull, "UTF-8")
            val endTimeParam = URLEncoder.encode(endFull, "UTF-8")

            if (builderArr != null && builderArr.length() > 0) {
                for (bIdx in 0 until builderArr.length()) {
                    val bObj = builderArr.optJSONObject(bIdx) ?: continue
                    val buildingId = bObj.optCleanString("id")
                    val buildingName = bObj.optCleanString("buildingName", "莲花街图书馆")
                    if (buildingId.isEmpty()) continue

                    // 拉取该场馆全部楼层的自习区（layerid 为空代表全部楼层）
                    val regionUrl = "$WSLIB_API_BASE_URL/seatbook/layout/region?buildingId=${URLEncoder.encode(buildingId, "UTF-8")}&layerid=&starttime=$startTimeParam&endtime=$endTimeParam"
                    val regionRaw = runCatching {
                        executeAuthenticatedApiGet(url = regionUrl, cleanToken = cleanToken)
                    }.getOrDefault("")
                    val regionRoot = runCatching { JSONObject(regionRaw) }.getOrNull()
                    val regionArr = regionRoot?.optJSONArray("regionList")
                        ?: regionRoot?.optJSONObject("data")?.optJSONArray("regionList")

                    if (regionArr != null && regionArr.length() > 0) {
                        for (rIdx in 0 until regionArr.length()) {
                            val rObj = regionArr.optJSONObject(rIdx) ?: continue
                            val regionId = rObj.optLong("id", -1L).takeIf { it > 0L } ?: continue
                            val regionName = rObj.optCleanString("regionName")
                            if (regionName.isEmpty()) continue
                            val totalNum = rObj.optInt("num", 0)
                            val canNum = rObj.optInt("cannum", 0)
                            val openDate = rObj.optCleanString("openDate", "07:30")
                            val closeDate = rObj.optCleanString("closeDate", "22:00")
                            val isOpen = rObj.optInt("isOpen", 1) != 0
                            val rawLayer = rObj.optCleanString("layerName")
                            val layerName = inferLayerNameFromRegion(regionName, rawLayer)
                            resultList.add(
                                LibraryStudyRegionItem(
                                    id = regionId,
                                    regionName = regionName,
                                    buildingId = buildingId,
                                    buildingName = buildingName,
                                    layerName = layerName,
                                    openTime = openDate,
                                    closeTime = closeDate,
                                    totalSeats = totalNum,
                                    availableSeats = if (isFutureDate && totalNum > 0) totalNum else canNum,
                                    isOpen = isOpen
                                )
                            )
                        }
                    } else {
                        // 若空 layerid 未返回，则逐楼层拉取
                        val layerUrl = "$WSLIB_API_BASE_URL/seatbook/layout/layer?buildingid=${URLEncoder.encode(buildingId, "UTF-8")}"
                        val layerRaw = runCatching {
                            executeAuthenticatedApiGet(url = layerUrl, cleanToken = cleanToken)
                        }.getOrDefault("")
                        val layerRoot = runCatching { JSONObject(layerRaw) }.getOrNull()
                        val layerArr = layerRoot?.optJSONArray("layerList")
                            ?: layerRoot?.optJSONObject("data")?.optJSONArray("layerList")
                        if (layerArr != null) {
                            for (lIdx in 0 until layerArr.length()) {
                                val lObj = layerArr.optJSONObject(lIdx) ?: continue
                                val layerId = lObj.optCleanString("id")
                                val rawLayer = lObj.optCleanString("layerName")
                                if (layerId.isEmpty()) continue
                                val lRegionUrl = "$WSLIB_API_BASE_URL/seatbook/layout/region?buildingId=${URLEncoder.encode(buildingId, "UTF-8")}&layerid=${URLEncoder.encode(layerId, "UTF-8")}&starttime=$startTimeParam&endtime=$endTimeParam"
                                val lRegionRaw = runCatching {
                                    executeAuthenticatedApiGet(url = lRegionUrl, cleanToken = cleanToken)
                                }.getOrDefault("")
                                val lRegionRoot = runCatching { JSONObject(lRegionRaw) }.getOrNull()
                                val lRegionArr = lRegionRoot?.optJSONArray("regionList")
                                    ?: lRegionRoot?.optJSONObject("data")?.optJSONArray("regionList")
                                    ?: continue
                                for (rIdx in 0 until lRegionArr.length()) {
                                    val rObj = lRegionArr.optJSONObject(rIdx) ?: continue
                                    val regionId = rObj.optLong("id", -1L).takeIf { it > 0L } ?: continue
                                    val regionName = rObj.optCleanString("regionName")
                                    if (regionName.isEmpty()) continue
                                    val totalNum = rObj.optInt("num", 0)
                                    val canNum = rObj.optInt("cannum", 0)
                                    resultList.add(
                                        LibraryStudyRegionItem(
                                            id = regionId,
                                            regionName = regionName,
                                            buildingId = buildingId,
                                            buildingName = buildingName,
                                            layerId = layerId,
                                            layerName = inferLayerNameFromRegion(regionName, rawLayer),
                                            openTime = rObj.optCleanString("openDate", "07:30"),
                                            closeTime = rObj.optCleanString("closeDate", "22:00"),
                                            totalSeats = totalNum,
                                            availableSeats = if (isFutureDate && totalNum > 0) totalNum else canNum,
                                            isOpen = rObj.optInt("isOpen", 1) != 0
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            resultList.distinctBy { it.id }
        }
    }

    /**
     * 读取指定自习区域 (`regionId`) 在目标日期的默认时段与全部下属座位列表 (`/seatbook/layout/getDefaultTime` & `/seatbook/layout/query`)。
     * 当用户在当日营业时间内选择明日预约（`bookingDate > today`）时，学校 `/seatbook/layout/query` 对跨日查询会返回 `seat.10057 (500)`，
     * 此处自动采用当日合法时间窗获取该自习室的真实物理座位图，并将明日待抢座位统一标记为可预选 (`seatStatus = 2`)。
     */
    suspend fun fetchLibraryRegionSeats(
        rawToken: String,
        regionId: Long,
        bookingDate: String
    ): Result<LibraryRegionSeatsSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = normalizeRawToken(rawToken)
            if (cleanToken.isBlank()) throw LibrarySeatSessionExpiredException()

            val shanghaiToday = currentShanghaiDateString()
            val isFutureDate = bookingDate.isNotBlank() && bookingDate > shanghaiToday
            val queryDate = if (isFutureDate) shanghaiToday else bookingDate.ifBlank { shanghaiToday }

            val (startFull, endFull, dtRoot) = resolveDefaultTimeWindow(cleanToken, regionId, queryDate)
            val rawStartTime = dtRoot?.optCleanString("startTime", "07:30") ?: "07:30"
            val rawEndTime = dtRoot?.optCleanString("endTime", "22:00") ?: "22:00"
            val startTime = if (isFutureDate || rawStartTime >= "21:50" || rawStartTime >= rawEndTime) "07:30" else rawStartTime
            val endTime = if (isFutureDate) "22:00" else rawEndTime.ifBlank { "22:00" }
            val canSetTime = (dtRoot?.optInt("isCanSetTime", 0) ?: 0) != 0
            val cleanBookingDate = bookingDate.cleanNullLiteral(shanghaiToday).ifBlank { shanghaiToday }

            val queryUrl = "$WSLIB_API_BASE_URL/seatbook/layout/query?starttime=${URLEncoder.encode(startFull, "UTF-8")}&endtime=${URLEncoder.encode(endFull, "UTF-8")}&regionid=$regionId"
            val queryRaw = executeAuthenticatedApiGet(url = queryUrl, cleanToken = cleanToken)
            var qRoot = JSONObject(queryRaw)
            if (qRoot.optInt("code", -1) == 401) throw LibrarySeatSessionExpiredException()

            var seatArr = qRoot.optJSONArray("seatList")
                ?: qRoot.optJSONObject("data")?.optJSONArray("seatList")
            if ((seatArr == null || seatArr.length() == 0) && queryDate != shanghaiToday) {
                val (todayStart, todayEnd, _) = resolveDefaultTimeWindow(cleanToken, regionId, shanghaiToday)
                val fallbackUrl = "$WSLIB_API_BASE_URL/seatbook/layout/query?starttime=${URLEncoder.encode(todayStart, "UTF-8")}&endtime=${URLEncoder.encode(todayEnd, "UTF-8")}&regionid=$regionId"
                val fallbackRaw = runCatching { executeAuthenticatedApiGet(url = fallbackUrl, cleanToken = cleanToken) }.getOrDefault("")
                val fallbackRoot = runCatching { JSONObject(fallbackRaw) }.getOrNull()
                if (fallbackRoot != null) {
                    qRoot = fallbackRoot
                    seatArr = qRoot.optJSONArray("seatList")
                        ?: qRoot.optJSONObject("data")?.optJSONArray("seatList")
                }
            }

            val layoutName = qRoot.optJSONObject("layout")?.optCleanString("regionName").orEmpty()
            val seats = mutableListOf<LibrarySeatItem>()
            if (seatArr != null) {
                for (i in 0 until seatArr.length()) {
                    val sObj = seatArr.optJSONObject(i) ?: continue
                    val seatId = sObj.optLong("id", -1L).takeIf { it > 0L } ?: continue
                    val rawSeatName = sObj.optCleanString("seatName")
                    if (rawSeatName.isEmpty()) continue
                    val shortSeatName = if (layoutName.isNotEmpty() && rawSeatName.startsWith(layoutName)) {
                        rawSeatName.removePrefix(layoutName).trim().ifEmpty { rawSeatName }
                    } else {
                        rawSeatName
                    }
                    val rawStatus = sObj.optInt("seatStatus", 2)
                    val status = if (isFutureDate) 2 else rawStatus
                    seats.add(
                        LibrarySeatItem(
                            id = seatId,
                            seatName = shortSeatName,
                            seatStatus = status
                        )
                    )
                }
            }
            LibraryRegionSeatsSnapshot(
                regionId = regionId,
                regionName = layoutName,
                bookingDate = cleanBookingDate,
                startTime = startTime,
                endTime = endTime,
                canSetTime = canSetTime,
                seats = seats.sortedWith(compareBy({ it.seatName.length }, { it.seatName }))
            )
        }
    }

    /**
     * 取消/释放当前已生效的图书馆座位预约（自动尝试 `/seatbook/user/cancel`、`/seatbook/user/cancelbooking` 与 `/seatbook/user/signoff`）。
     */
    suspend fun cancelActiveSeatBooking(
        rawToken: String,
        bookingId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = normalizeRawToken(rawToken)
            if (cleanToken.isBlank()) throw LibrarySeatSessionExpiredException()

            val resolvedId = bookingId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
                ?: fetchSeatBookingOverview(cleanToken).getOrNull()?.currentBooking?.takeIf { it.isActive }?.bookingId?.toString()
                ?: throw IllegalStateException("当前没有正在生效的图书馆预约座位")

            val endpoints = listOf(
                "/seatbook/user/cancel?bookingid=$resolvedId",
                "/seatbook/user/cancel?id=$resolvedId",
                "/seatbook/user/cancelbooking?bookingid=$resolvedId",
                "/seatbook/user/signoff?bookingid=$resolvedId"
            )
            var lastMsg = "已发送退座释放请求"
            for (ep in endpoints) {
                val raw = runCatching {
                    executeAuthenticatedApiGet(url = "$WSLIB_API_BASE_URL$ep", cleanToken = cleanToken)
                }.getOrDefault("")
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val msg = obj.optString("msg").ifBlank { obj.optString("message") }
                if (msg.isNotBlank()) lastMsg = msg
                if (obj.optInt("code", -1) == 200) {
                    return@runCatching "已成功取消并释放座位（编号 $resolvedId）"
                }
            }
            val afterActive = fetchSeatBookingOverview(cleanToken).getOrNull()?.currentBooking?.takeIf { it.isActive }
            if (afterActive == null) {
                "已成功取消并释放座位"
            } else {
                throw IllegalStateException("退座未生效：$lastMsg")
            }
        }
    }

    /**
     * 执行自动选座并核实结果。
     *
     * 流程：
     * 1. 先检查是否已有生效座位；
     * 2. 自动根据所选自习室 (`preferredAreaName`) 与座位号 (`preferredSeatLabel`) 查询真实 `regionId`、开放时段及下属空闲 `seatId`；
     * 3. 按首选、备选顺序依次向 `/seatbook/user/addbooking?channel=1003&seatid=...&starttime=...&endtime=...` 提交选座；
     * 4. 再次调用 `/seatbook/user/my/crrentbooking` 确认选座结果。
     */
    suspend fun executeAutomatedSeatBooking(
        rawToken: String,
        targetDate: String,
        preferredAreaName: String,
        preferredSeatLabel: String,
        preferredSlotWindow: String = "07:30-22:00"
    ): Result<AutomatedSeatBookingResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = normalizeRawToken(rawToken)
            if (cleanToken.isBlank()) throw LibrarySeatSessionExpiredException()

            val candidateSeatNames = preferredSeatLabel
                .split(",", "，", "、", "/", "|", ";", "；")
                .map { it.trim().removeSuffix("号座").removeSuffix("号") }
                .filter { it.isNotEmpty() }

            // 1. 先解析实时区域列表与期望预约起止时段（用户计划时段优先于学校默认开放时段），供预检、写后核验与历史回退统一约束
            val regions = fetchLibraryStudyRegions(cleanToken, targetDate).getOrNull().orEmpty()
            val matchedRegion = regions.firstOrNull {
                it.regionName.equals(preferredAreaName.trim(), ignoreCase = true) ||
                    it.regionName.contains(preferredAreaName.trim()) ||
                    preferredAreaName.trim().contains(it.regionName)
            } ?: if (candidateSeatNames.isEmpty()) regions.firstOrNull { it.isOpen && it.availableSeats > 0 } else null

            val defaultWindowPair = if (matchedRegion != null) {
                val (s, e, _) = resolveDefaultTimeWindow(cleanToken, matchedRegion.id, targetDate)
                s to e
            } else {
                "$targetDate 07:30:00" to "$targetDate 22:00:00"
            }
            val (expectedStartFull, expectedEndFull) = resolveTargetSlotWindowBounds(
                targetDate = targetDate,
                slotWindow = preferredSlotWindow,
                fallbackStartFull = defaultWindowPair.first,
                fallbackEndFull = defaultWindowPair.second
            )

            val preOverview = fetchSeatBookingOverview(cleanToken).getOrNull()
            val preActiveList = (listOfNotNull(preOverview?.currentBooking) + preOverview?.inProgressBookings.orEmpty())
                .filter { it.isActive }
                .distinctBy { it.bookingId }
            val matchingPreActive = preActiveList.firstOrNull {
                isBookingRecordMatchingTarget(
                    record = it,
                    targetDate = targetDate,
                    preferredAreaName = matchedRegion?.regionName?.ifBlank { preferredAreaName } ?: preferredAreaName,
                    candidateSeatNames = candidateSeatNames,
                    expectedStartFull = expectedStartFull,
                    expectedEndFull = expectedEndFull,
                    expectedRegionId = matchedRegion?.id
                )
            }
            if (matchingPreActive != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("haut_active_${matchingPreActive.bookingId}_${matchingPreActive.seatName}".toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                return@runCatching AutomatedSeatBookingResult(
                    confirmed = true,
                    bookingId = matchingPreActive.bookingId.toString(),
                    seatTitle = matchingPreActive.displaySeatTitle,
                    evidenceSha256 = digest,
                    diagnosticMessage = "您已有生效中的目标座位：${matchingPreActive.displaySeatTitle}（时段 ${matchingPreActive.timeWindowText}）",
                    outcomeState = "CONFIRMED"
                )
            }
            val sameDayUnrelatedPreActive = preActiveList.firstOrNull {
                targetDate.isNotBlank() && it.startDate.trim().startsWith(targetDate.trim())
            }
            if (sameDayUnrelatedPreActive != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("haut_unrelated_${sameDayUnrelatedPreActive.bookingId}_${sameDayUnrelatedPreActive.seatName}".toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                return@runCatching AutomatedSeatBookingResult(
                    confirmed = false,
                    bookingId = sameDayUnrelatedPreActive.bookingId.toString(),
                    seatTitle = sameDayUnrelatedPreActive.displaySeatTitle,
                    evidenceSha256 = digest,
                    diagnosticMessage = "当前账号在该日期已有其他非目标座位预约（${sameDayUnrelatedPreActive.displaySeatTitle}），已停止抢座",
                    outcomeState = "FAILED"
                )
            }

            var apiRawMessage = ""
            var bookedIdFromApi: Long? = null
            var matchedConfirmedRecord: LibrarySeatBookingRecord? = null
            var attemptedLiveCandidatePool = false
            var unknownWriteStopped = false
            var resultIsUnknown = false

            if (matchedRegion != null) {
                val startFull = expectedStartFull
                val endFull = expectedEndFull
                val roomSnapshot = fetchLibraryRegionSeats(
                    rawToken = cleanToken,
                    regionId = matchedRegion.id,
                    bookingDate = targetDate
                ).getOrNull()

                if (roomSnapshot != null && roomSnapshot.seats.isNotEmpty()) {
                    // 优先按用户点选的首选/备选顺序找座位（即便早高峰跨日快照也能锁定候选 seatId）
                    val orderedCandidates = mutableListOf<LibrarySeatItem>()
                    for (wanted in candidateSeatNames) {
                        val normWanted = normalizeSeatComparisonToken(wanted, matchedRegion.regionName)
                        roomSnapshot.seats.firstOrNull { s ->
                            val normSeat = normalizeSeatComparisonToken(s.seatName, matchedRegion.regionName)
                            s.seatName.equals(wanted, ignoreCase = true) ||
                                normSeat == normWanted ||
                                s.id.toString() == wanted.trim()
                        }?.let { orderedCandidates.add(it) }
                    }
                    // 仅当用户未指定具体候选座位时，才回退取自习室前 3 个空座；严禁在用户已指定首选/备选座位时暗中塞入 01桌 座位
                    if (orderedCandidates.isEmpty() && candidateSeatNames.isEmpty()) {
                        roomSnapshot.seats.filter { it.isAvailable }.take(3).forEach { avail ->
                            orderedCandidates.add(avail)
                        }
                    }

                    val candidatePool = orderedCandidates.distinctBy { it.id }.take(5)
                    val candidatePoolIds = candidatePool.map { it.id }.toSet()
                    if (candidatePool.isNotEmpty()) {
                        attemptedLiveCandidatePool = true
                    }
                    val encodedStart = URLEncoder.encode(startFull, "UTF-8")
                    val encodedEnd = URLEncoder.encode(endFull, "UTF-8")

                    // 07:00:00 准点开闸只读探测：使用只读接口 /seatbook/layout/query 校验开闸状态，绝不调用写接口或触碰非用户候选座位
                    val nowLocalTime = java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalTime()
                    if (nowLocalTime.hour == 7 && nowLocalTime.minute == 0 && nowLocalTime.second <= 3) {
                        for (probeIdx in 0 until 12) {
                            if (probeIdx > 0) kotlinx.coroutines.delay(110L)
                            val probeUrl = "$WSLIB_API_BASE_URL/seatbook/layout/query?starttime=$encodedStart&endtime=$encodedEnd&regionid=${matchedRegion.id}"
                            val probeText = runCatching {
                                executeAuthenticatedApiGet(url = probeUrl, cleanToken = cleanToken)
                            }.getOrDefault("")
                            val probeObj = runCatching { JSONObject(probeText) }.getOrNull() ?: break
                            val probeRawMsg = probeObj.optString("msg")
                            val probeMsg = translateWslibDiagnosticMessage(probeRawMsg)
                            if (!isWslibNotYetOpenMessage(probeRawMsg) && !isWslibNotYetOpenMessage(probeMsg)) {
                                break
                            }
                        }
                    }

                    val seatLockedUntilMs = mutableMapOf<Long, Long>()
                    val permanentlyOccupiedIds = mutableSetOf<Long>()
                    val waveDelaysMs = listOf(0L, 180L, 220L, 260L, 320L, 380L, 460L, 550L, 680L, 800L, 950L, 1000L)
                    for ((attemptIdx, delayMs) in waveDelaysMs.withIndex()) {
                        if (attemptIdx > 0) {
                            val nowMs = System.currentTimeMillis()
                            val unlockedRemaining = candidatePool.filter { s ->
                                s.id !in permanentlyOccupiedIds && (seatLockedUntilMs[s.id] ?: 0L) <= nowMs
                            }
                            var waitMs = delayMs
                            if (unlockedRemaining.isEmpty()) {
                                val earliestUnlock = candidatePool
                                    .filter { it.id !in permanentlyOccupiedIds }
                                    .mapNotNull { seatLockedUntilMs[it.id] }
                                    .filter { it > nowMs }
                                    .minOrNull()
                                if (earliestUnlock != null) {
                                    waitMs = (earliestUnlock - nowMs + 40L).coerceIn(150L, 1200L)
                                }
                            }
                            if (waitMs > 0L) {
                                kotlinx.coroutines.delay(waitMs)
                            }
                        }
                        var needWaitOpening = false
                        var hasLockedCandidate = false
                        for (seat in candidatePool) {
                            if (seat.id in permanentlyOccupiedIds) continue
                            if ((seatLockedUntilMs[seat.id] ?: 0L) > System.currentTimeMillis()) {
                                hasLockedCandidate = true
                                continue
                            }
                            val bookingUrl = "$WSLIB_API_BASE_URL/seatbook/user/addbooking?channel=1003&seatid=${seat.id}&starttime=$encodedStart&endtime=$encodedEnd"
                            val respText = runCatching {
                                executeAuthenticatedApiGet(url = bookingUrl, cleanToken = cleanToken)
                            }.getOrDefault("")
                            val respObj = runCatching { JSONObject(respText) }.getOrNull()
                            if (respObj == null || !respObj.has("code")) {
                                // 写请求结果未知：执行一次严格按目标座位与日期的只读回查，若未确认或回查失败则立即停止所有后续写入与重试
                                unknownWriteStopped = true
                                val rbOverview = fetchSeatBookingOverview(cleanToken).getOrNull()
                                val rbActiveList = (listOfNotNull(rbOverview?.currentBooking) + rbOverview?.inProgressBookings.orEmpty())
                                    .filter { it.isActive }
                                    .distinctBy { it.bookingId }
                                val matched = rbActiveList.firstOrNull {
                                    isBookingRecordMatchingTarget(
                                        record = it,
                                        targetDate = targetDate,
                                        preferredAreaName = matchedRegion.regionName.ifBlank { preferredAreaName },
                                        candidateSeatNames = candidateSeatNames,
                                        candidatePoolIds = candidatePoolIds,
                                        specificSeat = seat,
                                        expectedStartFull = startFull,
                                        expectedEndFull = endFull,
                                        expectedRegionId = matchedRegion.id
                                    )
                                }
                                if (matched != null) {
                                    matchedConfirmedRecord = matched
                                    bookedIdFromApi = matched.bookingId
                                } else {
                                    resultIsUnknown = true
                                    apiRawMessage = "选座请求状态未知且回查未能确认目标座位，已停止重试以避免重复预约"
                                }
                                break
                            }
                            val code = respObj.optInt("code", -1)
                            val rawMsg = respObj.optString("msg")
                            apiRawMessage = translateWslibDiagnosticMessage(rawMsg)
                            if (code == 200) {
                                unknownWriteStopped = true
                                val returnedId = respObj.optLong("bookingid", -1L).takeIf { it > 0L }
                                bookedIdFromApi = returnedId
                                val rbOverview = fetchSeatBookingOverview(cleanToken).getOrNull()
                                val rbActiveList = (listOfNotNull(rbOverview?.currentBooking) + rbOverview?.inProgressBookings.orEmpty())
                                    .filter { it.isActive }
                                    .distinctBy { it.bookingId }
                                val matched = rbActiveList.firstOrNull {
                                    isBookingRecordMatchingTarget(
                                        record = it,
                                        targetDate = targetDate,
                                        preferredAreaName = matchedRegion.regionName.ifBlank { preferredAreaName },
                                        candidateSeatNames = candidateSeatNames,
                                        candidatePoolIds = candidatePoolIds,
                                        specificSeat = seat,
                                        expectedBookingId = returnedId,
                                        expectedStartFull = startFull,
                                        expectedEndFull = endFull,
                                        expectedRegionId = matchedRegion.id
                                    )
                                }
                                if (matched != null) {
                                    matchedConfirmedRecord = matched
                                    bookedIdFromApi = matched.bookingId
                                } else if (rbActiveList.isNotEmpty()) {
                                    apiRawMessage = "回查到的预约（${rbActiveList.first().displaySeatTitle}）与目标候选座位或日期不符，已停止重试"
                                } else {
                                    resultIsUnknown = true
                                    apiRawMessage = "选座已提交但回查未能核实目标座位与日期，已停止重试"
                                }
                                break
                            }
                            if (isWslibSeatOccupiedByOthersMessage(rawMsg, code) || isWslibSeatOccupiedByOthersMessage(apiRawMessage, code)) {
                                permanentlyOccupiedIds.add(seat.id)
                                continue
                            }
                            if (isWslibNotYetOpenMessage(rawMsg) || isWslibNotYetOpenMessage(apiRawMessage)) {
                                // 07:00 未开闸时触发了该座位的 3 秒 Redis 锁，记录锁定截止时间并立即跳出内层循环，避免连环锁死备选座位
                                seatLockedUntilMs[seat.id] = System.currentTimeMillis() + 3_050L
                                needWaitOpening = true
                                break
                            }
                            if (isWslibSeatLockedMessage(rawMsg, code) || isWslibSeatLockedMessage(apiRawMessage, code)) {
                                if ((seatLockedUntilMs[seat.id] ?: 0L) <= System.currentTimeMillis()) {
                                    seatLockedUntilMs[seat.id] = System.currentTimeMillis() + 1_050L
                                }
                                hasLockedCandidate = true
                                continue
                            }
                            if (isWslibAlreadyBookedMessage(rawMsg) || isWslibAlreadyBookedMessage(apiRawMessage)) {
                                unknownWriteStopped = true
                                val rbOverview = fetchSeatBookingOverview(cleanToken).getOrNull()
                                val rbActiveList = (listOfNotNull(rbOverview?.currentBooking) + rbOverview?.inProgressBookings.orEmpty())
                                    .filter { it.isActive }
                                    .distinctBy { it.bookingId }
                                val matched = rbActiveList.firstOrNull {
                                    isBookingRecordMatchingTarget(
                                        record = it,
                                        targetDate = targetDate,
                                        preferredAreaName = matchedRegion.regionName.ifBlank { preferredAreaName },
                                        candidateSeatNames = candidateSeatNames,
                                        candidatePoolIds = candidatePoolIds,
                                        expectedStartFull = startFull,
                                        expectedEndFull = endFull,
                                        expectedRegionId = matchedRegion.id
                                    )
                                }
                                if (matched != null) {
                                    matchedConfirmedRecord = matched
                                    bookedIdFromApi = matched.bookingId
                                } else if (rbActiveList.isNotEmpty()) {
                                    apiRawMessage = "当前账号已有其他非目标预约（${rbActiveList.first().displaySeatTitle}），已停止抢座"
                                } else {
                                    resultIsUnknown = true
                                    apiRawMessage = "学校提示已有预约但回查未能核实目标座位与日期，已停止重试"
                                }
                                break
                            }
                            // 其他未识别的写响应码：执行一次回查并立即停止所有后续写入与重试
                            unknownWriteStopped = true
                            val rbOverview = fetchSeatBookingOverview(cleanToken).getOrNull()
                            val rbActiveList = (listOfNotNull(rbOverview?.currentBooking) + rbOverview?.inProgressBookings.orEmpty())
                                .filter { it.isActive }
                                .distinctBy { it.bookingId }
                            val matched = rbActiveList.firstOrNull {
                                isBookingRecordMatchingTarget(
                                    record = it,
                                    targetDate = targetDate,
                                    preferredAreaName = matchedRegion.regionName.ifBlank { preferredAreaName },
                                    candidateSeatNames = candidateSeatNames,
                                    candidatePoolIds = candidatePoolIds,
                                    specificSeat = seat,
                                    expectedStartFull = startFull,
                                    expectedEndFull = endFull,
                                    expectedRegionId = matchedRegion.id
                                )
                            }
                            if (matched != null) {
                                matchedConfirmedRecord = matched
                                bookedIdFromApi = matched.bookingId
                            } else {
                                resultIsUnknown = true
                            }
                            break
                        }
                        if (matchedConfirmedRecord != null ||
                            unknownWriteStopped ||
                            permanentlyOccupiedIds.size >= candidatePool.size ||
                            (!needWaitOpening && !hasLockedCandidate)
                        ) {
                            break
                        }
                    }
                }
            }

            // 2. 若实时列表未能拉取且未发生未知写状态，才回退匹配用户所选候选座位的历史真实 seatId（必须先严格约束目标区域）
            if (matchedConfirmedRecord == null && !attemptedLiveCandidatePool && !unknownWriteStopped) {
                val matchedHistorical = preOverview?.recentHistory?.firstOrNull { rec ->
                    isHistoricalRecordMatchingCandidate(
                        record = rec,
                        preferredAreaName = matchedRegion?.regionName?.ifBlank { preferredAreaName } ?: preferredAreaName,
                        candidateSeatNames = candidateSeatNames,
                        expectedRegionId = matchedRegion?.id
                    )
                }

                val targetSeatId = matchedHistorical?.seatId
                if (targetSeatId != null && targetSeatId > 0L) {
                    val fallbackStartFull = expectedStartFull
                    val fallbackEndFull = expectedEndFull
                    val bookingUrl = "$WSLIB_API_BASE_URL/seatbook/user/addbooking?channel=1003&seatid=$targetSeatId&starttime=${URLEncoder.encode(fallbackStartFull, "UTF-8")}&endtime=${URLEncoder.encode(fallbackEndFull, "UTF-8")}"
                    val respText = runCatching {
                        executeAuthenticatedApiGet(url = bookingUrl, cleanToken = cleanToken)
                    }.getOrDefault("")
                    val respObj = runCatching { JSONObject(respText) }.getOrNull()
                    val returnedId = respObj?.optLong("bookingid", -1L)?.takeIf { it > 0L }
                    if (apiRawMessage.isBlank()) {
                        apiRawMessage = translateWslibDiagnosticMessage(respObj?.optString("msg").orEmpty())
                    }
                    val postOverview = fetchSeatBookingOverview(cleanToken).getOrNull()
                    val postActiveList = (listOfNotNull(postOverview?.currentBooking) + postOverview?.inProgressBookings.orEmpty())
                        .filter { it.isActive }
                        .distinctBy { it.bookingId }
                    matchedConfirmedRecord = postActiveList.firstOrNull {
                        isBookingRecordMatchingTarget(
                            record = it,
                            targetDate = targetDate,
                            preferredAreaName = matchedRegion?.regionName?.ifBlank { preferredAreaName } ?: preferredAreaName,
                            candidateSeatNames = candidateSeatNames,
                            candidatePoolIds = setOf(targetSeatId),
                            expectedBookingId = returnedId,
                            expectedStartFull = fallbackStartFull,
                            expectedEndFull = fallbackEndFull,
                            expectedRegionId = matchedRegion?.id
                        )
                    }
                    if (matchedConfirmedRecord == null && (respObj == null || !respObj.has("code") || respObj.optInt("code", -1) == 200)) {
                        resultIsUnknown = true
                    }
                }
            }

            // 3. 仅当回查严格核实了目标日期、目标时段、目标区域与目标候选座位时，才确认为抢座成功
            val confirmedRecord = matchedConfirmedRecord
            if (confirmedRecord != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("haut_confirmed_${confirmedRecord.bookingId}_${confirmedRecord.seatName}".toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                return@runCatching AutomatedSeatBookingResult(
                    confirmed = true,
                    bookingId = confirmedRecord.bookingId.toString(),
                    seatTitle = confirmedRecord.displaySeatTitle,
                    evidenceSha256 = digest,
                    diagnosticMessage = "抢座成功！已为您预约 ${confirmedRecord.displaySeatTitle}，请按规定时间刷脸入馆签到",
                    outcomeState = "CONFIRMED"
                )
            }

            val fallbackId = "haut_${targetDate}_${candidateSeatNames.firstOrNull() ?: preferredSeatLabel}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$fallbackId|$cleanToken".toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val detailHint = if (apiRawMessage.isNotBlank()) {
                "图书馆系统提示：$apiRawMessage"
            } else {
                "未能在目标候选座位完成预约确认（$preferredAreaName ${candidateSeatNames.joinToString("/")}）"
            }
            AutomatedSeatBookingResult(
                confirmed = false,
                bookingId = bookedIdFromApi?.toString() ?: fallbackId,
                seatTitle = "$preferredAreaName · ${candidateSeatNames.firstOrNull() ?: preferredSeatLabel}",
                evidenceSha256 = digest,
                diagnosticMessage = detailHint,
                outcomeState = if (resultIsUnknown) "RESULT_UNKNOWN" else "FAILED"
            )
        }
    }

    internal fun normalizeSeatComparisonToken(rawSeat: String, regionName: String = ""): String {
        var text = rawSeat.trim()
        if (text.isEmpty()) return ""
        val cleanReg = regionName.trim()
        if (cleanReg.isNotEmpty() && text.startsWith(cleanReg, ignoreCase = true)) {
            text = text.substring(cleanReg.length).trim()
        }
        return text
            .removeSuffix("号座")
            .removeSuffix("号")
            .replace(Regex("""\s+"""), "")
            .replace(Regex("""^0+(\d+)"""), "$1")
            .uppercase()
    }

    internal fun extractDateAndTimeHm(rawDateTime: String?): Pair<String, String>? {
        val text = rawDateTime?.trim().orEmpty()
        val m = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})(?::\d{2})?$""").matchEntire(text)
            ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    internal fun resolveTargetSlotWindowBounds(
        targetDate: String,
        slotWindow: String?,
        fallbackStartFull: String? = null,
        fallbackEndFull: String? = null
    ): Pair<String, String> {
        val cleanDate = targetDate.trim()
        val sw = slotWindow?.trim().orEmpty()
        val m = Regex("""^(\d{2}:\d{2})(?::(\d{2}))?\s*-\s*(\d{2}:\d{2})(?::(\d{2}))?$""").matchEntire(sw)
        if (m != null && cleanDate.isNotEmpty()) {
            val sSec = m.groupValues[2].ifEmpty { "00" }
            val eSec = m.groupValues[4].ifEmpty { "00" }
            return "$cleanDate ${m.groupValues[1]}:$sSec" to "$cleanDate ${m.groupValues[3]}:$eSec"
        }
        return (fallbackStartFull ?: "$cleanDate 07:30:00") to (fallbackEndFull ?: "$cleanDate 22:00:00")
    }

    internal fun isHistoricalRecordMatchingCandidate(
        record: LibrarySeatBookingRecord?,
        preferredAreaName: String,
        candidateSeatNames: List<String>,
        expectedRegionId: Long? = null
    ): Boolean {
        if (record == null || record.seatId == null || record.seatId <= 0L) return false
        if (expectedRegionId != null && record.buildingLayerRegionId != null && record.buildingLayerRegionId != expectedRegionId) {
            return false
        }
        val cleanArea = preferredAreaName.trim()
        val recRegion = record.regionName.trim()
        val recSeat = record.seatName.trim()
        if (cleanArea.isNotEmpty()) {
            if (recRegion.isNotEmpty()) {
                if (!recRegion.equals(cleanArea, ignoreCase = true)) return false
            } else if (!recSeat.startsWith(cleanArea)) {
                return false
            }
        } else if (expectedRegionId == null || record.buildingLayerRegionId != expectedRegionId) {
            return false
        }
        if (candidateSeatNames.isEmpty()) return true
        val normRec = normalizeSeatComparisonToken(recSeat, cleanArea.ifEmpty { recRegion })
        if (normRec.isEmpty()) return false
        return candidateSeatNames.any { cand ->
            val cleanCand = cand.trim()
            (cleanCand.all(Char::isDigit) && record.seatId.toString() == cleanCand) ||
                normRec == normalizeSeatComparisonToken(cleanCand, cleanArea.ifEmpty { recRegion })
        }
    }

    internal fun isBookingRecordMatchingTarget(
        record: LibrarySeatBookingRecord?,
        targetDate: String,
        preferredAreaName: String,
        candidateSeatNames: List<String>,
        candidatePoolIds: Set<Long> = emptySet(),
        specificSeat: LibrarySeatItem? = null,
        expectedBookingId: Long? = null,
        expectedStartFull: String? = null,
        expectedEndFull: String? = null,
        expectedRegionId: Long? = null
    ): Boolean {
        if (record == null || !record.isActive) return false
        if (expectedBookingId != null && expectedBookingId > 0L && record.bookingId != expectedBookingId) {
            return false
        }

        val actStart = extractDateAndTimeHm(record.startDate) ?: return false
        val actEnd = extractDateAndTimeHm(record.endDate) ?: return false
        if (actStart.first != actEnd.first) return false

        val cleanDate = targetDate.trim()
        if (cleanDate.isNotEmpty() && actStart.first != cleanDate) {
            return false
        }
        if (!expectedStartFull.isNullOrBlank()) {
            val expStart = extractDateAndTimeHm(expectedStartFull) ?: return false
            if (actStart.first != expStart.first || actStart.second != expStart.second) {
                return false
            }
        }
        if (!expectedEndFull.isNullOrBlank()) {
            val expEnd = extractDateAndTimeHm(expectedEndFull) ?: return false
            if (actEnd.first != expEnd.first || actEnd.second != expEnd.second) {
                return false
            }
        }

        if (expectedRegionId != null && record.buildingLayerRegionId != null) {
            if (record.buildingLayerRegionId != expectedRegionId) {
                return false
            }
        }

        val cleanArea = preferredAreaName.trim()
        val recRegion = record.regionName.trim()
        val recSeat = record.seatName.trim()
        val hasValidRegionIdMatch =
            expectedRegionId != null && record.buildingLayerRegionId != null && record.buildingLayerRegionId == expectedRegionId

        if (cleanArea.isNotEmpty()) {
            if (recRegion.isNotEmpty()) {
                if (!recRegion.equals(cleanArea, ignoreCase = true)) {
                    return false
                }
            } else if (!recSeat.startsWith(cleanArea)) {
                return false
            }
        } else if (!hasValidRegionIdMatch && recRegion.isEmpty()) {
            return false
        }

        val effectiveRegionForNorm = cleanArea.ifEmpty { recRegion }

        if (specificSeat != null) {
            if (record.seatId != null && record.seatId != specificSeat.id) {
                return false
            }
            val normRec = normalizeSeatComparisonToken(record.seatName, effectiveRegionForNorm)
            val normSpec = normalizeSeatComparisonToken(specificSeat.seatName, effectiveRegionForNorm)
            if (normRec.isNotEmpty() && normSpec.isNotEmpty() && normRec != normSpec) {
                return false
            }
            return (record.seatId != null && record.seatId == specificSeat.id) ||
                (normRec.isNotEmpty() && normRec == normSpec)
        }

        if (candidatePoolIds.isNotEmpty() && record.seatId != null && record.seatId !in candidatePoolIds) {
            return false
        }

        if (candidateSeatNames.isNotEmpty()) {
            val normRec = normalizeSeatComparisonToken(record.seatName, effectiveRegionForNorm)
            val matchedName = candidateSeatNames.any { wanted ->
                val cleanWanted = wanted.trim()
                (cleanWanted.all(Char::isDigit) && record.seatId != null && record.seatId.toString() == cleanWanted) ||
                    (normRec.isNotEmpty() && normRec == normalizeSeatComparisonToken(cleanWanted, effectiveRegionForNorm))
            }
            if (!matchedName) {
                return false
            }
        }

        return true
    }

    internal fun translateWslibDiagnosticMessage(rawMsg: String): String {
        val text = rawMsg.trim()
        if (text.isEmpty()) return ""
        return when {
            text.contains("seat.10042", ignoreCase = true) -> "只能预约1天之内的时间(含当天)"
            text.contains("seat.10056", ignoreCase = true) -> "预约开始时间须大于当前时间"
            text.contains("seat.10057", ignoreCase = true) -> "只能预约1天之内的时间(含当天)"
            text.contains("seat.10058", ignoreCase = true) -> "预约时间不在自习室开放时段内"
            text.contains("seat.10062", ignoreCase = true) -> "本座位已锁定 请稍后再试!"
            text.contains("seat.10079", ignoreCase = true) -> "未到07:00开放时间或该时段内已有生效预约"
            else -> text
        }
    }

    internal fun isWslibNotYetOpenMessage(rawMsg: String): Boolean {
        val text = rawMsg.trim()
        return Regex("""未到|未开放|不在.*时间|只能预约1天|尚未开放|seat\.10042|seat\.10057|seat\.10058|seat\.10079""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    internal fun isWslibSeatLockedMessage(rawMsg: String, code: Int = -1): Boolean {
        if (code == 1122) return true
        val text = rawMsg.trim()
        return Regex("""本座位已锁定|已锁定.*稍后再试|seat\.10062""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    internal fun isWslibSeatOccupiedByOthersMessage(rawMsg: String, code: Int = -1): Boolean {
        val text = rawMsg.trim()
        if (code == 404 && (text.contains("该座位") || text.contains("此座位"))) return true
        return Regex("""该座位已有预约|此座位已有预约|该座位已被预约|座位已被占用""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    internal fun isWslibAlreadyBookedMessage(rawMsg: String): Boolean {
        val text = rawMsg.trim()
        if (isWslibSeatOccupiedByOthersMessage(text)) return false
        return Regex("""您已有.*预约|不能重复|已有一个.*预约|只能预约一个座位|当前已有预约|seat\.10060|seat\.10061|seat\.10079""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    private fun executeAuthenticatedApiGet(url: String, cleanToken: String): String {
        val uri = validateAuthUri(url)
        val conn = (uri.toURL().openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            conn.setRequestProperty("Authorization", "Bearer $cleanToken")
            conn.setRequestProperty("Access-Control-Allow-Methods", "POST,GET,OPTIONS")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val status = conn.responseCode
            if (status == 401) throw LibrarySeatSessionExpiredException()
            return readBoundedBody(conn, status)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 在线校验 `wslib` Token 是否仍然有效。
     */
    suspend fun validateTokenOnline(rawToken: String): LibrarySeatUserInfo? = withContext(Dispatchers.IO) {
        val cleanToken = normalizeRawToken(rawToken)
        if (cleanToken.isBlank()) return@withContext null
        runCatching {
            val uri = validateAuthUri(WSLIB_USER_INFO_URL)
            val conn = (uri.toURL().openConnection() as HttpURLConnection)
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                conn.setRequestProperty("Authorization", "Bearer $cleanToken")
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                val status = conn.responseCode
                if (status != 200) return@runCatching null
                val body = readBoundedBody(conn)
                parseUserInfoResponse(body)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 将持久化统一身份认证 Cookie (`CASTGC` / `JSESSIONID`) 同步写入系统 [CookieManager]，
     * 使得内嵌 WebView 与后台 HTTP 客户端共享同一登录态。
     */
    fun syncCasCookiesToWebView(casTgcCookie: String?, casJsessionId: String? = null) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (!casTgcCookie.isNullOrBlank()) {
                cm.setCookie(
                    "https://mapp.haut.edu.cn/cas/",
                    "CASTGC=${casTgcCookie.trim()}; Path=/cas/; Secure; HttpOnly"
                )
            }
            if (!casJsessionId.isNullOrBlank()) {
                cm.setCookie(
                    "https://mapp.haut.edu.cn/cas",
                    "JSESSIONID=${casJsessionId.trim()}; Path=/cas; Secure; HttpOnly"
                )
            }
            SchoolWebSessionManager.persistCookiesAsync()
        }
    }

    /**
     * 从系统 [CookieManager] 中提取当前 `mapp.haut.edu.cn` 的 `CASTGC` Cookie（若存在）。
     */
    fun extractCastgcFromWebView(): String? = runCatching {
        val cm = CookieManager.getInstance()
        val cookieHeader = listOfNotNull(
            cm.getCookie("https://mapp.haut.edu.cn/cas/"),
            cm.getCookie("https://mapp.haut.edu.cn/cas/login"),
            cm.getCookie("https://mapp.haut.edu.cn")
        ).joinToString("; ")
        Regex("""(?:^|;\s*)CASTGC=([^;\s]+)""")
            .find(cookieHeader)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 使用持久化的 `CASTGC` Cookie 尝试静默换取新的 `wslib` Token（无需再次提交密码）。
     */
    suspend fun tryExchangeTokenWithPersistedCookie(
        casTgcCookie: String,
        casJsessionId: String? = null
    ): LibrarySeatSessionSnapshot? = withContext(Dispatchers.IO) {
        val cleanTgc = casTgcCookie.trim()
        if (cleanTgc.isBlank()) return@withContext null
        runCatching {
            val cookieJar = IsolatedAuthCookieJar()
            cookieJar.seedCookie(
                uri = URI("https://mapp.haut.edu.cn/cas/"),
                name = "CASTGC",
                value = cleanTgc,
                path = "/cas/"
            )
            if (!casJsessionId.isNullOrBlank()) {
                cookieJar.seedCookie(
                    uri = URI("https://mapp.haut.edu.cn/cas"),
                    name = "JSESSIONID",
                    value = casJsessionId.trim(),
                    path = "/cas"
                )
            }

            val hop0 = executeHop(
                url = CAS_LOGIN_ENTRY_URL,
                method = "GET",
                cookieJar = cookieJar
            )
            if (hop0.statusCode !in listOf(301, 302, 303, 307, 308) || hop0.location.isNullOrBlank()) {
                return@runCatching null
            }
            val finalUrl = followRedirectChain(
                initialLocation = hop0.location,
                baseHostUrl = CAS_LOGIN_ENTRY_URL,
                cookieJar = cookieJar
            )
            val token = extractTokenFromUrl(finalUrl) ?: return@runCatching null
            val userInfo = validateTokenOnline(token) ?: return@runCatching null
            val latestTgc = cookieJar.findCookieValue(OfficialServiceRoutePolicy.MAPP_CAS_HOST, "CASTGC") ?: cleanTgc
            val latestSession = cookieJar.findCookieValue(OfficialServiceRoutePolicy.MAPP_CAS_HOST, "JSESSIONID") ?: casJsessionId
            syncCasCookiesToWebView(latestTgc, latestSession)
            LibrarySeatSessionSnapshot(
                rawToken = token,
                casTgcCookie = latestTgc,
                casJsessionId = latestSession,
                userInfo = userInfo,
                updatedAtEpochMs = System.currentTimeMillis(),
                refreshSource = LibrarySeatRefreshSource.PERSISTED_COOKIE
            )
        }.getOrNull()
    }

    /**
     * 使用加密数据库中的学号与密码执行后台 CAS 统一身份认证登录，获取并验证最新 `wslib` Token 与 `CASTGC` Cookie。
     */
    suspend fun loginWithCredentials(
        studentId: String,
        passwordPlain: String
    ): Result<LibrarySeatSessionSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanStudentId = studentId.trim()
            require(cleanStudentId.isNotBlank() && passwordPlain.isNotBlank()) { "学号或统一身份认证密码不能为空" }

            val cookieJar = IsolatedAuthCookieJar()
            val hop1 = executeHop(
                url = CAS_LOGIN_ENTRY_URL,
                method = "GET",
                cookieJar = cookieJar
            )

            // 若当前偶发已重定向带出票据，则直接跟随
            val finalRedirectUrl = if (hop1.statusCode in listOf(301, 302, 303, 307, 308) && !hop1.location.isNullOrBlank()) {
                followRedirectChain(
                    initialLocation = hop1.location,
                    baseHostUrl = CAS_LOGIN_ENTRY_URL,
                    cookieJar = cookieJar
                )
            } else {
                val lt = Regex("""name="lt"\s+value="([^"]+)"""").find(hop1.body)?.groupValues?.get(1)
                    ?: Regex("""value="([^"]+)"\s+name="lt"""").find(hop1.body)?.groupValues?.get(1)
                    ?: error("未能从统一身份认证页面提取登录令牌 (lt)")
                val execution = Regex("""name="execution"\s+value="([^"]+)"""").find(hop1.body)?.groupValues?.get(1)
                    ?: "e1s1"
                val formActionRaw = Regex("""<form[^>]*id="fm1"[^>]*action="([^"]+)"""").find(hop1.body)
                    ?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                val casPostUrl = if (!formActionRaw.isNullOrBlank()) {
                    resolveRedirectUrl(formActionRaw, CAS_LOGIN_ENTRY_URL)
                } else {
                    CAS_LOGIN_ENTRY_URL
                }
                val postUri = validateAuthUri(casPostUrl)
                check(
                    postUri.host == OfficialServiceRoutePolicy.MAPP_CAS_HOST &&
                        (postUri.path == "/cas/login" || postUri.path.startsWith("/cas/login;"))
                ) {
                    "统一身份认证登录地址校验未通过"
                }

                val passwordBytes = passwordPlain.toByteArray(StandardCharsets.UTF_8)
                val encodedPwd = try {
                    "{gilight}_" + Base64.getEncoder().encodeToString(passwordBytes)
                } finally {
                    passwordBytes.fill(0)
                }

                val formBody = buildFormBody(
                    "username" to cleanStudentId,
                    "password" to encodedPwd,
                    "lt" to lt,
                    "execution" to execution,
                    "_eventId" to "submit"
                )
                val hop2 = executeHop(
                    url = casPostUrl,
                    method = "POST",
                    cookieJar = cookieJar,
                    formBody = formBody
                )
                val nextLocation = hop2.location
                if (nextLocation.isNullOrBlank()) {
                    val needsCaptcha = hop2.body.contains("验证码") || hop2.body.contains("captcha", ignoreCase = true)
                    if (needsCaptcha) {
                        throw LibrarySeatAuthException(
                            message = "统一身份认证要求输入图形验证码，请在下方网页中手动完成一次验证，系统将自动持久化新 Cookie",
                            requiresManualVerification = true
                        )
                    }
                    throw LibrarySeatAuthException(
                        message = "统一身份认证未通过，请核对绑定的学号与密码是否正确",
                        requiresManualVerification = false
                    )
                }
                followRedirectChain(
                    initialLocation = nextLocation,
                    baseHostUrl = casPostUrl,
                    cookieJar = cookieJar
                )
            }

            val rawToken = extractTokenFromUrl(finalRedirectUrl)
                ?: throw LibrarySeatAuthException("未从图书馆空间预约回调中获取登录 Token，请稍后重试")
            val userInfo = validateTokenOnline(rawToken) ?: LibrarySeatUserInfo(
                userNo = cleanStudentId,
                userName = cleanStudentId,
                department = "",
                cardNo = "",
                breakNum = "0"
            )
            val casTgc = cookieJar.findCookieValue(OfficialServiceRoutePolicy.MAPP_CAS_HOST, "CASTGC")
            val casJsessionId = cookieJar.findCookieValue(OfficialServiceRoutePolicy.MAPP_CAS_HOST, "JSESSIONID")
            syncCasCookiesToWebView(casTgc, casJsessionId)

            LibrarySeatSessionSnapshot(
                rawToken = rawToken,
                casTgcCookie = casTgc,
                casJsessionId = casJsessionId,
                userInfo = userInfo,
                updatedAtEpochMs = System.currentTimeMillis(),
                refreshSource = LibrarySeatRefreshSource.BACKGROUND_PASSWORD_LOGIN
            )
        }.recoverCatching { error ->
            if (error is CancellationException) throw error
            if (error is LibrarySeatAuthException) throw error
            throw LibrarySeatAuthException(
                message = error.message?.takeIf { it.isNotBlank() } ?: "自动后台登录图书馆预约系统失败，请检查校园网络",
                requiresManualVerification = false
            )
        }
    }

    private fun followRedirectChain(
        initialLocation: String,
        baseHostUrl: String,
        cookieJar: IsolatedAuthCookieJar
    ): String {
        var nextLocation: String? = initialLocation
        var currentBaseUrl = baseHostUrl
        var hops = 0
        while (!nextLocation.isNullOrBlank() && hops < 12) {
            hops++
            val resolvedUrl = resolveRedirectUrl(nextLocation, currentBaseUrl)
            // 如果已经跳转到 wslib H5 首页并携带 token 参数，可直接提取而无需下载静态 HTML
            if (extractTokenFromUrl(resolvedUrl) != null) {
                return resolvedUrl
            }
            var hop = executeHop(
                url = resolvedUrl,
                method = "GET",
                cookieJar = cookieJar
            )
            currentBaseUrl = resolvedUrl
            if (hop.statusCode >= 500 &&
                hop.location.isNullOrBlank() &&
                resolvedUrl.contains("/stage-api/api/caslogin/ticketlogin")
            ) {
                val fallbackUrl = resolvedUrl.replace(
                    "/stage-api/api/caslogin/ticketlogin",
                    "/spacelink/api/caslogin/ticketlogin"
                )
                hop = executeHop(
                    url = fallbackUrl,
                    method = "GET",
                    cookieJar = cookieJar
                )
                currentBaseUrl = fallbackUrl
            }
            if (hop.statusCode !in listOf(301, 302, 303, 307, 308)) {
                return currentBaseUrl
            }
            nextLocation = hop.location
        }
        return currentBaseUrl
    }

    private data class HopResponse(
        val statusCode: Int,
        val location: String?,
        val body: String
    )

    private fun executeHop(
        url: String,
        method: String,
        cookieJar: IsolatedAuthCookieJar,
        formBody: String? = null
    ): HopResponse {
        val uri = validateAuthUri(url)
        val conn = (uri.toURL().openConnection() as HttpURLConnection)
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            cookieJar.headers(uri).forEach { (k, values) ->
                conn.setRequestProperty(k, values.joinToString("; "))
            }
            if (formBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                val bytes = formBody.toByteArray(StandardCharsets.UTF_8)
                try {
                    conn.outputStream.use { it.write(bytes) }
                } finally {
                    bytes.fill(0)
                }
            }
            val status = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }
            cookieJar.accept(uri, headers)
            val body = readBoundedBody(conn, status)
            return HopResponse(
                statusCode = status,
                location = conn.getHeaderField("Location"),
                body = body
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun readBoundedBody(conn: HttpURLConnection, statusCode: Int = 200): String {
        val stream = if (statusCode in 200..399) conn.inputStream else conn.errorStream
        return stream?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > 2 * 1024 * 1024) error("学校认证响应超出安全上限")
                out.write(buf, 0, n)
            }
            out.toString(StandardCharsets.UTF_8.name())
        }.orEmpty()
    }

    private fun validateAuthUri(rawUrl: String): URI {
        val trimmed = rawUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrElse {
            throw LibrarySeatAuthException("学校认证地址格式无效")
        }
        val host = uri.host?.lowercase() ?: throw LibrarySeatAuthException("学校认证地址缺少主机名")
        if (uri.scheme != "https" || host !in ALLOWED_AUTH_HOSTS || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) {
            throw LibrarySeatAuthException("学校认证跳转到了非受信地址 ($host)，已阻断请求")
        }
        return uri
    }

    private fun resolveRedirectUrl(rawLocation: String, baseHostUrl: String): String {
        val base = validateAuthUri(baseHostUrl)
        val trimmed = rawLocation.trim()
        val relative = URI(trimmed)
        val resolved = if (!relative.isAbsolute && relative.rawAuthority == null && relative.rawPath.isEmpty() && relative.rawQuery != null) {
            URI(baseHostUrl.substringBefore('?') + "?" + relative.rawQuery)
        } else {
            base.resolve(relative)
        }
        val upgraded = if (resolved.scheme == "http" && resolved.host?.lowercase() in ALLOWED_AUTH_HOSTS && resolved.port in setOf(-1, 80)) {
            buildString {
                append("https://")
                append(resolved.host.lowercase())
                if (!resolved.rawPath.isNullOrEmpty()) append(resolved.rawPath)
                if (resolved.rawQuery != null) {
                    append('?')
                    append(resolved.rawQuery)
                }
            }
        } else {
            resolved.toString()
        }
        return validateAuthUri(upgraded).toString()
    }

    private fun buildFormBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private class IsolatedAuthCookieJar {
        private val manager = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)

        fun seedCookie(uri: URI, name: String, value: String, path: String) {
            val cookie = HttpCookie(name, value).apply {
                domain = uri.host
                this.path = path
                secure = true
                isHttpOnly = true
            }
            manager.cookieStore.add(uri, cookie)
        }

        fun headers(uri: URI): Map<String, List<String>> = manager.get(uri, emptyMap())

        fun accept(uri: URI, responseHeaders: Map<String, List<String>>) {
            manager.put(uri, responseHeaders)
        }

        fun findCookieValue(host: String, cookieName: String): String? {
            return manager.cookieStore.cookies
                .lastOrNull { cookie ->
                    cookie.name.equals(cookieName, ignoreCase = true) &&
                        cookie.domain.orEmpty().trimStart('.').equals(host, ignoreCase = true)
                }
                ?.value
                ?.takeIf { it.isNotBlank() }
        }
    }
}

class LibrarySeatAuthException(
    override val message: String,
    val requiresManualVerification: Boolean = false
) : IllegalStateException(message)

class LibrarySeatSessionExpiredException : IllegalStateException("图书馆空间预约登录态已过期")
