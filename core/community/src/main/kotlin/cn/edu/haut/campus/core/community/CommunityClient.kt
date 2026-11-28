package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.Staleness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.Instant

/**
 * W4-05 手写契约客户端。operation 表与 contracts/openapi/cloud-v1.yaml 做单元测试比对；
 * data 保持契约 JSON，后续 feature 卡只在自己的边界做领域映射。
 */
class CommunityClient(
    baseUrl: String,
    private val scope: SchoolAccountScope,
    private val accessTokenProvider: suspend () -> String?,
    private val featureGate: CommunityFeatureGate,
    private val cacheStore: CommunityCacheStore? = null,
    private val transport: CommunityTransport = OkHttpCommunityTransport(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val normalizedBaseUrl = validateAndNormalizeBaseUrl(baseUrl)

    suspend fun execute(
        operation: CommunityOperation,
        pathParams: Map<String, String> = emptyMap(),
        queryParams: Map<String, List<String>> = emptyMap(),
        body: JsonValue.Obj? = null,
        idempotencyKey: String? = null,
    ): CommunityResult<CommunityEnvelope> {
        val decision = featureGate.decision(operation.feature)
        if (!decision.enabled) {
            return CommunityResult.Disabled(operation.feature, decision.reason ?: "community feature disabled")
        }
        val accessToken = accessTokenProvider().orEmpty()
        if (accessToken.isBlank()) return CommunityResult.AuthenticationRequired
        validateOperationShape(operation, pathParams, body, idempotencyKey)

        val resolvedPath = resolvePath(operation.pathTemplate, pathParams)
        val cacheKey = CommunityCacheKey(
            operationId = operation.operationId,
            resolvedPath = resolvedPath,
            queryParams = queryParams,
            body = if (operation.readOnly && operation.offlineCacheAllowed) body else null,
        )
        val url = normalizedBaseUrl + resolvedPath + encodeQuery(queryParams)
        val headers = linkedMapOf("Authorization" to "Bearer " + accessToken)
        if (operation.idempotencyRequired) {
            headers["Idempotency-Key"] = idempotencyKey.orEmpty()
        }
        val request = CommunityHttpRequest(
            method = operation.method,
            url = url,
            headers = headers,
            body = body?.let { Json.canonical(it) },
        )

        if (!operation.readOnly) {
            // 写请求即使网络失败也可能已在服务端提交（STORAGE_COMMIT_UNKNOWN 类竞态）。
            // 乐观丢弃本地快照最多造成一次重新拉取；保留旧快照则可能继续展示已退出成员或已撤回共享。
            runCatching { cacheStore?.invalidateAll() }
        }

        val response = try {
            transport.execute(request)
        } catch (error: IOException) {
            return cachedOrFailure(
                cacheKey = cacheKey,
                cacheable = operation.readOnly && operation.offlineCacheAllowed,
                fallbackReason = CommunityCacheFallbackReason.NETWORK_UNAVAILABLE,
                error = CommunityError(
                    kind = CommunityErrorKind.NETWORK,
                    code = "NETWORK_UNAVAILABLE",
                    message = "无法连接校园云服务，请检查网络后重试",
                    retryable = true,
                ),
            )
        }

        if (response.statusCode in 200..299) {
            val envelope = parseSuccessBody(response.body)
                ?: return CommunityResult.Failure(
                    CommunityError(
                        kind = CommunityErrorKind.CONTRACT,
                        code = "CONTRACT_RESPONSE_INVALID",
                        message = "校园云响应不符合成功 envelope 契约",
                        statusCode = response.statusCode,
                        retryable = false,
                    ),
                )
            if (operation.readOnly && operation.offlineCacheAllowed) {
                val store = cacheStore
                if (store != null) {
                    runCatching { store.write(cacheKey, envelope, clock.millis()) }
                }
            } else {
                // 写前已保守清空；这里重复一次以覆盖写前清理自身失败但写成功的少见路径。
                runCatching { cacheStore?.invalidateAll() }
            }
            return CommunityResult.Success(
                value = envelope,
                provenance = provenance(clock.millis()),
                staleness = Staleness.FRESH,
            )
        }

        if (response.statusCode == 401) return CommunityResult.AuthenticationRequired
        val error = parseErrorBody(response.statusCode, response.body)
        val serverUnavailable = response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500
        if (serverUnavailable) {
            return cachedOrFailure(
                cacheKey = cacheKey,
                cacheable = operation.readOnly && operation.offlineCacheAllowed,
                fallbackReason = CommunityCacheFallbackReason.SERVER_UNAVAILABLE,
                error = error,
            )
        }
        return CommunityResult.Failure(error)
    }

    private suspend fun cachedOrFailure(
        cacheKey: CommunityCacheKey,
        cacheable: Boolean,
        fallbackReason: CommunityCacheFallbackReason,
        error: CommunityError,
    ): CommunityResult<CommunityEnvelope> {
        if (cacheable) {
            val cached = runCatching { cacheStore?.read(cacheKey) }.getOrNull()
            if (cached != null) {
                return CommunityResult.Success(
                    value = cached.envelope,
                    provenance = provenance(cached.cachedAtEpochMillis),
                    staleness = Staleness.STALE,
                    cachedAtEpochMillis = cached.cachedAtEpochMillis,
                    cacheFallbackReason = fallbackReason,
                )
            }
        }
        return CommunityResult.Failure(error)
    }

    private fun provenance(fetchedAtEpochMillis: Long): DataProvenance =
        DataProvenance(
            providerId = "cloud-in-school",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "campus-cloud-community/v1",
            // 通用 envelope 客户端不能把 operationId 冒充为记录 ID；领域映射层拿到 data 后再填真实记录标识。
            sourceRecordId = null,
            fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
            completeness = SnapshotCompleteness.UNKNOWN,
            trust = VerificationLevel.SOURCE_CONFIRMED,
            schemaVersion = 1,
        )

    private fun parseSuccessBody(body: String): CommunityEnvelope? {
        val raw = runCatching { Json.parse(body).asObjOrNull }.getOrNull() ?: return null
        return parseCommunityEnvelope(raw)
    }

    private fun parseErrorBody(statusCode: Int, body: String): CommunityError {
        val root = runCatching { Json.parse(body).asObjOrNull }.getOrNull()
        val detail = root?.obj("error")?.asObjOrNull
        val code = detail?.str("code") ?: root?.str("code") ?: "HTTP_" + statusCode
        val message = detail?.str("message") ?: "校园云请求失败（HTTP " + statusCode + "）"
        val retryable = detail?.obj("retryable")?.boolOrNull()
            ?: (statusCode == 408 || statusCode == 429 || statusCode >= 500)
        return CommunityError(
            kind = CommunityErrorKind.HTTP,
            code = code,
            message = message,
            statusCode = statusCode,
            retryable = retryable,
            userAction = detail?.str("user_action"),
            detail = detail?.obj("detail")?.asObjOrNull,
        )
    }

    private fun validateOperationShape(
        operation: CommunityOperation,
        pathParams: Map<String, String>,
        body: JsonValue.Obj?,
        idempotencyKey: String?,
    ) {
        val requiredPathParams = Regex("""\{([^}]+)\}""").findAll(operation.pathTemplate).map { it.groupValues[1] }.toSet()
        val unexpected = pathParams.keys - requiredPathParams
        require(unexpected.isEmpty()) { "存在多余 path 参数：" + unexpected.joinToString(",") }
        for (name in requiredPathParams) {
            require(!pathParams[name].isNullOrBlank()) { "缺少 path 参数：" + name }
        }
        if (operation.requestSchema != null) {
            requireNotNull(body) { operation.operationId + " 需要 " + operation.requestSchema + " 请求体" }
        } else {
            require(body == null) { operation.operationId + " 不允许请求体" }
        }
        if (operation.idempotencyRequired) {
            require(!idempotencyKey.isNullOrBlank()) { operation.operationId + " 需要稳定 Idempotency-Key" }
            require(idempotencyKey.length in 8..128) { "Idempotency-Key 长度必须在 8..128" }
        }
    }

    private fun resolvePath(template: String, params: Map<String, String>): String {
        var resolved = template
        Regex("""\{([^}]+)\}""").findAll(template).forEach { match ->
            val name = match.groupValues[1]
            resolved = resolved.replace("{" + name + "}", percentEncode(checkNotNull(params[name])))
        }
        return resolved
    }

    private fun encodeQuery(queryParams: Map<String, List<String>>): String {
        val parts = mutableListOf<String>()
        queryParams.forEach { (name, values) ->
            if (values.isEmpty()) return@forEach
            require(name.isNotBlank() && !name.contains('&') && !name.contains('=')) { "非法 query 参数名" }
            val encodedValues = values.joinToString(",") { percentEncode(it) }
            parts += percentEncode(name) + "=" + encodedValues
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    companion object {
        fun validateAndNormalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "cloud baseUrl 不能为空" }
            val uri = runCatching { URI(trimmed) }.getOrElse { throw IllegalArgumentException("cloud baseUrl 非法", it) }
            val host = uri.host?.lowercase()
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
            require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
                "校园云客户端默认只允许 HTTPS；仅本机 loopback 测试可使用 HTTP"
            }
            require(uri.userInfo == null && uri.fragment == null && uri.query == null) {
                "cloud baseUrl 不得包含凭据、query 或 fragment"
            }
            return trimmed
        }

        internal fun percentEncode(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val out = StringBuilder()
            for (byte in bytes) {
                val unsigned = byte.toInt() and 0xff
                val ch = unsigned.toChar()
                val unreserved = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~'
                if (unreserved) {
                    out.append(ch)
                } else {
                    out.append('%').append("%02X".format(unsigned))
                }
            }
            return out.toString()
        }
    }
}

internal fun communityQueryParams(vararg pairs: Pair<String, Any?>): Map<String, List<String>> {
    val result = LinkedHashMap<String, List<String>>()
    pairs.forEach { (name, value) ->
        when (value) {
            null -> Unit
            is List<*> -> result[name] = value.map {
                requireNotNull(it) { name + " 不允许 null 数组项" }.toString()
            }
            is Array<*> -> result[name] = value.map {
                requireNotNull(it) { name + " 不允许 null 数组项" }.toString()
            }
            else -> result[name] = listOf(value.toString())
        }
    }
    return result
}
