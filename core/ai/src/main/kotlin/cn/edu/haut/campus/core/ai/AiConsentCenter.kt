package cn.edu.haut.campus.core.ai

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Digests
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class AiConsentVerdict {
    ACCEPTED,
    INVALID,
    EXPIRED,
    CONSUMED,
    MISMATCH
}

/**
 * 进程内单次同意中心。token 只在预览页用户点击“发送”后 issue；
 * send 原子 consume，拒绝重放、跨 Profile、跨 endpoint 或请求内容被改写的 token。
 * 进程死亡后 token 全部失效，这是刻意的 fail-closed 语义。
 */
class AiConsentCenter(
    private val clock: () -> Instant = Instant::now,
    private val ttl: Duration = Duration.ofMinutes(2),
    private val random: SecureRandom = SecureRandom()
) {
    private data class Entry(val requestFingerprint: String, val expiresAt: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        require(!ttl.isNegative && !ttl.isZero && ttl.seconds <= 600) { "AI 同意有效期必须在 1 秒到 10 分钟之间" }
    }

    fun issue(scope: SchoolAccountScope, endpoint: AiEndpoint, preview: AiSendPreview): AiConsentToken {
        val fingerprint = fingerprint(scope, endpoint, preview)
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val tokenId = "consent-" + bytes.joinToString("") { "%02x".format(it) }
        val expiresAt = clock().plus(ttl)
        entries[tokenId] = Entry(fingerprint, expiresAt)
        return AiConsentToken(
            tokenId = tokenId,
            scopeFingerprint = scopeFingerprint(scope),
            requestFingerprint = fingerprint,
            expiresAt = expiresAt
        )
    }

    fun consume(
        scope: SchoolAccountScope,
        endpoint: AiEndpoint,
        preview: AiSendPreview,
        token: AiConsentToken
    ): AiConsentVerdict {
        val now = clock()
        if (!now.isBefore(token.expiresAt)) {
            entries.remove(token.tokenId)
            return AiConsentVerdict.EXPIRED
        }
        val expected = fingerprint(scope, endpoint, preview)
        if (token.scopeFingerprint != scopeFingerprint(scope) || token.requestFingerprint != expected) {
            entries.remove(token.tokenId)
            return AiConsentVerdict.MISMATCH
        }
        val entry = entries.remove(token.tokenId) ?: return AiConsentVerdict.CONSUMED
        if (!now.isBefore(entry.expiresAt)) return AiConsentVerdict.EXPIRED
        return if (entry.requestFingerprint == expected) AiConsentVerdict.ACCEPTED else AiConsentVerdict.MISMATCH
    }

    companion object {
        fun scopeFingerprint(scope: SchoolAccountScope): String = Digests.sha256Hex(
            "ai-scope-v1",
            scope.schoolId.value,
            scope.accountId.value,
            scope.profileId.value,
            scope.campusId?.value.orEmpty()
        )

        fun fingerprint(scope: SchoolAccountScope, endpoint: AiEndpoint, preview: AiSendPreview): String {
            val parts = buildList {
                add("ai-consent-preview-v1")
                add(scopeFingerprint(scope))
                add(endpoint.baseUrl.withoutTrailingSlash)
                add(endpoint.model)
                add(endpoint.supportsVision.toString())
                add(endpoint.host)
                add(preview.purpose.name)
                add(preview.maxTokens.toString())
                add(preview.textChars.toString())
                add(preview.attachmentCount.toString())
                preview.attachmentSha256.forEach(::add)
                preview.redactions.forEach { redaction ->
                    add(redaction.kind.name)
                    add(redaction.count.toString())
                }
                preview.outboundMessages.forEach { message ->
                    add(message.role.name)
                    add(message.text)
                }
            }
            return Digests.sha256Hex(*parts.toTypedArray())
        }
    }
}
