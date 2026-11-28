package cn.edu.haut.campus.core.vault.sync

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class E2eeSyncHttpResponse(
    val status: Int,
    val body: String
)

interface E2eeSyncHttpTransport {
    suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>
    ): E2eeSyncHttpResponse
}

fun interface E2eeSyncAccessTokenProvider {
    suspend fun accessToken(forceRefresh: Boolean, failedAccessToken: String?): String
}

/** 只允许 HTTPS 的最小 HTTP transport；不写请求/响应日志，避免把密文包大小之外的调试信息扩散到日志系统。 */
class HttpsE2eeSyncHttpTransport(
    connectTimeoutMs: Int = 15_000,
    readTimeoutMs: Int = 30_000
) : E2eeSyncHttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>
    ): E2eeSyncHttpResponse = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrElse { throw E2eeSyncException("云端同步地址非法", it) }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw E2eeSyncException("E2EE 云同步只允许 HTTPS 云端地址")
        }
        val requestBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(uri.toURL())
            .method(method.uppercase(), requestBody)
            .apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
                if (body != null) addHeader("Content-Type", "application/json; charset=utf-8")
            }
            .build()
        client.newCall(request).execute().use { response ->
            E2eeSyncHttpResponse(
                status = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }
}

/** CT-07 HTTPS client。它序列化/解析的只能是契约字段； plaintext payload 在进入本层前必须已经加密。 */
class HttpE2eeSyncApi(
    private val cloudBaseUrl: String,
    private val tokenProvider: E2eeSyncAccessTokenProvider,
    private val syncDeviceIdProvider: () -> String?,
    private val transport: E2eeSyncHttpTransport = HttpsE2eeSyncHttpTransport()
) : E2eeSyncApi {
    private val base = cloudBaseUrl.trimEnd('/')

    override suspend fun grantConsent(): SyncConsentRecord {
        val body = JSONObject()
            .put("purpose", E2eeSyncProtocol.CONSENT_PURPOSE)
            .put("document_version", E2eeSyncProtocol.CONSENT_DOCUMENT_VERSION)
            .put("document_sha256", E2eeSyncProtocol.CONSENT_DOCUMENT_SHA256)
            .put("school_id", "user")
        val data = request("POST", "/api/v1/me/consents", body, syncHeader = false).getJSONObject("data")
        return SyncConsentRecord(
            id = data.getString("id"),
            purpose = data.optString("purpose"),
            documentVersion = data.optString("document_version"),
            documentSha256 = data.optString("document_sha256")
        ).also {
            require(it.purpose == E2eeSyncProtocol.CONSENT_PURPOSE) { "云端返回的 E2EE consent purpose 不匹配" }
        }
    }

    override suspend fun withdrawConsent(consentId: String) {
        request("DELETE", "/api/v1/me/consents/" + encodePath(consentId), null, syncHeader = false)
    }

    override suspend fun listDevices(): List<SyncDevice> {
        val data = request("GET", "/api/v1/sync/devices", null, syncHeader = false).getJSONArray("data")
        val out = mutableListOf<SyncDevice>()
        for (i in 0 until data.length()) out += parseDevice(data.getJSONObject(i))
        return out
    }

    override suspend fun registerDevice(
        deviceId: String,
        consentId: String,
        deviceLabel: String?,
        idempotencyKey: String
    ): SyncDevice {
        val body = JSONObject()
            .put("consent_id", consentId)
            .put("platform", "ANDROID")
            .put("sync_protocol_version", E2eeSyncProtocol.SYNC_PROTOCOL_VERSION)
        if (!deviceLabel.isNullOrBlank()) body.put("device_label", deviceLabel.trim().take(80))
        val data = request(
            "PUT",
            "/api/v1/sync/devices/" + encodePath(deviceId),
            body,
            syncHeader = false,
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey)
        ).getJSONObject("data")
        return parseDevice(data)
    }

    override suspend fun revokeDevice(deviceId: String, reason: String): SyncDevice {
        val data = request(
            "DELETE",
            "/api/v1/sync/devices/" + encodePath(deviceId),
            JSONObject().put("reason", reason),
            syncHeader = false
        ).getJSONObject("data")
        return parseDevice(data)
    }

    override suspend fun putBlob(blobId: String, request: SyncBlobPutInput): SyncBlobMetadata {
        val body = JSONObject()
            .put("encryption", encryptionToJson(request.encryption))
            .put("ciphertext_base64", request.ciphertextBase64)
            .put("ciphertext_sha256", request.ciphertextSha256)
            .put("base_vector", vectorToJson(request.baseVector))
            .put("writer_counter", request.writerCounter)
        val data = request(
            "PUT",
            "/api/v1/sync/blobs/" + encodePath(blobId),
            body,
            syncHeader = true,
            extraHeaders = mapOf("Idempotency-Key" to request.idempotencyKey)
        ).getJSONObject("data")
        return parseMetadata(data)
    }

    override suspend fun downloadBlob(blobId: String): Pair<SyncBlobMetadata, String> {
        val data = request("GET", "/api/v1/sync/blobs/" + encodePath(blobId), null, syncHeader = true).getJSONObject("data")
        return parseMetadata(data.getJSONObject("metadata")) to data.getString("ciphertext_base64")
    }

    override suspend fun deleteBlob(blobId: String, request: SyncBlobDeleteInput): SyncBlobMetadata {
        val body = JSONObject()
            .put("base_vector", vectorToJson(request.baseVector))
            .put("writer_counter", request.writerCounter)
        val data = request(
            "DELETE",
            "/api/v1/sync/blobs/" + encodePath(blobId),
            body,
            syncHeader = true,
            extraHeaders = mapOf("Idempotency-Key" to request.idempotencyKey)
        ).getJSONObject("data")
        return parseMetadata(data)
    }

    override suspend fun listChanges(cursor: String?, limit: Int): SyncChangePage {
        val query = StringBuilder("limit=").append(limit.coerceIn(1, E2eeSyncProtocol.MAX_CHANGE_PAGE_LIMIT))
        if (!cursor.isNullOrBlank()) query.append("&cursor=").append(encodeQuery(cursor))
        val root = request("GET", "/api/v1/sync/changes?" + query, null, syncHeader = true)
        val rows = mutableListOf<SyncBlobMetadata>()
        val data = root.getJSONArray("data")
        for (i in 0 until data.length()) rows += parseMetadata(data.getJSONObject(i))
        val meta = root.getJSONObject("meta")
        return SyncChangePage(
            rows = rows,
            hasMore = meta.getBoolean("has_more"),
            nextCursor = meta.getString("next_cursor"),
            snapshotEpoch = meta.getLong("snapshot_epoch"),
            compactionEpoch = meta.getLong("compaction_epoch")
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
        syncHeader: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
        retryOnUnauthorized: Boolean = true
    ): JSONObject {
        var token = tokenProvider.accessToken(false, null)
        if (token.isBlank()) throw E2eeSyncApiException(401, "CLOUD_AUTH_REQUIRED", "云端会话不可用，请先登录 XPeach Cloud")
        var response = perform(method, path, body, token, syncHeader, extraHeaders)
        if (response.status == 401 && retryOnUnauthorized) {
            val refreshed = tokenProvider.accessToken(true, token)
            if (refreshed.isNotBlank() && refreshed != token) {
                token = refreshed
                response = perform(method, path, body, token, syncHeader, extraHeaders)
            }
        }
        val root = runCatching { if (response.body.isBlank()) JSONObject() else JSONObject(response.body) }.getOrElse {
            throw E2eeSyncApiException(response.status, "BAD_RESPONSE", "云端同步响应不是合法 JSON")
        }
        if (response.status !in 200..299 || !root.optBoolean("ok", false)) {
            val error = root.optJSONObject("error")
            val detail = error?.opt("detail")?.toString()
            throw E2eeSyncApiException(
                httpStatus = response.status,
                errorCode = error?.optString("code")?.ifBlank { "HTTP_" + response.status } ?: ("HTTP_" + response.status),
                message = error?.optString("message")?.ifBlank { "云端同步请求失败" } ?: "云端同步请求失败",
                detail = detail
            )
        }
        return root
    }

    private suspend fun perform(
        method: String,
        path: String,
        body: JSONObject?,
        token: String,
        syncHeader: Boolean,
        extraHeaders: Map<String, String>
    ): E2eeSyncHttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer " + token
        headers["Accept"] = "application/json"
        if (syncHeader) {
            val deviceId = syncDeviceIdProvider()?.takeIf { it.isNotBlank() }
                ?: throw E2eeSyncApiException(412, "SYNC_DEVICE_NOT_FOUND", "本机尚未注册同步设备")
            headers["X-Dis-Sync-Device-Id"] = deviceId
        }
        headers.putAll(extraHeaders)
        return try {
            transport.request(method, base + path, body?.toString(), headers)
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw E2eeSyncException("云端同步网络请求失败", error)
        }
    }

    private fun parseDevice(obj: JSONObject): SyncDevice = SyncDevice(
        deviceId = obj.getString("device_id"),
        status = obj.getString("status"),
        deviceLabel = obj.optNullableString("device_label"),
        platform = obj.getString("platform"),
        syncProtocolVersion = obj.getInt("sync_protocol_version"),
        registeredAt = obj.getString("registered_at"),
        revokedAt = obj.optNullableString("revoked_at"),
        revocationReason = obj.optNullableString("revocation_reason"),
        lastSeenAt = obj.optNullableString("last_seen_at")
    )

    private fun parseMetadata(obj: JSONObject): SyncBlobMetadata {
        val vector = parseVector(obj.getJSONArray("version_vector"))
        val encryption = obj.optJSONObject("encryption")?.let { enc ->
            SyncEncryptionEnvelope(
                format = enc.getString("format"),
                cipherSuite = enc.getString("cipher_suite"),
                paddingScheme = enc.getString("padding_scheme"),
                keyId = enc.getString("key_id"),
                nonceBase64 = enc.getString("nonce_base64")
            )
        }
        return SyncBlobMetadata(
            blobId = obj.getString("blob_id"),
            changeKind = obj.getString("change_kind"),
            versionVector = vector,
            authorDeviceId = obj.getString("author_device_id"),
            changeSequence = obj.getLong("change_sequence"),
            encryption = encryption,
            ciphertextSha256 = obj.optNullableString("ciphertext_sha256"),
            ciphertextSizeBytes = if (obj.isNull("ciphertext_size_bytes")) null else obj.getInt("ciphertext_size_bytes"),
            committedAt = obj.getString("committed_at")
        )
    }

    private fun encryptionToJson(value: SyncEncryptionEnvelope): JSONObject = JSONObject()
        .put("format", value.format)
        .put("cipher_suite", value.cipherSuite)
        .put("padding_scheme", value.paddingScheme)
        .put("key_id", value.keyId)
        .put("nonce_base64", value.nonceBase64)

    private fun vectorToJson(vector: SyncVersionVector): JSONArray {
        val array = JSONArray()
        SyncVectors.canonical(vector).forEach {
            array.put(JSONObject().put("device_id", it.deviceId).put("counter", it.counter))
        }
        return array
    }

    private fun parseVector(array: JSONArray): SyncVersionVector {
        val entries = mutableListOf<SyncVersionVectorEntry>()
        val rawIds = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getString("device_id")
            rawIds += id.lowercase()
            entries += SyncVersionVectorEntry(id, item.getInt("counter"))
        }
        if (rawIds != rawIds.sorted()) throw E2eeSyncApiException(422, "SYNC_VECTOR_INVALID", "云端 version vector 未按 UUID 字典序序列化")
        return SyncVectors.canonical(entries)
    }

    private fun encodePath(value: String): String = encodeQuery(value)

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
