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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class LibrarySeatRefreshSource(val label: String) {
    CACHED_TOKEN("持久化会话直连"),
    PERSISTED_COOKIE("持久化 Cookie 自动续期"),
    BACKGROUND_PASSWORD_LOGIN("加密凭据自动后台重登"),
    WEBVIEW_CAPTURE("网页登录同步持久化")
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
    data class Refreshing(val hint: String = "正在校验持久会话并自动后台登录…") : LibrarySeatSessionState
    data class Authenticated(val snapshot: LibrarySeatSessionSnapshot) : LibrarySeatSessionState
    data class CredentialsRequired(val message: String = "绑定统一身份认证账号后，Cookie 失效将自动后台登录") : LibrarySeatSessionState
    data class AuthError(val message: String, val requiresManualVerification: Boolean = false) : LibrarySeatSessionState
}

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
            val rawToken = HautLibrarySeatAuthClient.normalizeRawToken(obj.optString("rawToken"))
            if (rawToken.isBlank()) return null
            val sourceName = obj.optString("refreshSource", LibrarySeatRefreshSource.CACHED_TOKEN.name)
            val source = runCatching { LibrarySeatRefreshSource.valueOf(sourceName) }
                .getOrDefault(LibrarySeatRefreshSource.CACHED_TOKEN)
            LibrarySeatSessionSnapshot(
                rawToken = rawToken,
                casTgcCookie = obj.optString("casTgcCookie").takeIf { it.isNotBlank() },
                casJsessionId = obj.optString("casJsessionId").takeIf { it.isNotBlank() },
                userInfo = LibrarySeatUserInfo(
                    userNo = obj.optString("userNo"),
                    userName = obj.optString("userName"),
                    department = obj.optString("department"),
                    cardNo = obj.optString("cardNo"),
                    breakNum = obj.optString("breakNum", "0")
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
        val payload = JSONObject()
            .put("rawToken", cleanToken)
            .put("casTgcCookie", snapshot.casTgcCookie.orEmpty())
            .put("casJsessionId", snapshot.casJsessionId.orEmpty())
            .put("userNo", snapshot.userInfo.userNo)
            .put("userName", snapshot.userInfo.userName)
            .put("department", snapshot.userInfo.department)
            .put("cardNo", snapshot.userInfo.cardNo)
            .put("breakNum", snapshot.userInfo.breakNum)
            .put("updatedAtEpochMs", snapshot.updatedAtEpochMs)
            .put("refreshSource", snapshot.refreshSource.name)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val encrypted = try {
            encryptBytes(payload)
        } catch (_: Exception) {
            resetCorruptedStorage()
            encryptBytes(payload)
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
    const val WSLIB_USER_INFO_URL = "https://wslib.haut.edu.cn/spacelink/api/h5user/my/userinfo"

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
        val userNo = data.optString("userNo").trim()
        val userName = data.optString("userName").trim()
        if (userNo.isEmpty() && userName.isEmpty()) return null
        return LibrarySeatUserInfo(
            userNo = userNo,
            userName = userName,
            department = data.optString("department").trim(),
            cardNo = data.optString("cardNo").trim(),
            breakNum = data.optString("breakNum", "0").trim()
        )
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
            val hop = executeHop(
                url = resolvedUrl,
                method = "GET",
                cookieJar = cookieJar
            )
            currentBaseUrl = resolvedUrl
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
