package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

class AiConsentAndCodecTest {
    @Test
    fun consentTokenIsSingleUseAndBoundToPreview() {
        var now = Instant.parse("2026-11-28T08:00:00Z")
        val center = AiConsentCenter(clock = { now }, ttl = Duration.ofSeconds(30))
        val req = AiTestFixtures.request()
        val preview = AiTestFixtures.preview(req)
        val token = center.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        assertEquals(AiConsentVerdict.ACCEPTED, center.consume(AiTestFixtures.scope, AiTestFixtures.endpoint, preview, token))
        assertEquals(AiConsentVerdict.CONSUMED, center.consume(AiTestFixtures.scope, AiTestFixtures.endpoint, preview, token))

        val second = center.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)
        val changed = preview.copy(maxTokens = preview.maxTokens + 1)
        assertEquals(AiConsentVerdict.MISMATCH, center.consume(AiTestFixtures.scope, AiTestFixtures.endpoint, changed, second))

        val third = center.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)
        now = now.plusSeconds(31)
        assertEquals(AiConsentVerdict.EXPIRED, center.consume(AiTestFixtures.scope, AiTestFixtures.endpoint, preview, third))
    }

    @Test
    fun chatCodecBuildsOpenAiVisionPayloadWithoutAlteringText() {
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val blob = AiTestFixtures.blobRef(byteCount = imageBytes.size.toLong())
        val payload = AiVisionPayload(blob, Base64.getEncoder().encodeToString(imageBytes))
        val body = OpenAiCompatibleCodec.encodeChatRequest(
            endpoint = AiTestFixtures.endpoint,
            redactedMessages = listOf(AiMessage(AiMessageRole.USER, "看这张图")),
            maxTokens = 32,
            visionAttachments = listOf(payload)
        )
        val root = Json.parse(body)

        assertEquals(AiTestFixtures.endpoint.model, root.str("model"))
        assertEquals(32, root.obj("max_tokens")?.intOrNull())
        val message = root.arr("messages")?.single()?.asObjOrNull
        assertNotNull(message)
        val content = message?.arr("content")
        assertEquals("text", content?.get(0)?.str("type"))
        assertEquals("看这张图", content?.get(0)?.str("text"))
        val imageUrl = content?.get(1)?.obj("image_url")?.str("url")
        assertTrue(imageUrl?.startsWith("data:image/png;base64,") == true)
    }

    @Test
    fun responseCodecExtractsTextAndUsage() {
        val body = """{"model":"remote-model","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"答案"}}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}"""
        val result = OpenAiCompatibleCodec.decodeChatResponse(body, "fallback")

        val success = result as AiResult.Success
        assertEquals("答案", success.text)
        assertEquals("remote-model", success.model)
        assertEquals("stop", success.finishReason)
        assertEquals(7, success.usage?.promptTokens)
        assertEquals(10, success.usage?.totalTokens)
    }

    @Test
    fun remoteErrorMessageNeverLeaksArbitraryProviderText() {
        val canary = AiTestFixtures.keyCanary()
        val body = """{"error":{"type":"invalid_api_key","message":"bad credential $canary"}}"""
        val sanitized = OpenAiCompatibleCodec.sanitizedRemoteErrorType(body)

        assertEquals("invalid_api_key", sanitized)
        assertFalse(sanitized?.contains(canary) == true)
    }
}
