/*
 * Adapted in part from ExpressAssistant (https://github.com/Halo0sama/ExpressAssistant)
 * Commit: b892565502d381a58a5a2c57e6d9068e07648c7a
 * Licensed under the MIT License (see THIRD_PARTY_NOTICES.md).
 */
package cn.edu.haut.campus.core.campusservices

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class XiaomiMintResult(
    val token: String,
    val cUserId: String,
    val accountId: String
)

data class XiaomiAccountCredential(
    val id: String,
    val label: String,
    val token: String,
    val cUserId: String,
    val accountId: String,
    val oaid: String = "",
    val vaid: String = "",
    val phones: List<String> = emptyList(),
    val enabled: Boolean = true,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    val maskedPhonesSummary: String
        get() = if (phones.isEmpty()) {
            "暂未绑定收件手机号（请点击「管理/绑定手机号」添加）"
        } else {
            phones.joinToString("、") { XiaomiPassportClient.maskPhone(it) }
        }
}

data class XiaomiApiSimpleResult(
    val ok: Boolean,
    val code: Int,
    val message: String,
    val rawJson: String
)

data class XiaomiTrackPoint(
    val desc: String,
    val time: String
)

data class XiaomiExpressParcelDto(
    val mailNo: String,
    val cpCode: String,
    val companyName: String,
    val latestText: String,
    val latestTime: String,
    val stateName: String,
    val stateNum: Int,
    val lifecycleStatus: ParcelLifecycleStatus,
    val phone: String?,
    val provider: String,
    val queryChannel: String,
    val iconUrl: String,
    val pickupCode: String?,
    val stationName: String?,
    val checkpoints: List<XiaomiTrackPoint>
)

data class XiaomiExpressDetailDto(
    val mailNo: String,
    val companyName: String,
    val stateNum: Int,
    val isReceived: Boolean,
    val points: List<XiaomiTrackPoint>
)

data class XiaomiSyncSummary(
    val accountCount: Int,
    val phoneCount: Int,
    val fetchedParcelCount: Int,
    val readyPickupCount: Int,
    val message: String
)

object XiaomiPassportClient {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val PHONE_URL_PATTERN = Pattern.compile("[?&](_user|user)=1(\\d{10})")
    private val PHONE_11_PATTERN = Pattern.compile("1\\d{10}")

    fun maskPhone(phone: String): String {
        val clean = phone.trim()
        return if (clean.length == 11) {
            "${clean.substring(0, 3)}****${clean.substring(7)}"
        } else if (clean.length > 4) {
            "${clean.take(2)}***${clean.takeLast(2)}"
        } else {
            clean
        }
    }

    fun isValidMainlandPhone(phone: String): Boolean =
        PHONE_11_PATTERN.matcher(phone.trim()).matches()

