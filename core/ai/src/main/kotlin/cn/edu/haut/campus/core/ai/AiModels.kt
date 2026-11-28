package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.StoredBlobRef
import kotlinx.coroutines.flow.Flow
import java.net.URI
import java.time.Instant

/** HTTPS-only base URL。拒绝 userinfo、query、fragment、空白和控制字符；路径用于 OpenAI 兼容 /v1 前缀。 */
@JvmInline
value class HttpsUrl(val value: String) {
    init {
        require(value.length in 8..2048) { "AI baseUrl 长度非法" }
        require(value.none { it.isWhitespace() || it.isISOControl() }) { "AI baseUrl 不能包含空白或控制字符" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("AI baseUrl 不是合法 URL") }
        require(uri.scheme.equals("https", ignoreCase = true)) { "AI baseUrl 只允许 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "AI baseUrl 缺少主机名" }
        require(uri.rawUserInfo == null) { "AI baseUrl 不得携带用户名或密码" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "AI baseUrl 不得携带 query 或 fragment" }
    }

    val host: String get() = URI(value).host
    val withoutTrailingSlash: String get() = value.trimEnd('/').ifEmpty { value }
}

data class AiEndpoint(
    val baseUrl: HttpsUrl,
    val model: String,
    val supportsVision: Boolean
) {
    init {
        require(model.isNotBlank() && model.length <= 256) { "AI model 为空或过长" }
        require(model.none { it.isISOControl() || it.isWhitespace() }) { "AI model 不能包含空白或控制字符" }
    }

    val host: String get() = baseUrl.host
}

enum class AiKeyStatus {
    NONE,
    CONFIGURED,
    INVALID
}

/** Key 仓公开面永不返回 Key 本身；Android 实现必须 Keystore AES-GCM、按 Profile 隔离并放在 noBackupFilesDir。 */
interface AiKeyStore {
    suspend fun put(scope: SchoolAccountScope, endpoint: AiEndpoint, key: CharArray)
    suspend fun clear(scope: SchoolAccountScope)
    fun status(scope: SchoolAccountScope): Flow<AiKeyStatus>
}

enum class AiPurpose {
    INBOX_PARSE,
    KNOWLEDGE_QA,
    QUIZ,
    CONNECTIVITY_TEST
}

enum class AiMessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}

data class AiMessage(
    val role: AiMessageRole,
    val text: String
) {
    init {
        require(text.isNotBlank()) { "AI 消息不能为空" }
        require(text.length <= MAX_MESSAGE_CHARS) { "AI 单条消息过长" }
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 64_000
    }
}

data class AiRequest(
    val purpose: AiPurpose,
    val messages: List<AiMessage>,
    val attachments: List<StoredBlobRef>,
    val maxTokens: Int
) {
    init {
        require(messages.isNotEmpty()) { "AI 请求至少需要一条消息" }
        require(messages.size <= 32) { "AI 请求消息数量超限" }
        require(messages.sumOf { it.text.length } <= MAX_TOTAL_TEXT_CHARS) { "AI 请求文本总量超限" }
        require(attachments.size <= MAX_ATTACHMENTS) { "AI 请求附件数量超限" }
        require(attachments.map { it.blobId }.distinct().size == attachments.size) { "AI 请求附件重复" }
        require(maxTokens in 1..MAX_COMPLETION_TOKENS) { "maxTokens 超出允许范围" }
    }

    companion object {
        const val MAX_TOTAL_TEXT_CHARS = 256_000
        const val MAX_ATTACHMENTS = 8
        const val MAX_COMPLETION_TOKENS = 8192
    }
}

enum class RedactionKind {
    STUDENT_ID,
    PHONE_NUMBER,
    NATIONAL_ID,
    DORM_ROOM
}

/** 只记录命中类别和次数；绝不把原始或片段化 PII 放进预览、日志或诊断。 */
data class Redaction(
    val kind: RedactionKind,
    val count: Int
) {
    init { require(count > 0) { "脱敏次数必须为正" } }
}

/**
 * 发送预览。outboundMessages 是本地脱敏后的完整出站文本，UI 必须在生成同意 token 前展示；
 * 它只包含占位符，不包含被脱敏的原始 PII。
 */
