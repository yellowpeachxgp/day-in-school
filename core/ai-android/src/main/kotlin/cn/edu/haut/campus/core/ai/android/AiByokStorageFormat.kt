package cn.edu.haut.campus.core.ai.android

import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.HttpsUrl
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** BYOK 密文 envelope 的纯二进制格式；endpoint 与 Key 同属一个 AES-GCM 认证载荷。 */
internal object AiByokStorageFormat {
    const val ENVELOPE_VERSION = 1
    const val IV_BYTES = 12
    const val MAX_KEY_BYTES = 16 * 1024
    const val MAX_ENDPOINT_BYTES = 4096
    const val MAX_ENVELOPE_BYTES = 4 + IV_BYTES + 16 + 32 + MAX_ENDPOINT_BYTES + MAX_KEY_BYTES

    fun associatedData(scope: SchoolAccountScope): ByteArray = encodeFields(
        "day-in-school-ai-byok-v1".toByteArray(StandardCharsets.UTF_8),
        scope.schoolId.value.toByteArray(StandardCharsets.UTF_8),
        scope.accountId.value.toByteArray(StandardCharsets.UTF_8),
        scope.profileId.value.toByteArray(StandardCharsets.UTF_8),
        (scope.campusId?.value ?: "").toByteArray(StandardCharsets.UTF_8)
    )

    fun scopeStorageKey(scope: SchoolAccountScope): String =
        MessageDigest.getInstance("SHA-256").digest(associatedData(scope)).joinToString("") { "%02x".format(it) }

    fun encodePayload(endpoint: AiEndpoint, keyBytes: ByteArray): ByteArray {
        require(keyBytes.size in 1..MAX_KEY_BYTES) { "AI Key 长度非法" }
        return encodeFields(
            endpoint.baseUrl.withoutTrailingSlash.toByteArray(StandardCharsets.UTF_8),
            endpoint.model.toByteArray(StandardCharsets.UTF_8),
            byteArrayOf(if (endpoint.supportsVision) 1 else 0),
            keyBytes
        )
    }

    fun decodePayload(payload: ByteArray): DecodedAiPayload {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val baseUrl = readField(input, MAX_ENDPOINT_BYTES)
        val model = readField(input, MAX_ENDPOINT_BYTES)
        val vision = readField(input, 1)
        val key = readField(input, MAX_KEY_BYTES)
        if (input.available() != 0) {
            baseUrl.fill(0)
            model.fill(0)
            vision.fill(0)
            key.fill(0)
            throw IllegalArgumentException("AI BYOK 载荷存在尾随字节")
        }
        try {
            val endpoint = AiEndpoint(
                baseUrl = HttpsUrl(String(baseUrl, StandardCharsets.UTF_8)),
                model = String(model, StandardCharsets.UTF_8),
                supportsVision = vision.single().toInt() == 1
            )
            return DecodedAiPayload(endpoint, key)
        } catch (error: Exception) {
            key.fill(0)
            throw error
        } finally {
            baseUrl.fill(0)
            model.fill(0)
            vision.fill(0)
        }
    }

    fun encodeFields(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            values.forEach { value ->
                data.writeInt(value.size)
                data.write(value)
            }
        }
        output.toByteArray()
    }

    private fun readField(input: DataInputStream, maxBytes: Int): ByteArray {
        val size = input.readInt()
        require(size in 1..maxBytes && size <= input.available()) { "AI BYOK 载荷字段非法" }
        return ByteArray(size).also(input::readFully)
    }
}

internal class DecodedAiPayload(
    val endpoint: AiEndpoint,
    val keyBytes: ByteArray
) {
    fun wipe() {
        keyBytes.fill(0)
    }
}
