package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.StoredBlobRef

object AiTestFixtures {
    val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-test"),
        profileId = ProfileId("profile-test"),
        campusId = CampusId("north")
    )
    val otherScope = scope.copy(accountId = AccountId("other-account"), profileId = ProfileId("other-profile"))
    val endpoint = AiEndpoint(
        baseUrl = HttpsUrl("https://provider.example/v1"),
        model = "chat-vision-test",
        supportsVision = true
    )

    fun request(text: String = "请总结这周的调课通知", maxTokens: Int = 128): AiRequest = AiRequest(
        purpose = AiPurpose.INBOX_PARSE,
        messages = listOf(AiMessage(AiMessageRole.USER, text)),
        attachments = emptyList(),
        maxTokens = maxTokens
    )

    fun preview(request: AiRequest = request()): AiSendPreview {
        val redacted = AiLocalRedactor.redact(request.messages)
        return AiSendPreview(
            endpointHost = endpoint.host,
            model = endpoint.model,
            textChars = redacted.messages.sumOf { it.text.length },
            attachmentCount = request.attachments.size,
            redactions = redacted.redactions,
            outboundMessages = redacted.messages,
            estimatedRequestBytes = 1024,
            purpose = request.purpose,
            maxTokens = request.maxTokens,
            attachmentSha256 = request.attachments.map { it.sha256 }
        )
    }

    fun blobRef(
        sha256: String = "a".repeat(64),
        byteCount: Long = 4,
        mimeType: String = "image/png",
        ownerScope: SchoolAccountScope = scope
    ): StoredBlobRef = StoredBlobRef(
        blobId = "blob-" + sha256.take(16) + "-test",
        scope = ownerScope,
        sha256 = sha256,
        mimeType = mimeType,
        byteCount = byteCount,
        owner = null
    )

    fun keyCanary(): String = listOf("unit", "test", "key", "canary").joinToString("-")
}
