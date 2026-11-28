package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.StoredBlobRef
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Key 材料受控使用端口。实现只在 callback 生命周期内提供明文副本，并必须在 callback 返回或抛出后清零；
 * 该接口故意不支持“返回 Key 给调用方保存”。
 */
interface AiKeyMaterialAccess {
    suspend fun <T> withKeyMaterial(
        scope: SchoolAccountScope,
        block: suspend (endpoint: AiEndpoint, key: CharArray) -> T
    ): T
}

interface AiAttachmentReader {
    suspend fun read(scope: SchoolAccountScope, ref: StoredBlobRef): ByteArray?
}

class AiKeyAccessException(val status: AiKeyStatus) : IllegalStateException("AI Key 不可用: " + status.name)

private class AiLocalFailure(val result: AiResult.Failure) : Exception(null, null, false, false)

/** OpenAI 兼容 chat/vision Provider。所有网络发送都必须先通过单次同意中心。 */
class OpenAiCompatibleProvider(
    private val settingsAccess: AiSettingsAccess,
    private val keyMaterialAccess: AiKeyMaterialAccess,
    private val consentCenter: AiConsentCenter,
    private val attachmentReader: AiAttachmentReader? = null,
    private val transport: AiTransport = HttpUrlConnectionAiTransport()
) : AiProviderPort {
    override suspend fun preview(scope: SchoolAccountScope, req: AiRequest): AiSendPreview {
        val endpoint = requireReadyEndpoint(scope)
        return buildPreview(scope, endpoint, req)
    }

    override suspend fun send(scope: SchoolAccountScope, req: AiRequest, consent: AiConsentToken): AiResult {
        return try {
            val endpoint = requireReadyEndpoint(scope)
            val preview = buildPreview(scope, endpoint, req)
            when (val verdict = consentCenter.consume(scope, endpoint, preview, consent)) {
                AiConsentVerdict.ACCEPTED -> Unit
                AiConsentVerdict.INVALID -> return AiResult.Failure(AiError.CONSENT_INVALID, retryable = false)
                AiConsentVerdict.EXPIRED -> return AiResult.Failure(AiError.CONSENT_EXPIRED, retryable = false)
                AiConsentVerdict.CONSUMED -> return AiResult.Failure(AiError.CONSENT_CONSUMED, retryable = false)
                AiConsentVerdict.MISMATCH -> return AiResult.Failure(AiError.CONSENT_MISMATCH, retryable = false)
            }
            val visionAttachments = loadVisionAttachments(scope, endpoint, req)
            keyMaterialAccess.withKeyMaterial(scope) { keyEndpoint, key ->
                if (keyEndpoint != endpoint) {
                    return@withKeyMaterial AiResult.Failure(
                        AiError.INVALID_CONFIGURATION,
                        retryable = false,
                        detail = "Key 与当前 endpoint 配置不一致"
                    )
                }
                val body = OpenAiCompatibleCodec.encodeChatRequest(
                    endpoint = endpoint,
                    redactedMessages = preview.outboundMessages,
                    maxTokens = req.maxTokens,
                    visionAttachments = visionAttachments
                )
                executeHttp(endpoint, key, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AiLocalFailure) {
            failure.result
        } catch (providerError: AiProviderException) {
            AiResult.Failure(providerError.error, retryable = false, detail = providerError.message)
        } catch (keyError: AiKeyAccessException) {
            when (keyError.status) {
                AiKeyStatus.NONE -> AiResult.Failure(AiError.NOT_CONFIGURED, retryable = false)
                else -> AiResult.Failure(AiError.INVALID_CONFIGURATION, retryable = false)
            }
        } catch (transportError: AiTransportException) {
            transportFailure(transportError)
        } catch (_: IllegalArgumentException) {
            AiResult.Failure(AiError.INVALID_CONFIGURATION, retryable = false)
        }
    }

    private suspend fun requireReadyEndpoint(scope: SchoolAccountScope): AiEndpoint {
        val settings = settingsAccess.settings(scope)
        if (!settings.enabled) {
            throw AiProviderException(AiError.FEATURE_DISABLED, "BYOK AI 默认关闭，请先单独开启")
        }
        if (settings.keyStatus != AiKeyStatus.CONFIGURED || settings.endpoint == null) {
            throw AiProviderException(AiError.NOT_CONFIGURED, "尚未配置 AI endpoint 或 Key")
        }
        return settings.endpoint
    }

    private fun buildPreview(scope: SchoolAccountScope, endpoint: AiEndpoint, req: AiRequest): AiSendPreview {
        validateAttachmentsInScope(scope, req)
        validateAttachmentMetadata(endpoint, req)
        val redacted = AiLocalRedactor.redact(req.messages)
        val textChars = redacted.messages.sumOf { it.text.length }
        val textBytes = redacted.messages.sumOf {
            it.text.toByteArray(StandardCharsets.UTF_8).size.toLong() + it.role.name.length + 16L
        }
        val imagePayloadBytes = req.attachments.sumOf { (it.byteCount + 2L) / 3L * 4L }
        return AiSendPreview(
            endpointHost = endpoint.host,
            model = endpoint.model,
            textChars = textChars,
            attachmentCount = req.attachments.size,
            redactions = redacted.redactions,
            outboundMessages = redacted.messages,
            estimatedRequestBytes = textBytes + imagePayloadBytes + 512L,
            purpose = req.purpose,
            maxTokens = req.maxTokens,
            attachmentSha256 = req.attachments.map { it.sha256 }
        )
    }

    private fun validateAttachmentsInScope(scope: SchoolAccountScope, req: AiRequest) {
        if (req.attachments.any { it.scope != scope }) {
            throw AiProviderException(AiError.ATTACHMENT_MISSING, "附件不属于当前 Profile")
        }
    }

    private fun validateAttachmentMetadata(endpoint: AiEndpoint, req: AiRequest) {
        if (req.attachments.isEmpty()) return
        if (!endpoint.supportsVision) {
            throw AiProviderException(AiError.ATTACHMENT_UNSUPPORTED, "当前模型未声明支持 vision")
        }
        if (req.attachments.any { it.mimeType !in AiVisionPayload.SUPPORTED_IMAGE_MIME_TYPES }) {
            throw AiProviderException(AiError.ATTACHMENT_UNSUPPORTED, "附件不是受支持的图片类型")
        }
        if (req.attachments.any { it.byteCount <= 0L || it.byteCount > AiVisionPayload.MAX_IMAGE_BYTES }) {
            throw AiProviderException(AiError.ATTACHMENT_TOO_LARGE, "附件大小超出上限")
        }
        if (req.attachments.sumOf { it.byteCount } > AiVisionPayload.MAX_TOTAL_IMAGE_BYTES) {
            throw AiProviderException(AiError.ATTACHMENT_TOO_LARGE, "附件总大小超出上限")
        }
    }

    private suspend fun loadVisionAttachments(
        scope: SchoolAccountScope,
        endpoint: AiEndpoint,
        req: AiRequest
    ): List<AiVisionPayload> {
        validateAttachmentMetadata(endpoint, req)
        if (req.attachments.isEmpty()) return emptyList()
        val reader = attachmentReader
            ?: throw AiLocalFailure(AiResult.Failure(AiError.ATTACHMENT_MISSING, retryable = false, detail = "缺少附件读取端口"))
        return req.attachments.map { ref ->
            val bytes = reader.read(scope, ref)
                ?: throw AiLocalFailure(AiResult.Failure(AiError.ATTACHMENT_MISSING, retryable = false))
            if (bytes.size.toLong() != ref.byteCount || bytes.isEmpty() || bytes.size > AiVisionPayload.MAX_IMAGE_BYTES) {
                bytes.fill(0)
                throw AiLocalFailure(AiResult.Failure(AiError.ATTACHMENT_TOO_LARGE, retryable = false))
            }
            val actualSha256 = sha256Hex(bytes)
            if (actualSha256 != ref.sha256) {
                bytes.fill(0)
                throw AiLocalFailure(
                    AiResult.Failure(
                        AiError.ATTACHMENT_MISSING,
                        retryable = false,
                        detail = "附件内容摘要与预览不一致"
                    )
                )
            }
            try {
                AiVisionPayload(ref = ref, base64Data = Base64.getEncoder().encodeToString(bytes))
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun executeHttp(endpoint: AiEndpoint, key: CharArray, body: String): AiResult {
        val response = try {
            transport.postJson(OpenAiCompatibleCodec.chatCompletionsUrl(endpoint), key, body)
        } catch (error: AiTransportException) {
            return transportFailure(error)
        }
        return when (response.statusCode) {
            in 200..299 -> OpenAiCompatibleCodec.decodeChatResponse(response.body, endpoint.model)
            401, 403 -> AiResult.Failure(
                AiError.AUTH_REJECTED,
                retryable = false,
                detail = httpDetail(response)
            )
            429 -> AiResult.Failure(AiError.RATE_LIMITED, retryable = true, detail = httpDetail(response))
            in 300..399 -> AiResult.Failure(
                AiError.HTTP_ERROR,
                retryable = false,
                detail = "端点返回重定向，已拒绝转发凭据"
            )
            in 500..599 -> AiResult.Failure(AiError.HTTP_ERROR, retryable = true, detail = httpDetail(response))
            else -> AiResult.Failure(AiError.HTTP_ERROR, retryable = false, detail = httpDetail(response))
        }
    }

    private fun transportFailure(error: AiTransportException): AiResult.Failure = when (error.kind) {
        AiTransportFailureKind.TIMEOUT -> AiResult.Failure(AiError.TIMEOUT, retryable = true)
        AiTransportFailureKind.TLS -> AiResult.Failure(AiError.TLS_ERROR, retryable = false)
        AiTransportFailureKind.INVALID_URL -> AiResult.Failure(AiError.ENDPOINT_NOT_HTTPS, retryable = false)
        AiTransportFailureKind.RESPONSE_TOO_LARGE -> AiResult.Failure(AiError.INVALID_RESPONSE, retryable = false, detail = "响应体超限")
        AiTransportFailureKind.NETWORK -> AiResult.Failure(AiError.NETWORK_OFFLINE, retryable = true)
    }

    private fun httpDetail(response: AiTransportResponse): String {
        val remoteType = OpenAiCompatibleCodec.sanitizedRemoteErrorType(response.body)
        return "HTTP " + response.statusCode + (remoteType?.let { " · " + it } ?: "")
    }
}
