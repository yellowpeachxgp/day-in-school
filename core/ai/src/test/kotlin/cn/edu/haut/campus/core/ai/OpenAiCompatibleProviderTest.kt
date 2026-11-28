package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.StoredBlobRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderTest {
    @Test
    fun sendRequiresSingleUseConsentAndTransportsOnlyRedactedText() = runTest {
        val transport = RecordingTransport(
            AiTransportResponse(
                200,
                """{"model":"remote","choices":[{"finish_reason":"stop","message":{"content":"完成"}}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""
            )
        )
        val harness = Harness(transport = transport)
        val phone = "13812345678"
        val req = AiTestFixtures.request("请发送提醒给 $phone")
        val preview = harness.provider.preview(AiTestFixtures.scope, req)
        val token = harness.consentCenter.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        val result = harness.provider.send(AiTestFixtures.scope, req, token)

        assertTrue(result is AiResult.Success)
        assertEquals(1, transport.calls)
        assertFalse(transport.lastBody.orEmpty().contains(phone))
        assertTrue(transport.lastBody.orEmpty().contains("[已脱敏:手机号]"))
        assertEquals(7, (result as AiResult.Success).usage?.totalTokens)

        val replay = harness.provider.send(AiTestFixtures.scope, req, token)
        assertEquals(AiError.CONSENT_CONSUMED, (replay as AiResult.Failure).error)
        assertEquals(1, transport.calls)
    }

    @Test
    fun forgedOrMissingConsentNeverReachesTransportOrKeyMaterial() = runTest {
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val harness = Harness(transport = transport)
        val req = AiTestFixtures.request()
        val forged = AiConsentToken(
            tokenId = "consent-" + "0".repeat(64),
            scopeFingerprint = "0".repeat(64),
            requestFingerprint = "0".repeat(64),
            expiresAt = java.time.Instant.now().plusSeconds(30)
        )

        val result = harness.provider.send(AiTestFixtures.scope, req, forged)

        assertEquals(AiError.CONSENT_MISMATCH, (result as AiResult.Failure).error)
        assertEquals(0, transport.calls)
        assertEquals(0, harness.keyAccess.calls)
    }

    @Test
    fun forgedConsentNeverReadsVisionAttachmentBytes() = runTest {
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val attachmentReader = object : AiAttachmentReader {
            var calls = 0
            override suspend fun read(scope: SchoolAccountScope, ref: StoredBlobRef): ByteArray {
                calls += 1
                return byteArrayOf(1, 2, 3)
            }
        }
        val harness = Harness(transport = transport, attachmentReader = attachmentReader)
        val req = AiTestFixtures.request().copy(attachments = listOf(AiTestFixtures.blobRef(byteCount = 3)))
        val forged = AiConsentToken(
            tokenId = "consent-" + "2".repeat(64),
            scopeFingerprint = "2".repeat(64),
            requestFingerprint = "2".repeat(64),
            expiresAt = java.time.Instant.now().plusSeconds(30)
        )

        val result = harness.provider.send(AiTestFixtures.scope, req, forged)

        assertEquals(AiError.CONSENT_MISMATCH, (result as AiResult.Failure).error)
        assertEquals(0, attachmentReader.calls)
        assertEquals(0, transport.calls)
        assertEquals(0, harness.keyAccess.calls)
    }

    @Test
    fun disabledByDefaultBlocksPreviewAndSendBeforeKeyMaterial() = runTest {
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val harness = Harness(enabled = false, transport = transport)
        val req = AiTestFixtures.request()

        val previewError = runCatching {
            harness.provider.preview(AiTestFixtures.scope, req)
        }.exceptionOrNull()
        assertTrue(previewError is AiProviderException)
        assertEquals(AiError.FEATURE_DISABLED, (previewError as AiProviderException).error)

        val token = AiConsentToken(
            tokenId = "consent-" + "1".repeat(64),
            scopeFingerprint = "1".repeat(64),
            requestFingerprint = "1".repeat(64),
            expiresAt = java.time.Instant.now().plusSeconds(30)
        )
        val result = harness.provider.send(AiTestFixtures.scope, req, token)
        assertEquals(AiError.FEATURE_DISABLED, (result as AiResult.Failure).error)
        assertEquals(0, transport.calls)
        assertEquals(0, harness.keyAccess.calls)
    }

    @Test
    fun visionRequestReadsAttachmentAfterConsentAndEncodesImage() = runTest {
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        val blob = AiTestFixtures.blobRef(
            sha256 = "63d987d1c6d69751c17297f410f5b3547a65d096a8993b35bcb4f9cad054f176",
            byteCount = imageBytes.size.toLong()
        )
        val attachmentReader = object : AiAttachmentReader {
            var calls = 0
            override suspend fun read(scope: SchoolAccountScope, ref: StoredBlobRef): ByteArray {
                calls += 1
                return imageBytes.copyOf()
            }
        }
        val transport = RecordingTransport(
            AiTransportResponse(200, """{"choices":[{"message":{"content":"看图完成"}}]}""")
        )
        val harness = Harness(transport = transport, attachmentReader = attachmentReader)
        val req = AiTestFixtures.request("描述图片").copy(attachments = listOf(blob))
        val preview = harness.provider.preview(AiTestFixtures.scope, req)
        val token = harness.consentCenter.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        val result = harness.provider.send(AiTestFixtures.scope, req, token)

        assertTrue(result is AiResult.Success)
        assertEquals(1, attachmentReader.calls)
        assertTrue(transport.lastBody.orEmpty().contains("data:image/png;base64,"))
    }

    @Test
    fun attachmentHashMismatchStopsBeforeTransportAndKeyMaterial() = runTest {
        val imageBytes = byteArrayOf(9, 8, 7, 6)
        val attachmentReader = object : AiAttachmentReader {
            override suspend fun read(scope: SchoolAccountScope, ref: StoredBlobRef): ByteArray = imageBytes.copyOf()
        }
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val harness = Harness(transport = transport, attachmentReader = attachmentReader)
        val req = AiTestFixtures.request("描述图片").copy(
            attachments = listOf(AiTestFixtures.blobRef(byteCount = imageBytes.size.toLong()))
        )
        val preview = harness.provider.preview(AiTestFixtures.scope, req)
        val token = harness.consentCenter.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        val result = harness.provider.send(AiTestFixtures.scope, req, token)

        assertEquals(AiError.ATTACHMENT_MISSING, (result as AiResult.Failure).error)
        assertEquals(0, transport.calls)
        assertEquals(0, harness.keyAccess.calls)
    }

    @Test
    fun remoteErrorDetailsDoNotEchoKeyMaterialEvenIfProviderDoes() = runTest {
        val canary = AiTestFixtures.keyCanary()
        val transport = RecordingTransport(
            AiTransportResponse(401, """{"error":{"type":"$canary","code":"$canary","message":"bad key $canary"}}""")
        )
        val harness = Harness(transport = transport)
        val req = AiTestFixtures.request()
        val preview = harness.provider.preview(AiTestFixtures.scope, req)
        val token = harness.consentCenter.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        val result = harness.provider.send(AiTestFixtures.scope, req, token) as AiResult.Failure

        assertEquals(AiError.AUTH_REJECTED, result.error)
        assertFalse("远端 error.type/code/message 均不得反射进 detail", result.detail.orEmpty().contains(canary))
        assertEquals("HTTP 401", result.detail)
    }

    @Test
    fun changingKeyConfigurationGenerationRejectsOutstandingConsentBeforeKeyAndTransport() = runTest {
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val harness = Harness(transport = transport)
        val req = AiTestFixtures.request()
        val preview = harness.provider.preview(AiTestFixtures.scope, req)
        val token = harness.consentCenter.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        harness.configurationGeneration += 1
        val result = harness.provider.send(AiTestFixtures.scope, req, token)

        assertEquals(AiError.CONSENT_MISMATCH, (result as AiResult.Failure).error)
        assertEquals(0, transport.calls)
        assertEquals(0, harness.keyAccess.calls)
    }

    @Test
    fun keyMaterialGenerationSnapshotMismatchStopsBeforeTransportAfterConsentConsumption() = runTest {
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val harness = Harness(transport = transport)
        val req = AiTestFixtures.request()
        val preview = harness.provider.preview(AiTestFixtures.scope, req)
        val token = harness.consentCenter.issue(AiTestFixtures.scope, AiTestFixtures.endpoint, preview)

        harness.keyAccess.configurationGeneration = 2
        val result = harness.provider.send(AiTestFixtures.scope, req, token)

        assertEquals(AiError.CONSENT_MISMATCH, (result as AiResult.Failure).error)
        assertEquals("generation 快照检查发生在 Key 读取之后、网络发送之前", 1, harness.keyAccess.calls)
        assertEquals(0, transport.calls)
    }

    @Test
    fun attachmentFromAnotherProfileIsRejectedBeforeConsentConsumption() = runTest {
        val transport = RecordingTransport(AiTransportResponse(200, "{}"))
        val harness = Harness(transport = transport)
        val foreignBlob = AiTestFixtures.blobRef(ownerScope = AiTestFixtures.otherScope)
        val req = AiTestFixtures.request().copy(attachments = listOf(foreignBlob))

        val previewError = runCatching { harness.provider.preview(AiTestFixtures.scope, req) }.exceptionOrNull()

        assertTrue(previewError is AiProviderException)
        assertEquals(AiError.ATTACHMENT_MISSING, (previewError as AiProviderException).error)
        assertEquals(0, transport.calls)
        assertEquals(0, harness.keyAccess.calls)
    }

    private class Harness(
        enabled: Boolean = true,
        transport: RecordingTransport,
        attachmentReader: AiAttachmentReader? = null
    ) {
        var configurationGeneration = 1L
        val consentCenter = AiConsentCenter()
        val settingsAccess = object : AiSettingsAccess {
            override suspend fun settings(scope: SchoolAccountScope): AiSettings =
                AiSettings(
                    endpoint = AiTestFixtures.endpoint,
                    keyStatus = AiKeyStatus.CONFIGURED,
                    enabled = enabled,
                    configurationGeneration = configurationGeneration
                )
        }
        val keyAccess = RecordingKeyAccess()
        val provider = OpenAiCompatibleProvider(
            settingsAccess = settingsAccess,
            keyMaterialAccess = keyAccess,
            consentCenter = consentCenter,
            attachmentReader = attachmentReader,
            transport = transport
        )
    }

    private class RecordingKeyAccess : AiKeyMaterialAccess {
        var calls = 0
        var configurationGeneration = 1L
        override suspend fun <T> withKeyMaterial(
            scope: SchoolAccountScope,
            block: suspend (endpoint: AiEndpoint, key: CharArray, configurationGeneration: Long) -> T
        ): T {
            calls += 1
            return block(AiTestFixtures.endpoint, AiTestFixtures.keyCanary().toCharArray(), configurationGeneration)
        }
    }

    private class RecordingTransport(private val response: AiTransportResponse) : AiTransport {
        var calls = 0
        var lastBody: String? = null
        var lastAuthorization: String? = null
        override suspend fun postJson(url: String, bearerKey: CharArray, body: String): AiTransportResponse {
            calls += 1
            lastBody = body
            lastAuthorization = String(bearerKey)
            return response
        }
    }
}
