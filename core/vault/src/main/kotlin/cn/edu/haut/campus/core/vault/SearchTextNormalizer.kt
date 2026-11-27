package cn.edu.haut.campus.core.vault

import java.text.Normalizer
import java.util.Locale

/**
 * R01 确定的写入/查询共用中文检索规范化器。
 *
 * 不依赖 SQLite ICU：CJK 连续段生成重叠 bigram，ASCII/数字/下划线保留为整词；
 * 写入索引与查询改写必须都经过本对象，避免两侧 drift。
 */
object SearchTextNormalizer {
    data class NormalizedText(
        val normalizedText: String,
        val tokens: List<String>
    )

    data class QueryRewrite(
        val normalizedQuery: String,
        val tokens: List<String>,
        val ftsQuery: String?,
        val singleCharFallbacks: List<String>
    ) {
        val isEmpty: Boolean get() = normalizedQuery.isBlank()
    }

    private enum class ScriptKind { CJK, WORD }

    private data class ScriptRun(
        val kind: ScriptKind,
        val text: String,
        val codePoints: List<String>
    )

    fun normalize(text: String): NormalizedText {
        val normalizedText = normalizePlain(text)
        return NormalizedText(
            normalizedText = normalizedText,
            tokens = tokenizeNormalized(normalizedText)
        )
    }

    fun normalizePlain(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val lowered = nfkc.lowercase(Locale.ROOT)
        val out = StringBuilder(lowered.length)
        var pendingSpace = false
        var index = 0
        while (index < lowered.length) {
            val codePoint = Character.codePointAt(lowered, index)
            index += Character.charCount(codePoint)
            when {
                isInvisible(codePoint) -> Unit
                isSeparator(codePoint) -> pendingSpace = out.isNotEmpty()
                else -> {
                    if (pendingSpace) {
                        out.append(' ')
                        pendingSpace = false
                    }
                    out.append(String(Character.toChars(codePoint)))
                }
            }
        }
        return out.toString()
    }

    fun tokenStreamFor(vararg parts: String?): String =
        parts.asSequence()
            .filterNotNull()
            .flatMap { normalize(it).tokens.asSequence() }
            .joinToString(" ")

    /**
     * 把用户输入改写为安全的 FTS4 MATCH 查询。
     *
     * 用户不获得 FTS 操作符语法：所有输入先经同一规范化；CJK bigram 用 phrase 保持顺序，
     * ASCII 查询末尾长词才使用 unicode61 prefix。单字 CJK 不进入 MATCH，由调用方走
     * normalizedText fallback，避免前缀误配。
     */
    fun rewriteQuery(query: String): QueryRewrite {
        val normalized = normalize(query)
        if (normalized.normalizedText.isBlank()) {
            return QueryRewrite(
                normalizedQuery = normalized.normalizedText,
                tokens = normalized.tokens,
                ftsQuery = null,
                singleCharFallbacks = emptyList()
            )
        }
        val fragments = mutableListOf<String>()
        val fallbacks = mutableListOf<String>()
        val units = normalized.normalizedText.split(' ').filter { it.isNotBlank() }
        units.forEachIndexed { unitIndex, unit ->
            val runs = splitScriptRuns(unit)
            runs.forEachIndexed { runIndex, run ->
                when (run.kind) {
                    ScriptKind.CJK -> {
                        if (run.codePoints.size == 1) {
                            fallbacks += run.text
                        } else {
                            val bigrams = overlappingBigrams(run.codePoints)
                            if (bigrams.size == 1) {
                                fragments += bigrams.first()
                            } else {
                                fragments += """ + bigrams.joinToString(" ") + """
                            }
                        }
                    }
                    ScriptKind.WORD -> {
                        val isQueryTail = unitIndex == units.lastIndex && runIndex == runs.lastIndex
                        fragments += if (isQueryTail && run.text.length >= 3) {
                            run.text + "*"
                        } else {
                            '"' + run.text + '"'
                        }
                    }
                }
            }
        }
        return QueryRewrite(
            normalizedQuery = normalized.normalizedText,
            tokens = normalized.tokens,
            ftsQuery = fragments.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            singleCharFallbacks = fallbacks.distinct()
        )
    }

    private fun tokenizeNormalized(normalizedText: String): List<String> {
        if (normalizedText.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        normalizedText.split(' ').filter { it.isNotBlank() }.forEach { unit ->
            splitScriptRuns(unit).forEach { run ->
                when (run.kind) {
                    ScriptKind.CJK -> {
                        if (run.codePoints.size == 1) {
                            tokens += run.text
                        } else {
                            tokens += overlappingBigrams(run.codePoints)
                        }
                    }
                    ScriptKind.WORD -> tokens += run.text
                }
            }
        }
        return tokens
    }

    private fun splitScriptRuns(unit: String): List<ScriptRun> {
        val parts = codePointStrings(unit)
        if (parts.isEmpty()) return emptyList()
        val runs = mutableListOf<ScriptRun>()
        var currentKind = scriptKind(parts.first())
        var currentParts = mutableListOf<String>()
        fun flush() {
            if (currentParts.isNotEmpty()) {
                runs += ScriptRun(currentKind, currentParts.joinToString(""), currentParts.toList())
            }
        }
        parts.forEach { part ->
            val kind = scriptKind(part)
            if (kind != currentKind) {
                flush()
                currentKind = kind
                currentParts = mutableListOf()
            }
            currentParts += part
        }
        flush()
        return runs
    }

    private fun overlappingBigrams(codePoints: List<String>): List<String> {
        if (codePoints.size < 2) return codePoints
        return codePoints.windowed(2, step = 1) { window -> window[0] + window[1] }
    }

    private fun codePointStrings(value: String): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val width = Character.charCount(codePoint)
            result += value.substring(index, index + width)
            index += width
        }
        return result
    }

    private fun scriptKind(codePointText: String): ScriptKind {
        val codePoint = codePointText.codePointAt(0)
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> ScriptKind.CJK
            else -> ScriptKind.WORD
        }
    }

    private fun isInvisible(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            codePoint == 0x200B ||
            codePoint == 0x200C ||
            codePoint == 0x200D ||
            codePoint == 0xFEFF
    }

    private fun isSeparator(codePoint: Int): Boolean {
        if (codePoint == '_'.code) return false
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return true
        return when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }
}
