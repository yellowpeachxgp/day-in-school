package cn.edu.haut.campus.core.protocol.zf

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.UserAction
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonParseException
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.protocol.AuthOutcome
import cn.edu.haut.campus.core.protocol.GatewayRequest
import cn.edu.haut.campus.core.protocol.GatewayResponse
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.HttpMethod
import cn.edu.haut.campus.core.protocol.RequestPurpose
import cn.edu.haut.campus.core.protocol.TransportFailure
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

/** 拼 URL 与表单编码；不引入任何第三方 Http 库。 */
internal object ZfUrls {

    fun absolute(origin: String, pathOrUrl: String): String {
        val value = pathOrUrl.trim()
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return origin.trimEnd('/') + "/" + value.trimStart('/')
    }

    /**
     * 追加查询参数（保持已有参数顺序）。返回值顺序稳定，
     * 因此夹具可以精确匹配请求地址。
     */
    fun withQuery(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val query = params.entries.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }
        val separator = if (url.contains('?')) "&" else "?"
        return url + separator + query
    }

    fun formEncode(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

/**
 * 只读请求的公共通道：统一处理状态码、登录页回跳与 JSON 解析，
 * 保证两个适配器对“会话过期”“限流”“页面结构变化”的判断完全一致。
 */
internal object ZfRead {

    /** 声明为非 UTF-8 时由适配器再做一次解码，这里只负责取原始响应。 */
    suspend fun get(
        session: GatewaySession,
        url: String,
        params: Map<String, String> = emptyMap()
    ): GatewayResponse {
        val target = ZfUrls.withQuery(url, params)
        return requireReadable(session.fetch(GatewayRequest(HttpMethod.GET, target)))
    }

    /**
     * 表单 POST。浏览器语义：登录/查询表单提交后遇到 301/302/303 一律降级为 GET，
     * 否则会把表单重新提交到服务地址（CAS 回跳、ASP.NET 登录跳转都依赖这一点）。
     */
    suspend fun post(session: GatewaySession, request: GatewayRequest): GatewayResponse {
        var current = request
        repeat(MAX_POST_HOPS) {
            val response = session.send(current)
            if (!response.isRedirect) return response
            val location = response.header("Location") ?: return response
            val next = session.urlPolicy.resolve(current.url, location, RequestPurpose.READ)
            current = current.copy(
                url = next,
                method = HttpMethod.GET,
                body = null,
                contentType = null,
                purpose = RequestPurpose.READ
            )
        }
        throw TransportFailure(AcademicError.PAGINATION_LOOP, "教务跳转次数过多，已停止请求")
    }

    fun requireReadable(response: GatewayResponse): GatewayResponse {
        when {
            response.status == 401 -> throw TransportFailure(AcademicError.SESSION_EXPIRED, "教务会话已失效，需要重新登录")
            response.status == 403 -> throw TransportFailure(AcademicError.ACCESS_DENIED, "当前账号没有该功能的访问权限")
            response.status == 429 -> throw TransportFailure(AcademicError.RATE_LIMITED, "教务系统限流，请稍后再试")
            !response.isSuccess -> throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "教务返回了异常状态：" + response.status)
            looksLikeLoginPage(response.body) -> throw TransportFailure(AcademicError.SESSION_EXPIRED, "教务把请求重定向到了登录页")
        }
        return response
    }

    /** 判定“这是登录页”：必须同时出现 password 字段与可解析的密码输入框。 */
    fun looksLikeLoginPage(body: String): Boolean {
        if (!body.contains("password", ignoreCase = true)) return false
        return ZfHtml.forms(body).any { it.hasPassword }
    }

    fun json(body: String): JsonValue = try {
        Json.parse(body)
    } catch (_: JsonParseException) {
        throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "教务返回的 JSON 无法解析")
    }

    private const val MAX_POST_HOPS = 5
}

/**
 * 登录结果判定（UNVERIFIED）。只看“去掉标签后的文本”：
 * 先认错误提示，再认验证码提示，最后才看是否仍停在登录页。
 * 返回的消息是固定文案，绝不回显服务端原文或凭据。
 */
internal object ZfAuth {

    private val REJECT_MARKERS = listOf(
        "密码错误", "用户名或密码", "账号或密码", "密码不正确", "用户不存在", "账号不存在", "账户已被锁定", "登录失败"
    )
    private val CAPTCHA_MARKERS = listOf("验证码")

    fun classify(response: GatewayResponse): AuthOutcome {
        if (response.status == 401 || response.status == 403) {
            return AuthOutcome.Rejected("教务系统拒绝了本次登录请求")
        }
        val text = ZfHtml.plainText(response.body)
        if (REJECT_MARKERS.any { text.contains(it) }) {
            return AuthOutcome.Rejected("账号或密码不正确")
        }
        if (CAPTCHA_MARKERS.any { text.contains(it) }) {
            return AuthOutcome.RequiresUserAction(UserAction.COMPLETE_CAPTCHA, "学校要求先完成验证码，再重试登录")
        }
        if (ZfRead.looksLikeLoginPage(response.body)) {
            return AuthOutcome.Rejected("账号或密码不正确")
        }
        if (!response.isSuccess) {
            return AuthOutcome.Rejected("教务系统返回了异常状态：" + response.status)
        }
        return AuthOutcome.Authenticated
    }
}

private val RETRYABLE_ERRORS = setOf(
    AcademicError.NETWORK_OFFLINE,
    AcademicError.TIMEOUT,
    AcademicError.RATE_LIMITED
)

/**
 * 把协议层的结构化失败折叠成 AcademicResult；不捕获编程错误，
 * 避免把适配器自身的 bug 伪装成“学校数据有问题”。
 */
internal suspend fun <T> zfGuarded(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
    block()
} catch (failure: TransportFailure) {
    AcademicResult.Failure(
        code = failure.error,
        retryable = failure.error in RETRYABLE_ERRORS,
        detail = failure.message
    )
}

/**
 * 证据等级固定为 MOCK_TESTED：本适配器只在合成夹具上验证过，
 * 不能对外声称已在任何真实学校可用。
 */
internal fun zfProvenance(
    session: GatewaySession,
    providerId: String,
    completeness: SnapshotCompleteness
): DataProvenance = DataProvenance(
    providerId = providerId,
    schoolId = SchoolId(session.schoolId),
    accountId = AccountId(session.accountId),
    sourceSystem = providerId,
    fetchedAt = Instant.now(),
    completeness = completeness,
    trust = VerificationLevel.MOCK_TESTED
)



internal fun zfEmptyToNull(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
