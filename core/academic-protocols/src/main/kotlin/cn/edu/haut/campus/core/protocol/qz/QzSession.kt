package cn.edu.haut.campus.core.protocol.qz

import cn.edu.haut.campus.core.model.AcademicError
import cn.edu.haut.campus.core.model.AcademicResult
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.protocol.GatewayResponse
import cn.edu.haut.campus.core.protocol.GatewaySession
import cn.edu.haut.campus.core.protocol.TransportFailure

/**
 * 强智两代适配器共用的小工具：URL 拼装、来源溯源、可读性判定与统一错误映射。
 * 这里不做任何重试、缓存或网络直连 —— 一切请求都必须经 GatewaySession。
 */
internal object QzSession {

    fun url(session: GatewaySession, path: String): String = session.origin.trimEnd('/') + path

    fun withQuery(session: GatewaySession, path: String, query: String): String {
        val base = url(session, path)
        return if (query.isEmpty()) base else base + "?" + query
    }

    fun provenance(providerId: String, sourceSystem: String, session: GatewaySession): DataProvenance =
        DataProvenance(
            providerId = providerId,
            schoolId = SchoolId(session.schoolId),
            accountId = AccountId(session.accountId),
            sourceSystem = sourceSystem
        )

    /**
     * 读接口的统一前置校验：401/403 或"返回的其实是登录页"都视为会话过期，
     * 其它非 2xx 视为来源数据异常。消息里只带状态码，绝不带响应正文。
     */
    fun ensureReadable(response: GatewayResponse, body: String) {
        if (response.isUnauthorized) throw TransportFailure(AcademicError.SESSION_EXPIRED, "教务会话已过期")
        if (!response.isSuccess) {
            throw TransportFailure(AcademicError.SOURCE_DATA_INVALID, "教务返回了 " + response.status + " 状态")
        }
        if (QzLoginPage.parse(body) != null) {
            throw TransportFailure(AcademicError.SESSION_EXPIRED, "教务会话已过期，返回的是登录页")
        }
    }
}

/** 把传输层失败统一映射成结构化结果；UNTRUSTED_REDIRECT 等策略错误原样保留错误码。 */
internal suspend fun <T> qzGuard(block: suspend () -> AcademicResult<T>): AcademicResult<T> = try {
    block()
} catch (failure: TransportFailure) {
    AcademicResult.Failure(failure.error, retryable = false, detail = failure.message)
}
