package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.UserAction
import java.nio.charset.StandardCharsets

/**
 * 短生命周期凭据：读取后立即复制，close() 清零底层数组；toString 永不包含秘密。
 * 协议实现不得把凭据写入任何日志、异常或会话对象。
 */
class ProtocolCredentials internal constructor(
    private val studentBytes: ByteArray,
    private val passwordBytes: ByteArray
) : AutoCloseable {
    private var closed = false

    val studentId: String get() = String(requireOpen(studentBytes), StandardCharsets.UTF_8)
    val password: String get() = String(requireOpen(passwordBytes), StandardCharsets.UTF_8)

    private fun requireOpen(bytes: ByteArray): ByteArray {
        check(!closed) { "凭据已释放" }
        return bytes
    }

    override fun close() {
        studentBytes.fill(0)
        passwordBytes.fill(0)
        closed = true
    }

    override fun toString(): String = "ProtocolCredentials(已隐藏)"

    companion object {
        fun of(studentId: String, password: String): ProtocolCredentials {
            require(studentId.isNotBlank() && password.isNotBlank()) { "学号与密码不能为空" }
            require(studentId.length <= 200 && password.length <= 200) { "凭据超出长度限制" }
            return ProtocolCredentials(
                studentId.toByteArray(StandardCharsets.UTF_8),
                password.toByteArray(StandardCharsets.UTF_8)
            )
        }
    }
}

/**
 * 一次账号会话的全部可变边界。Gateway 实现只能通过它发起请求，
 * 不允许自行创建 HttpClient、CookieManager 或读取全局状态。
 */
class GatewaySession(
    val schoolId: String,
    val accountId: String,
    val origin: String,
    val transport: GatewayTransport,
    val urlPolicy: GatewayUrlPolicy,
    val cookieJar: ProtocolCookieJar,
    val grantedCapabilities: Set<GatewayCapability> = GatewayCapability.readOnly()
) {
    init {
        require(schoolId.isNotBlank() && accountId.isNotBlank()) { "会话必须绑定学校与账号" }
        require(origin.startsWith("https://")) { "会话来源必须是 HTTPS" }
    }

    suspend fun send(request: GatewayRequest): GatewayResponse {
        if (request.purpose == RequestPurpose.WRITE && GatewayCapability.SELECTION_WRITE !in grantedCapabilities) {
            throw TransportFailure(AcademicError.PERMISSION_REVOKED, "当前账号未授权选课写入")
        }
        urlPolicy.validate(request.url, request.purpose)
        val merged = request.headers + cookieJar.asHeaderMap(request.url)
        val response = transport.execute(request.copy(headers = merged))
        val setCookies = response.headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .map { it.value }
        if (setCookies.isNotEmpty()) cookieJar.store(response.finalUrl.ifEmpty { request.url }, setCookies)
        return response
    }

    /**
     * 安全跳转：每一跳都经过 URL 策略校验与 Cookie 作用域检查，
     * 越权地址直接抛 UNTRUSTED_REDIRECT，绝不把凭据带到未声明来源。
     */
    suspend fun fetch(request: GatewayRequest, maxHops: Int = 6): GatewayResponse {
        var current = request
        repeat(maxHops) {
            val response = send(current)
            if (!response.isRedirect) return response
            val location = response.header("Location") ?: return response
            val nextUrl = urlPolicy.resolve(current.url, location, current.purpose)
            current = current.copy(
                url = nextUrl,
                method = if (response.status == 303) HttpMethod.GET else current.method
            )
        }
        throw TransportFailure(AcademicError.PAGINATION_LOOP, "学校跳转次数过多，已停止请求")
    }

    fun close() = cookieJar.clear()
}

sealed interface AuthOutcome {
    data object Authenticated : AuthOutcome
    data class RequiresUserAction(val action: UserAction, val message: String) : AuthOutcome
    data class Rejected(val message: String) : AuthOutcome
}

/** 九类协议的最小只读契约；写能力通过独立接口声明，不在默认实现中默认开启。 */
interface AcademicGateway {
    val descriptor: GatewayDescriptor

    suspend fun authenticate(session: GatewaySession, credentials: ProtocolCredentials?): AuthOutcome

    suspend fun terms(session: GatewaySession): AcademicResult<List<RemoteTerm>>

    suspend fun timetable(session: GatewaySession, term: RemoteTerm): AcademicResult<TimetableSnapshot>

    suspend fun grades(session: GatewaySession, term: RemoteTerm?): AcademicResult<GradeSnapshot>

    suspend fun exams(session: GatewaySession, term: RemoteTerm?): AcademicResult<ExamSnapshot>
}
