package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * Timeline 信封的无损文本安全协议（W1-05）。
 *
 * TimelineEvent.title 与 sourceMetadata value 禁止 ISO 控制字符，但课程、datapack 等
 * 上游文本合法地允许换行/控制字符。协议分两层：信封标题只做安全投影；需要无损保留的
 * 原文按 canonical JSON 转义并分片进 sourceMetadata。任何字段装不进 64 条/4096 字符
 * 硬预算时显式 truncated，由调用侧用权威输入兜底，绝不允许投影 flow 崩溃或静默截断冒充原文。
 */
object TimelineTextSafety {
    private const val JSON_CHUNK_COUNT_SUFFIX = ".json.chunkCount"
    private const val JSON_TRUNCATED_SUFFIX = ".json.truncated"
    private const val JSON_CHUNK_PREFIX_SUFFIX = ".json.chunk."
    private const val METADATA_VALUE_LIMIT = 4096
    private const val METADATA_ENTRY_LIMIT = 64

    /**
     * TimelineEvent 信封标题的安全投影：控制字符替换为 U+FFFD、超长截断，
     * 空/全控制输入仍给非空占位。需要原文时必须同时使用 JSON 分片 metadata 或调用侧权威快照。
     */
    fun safeTimelineTitle(raw: String, blankFallback: String): String {
        require(blankFallback.isNotBlank() && blankFallback.length <= 256 && blankFallback.none { it.isISOControl() }) {
            "Timeline 标题占位文本必须本身合法"
        }
        val cleaned = buildString(raw.length) {
            raw.forEach { ch -> append(if (ch.isISOControl()) '\uFFFD' else ch) }
        }
        val nonBlank = cleaned.takeUnless { it.isBlank() } ?: blankFallback
        return if (nonBlank.length <= 256) nonBlank else nonBlank.take(255) + "…"
    }

    /** canonical JSON 只强制转义 C0；Timeline metadata 额外禁止 C1/DEL，这里补齐为 ASCII Unicode 转义。 */
    private fun metadataJsonString(raw: String): String {
        val canonical = Json.canonical(JsonValue.Str(raw))
        return buildString(canonical.length) {
            canonical.forEach { ch ->
                if (ch.isISOControl()) {
                    append("\\u" + ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }

    private fun isSimpleLosslessText(value: String): Boolean =
        value.none { it.isISOControl() } && value.length <= METADATA_VALUE_LIMIT

    /**
     * 把若干自由文本写入同一 sourceMetadata 预算。安全短文本放原 key；含控制字符或超长文本
     * 使用同一 JSON 转义分片协议。target 的既有条目计入 64 条硬预算，调用方不得绕开该入口手造分片。
     */
    fun encodeLosslessTextFields(target: MutableMap<String, String>, textInputs: List<Pair<String, String>>) {
        val encodedTextInputs = textInputs.filterNot { (_, value) -> isSimpleLosslessText(value) }
        textInputs.filter { (_, value) -> isSimpleLosslessText(value) }.forEach { (key, value) ->
            target[key] = value
        }
        encodedTextInputs.forEachIndexed { index, (baseKey, value) ->
            val encodedChunks = metadataJsonString(value).chunked(METADATA_VALUE_LIMIT)
            val remainingEncodedFields = encodedTextInputs.size - index - 1
            // count + truncated 是每字段最少控制开销；为后续字段也保留最低控制开销。
            val reservedControlEntries = 2 + remainingEncodedFields * 2
            val availableChunkCount = (METADATA_ENTRY_LIMIT - target.size - reservedControlEntries).coerceAtLeast(0)
            val storedChunks = encodedChunks.take(availableChunkCount)
            target[baseKey + JSON_CHUNK_COUNT_SUFFIX] = storedChunks.size.toString()
            storedChunks.forEachIndexed { chunkIndex, chunk ->
                target[baseKey + JSON_CHUNK_PREFIX_SUFFIX + chunkIndex] = chunk
            }
            if (storedChunks.size < encodedChunks.size) {
                target[baseKey + JSON_TRUNCATED_SUFFIX] = "true"
            }
        }
    }

    /** 读取统一信封；完整 JSON 分片逐字还原，安全短文本按原 key 直读，截断/残缺返回 null 让调用侧兜底。 */
    fun decodeLosslessText(metadata: Map<String, String>, baseKey: String): String? {
        val chunkCountText = metadata[baseKey + JSON_CHUNK_COUNT_SUFFIX] ?: return metadata[baseKey]
        if (metadata[baseKey + JSON_TRUNCATED_SUFFIX] == "true") return null
        val chunkCount = chunkCountText.toIntOrNull() ?: return null
        if (chunkCount < 0) return null
        val parts = (0 until chunkCount).map { index ->
            metadata[baseKey + JSON_CHUNK_PREFIX_SUFFIX + index] ?: return null
        }
        return runCatching {
            Json.parse(parts.joinToString("")).stringOrNull()
        }.getOrNull()
    }

    fun hasTruncatedLosslessText(metadata: Map<String, String>): Boolean =
        metadata.keys.any { it.endsWith(JSON_TRUNCATED_SUFFIX) && metadata[it] == "true" }
}
