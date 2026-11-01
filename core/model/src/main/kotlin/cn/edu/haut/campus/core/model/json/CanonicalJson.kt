package cn.edu.haut.campus.core.model.json

import java.math.BigDecimal
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * 独立于 Android 的最小 JSON 值模型。协议解析、学校配置包与签名目录共用同一实现，
 * 避免不同模块对数字、转义、键顺序产生不一致的规范化结果。
 */
sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val literal: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    val asObjOrNull: Obj? get() = this as? Obj
    val asArrOrNull: Arr? get() = this as? Arr

    fun stringOrNull(): String? = (this as? Str)?.value
    fun intOrNull(): Int? = (this as? Num)?.literal?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    fun boolOrNull(): Boolean? = (this as? Bool)?.value
    fun obj(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)
    fun str(key: String): String? = obj(key)?.stringOrNull()
    fun arr(key: String): List<JsonValue>? = obj(key)?.asArrOrNull?.items
}

class JsonParseException(message: String, val offset: Int = -1) : IllegalArgumentException(message)

/** 严格、可规范化、无第三方依赖的 JSON 编解码。 */
object Json {
    const val MAX_INPUT_BYTES: Int = 8 * 1024 * 1024
    const val MAX_DEPTH: Int = 64
    private const val MAX_NUMBER_LENGTH: Int = 64

    fun parse(text: String): JsonValue {
        require(text.length <= MAX_INPUT_BYTES) { "JSON 超出允许长度" }
        return Parser(text).parseDocument()
    }

    /**
     * 规范形式：对象键按 Unicode 码点排序、无多余空白、数字经 BigDecimal 归一化。
     * 用于内容摘要与签名范围，保证不同来源的同义 JSON 得到同一摘要。
     */
    fun canonical(value: JsonValue): String = StringBuilder().also { appendCanonical(it, value) }.toString()

    fun canonicalDigest(value: JsonValue): String =
        Digests.sha256Hex(canonical(value).toByteArray(StandardCharsets.UTF_8))

    private fun appendCanonical(out: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    appendString(out, key)
                    out.append(':')
                    appendCanonical(out, value.fields.getValue(key))
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendCanonical(out, item)
                }
                out.append(']')
            }
            is JsonValue.Str -> appendString(out, value.value)
            is JsonValue.Num -> out.append(normalizeNumber(value.literal))
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    private fun appendString(out: StringBuilder, raw: String) {
        out.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private fun normalizeNumber(literal: String): String {
        if (literal.length > MAX_NUMBER_LENGTH) throw JsonParseException("数字超出允许长度")
        val value = BigDecimal(literal).stripTrailingZeros()
        if (value.scale() <= 0) {
            val plain = value.toBigInteger().toString()
            if (plain.length > MAX_NUMBER_LENGTH) throw JsonParseException("数字归一化后超出允许长度")
            return plain
        }
        val plain = value.toPlainString()
        if (plain.length > MAX_NUMBER_LENGTH) throw JsonParseException("数字归一化后超出允许长度")
        return plain
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) fail("文档末尾存在多余内容")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("JSON 嵌套过深")
            if (index >= text.length) fail("JSON 意外结束")
            return when (val ch = text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> { expectLiteral("true"); JsonValue.Bool(true) }
                'f' -> { expectLiteral("false"); JsonValue.Bool(false) }
                'n' -> { expectLiteral("null"); JsonValue.Null }
                else -> if (ch == '-' || ch.isDigit()) parseNumber() else fail("非法 JSON 字符")
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            index++
            skipWhitespace()
            val fields = LinkedHashMap<String, JsonValue>()
            if (peek() == '}') { index++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("对象键后缺少冒号")
                index++
                skipWhitespace()
                val value = parseValue(depth + 1)
                if (fields.put(key, value) != null) fail("JSON 对象包含重复键")
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; skipWhitespace(); if (peek() == '}') fail("对象存在尾随逗号") }
                    '}' -> { index++; return JsonValue.Obj(fields) }
                    else -> fail("对象缺少分隔符")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            index++
            skipWhitespace()
            val items = ArrayList<JsonValue>()
            if (peek() == ']') { index++; return JsonValue.Arr(items) }
            while (true) {
                skipWhitespace()
                items += parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> { index++; skipWhitespace(); if (peek() == ']') fail("数组存在尾随逗号") }
                    ']' -> { index++; return JsonValue.Arr(items) }
                    else -> fail("数组缺少分隔符")
                }
            }
        }

        private fun parseString(): String {
            index++
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) fail("字符串未闭合")
                when (val ch = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= text.length) fail("转义序列未完成")
                        when (val escaped = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("Unicode 转义未完成")
                                val hex = text.substring(index, index + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Unicode 转义非法")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> fail("非法转义字符")
                        }
                    }
                    else -> {
                        if (ch < ' ') fail("字符串包含未转义控制字符")
                        builder.append(ch)
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length || !text[index].isDigit()) fail("数字缺少整数部分")
            if (text[index] == '0' && index + 1 < text.length && text[index + 1].isDigit()) fail("数字存在前导零")
            while (index < text.length && text[index].isDigit()) index++
            if (index < text.length && text[index] == '.') {
                index++
                if (index >= text.length || !text[index].isDigit()) fail("数字缺少小数部分")
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || !text[index].isDigit()) fail("数字指数字段非法")
                while (index < text.length && text[index].isDigit()) index++
            }
            val literal = text.substring(start, index)
            if (literal.length > MAX_NUMBER_LENGTH) fail("数字超出允许长度")
            return JsonValue.Num(literal)
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) fail("非法字面量")
            index += literal.length
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) index++
        }

        private fun peek(): Char {
            if (index >= text.length) fail("JSON 意外结束")
            return text[index]
        }

        private fun fail(message: String): Nothing = throw JsonParseException(message, index)
    }
}

object Digests {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    fun sha256Hex(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