    fun extractPhoneFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val m = PHONE_URL_PATTERN.matcher(url)
            if (m.find()) "1" + m.group(2) else null
        }.getOrNull()
    }

    fun parseCookies(cookieHeader: String?): Map<String, String> {
        if (cookieHeader.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in cookieHeader.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) {
                map[kv[0].trim()] = kv[1].trim()
            }
        }
        return map
    }

    internal fun requireAllowedXiaomiUrl(rawUrl: String): URL {
        val endpoint = OfficialServiceRoutePolicy.parseHttpsEndpoint(rawUrl)
            ?: throw SecurityException("拒绝非 HTTPS 或含非法端口的小米认证/服务地址")
        if (!OfficialServiceRoutePolicy.isAllowedXiaomiHost(endpoint.host)) {
            throw SecurityException("拒绝向未授权的小米域名发送凭据: ${endpoint.host}")
        }
        return URL(endpoint.urlText)
    }

    fun getLoginUrl(): String {
        val body = httpGet(
            "https://account.xiaomi.com/pass/serviceLogin?sid=assistant&_json=true",
            null
        )
        val idx = body.indexOf("{")
        check(idx >= 0) { "小米登录页初始化返回格式异常" }
        val json = body.substring(idx)
        val obj = JSONObject(json)
        val location = obj.optString("location")
        check(location.isNotEmpty()) { "未获取到小米登录跳转地址: $json" }
        requireAllowedXiaomiUrl(location)
        return location
    }

    fun mint(cookies: Map<String, String>, accountId: String): XiaomiMintResult {
        var lastError: Throwable? = null
        for (attempt in 0 until 2) {
            try {
                return mintOnce(cookies, accountId)
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("小米令牌签发失败")
    }

    private fun mintOnce(cookies: Map<String, String>, accountId: String): XiaomiMintResult {
        val passToken = cookies["passToken"] ?: throw IllegalStateException("缺少 passToken Cookie")
        val userId = cookies["userId"] ?: accountId.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("缺少 userId Cookie")
        val deviceId = cookies["deviceId"] ?: UUID.randomUUID().toString().replace("-", "").take(16)

        val loginUrl = "https://account.xiaomi.com/pass/serviceLogin" +
            "?sid=assistant&_json=true&_appName=com.miui.personalassistant&_locale=zh_CN"
        val cookie = "userId=${urlEncode(userId)}; passToken=${urlEncode(passToken)}; deviceId=${urlEncode(deviceId)}"
        val loginBody = httpGet(loginUrl, cookie)
        val idx = loginBody.indexOf("{")
        check(idx >= 0) { "小米 serviceLogin 响应异常" }
        val json = loginBody.substring(idx)
        val obj = JSONObject(json)
        if (obj.optInt("code") != 0) {
            throw IllegalStateException("小米登录状态校验失败: ${obj.optString("desc")}")
        }
        val ssecurity = obj.getString("ssecurity")
        val nonce = obj.getLong("nonce")
        val location = obj.getString("location")
        val cUserId = obj.optString("cUserId")

        val clientSign = sha1Base64("nonce=$nonce&$ssecurity")
        val sep = if (location.contains("?")) "&" else "?"
        val stsUrl = location + sep + "clientSign=" + urlEncode(clientSign) + "&_userIdNeedEncrypt=true"
        requireAllowedXiaomiUrl(stsUrl)

        val (headers, stsBody) = httpGetWithHeaders(stsUrl, cookie)
        val token = extractCookie(headers, "serviceToken")
            ?: extractCookie(headers, "assistant_serviceToken")
        if (token.isNullOrBlank()) {
            val stsJson = runCatching { stsBody.substring(stsBody.indexOf("{")) }.getOrDefault("")
            if (stsJson.contains("\"S\":\"OK\"") && (stsJson.contains("\"R\":\"\"") || !stsJson.contains("\"R\":"))) {
                throw IllegalStateException("小米返回空令牌（可能触发安全验证或登录态过期），请重新登录")
            }
            throw IllegalStateException("未从小米 STS 响应获取到 serviceToken，请重试")
        }
        return XiaomiMintResult(token = token, cUserId = cUserId, accountId = userId)
    }

    fun autoDetectPhones(
        phoneFromUrl: String?,
        lastUrl: String?,
        passToken: String?,
        userId: String?,
        cUserId: String?
    ): List<String> {
        val out = ArrayList<String>()
        if (!phoneFromUrl.isNullOrBlank() && isValidMainlandPhone(phoneFromUrl)) {
            out.add(phoneFromUrl.trim())
        } else {
            extractPhoneFromUrl(lastUrl)?.let { out.add(it) }
        }
        if (out.isEmpty() && (!userId.isNullOrBlank() || !passToken.isNullOrBlank())) {
            val cookie = buildString {
                if (!userId.isNullOrBlank()) append("userId=").append(userId).append("; ")
                if (!passToken.isNullOrBlank()) append("passToken=").append(passToken).append("; ")
                if (!cUserId.isNullOrBlank()) append("cUserId=").append(cUserId)
            }
            val urls = listOf(
                "https://account.xiaomi.com/fe/service/account/profile",
                "https://account.xiaomi.com/fe/api/account/profile",
                "https://account.xiaomi.com/user/profile",
                "https://api.account.xiaomi.com/user/profile"
            )
            for (u in urls) {
                try {
                    val conn = (requireAllowedXiaomiUrl(u).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 6000
                        readTimeout = 6000
                        setRequestProperty("Cookie", cookie)
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    }
                    val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    val m = PHONE_11_PATTERN.matcher(body)
                    if (m.find()) {
                        out.add(m.group())
                        break
                    }
                } catch (_: Throwable) {
                }
            }
        }
        return out.distinct()
    }

    private fun httpGet(url: String, cookie: String?): String {
        val conn = (requireAllowedXiaomiUrl(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", DESKTOP_UA)
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return "HTTP $code\n$text"
    }

    private fun httpGetWithHeaders(url: String, cookie: String?): Pair<Map<String, String>, String> {
        val conn = (requireAllowedXiaomiUrl(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", DESKTOP_UA)
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        val code = conn.responseCode
        val headers = HashMap<String, String>()
        for ((k, v) in conn.headerFields) {
            if (k != null && v != null) {
                headers[k] = v.joinToString("\n")
            }
        }
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return headers to text
    }

    private fun extractCookie(headers: Map<String, String>, name: String): String? {
        for ((k, v) in headers) {
            if (!k.equals("Set-Cookie", ignoreCase = true)) continue
            for (line in v.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.startsWith("$name=", ignoreCase = true)) {
                    return trimmed.substring(name.length + 1).substringBefore(";")
                }
            }
        }
        return null
    }

    private fun sha1Base64(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

object MiuiExpressCrypto {
    private const val KEY = "d101b17c77ff93cs"
    private const val MIUI_CBC_IV = "0102030405060708"

    @Volatile
    var lastError: String = ""
        private set

    fun encode(plain: String): String {
        return try {
            reflect("base64AesEncode", plain, KEY)
        } catch (t: Throwable) {
            lastError = "encode-reflect: ${t.javaClass.simpleName}: ${t.message}"
            aesCbcBase64(plain)
        }
    }

    fun decode(cipherText: String): String {
        return try {
            reflect("base6AesDecode", cipherText, KEY)
        } catch (t: Throwable) {
            lastError = "decode-reflect: ${t.javaClass.simpleName}: ${t.message}"
            runCatching { aesCbcDecode(cipherText) }.getOrElse { aesEcbDecode(cipherText) }
        }
    }

    private fun reflect(method: String, arg1: String, arg2: String): String {
        val cls = loadCoderUtilsClass()
        val m = cls.getMethod(method, String::class.java, String::class.java)
        return m.invoke(null, arg1, arg2) as? String ?: ""
    }

    private fun loadCoderUtilsClass(): Class<*> {
        runCatching { return Class.forName("miui.util.CoderUtils") }
        val jarCandidates = listOf(
            "/system/framework/miui.jar",
            "/system_ext/framework/miui.jar",
            "/product/framework/miui.jar"
        )
        for (jarPath in jarCandidates) {
            runCatching {
                val file = java.io.File(jarPath)
                if (file.exists()) {
                    val loader = dalvik.system.PathClassLoader(jarPath, ClassLoader.getSystemClassLoader())
                    return loader.loadClass("miui.util.CoderUtils")
                }
            }
        }
        throw ClassNotFoundException("miui.util.CoderUtils")
    }

    /**
     * URL query 参数 yellowpage_encparam 专用：AES/ECB/PKCS5Padding
     */
    fun aesBase64(plain: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(StandardCharsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    /**
     * 请求体与响应 data 字段（与 miui.util.CoderUtils.base64AesEncode / base6AesDecode 一致）：
     * AES/CBC/PKCS5Padding，IV = "0102030405060708"
     */
    fun aesCbcBase64(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(MIUI_CBC_IV.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    fun aesCbcDecode(cipherText: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(KEY.toByteArray(StandardCharsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(MIUI_CBC_IV.toByteArray(StandardCharsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val clean = cipherText.replace("\n", "").replace("\r", "").trim()
        return String(cipher.doFinal(Base64.getDecoder().decode(clean)), StandardCharsets.UTF_8)
    }

    private fun aesEcbDecode(cipherText: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(StandardCharsets.UTF_8), "AES"))
        val clean = cipherText.replace("\n", "").replace("\r", "").trim()
        return String(cipher.doFinal(Base64.getDecoder().decode(clean)), StandardCharsets.UTF_8)
    }
}

object XiaomiExpressApi {
    private const val HOST = "https://verca.xpa.assistant.miui.com"
    private const val GET_LIST_PATH = "/cpa/express/v2/getList"
    private const val QUERY_DETAIL_PATH = "/cpa/express/v2/query"
    private const val SIGN_SALT = "77eb2e8a5755abd016c0d69ba74b219c"

    fun sendVerificationCode(
        context: Context,
        cred: XiaomiAccountCredential,
        phone: String
    ): XiaomiApiSimpleResult {
        val cleanPhone = phone.trim()
        require(XiaomiPassportClient.isValidMainlandPhone(cleanPhone)) {
            "请输入有效的 11 位中国大陆手机号"
        }
        val body = """{"info":{"phone":${json(cleanPhone)}}}"""
        val raw = post(
            context = context,
            path = "/cpa/express/phone/sendVerificationCode",
            cred = cred,
            body = body
        )
        return parseSimpleResult(raw)
    }

    fun checkVerificationCode(
        context: Context,
        cred: XiaomiAccountCredential,
        phone: String,
        code: String
    ): XiaomiApiSimpleResult {
        val cleanPhone = phone.trim()
        val cleanCode = code.trim()
        require(XiaomiPassportClient.isValidMainlandPhone(cleanPhone)) { "手机号格式不正确" }
        require(cleanCode.isNotEmpty()) { "请输入短信验证码" }
        val body = """{"info":{"phone":${json(cleanPhone)},"verificationCode":${json(cleanCode)}}}"""
        val raw = post(
            context = context,
            path = "/cpa/express/phone/checkVerificationCode",
            cred = cred,
            body = body
        )
        return parseSimpleResult(raw)
    }

    fun bindPhone(
        context: Context,
        cred: XiaomiAccountCredential,
        phone: String,
        phones: List<String>,
        bind: Boolean
    ): XiaomiApiSimpleResult {
        val type = if (bind) 1 else 2
        val body =
            """{"cardId":3,"serviceKey":"express","info":{"type":$type,"phone":${json(phone.trim())},"phoneList":${toJsonArray(phones)}}}"""
        val raw = post(
            context = context,
            path = "/cpa/express/phone/bind",
            cred = cred,
            body = body
        )
        return parseSimpleResult(raw)
    }

    fun fetchExpressList(
        context: Context,
        cred: XiaomiAccountCredential,
        limit: Int = 29
    ): List<XiaomiExpressParcelDto> {
        val effectiveCUserId = cred.cUserId.ifBlank { cred.accountId }
        require(cred.token.isNotBlank() && effectiveCUserId.isNotBlank()) {
            "小米登录令牌不完整，请重新登录小米账号"
        }
        if (cred.phones.isEmpty()) {
            return emptyList()
        }
        val body =
            """{"info":{"limit":$limit,"phones":${toJsonArray(cred.phones)},"deletedMailNos":[],"modifiedMailNos":[]}}"""
        val raw = post(
            context = context,
            path = GET_LIST_PATH,
            cred = cred,
            body = body
        )
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) {
            throw IllegalStateException("小米智能助理返回错误 (${root.optInt("code")}): ${root.optCleanString("message", "请求被拒绝")}")
        }
        val dataObj = root.optJSONObject("data") ?: run {
            val dataStr = root.optCleanString("data")
            if (dataStr.isBlank()) return emptyList()
            JSONObject(dataStr)
        }
        val list = dataObj.optJSONArray("expressList") ?: JSONArray()
        val out = ArrayList<XiaomiExpressParcelDto>(list.length())
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            val mailNo = e.optCleanString("mailNo")
            if (mailNo.isEmpty()) continue
            val details = e.optJSONArray("details")
            val checkpoints = ArrayList<XiaomiTrackPoint>()
            val allTextBuilder = StringBuilder()
            if (details != null) {
                for (j in 0 until details.length()) {
                    val d = details.getJSONObject(j)
                    val desc = d.optCleanString("desc")
                    val time = d.optCleanString("time")
                    if (desc.isNotEmpty()) {
                        checkpoints.add(XiaomiTrackPoint(desc = desc, time = time))
                        allTextBuilder.append(desc).append(' ')
                    }
                }
            }
            val latest = checkpoints.lastOrNull()
            val rawStateName = e.optCleanString("state")
            val stateNum = e.optInt("stateNum")
            val latestText = latest?.desc?.takeIf { it.isNotBlank() } ?: rawStateName.ifBlank { "包裹运输中" }
            val latestTime = latest?.time.orEmpty()
            val extractedPickup = ParcelHubEngine.resolvePickupCodeFromText("$latestText $allTextBuilder")
            val lifecycleStatus = mapXiaomiStateToLifecycle(
                stateName = rawStateName,
                stateNum = stateNum,
                latestText = latestText,
                hasPickupCode = extractedPickup != null
            )
            val stateName = rawStateName.ifBlank {
                when (lifecycleStatus) {
                    ParcelLifecycleStatus.PICKED_UP -> "已签收"
                    ParcelLifecycleStatus.READY_FOR_PICKUP -> "待取件"
                    ParcelLifecycleStatus.OUT_FOR_DELIVERY -> "派送中"
                    else -> "运输中"
                }
            }
            out.add(
                XiaomiExpressParcelDto(
                    mailNo = mailNo,
                    cpCode = e.optCleanString("cpCode", "XIAOMI"),
                    companyName = e.optCleanString("name", "校园快递"),
                    latestText = latestText,
                    latestTime = latestTime,
                    stateName = stateName,
                    stateNum = stateNum,
                    lifecycleStatus = lifecycleStatus,
                    phone = e.optNullableCleanString("phone"),
                    provider = e.optCleanString("provider"),
                    queryChannel = e.optCleanString("queryChannel"),
                    iconUrl = e.optCleanString("iconUrl"),
                    pickupCode = extractedPickup?.pickupCode?.cleanNullableLiteral(),
                    stationName = extractedPickup?.stationName?.cleanNullableLiteral(),
                    checkpoints = checkpoints.asReversed()
                )
            )
        }
        return out
    }

    fun fetchExpressDetail(
        context: Context,
        cred: XiaomiAccountCredential,
        mailNo: String,
        cpCode: String?,
        companyName: String?,
        provider: String?,
        stateNum: Int,
        latestTime: String?,
        phone: String?,
        queryChannel: String?
    ): XiaomiExpressDetailDto {
        val sb = StringBuilder(
            """{"info":{"phones":${toJsonArray(cred.phones)},"cpCode":${json(cpCode)},"mailNo":${json(mailNo)},"name":${json(companyName)},"provider":${json(provider)},"stateNum":$stateNum,"logisticsUpdateTime":${json(toEpochMillisString(latestTime))}"""
        )
        if (!phone.isNullOrBlank()) sb.append(""","phone":${json(phone)}""")
        if (!queryChannel.isNullOrBlank()) sb.append(""","queryChannel":${json(queryChannel)}""")
        sb.append(""","channel":"1"}}""")
        val raw = post(
            context = context,
            path = QUERY_DETAIL_PATH,
            cred = cred,
            body = sb.toString()
        )
        val root = JSONObject(raw)
        if (root.optInt("code") != 0) {
            throw IllegalStateException("小米物流详情查询失败: ${root.optCleanString("message", "服务暂时不可用")}")
        }
        val data = JSONObject(root.optString("data"))
        val details = data.optJSONArray("details") ?: JSONArray()
        val points = ArrayList<XiaomiTrackPoint>(details.length())
        for (i in 0 until details.length()) {
            val d = details.getJSONObject(i)
            val desc = d.optCleanString("desc")
            val time = d.optCleanString("time")
            if (desc.isNotEmpty()) {
                points.add(XiaomiTrackPoint(desc = desc, time = time))
            }
        }
        val newStateNum = data.optInt("stateNum", stateNum)
        val isReceived = newStateNum == 107 || data.optCleanString("state").contains("签收")
        return XiaomiExpressDetailDto(
            mailNo = mailNo,
            companyName = companyName.cleanNullLiteral("校园快递"),
            stateNum = newStateNum,
            isReceived = isReceived,
            points = points
        )
    }

    private fun mapXiaomiStateToLifecycle(
        stateName: String,
        stateNum: Int,
        latestText: String,
        hasPickupCode: Boolean
    ): ParcelLifecycleStatus {
        if (stateNum == 107 || stateName.contains("签收") || stateName.contains("已取") || latestText.contains("已签收")) {
            return ParcelLifecycleStatus.PICKED_UP
        }
        if (hasPickupCode || stateName.contains("待取") || latestText.contains("凭取件码") || latestText.contains("到达驿站") || latestText.contains("已入柜")) {
            return ParcelLifecycleStatus.READY_FOR_PICKUP
        }
        if (stateNum == 105 || stateNum == 106 || stateName.contains("派送") || stateName.contains("配送")) {
            return ParcelLifecycleStatus.OUT_FOR_DELIVERY
        }
        if (stateNum == 103 || stateName.contains("揽收") || stateName.contains("揽件")) {
            return ParcelLifecycleStatus.CREATED
        }
        return ParcelLifecycleStatus.IN_TRANSIT
    }

    private fun parseSimpleResult(raw: String): XiaomiApiSimpleResult {
        return runCatching {
            val obj = JSONObject(raw)
            val code = obj.optInt("code", -1)
            val msg = obj.optString("message").ifBlank {
                if (code == 0) "操作成功" else "请求失败 (code=$code)"
            }
            XiaomiApiSimpleResult(ok = code == 0, code = code, message = msg, rawJson = raw)
        }.getOrElse {
            XiaomiApiSimpleResult(ok = false, code = -1, message = "解析响应失败: ${it.message}", rawJson = raw)
        }
    }

    private fun post(
        context: Context,
        path: String,
        cred: XiaomiAccountCredential,
        body: String,
        decrypt: Boolean = true
    ): String {
        val enc = MiuiExpressCrypto.aesBase64(path)
        val sign = sha1Hex("yellowpage_encparam$enc$SIGN_SALT").uppercase(Locale.US)
        val version = paVersionCode(context)
        val url =
            "$HOST$path?version=$version&appkey=yellowpage&yellowpage_encparam=${urlEncode(enc)}&sign=$sign&_encparam=${urlEncode(enc)}"
        val effectiveUserId = cred.accountId.ifBlank { cred.cUserId }
        val effectiveCUserId = cred.cUserId.ifBlank { cred.accountId }
        val wrapped = wrapBody(
            context = context,
            plain = body,
            userId = effectiveUserId,
            oaidIn = cred.oaid,
            vaidIn = cred.vaid
        )
        val encryptedBody = MiuiExpressCrypto.encode(wrapped)
        val conn = (XiaomiPassportClient.requireAllowedXiaomiUrl(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Cookie", "serviceToken=${cred.token.trim()}; cUserId=${effectiveCUserId.trim()}")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { out ->
            out.write(encryptedBody.toByteArray(StandardCharsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("小米快递服务 HTTP $code: ${text.take(180)}")
        }
        return if (decrypt) decryptResponse(text) else text
    }

    private fun decryptResponse(text: String): String {
        return try {
            val root = JSONObject(text)
            val data = root.optString("data")
            if (data.isNotEmpty() && data != "null") {
                root.put("data", MiuiExpressCrypto.decode(data))
                root.toString()
            } else {
                text
            }
        } catch (_: Throwable) {
            text
        }
    }

    internal fun wrapBody(
        context: Context?,
        plain: String,
        userId: String,
        oaidIn: String?,
        vaidIn: String?
    ): String {
        return try {
            val original = JSONObject(plain)
            val effectiveUserId = userId.cleanNullLiteral().ifBlank { "100000000" }
            val oaidV = resolveOaid(context, effectiveUserId, oaidIn)
            val vaidV = resolveVaid(context, effectiveUserId, vaidIn)
            val userSignal = JSONObject()
                .put("oaid", oaidV)
                .put("vaid", vaidV)
                .put("userId", effectiveUserId)
            val env = JSONObject()
                .put("terminal", "phone")
                .put("assistantVersion", paVersionCode(context))
                .put("launchVersion", pkgVersionCode(context, "com.miui.home"))
                .put("androidVersion", Build.VERSION.RELEASE ?: "14")
                .put("androidSdkVersion", Build.VERSION.SDK_INT.takeIf { it > 0 } ?: 34)
                .put("miuiType", miuiType())
                .put("phoneModel", Build.MODEL?.takeIf { it.isNotBlank() } ?: "Xiaomi")
                .put("phoneDevice", Build.DEVICE?.takeIf { it.isNotBlank() } ?: "xiaomi")
                .put("connectionType", "WIFI")
                .put("miuiVersionName", systemProp("ro.miui.ui.version.name") ?: "V8")
                .put("miuiVersion", systemProp("ro.miui.ui.version.code") ?: "")
                .put("osVersionName", systemProp("ro.mi.os.version.name") ?: "-1.0")
                .put("osVersion", systemProp("ro.mi.os.version.code") ?: "")
                .put("miSettingsVersionName", pkgVersionName(context, "com.xiaomi.misettings") ?: "")
                .put("miSettingsVersionCode", pkgVersionCode(context, "com.xiaomi.misettings"))
                .put("timeZone", TimeZone.getDefault().getDisplayName(false, 0))
                .put("language", Locale.getDefault().language.ifBlank { "zh" })
                .put("country", Locale.getDefault().country.ifBlank { "CN" })
                .put("screen", 1)
                .put("coordinate", "")
                .put("reqId", UUID.randomUUID().toString().replace("-", ""))
                .put("ip", localIp())
                .put("cpuArchitecture", Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a")
                .put("marketVersion", pkgVersionCode(context, "com.xiaomi.market"))
                .put("os", Build.VERSION.INCREMENTAL ?: "")
                .put("clientFlag", 0)
                .put("hwVersion", systemProp("ro.boot.hwversion") ?: "")
            val time = JSONObject()
                .put("clientTs", System.currentTimeMillis().toString())
                .put("timeZone", TimeZone.getDefault().getDisplayName(false, 0))
            JSONObject()
                .put("userSignal", userSignal)
                .put("environmentSignal", env)
                .put("timeSignal", time)
                .put("eventSignal", original)
                .toString()
        } catch (_: Throwable) {
            plain
        }
    }

    internal fun isValidDeviceSignalId(value: String?): Boolean {
        val clean = value.cleanNullLiteral()
        if (clean.length < 8) return false
        if (clean.equals("unknown", ignoreCase = true)) return false
        return clean.any { it != '0' && it != '-' }
    }

    fun resolveOaid(context: Context?, userId: String = "", candidate: String? = null): String {
        return resolveDeviceSignalId(
            context = context,
            method = "getOAID",
            kind = "oaid",
            userId = userId,
            candidate = candidate
        )
    }

    fun resolveVaid(context: Context?, userId: String = "", candidate: String? = null): String {
        return resolveDeviceSignalId(
            context = context,
            method = "getVAID",
            kind = "vaid",
            userId = userId,
            candidate = candidate
        )
    }

    private fun resolveDeviceSignalId(
        context: Context?,
        method: String,
        kind: String,
        userId: String,
        candidate: String?
    ): String {
        if (isValidDeviceSignalId(candidate)) {
            return candidate!!.trim()
        }
        val probed = probeIdentifier(context, method)
        if (isValidDeviceSignalId(probed)) {
            return probed.trim()
        }
        val androidId = runCatching {
            if (context != null) {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
            } else {
                null
            }
        }.getOrNull().cleanNullLiteral()
        val hwSeed = "${Build.BRAND.orEmpty()}|${Build.MODEL.orEmpty()}|${Build.DEVICE.orEmpty()}"
        val seed = "haut_xiaomi_${kind}_v1|${androidId.ifBlank { hwSeed }}|${userId.cleanNullLiteral()}"
        val derived = sha256Hex(seed).take(16)
        return if (isValidDeviceSignalId(derived)) derived else "8f3d9a2c4b1e7056"
    }

    private fun miuiType(): String {
        return try {
            val cls = Class.forName("miui.os.Build")
            val isAlpha = cls.getField("IS_ALPHA_BUILD").getBoolean(null)
            val isDev = cls.getField("IS_DEVELOPMENT_VERSION").getBoolean(null)
            val isStable = cls.getField("IS_STABLE_VERSION").getBoolean(null)
            when {
                isAlpha -> "alpha"
                isDev -> "dev"
                isStable -> "stable"
                else -> "custom"
            }
        } catch (_: Throwable) {
            "stable"
        }
    }

    private fun systemProp(name: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            (cls.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun pkgVersionName(context: Context?, pkg: String): String? {
        if (context == null) return null
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionName
        } catch (_: Throwable) {
            null
        }
    }

    private fun pkgVersionCode(context: Context?, pkg: String): Int {
        if (context == null) return 0
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0).versionCode
        } catch (_: Throwable) {
            0
        }
    }

    fun probeIdentifier(context: Context?, method: String): String {
        if (context == null) return ""
        return runCatching {
            val cls = Class.forName("com.android.id.impl.IdProviderImpl")
            val inst = cls.getDeclaredConstructor().newInstance()
            val m = cls.getMethod(method, Context::class.java)
            (m.invoke(inst, context) as? String).cleanNullLiteral()
        }.getOrDefault("")
    }

    private fun paVersionCode(context: Context?): Int {
        if (context == null) return 25000031
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("com.miui.personalassistant", 0).versionCode
        } catch (_: Throwable) {
            25000031
        }
    }

    private fun localIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (n in interfaces) {
                for (a in n.inetAddresses) {
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: ""
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun toEpochMillisString(time: String?): String {
        if (time.isNullOrBlank()) return System.currentTimeMillis().toString()
        return try {
            val dt = LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString()
        } catch (_: Throwable) {
            time
        }
    }

    private fun toJsonArray(list: List<String>): String =
        list.joinToString(prefix = "[", postfix = "]") { json(it) }

    private fun json(value: String?): String {
        if (value == null) return "null"
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun sha1Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * 基于 Android Keystore AES-256-GCM 的小米智能助理多账号与绑定手机号安全密库。
 */
class XiaomiAccountSecureVault(
    context: Context,
    scopeId: String = "default"
) {
    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFS_PREFIX = "haut_xiaomi_express_vault_"
        private const val KEY_ALIAS_PREFIX = "cn.edu.haut.campus.xiaomi.express.v1."
        private const val KEY_CIPHERTEXT = "encrypted_accounts_json_v1"
    }

    private val lock = Any()
    private val appContext = context.applicationContext
    private val scopeHash = MessageDigest.getInstance("SHA-256")
        .digest(scopeId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
    private val keyAlias = KEY_ALIAS_PREFIX + scopeHash
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_PREFIX + scopeHash, Context.MODE_PRIVATE)

    fun readAccounts(): List<XiaomiAccountCredential> = synchronized(lock) {
        val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return emptyList()
        return try {
            val raw = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            if (raw.size <= GCM_IV_LENGTH_BYTES + 16) return emptyList()
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = raw.copyOfRange(GCM_IV_LENGTH_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plain = cipher.doFinal(cipherBytes).toString(StandardCharsets.UTF_8)
            decodeAccountsJson(plain)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveAccounts(accounts: List<XiaomiAccountCredential>) = synchronized(lock) {
        val json = encodeAccountsJson(accounts)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(json.toByteArray(StandardCharsets.UTF_8))
        val encoded = android.util.Base64.encodeToString(iv + cipherBytes, android.util.Base64.NO_WRAP)
        prefs.edit().putString(KEY_CIPHERTEXT, encoded).commit()
    }

    fun upsertAccount(cred: XiaomiAccountCredential): List<XiaomiAccountCredential> = synchronized(lock) {
        val current = readAccounts().toMutableList()
        val effectiveUserId = cred.accountId.ifBlank { cred.cUserId }
        val normalizedCred = cred.copy(
            oaid = XiaomiExpressApi.resolveOaid(appContext, effectiveUserId, cred.oaid),
            vaid = XiaomiExpressApi.resolveVaid(appContext, effectiveUserId, cred.vaid)
        )
        val idx = current.indexOfFirst {
            it.id == normalizedCred.id || (it.accountId.isNotBlank() && it.accountId == normalizedCred.accountId)
        }
        if (idx >= 0) {
            val old = current[idx]
            val mergedPhones = (old.phones + normalizedCred.phones).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            current[idx] = normalizedCred.copy(
                id = old.id,
                phones = mergedPhones,
                label = buildAccountLabel(normalizedCred.accountId, mergedPhones)
            )
        } else {
            val cleanPhones = normalizedCred.phones.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            current.add(
                normalizedCred.copy(
                    phones = cleanPhones,
                    label = buildAccountLabel(normalizedCred.accountId, cleanPhones)
                )
            )
        }
        saveAccounts(current)
        current
    }

    fun updateAccountPhones(accountIdOrId: String, phones: List<String>): XiaomiAccountCredential? = synchronized(lock) {
        val current = readAccounts().toMutableList()
        val idx = current.indexOfFirst { it.id == accountIdOrId || it.accountId == accountIdOrId }
        if (idx < 0) return null
        val cleanPhones = phones.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val updated = current[idx].copy(
            phones = cleanPhones,
            label = buildAccountLabel(current[idx].accountId, cleanPhones),
            updatedAtEpochMillis = System.currentTimeMillis()
        )
        current[idx] = updated
        saveAccounts(current)
        updated
    }

    fun removeAccount(accountIdOrId: String): List<XiaomiAccountCredential> = synchronized(lock) {
        val next = readAccounts().filterNot { it.id == accountIdOrId || it.accountId == accountIdOrId }
        saveAccounts(next)
        next
    }

    private fun buildAccountLabel(accountId: String, phones: List<String>): String {
        val firstPhone = phones.firstOrNull()
        return if (!firstPhone.isNullOrBlank()) {
            "小米账号 (${XiaomiPassportClient.maskPhone(firstPhone)})"
        } else {
            "小米账号 (ID: ${accountId.take(4)}***${accountId.takeLast(2)})"
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = runCatching { store.getKey(keyAlias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        kg.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encodeAccountsJson(list: List<XiaomiAccountCredential>): String {
        val arr = JSONArray()
        for (a in list) {
            val phonesArr = JSONArray()
            a.phones.forEach { phonesArr.put(it) }
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("token", a.token)
                    .put("cUserId", a.cUserId)
                    .put("accountId", a.accountId)
                    .put("oaid", a.oaid)
                    .put("vaid", a.vaid)
                    .put("phones", phonesArr)
                    .put("enabled", a.enabled)
                    .put("updatedAtEpochMillis", a.updatedAtEpochMillis)
            )
        }
        return arr.toString()
    }

    private fun decodeAccountsJson(raw: String): List<XiaomiAccountCredential> {
        val arr = JSONArray(raw)
        val out = ArrayList<XiaomiAccountCredential>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pArr = o.optJSONArray("phones") ?: JSONArray()
            val phones = ArrayList<String>(pArr.length())
            for (j in 0 until pArr.length()) {
                val p = pArr.optString(j).trim()
                if (p.isNotEmpty()) phones.add(p)
            }
            val id = o.optCleanString("id")
            val cUserId = o.optCleanString("cUserId")
            val accountId = o.optCleanString("accountId").ifBlank { cUserId }
            val effectiveUserId = accountId.ifBlank { cUserId }.ifBlank { id }
            out.add(
                XiaomiAccountCredential(
                    id = id,
                    label = o.optCleanString("label", "小米账号"),
                    token = o.optCleanString("token"),
                    cUserId = cUserId.ifBlank { accountId },
                    accountId = accountId,
                    oaid = XiaomiExpressApi.resolveOaid(appContext, effectiveUserId, o.optCleanString("oaid")),
                    vaid = XiaomiExpressApi.resolveVaid(appContext, effectiveUserId, o.optCleanString("vaid")),
                    phones = phones,
                    enabled = o.optBoolean("enabled", true),
                    updatedAtEpochMillis = o.optLong("updatedAtEpochMillis", System.currentTimeMillis())
                )
            )
        }
        return out
    }
}
