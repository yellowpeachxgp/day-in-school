package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.StoredBlobRef
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/** 已读取并校验的 vision 附件；base64Data 只在构造请求体时短暂存在，绝不进入日志或诊断。 */
data class AiVisionPayload(
    val ref: StoredBlobRef,
    val base64Data: String
) {
    init {
        require(ref.mimeType in SUPPORTED_IMAGE_MIME_TYPES) { "AI vision 附件 MIME 类型不受支持" }
        require(base64Data.isNotBlank() && base64Data.length <= MAX_BASE64_CHARS) { "AI vision 附件大小非法" }
        require(base64Data.all { it in BASE64_CHARS }) { "AI vision 附件 base64 非法" }
    }

    companion object {
        val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_TOTAL_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_BASE64_CHARS = (MAX_IMAGE_BYTES + 2) / 3 * 4
        private val BASE64_CHARS: Set<Char> =
            ('A'..'Z').toSet() + ('a'..'z') + ('0'..'9') + setOf('+', '/', '=')
    }
}

object OpenAiCompatibleCodec {
    fun chatCompletionsUrl(endpoint: AiEndpoint): String =
        endpoint.baseUrl.withoutTrailingSlash + "/chat/completions"

    fun encodeChatRequest(
        endpoint: AiEndpoint,
        redactedMessages: List<AiMessage>,
        maxTokens: Int,
        visionAttachments: List<AiVisionPayload>
    ): String {
        require(redactedMessages.isNotEmpty()) { "AI 出站消息不能为空" }
        if (visionAttachments.isNotEmpty()) {
            require(endpoint.supportsVision) { "当前模型未声明支持 vision" }
            require(visionAttachments.sumOf { it.ref.byteCount } <= AiVisionPayload.MAX_TOTAL_IMAGE_BYTES) {
                "AI vision 附件总大小超限"
            }
        }
        val encodedMessages = encodeMessages(redactedMessages, visionAttachments)
        val body = JsonValue.Obj(
            linkedMapOf(
                "model" to JsonValue.Str(endpoint.model),
                "messages" to JsonValue.Arr(encodedMessages),
                "max_tokens" to JsonValue.Num(maxTokens.toString()),
                "stream" to JsonValue.Bool(false),
                "temperature" to JsonValue.Num("0")
            )
        )
        return Json.canonical(body)
    }

    private fun encodeMessages(
        messages: List<AiMessage>,
        visionAttachments: List<AiVisionPayload>
    ): List<JsonValue> {
        val lastUserIndex = messages.indexOfLast { it.role == AiMessageRole.USER }
        require(visionAttachments.isEmpty() || lastUserIndex >= 0) { "vision 请求至少需要一条 USER 消息" }
        return messages.mapIndexed { index, message ->
            val role = when (message.role) {
                AiMessageRole.SYSTEM -> "system"
                AiMessageRole.USER -> "user"
                AiMessageRole.ASSISTANT -> "assistant"
            }
            val content: JsonValue = if (index == lastUserIndex && visionAttachments.isNotEmpty()) {
                val parts = mutableListOf<JsonValue>(
                    JsonValue.Obj(
                        linkedMapOf(
                            "type" to JsonValue.Str("text"),
                            "text" to JsonValue.Str(message.text)
                        )
                    )
                )
                visionAttachments.forEach { attachment ->
                    parts += JsonValue.Obj(
                        linkedMapOf(
                            "type" to JsonValue.Str("image_url"),
                            "image_url" to JsonValue.Obj(
                                linkedMapOf(
                                    "url" to JsonValue.Str("data:" + attachment.ref.mimeType + ";base64," + attachment.base64Data)
                                )
                            )
                        )
                    )
                }
                JsonValue.Arr(parts)
            } else {
                JsonValue.Str(message.text)
            }
            JsonValue.Obj(
                linkedMapOf(
                    "role" to JsonValue.Str(role),
                    "content" to content
                )
            )
        }
    }

    fun decodeChatResponse(responseBody: String, fallbackModel: String): AiResult {
        val root = try {
            Json.parse(responseBody)
        } catch (_: Exception) {
            return AiResult.Failure(AiError.INVALID_RESPONSE, retryable = false, detail = "响应不是合法 JSON")
        }
        val choices = root.arr("choices")
        val first = choices?.firstOrNull()?.asObjOrNull
        val message = first?.obj("message")?.asObjOrNull
        val content = message?.str("content")
        if (first == null || message == null || content.isNullOrBlank()) {
            return AiResult.Failure(AiError.INVALID_RESPONSE, retryable = false, detail = "响应缺少 choices[0].message.content")
        }
        val usageObj = root.obj("usage")?.asObjOrNull
        val usage = usageObj?.let {
            AiUsage(
                promptTokens = it.obj("prompt_tokens")?.intOrNull(),
                completionTokens = it.obj("completion_tokens")?.intOrNull(),
                totalTokens = it.obj("total_tokens")?.intOrNull()
            )
        }
        return AiResult.Success(
            text = content,
            model = root.str("model")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: fallbackModel,
            finishReason = first.str("finish_reason")?.takeIf { it.length <= 64 },
            usage = usage
        )
    }

    /** 远端错误只提取有限字符集的 type/code，不回传可能含请求片段或账户信息的 message。 */
    fun sanitizedRemoteErrorType(responseBody: String): String? {
        val root = runCatching { Json.parse(responseBody) }.getOrNull() ?: return null
        val error = root.obj("error")?.asObjOrNull ?: return null
        val raw = error.str("type") ?: error.str("code") ?: return null
        return raw.takeIf { it.length <= 80 && it.all { ch -> ch.isLetterOrDigit() || ch in "_-." } }
    }
}
