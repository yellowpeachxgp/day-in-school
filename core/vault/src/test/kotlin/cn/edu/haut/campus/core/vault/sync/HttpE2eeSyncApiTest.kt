package cn.edu.haut.campus.core.vault.sync

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpE2eeSyncApiTest {
    @Test
    fun registerAndPutUseContractEndpointsHeadersAndSnakeCaseWithoutSecrets() = runBlocking {
        val deviceId = UUID.randomUUID().toString()
        val blobId = UUID.randomUUID().toString()
        val transport = RecordingTransport()
        val api = HttpE2eeSyncApi(
            cloudBaseUrl = "https://cloud.example.test",
            tokenProvider = E2eeSyncAccessTokenProvider { _, _ -> "test-access-token" },
            syncDeviceIdProvider = { deviceId },
            transport = transport
        )
        val consent = api.grantConsent()
        assertEquals("purpose", E2eeSyncProtocol.CONSENT_PURPOSE, consent.purpose)
        val grant = transport.requests.removeAt(0)
        assertEquals("POST", grant.method)
        assertEquals("https://cloud.example.test/api/v1/me/consents", grant.url)
        val grantBody = JSONObject(grant.body.orEmpty())
        assertEquals(E2eeSyncProtocol.CONSENT_PURPOSE, grantBody.getString("purpose"))
        assertEquals(E2eeSyncProtocol.CONSENT_DOCUMENT_VERSION, grantBody.getString("document_version"))
        assertTrue(grantBody.getString("document_sha256").matches(Regex("[0-9a-f]{64}")))
        assertFalse(grant.body.orEmpty().contains("master"))
        assertFalse(grant.body.orEmpty().contains("recovery"))

        api.registerDevice(deviceId, consent.id, "测试设备", "idem-device-123")
        val register = transport.requests.removeAt(0)
        assertEquals("PUT", register.method)
        assertEquals("https://cloud.example.test/api/v1/sync/devices/" + deviceId, register.url)
        assertEquals("idem-device-123", register.headers["Idempotency-Key"])
        assertFalse(register.headers.containsKey("X-Dis-Sync-Device-Id"))
        val registerBody = JSONObject(register.body.orEmpty())
        assertEquals("ANDROID", registerBody.getString("platform"))
        assertEquals(1, registerBody.getInt("sync_protocol_version"))

        val input = SyncBlobPutInput(
            encryption = SyncEncryptionEnvelope(keyId = "key-id-1", nonceBase64 = "AAAAAAAAAAAAAAAA"),
            ciphertextBase64 = "AA==",
            ciphertextSha256 = "0".repeat(64),
            baseVector = listOf(SyncVersionVectorEntry(deviceId, 3)),
            writerCounter = 4,
            idempotencyKey = "idem-put-123"
        )
        api.putBlob(blobId, input)
        val put = transport.requests.removeAt(0)
        assertEquals("https://cloud.example.test/api/v1/sync/blobs/" + blobId, put.url)
        assertEquals(deviceId, put.headers["X-Dis-Sync-Device-Id"])
        assertEquals("Bearer test-access-token", put.headers["Authorization"])
        val putBody = JSONObject(put.body.orEmpty())
        assertEquals("dis-sync-e2ee/v1", putBody.getJSONObject("encryption").getString("format"))
        assertEquals("AES-256-GCM", putBody.getJSONObject("encryption").getString("cipher_suite"))
        assertEquals("DIS_SYNC_SIZE_BUCKETS_POW4_V1", putBody.getJSONObject("encryption").getString("padding_scheme"))
        assertEquals(deviceId, putBody.getJSONArray("base_vector").getJSONObject(0).getString("device_id"))
        assertEquals(3, putBody.getJSONArray("base_vector").getJSONObject(0).getInt("counter"))
        assertEquals(4, putBody.getInt("writer_counter"))
    }

    @Test
    fun listChangesParsesClosedPaginationMetadataAndPropagatesErrorCode() = runBlocking {
        val deviceId = UUID.randomUUID().toString()
        val transport = RecordingTransport()
        val api = HttpE2eeSyncApi(
            cloudBaseUrl = "https://cloud.example.test/",
            tokenProvider = E2eeSyncAccessTokenProvider { _, _ -> "test-access-token" },
            syncDeviceIdProvider = { deviceId },
            transport = transport
        )
        val page = api.listChanges("cursor-v1-test", 100)
        assertEquals(false, page.hasMore)
        assertEquals("next-cursor-test", page.nextCursor)
        assertEquals(9L, page.snapshotEpoch)
        assertEquals(2L, page.compactionEpoch)
        assertEquals(1, page.rows.size)
        val request = transport.requests.removeAt(0)
        assertTrue(request.url.startsWith("https://cloud.example.test/api/v1/sync/changes?"))
        assertTrue(request.url.contains("cursor=cursor-v1-test"))
        assertEquals(deviceId, request.headers["X-Dis-Sync-Device-Id"])

        transport.failNext = true
        try {
            api.listChanges(null, 100)
            fail("错误码必须保留")
        } catch (error: E2eeSyncApiException) {
            assertEquals("SYNC_CURSOR_EXPIRED", error.errorCode)
            assertEquals(410, error.httpStatus)
        }
    }

    private class RecordingTransport : E2eeSyncHttpTransport {
        data class Request(val method: String, val url: String, val body: String?, val headers: Map<String, String>)

        val requests = mutableListOf<Request>()
        var failNext = false

        override suspend fun request(method: String, url: String, body: String?, headers: Map<String, String>): E2eeSyncHttpResponse {
            requests += Request(method, url, body, headers)
            if (failNext) {
                failNext = false
                return E2eeSyncHttpResponse(
                    410,
                    JSONObject().put("ok", false).put(
                        "error",
                        JSONObject().put("code", "SYNC_CURSOR_EXPIRED").put("message", "cursor expired")
                    ).toString()
                )
            }
            if (url.endsWith("/api/v1/me/consents")) {
                return ok(JSONObject().put("id", UUID.randomUUID().toString()).put("purpose", E2eeSyncProtocol.CONSENT_PURPOSE))
            }
            if (url.contains("/api/v1/sync/devices/")) {
                return ok(
                    JSONObject()
                        .put("device_id", url.substringAfterLast('/'))
                        .put("status", "ACTIVE")
                        .put("device_label", JSONObject.NULL)
                        .put("platform", "ANDROID")
                        .put("sync_protocol_version", 1)
                        .put("registered_at", "2026-01-01T00:00:00Z")
                        .put("revoked_at", JSONObject.NULL)
                        .put("revocation_reason", JSONObject.NULL)
                        .put("last_seen_at", JSONObject.NULL)
                )
            }
            if (url.contains("/api/v1/sync/changes")) {
                val vector = JSONArray().put(JSONObject().put("device_id", UUID.randomUUID().toString()).put("counter", 1))
                val encryption = JSONObject()
                    .put("format", E2eeSyncProtocol.FORMAT)
                    .put("cipher_suite", E2eeSyncProtocol.CIPHER_SUITE)
                    .put("padding_scheme", E2eeSyncProtocol.PADDING_SCHEME)
                    .put("key_id", "key-id-1")
                    .put("nonce_base64", "AAAAAAAAAAAAAAAA")
                val row = JSONObject()
                    .put("blob_id", UUID.randomUUID().toString())
                    .put("change_kind", "UPSERT")
                    .put("version_vector", vector)
                    .put("author_device_id", UUID.randomUUID().toString())
                    .put("change_sequence", 9)
                    .put("encryption", encryption)
                    .put("ciphertext_sha256", "0".repeat(64))
                    .put("ciphertext_size_bytes", 1024)
                    .put("committed_at", "2026-01-01T00:00:00Z")
                return E2eeSyncHttpResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("code", "SYNC_CHANGES_LISTED")
                        .put("request_id", "req")
                        .put("data", JSONArray().put(row))
                        .put(
                            "meta",
                            JSONObject()
                                .put("request_id", "req")
                                .put("api_version", "v1")
                                .put("has_more", false)
                                .put("next_cursor", "next-cursor-test")
                                .put("snapshot_epoch", 9)
                                .put("compaction_epoch", 2)
                        )
                        .toString()
                )
            }
            val blobMetadata = JSONObject()
                .put("blob_id", url.substringAfterLast('/'))
                .put("change_kind", "UPSERT")
                .put("version_vector", JSONArray())
                .put("author_device_id", UUID.randomUUID().toString())
                .put("change_sequence", 1)
                .put(
                    "encryption",
                    JSONObject()
                        .put("format", E2eeSyncProtocol.FORMAT)
                        .put("cipher_suite", E2eeSyncProtocol.CIPHER_SUITE)
                        .put("padding_scheme", E2eeSyncProtocol.PADDING_SCHEME)
                        .put("key_id", "key-id-1")
                        .put("nonce_base64", "AAAAAAAAAAAAAAAA")
                )
                .put("ciphertext_sha256", "0".repeat(64))
                .put("ciphertext_size_bytes", 1024)
                .put("committed_at", "2026-01-01T00:00:00Z")
            return ok(blobMetadata)
        }

        private fun ok(data: JSONObject): E2eeSyncHttpResponse = E2eeSyncHttpResponse(
            200,
            JSONObject().put("ok", true).put("code", "OK").put("request_id", "req").put("data", data).toString()
        )
    }
}