data class AiSendPreview(
    val endpointHost: String,
    val model: String,
    val textChars: Int,
    val attachmentCount: Int,
    val redactions: List<Redaction>,
    val outboundMessages: List<AiMessage>,
    val estimatedRequestBytes: Long,
    val purpose: AiPurpose,
    val maxTokens: Int,
    val attachmentSha256: List<String>,
    /** Key/endpoint 配置世代；只用于同意绑定，不是 secret。 */
    val configurationGeneration: Long = 0
) {
    init {
        require(configurationGeneration >= 0) { "配置世代不能为负" }
        require(endpointHost.isNotBlank() && endpointHost.length <= 512) { "预览 endpointHost 非法" }
        require(model.isNotBlank() && model.length <= 256) { "预览 model 非法" }
        require(textChars >= 0 && attachmentCount >= 0 && estimatedRequestBytes >= 0) { "预览统计不能为负" }
        require(maxTokens in 1..AiRequest.MAX_COMPLETION_TOKENS) { "预览 maxTokens 非法" }
        require(attachmentSha256.size == attachmentCount) { "预览附件摘要数量不一致" }
        require(attachmentSha256.all { it.matches(Regex("[0-9a-f]{64}")) }) { "预览附件摘要非法" }
    }
}

interface AiProviderPort {
    suspend fun preview(scope: SchoolAccountScope, req: AiRequest): AiSendPreview
    suspend fun send(scope: SchoolAccountScope, req: AiRequest, consent: AiConsentToken): AiResult
}

/** 单次同意 token。它不是凭据；有效性由进程内 AiConsentCenter 原子消费记录裁决。 */
data class AiConsentToken(
    val tokenId: String,
    val scopeFingerprint: String,
    val requestFingerprint: String,
    val expiresAt: Instant
) {
    init {
        require(tokenId.matches(Regex("consent-[0-9a-f]{64}"))) { "AI 同意 token 非法" }
        require(scopeFingerprint.matches(Regex("[0-9a-f]{64}"))) { "AI 同意 scope 指纹非法" }
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}"))) { "AI 同意请求指纹非法" }
    }
}

/**
 * BYOK 非秘密设置骨架：只描述 endpoint、Key 状态和用户显式开关；
 * 永远不含 Key 明文、密文、Authorization 或可还原 secret 材料。默认关闭。
 */
data class AiSettings(
    val endpoint: AiEndpoint? = null,
    val keyStatus: AiKeyStatus = AiKeyStatus.NONE,
    val enabled: Boolean = false,
    /** 进程内 Key/配置串行世代；不提供任何 Key 材料或可由离线反推 secret 的信息。 */
    val configurationGeneration: Long = 0
) {
    init { require(configurationGeneration >= 0) { "配置世代不能为负" } }

    val configured: Boolean get() = endpoint != null && keyStatus == AiKeyStatus.CONFIGURED
}

/** Provider 只需要的只读设置面；不存在任何 Key 读取能力。 */
interface AiSettingsAccess {
    suspend fun settings(scope: SchoolAccountScope): AiSettings
}

/** Profile 隔离的设置骨架公开面；Android 实现负责持久化、观察与默认关闭开关。 */
interface AiSettingsStore : AiSettingsAccess {
    fun observeSettings(scope: SchoolAccountScope): Flow<AiSettings>
    suspend fun setEnabled(scope: SchoolAccountScope, enabled: Boolean)
}

data class AiUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
) {
    init {
        listOfNotNull(promptTokens, completionTokens, totalTokens).forEach { require(it >= 0) { "AI usage 不能为负" } }
    }
}

enum class AiError {
    FEATURE_DISABLED,
    NOT_CONFIGURED,
    INVALID_CONFIGURATION,
    CONSENT_INVALID,
    CONSENT_EXPIRED,
    CONSENT_CONSUMED,
    CONSENT_MISMATCH,
    ENDPOINT_NOT_HTTPS,
    ATTACHMENT_UNSUPPORTED,
    ATTACHMENT_MISSING,
    ATTACHMENT_TOO_LARGE,
    NETWORK_OFFLINE,
    TIMEOUT,
    TLS_ERROR,
    AUTH_REJECTED,
    RATE_LIMITED,
    HTTP_ERROR,
    INVALID_RESPONSE,
    CANCELLED
}

sealed interface AiResult {
    data class Success(
        val text: String,
        val model: String,
        val finishReason: String?,
        val usage: AiUsage?
    ) : AiResult

    data class Failure(
        val error: AiError,
        val retryable: Boolean,
        val detail: String? = null
    ) : AiResult {
        init {
            require(detail == null || detail.length <= 240) { "AI 错误详情过长" }
            require(detail?.none { it.isISOControl() } != false) { "AI 错误详情不能包含控制字符" }
        }
    }
}

/** preview 无法构造时抛出；消息只允许说明配置/开关状态，不得携带 Key、请求原文或远程响应原文。 */
class AiProviderException(
    val error: AiError,
    message: String
) : IllegalStateException(message)
