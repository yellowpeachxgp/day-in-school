package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Parsed JSON plus its exact RFC-8785 canonical UTF-8 bytes. */
data class Rfc8785Document(
    val value: JsonValue,
    val canonicalText: String,
    val canonicalBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is Rfc8785Document && value == other.value && canonicalText == other.canonicalText && canonicalBytes.contentEquals(other.canonicalBytes)

    override fun hashCode(): Int = 31 * value.hashCode() + canonicalBytes.contentHashCode()
}

/**
 * RFC-8785/JCS canonical JSON used by dis-datapack/v1.
 *
 * This deliberately does not reuse core.model Json.canonical: that older helper normalizes numbers
 * through BigDecimal, while JCS requires IEEE-754 double serialization using ECMAScript Number::toString.
 */
object Rfc8785Json {
    const val MAX_ARTIFACT_BYTES: Int = 16 * 1024 * 1024
    const val MAX_DEPTH: Int = 64

    fun parseExactCanonical(bytes: ByteArray): Rfc8785Document {
        if (bytes.isEmpty()) throw datapackInvalid("dis-datapack/v1 内容为空")
        if (bytes.size > MAX_ARTIFACT_BYTES) {
            throw datapackInvalid("dis-datapack/v1 超过 16 MiB 上限", RUNTIME_DATAPACK_SCHEMA_INVALID)
        }
        val text = strictUtf8Decode(bytes)
        val value = try {
            Parser(text).parseDocument()
        } catch (error: PackageException) {
            throw error
        } catch (error: Exception) {
            throw datapackInvalid("dis-datapack/v1 JSON 解析失败", RUNTIME_DATAPACK_INVALID)
        }
        val canonicalText = canonicalize(value)
        val canonicalBytes = canonicalText.toByteArray(StandardCharsets.UTF_8)
        if (!canonicalBytes.contentEquals(bytes)) {
            throw datapackInvalid(
                "dis-datapack/v1 必须逐字节等于 RFC-8785 规范形式",
                RUNTIME_DATAPACK_EXACT_BYTES
            )
        }
        return Rfc8785Document(value, canonicalText, canonicalBytes)
    }

    fun strictUtf8Decode(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw datapackInvalid("dis-datapack/v1 不是合法 UTF-8", RUNTIME_DATAPACK_INVALID_UTF8)
        }
    }

    fun canonicalize(value: JsonValue): String = StringBuilder().also { appendCanonical(it, value, 0) }.toString()

    private fun appendCanonical(out: StringBuilder, value: JsonValue, depth: Int) {
        if (depth > MAX_DEPTH) throw datapackInvalid("dis-datapack/v1 JSON 嵌套过深", RUNTIME_DATAPACK_INVALID)
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> {
                val number = try {
                    value.literal.toDouble()
                } catch (error: Exception) {
                    throw datapackInvalid("dis-datapack/v1 数字不是合法 IEEE-754 double", RUNTIME_DATAPACK_NON_FINITE_NUMBER)
                }
                out.append(EcmaScriptNumberSerializer.serialize(number))
            }
            is JsonValue.Str -> appendString(out, value.value)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendCanonical(out, item, depth + 1)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    assertValidUnicode(key)
                    appendString(out, key)
                    out.append(':')
                    appendCanonical(out, value.fields.getValue(key), depth + 1)
                }
                out.append('}')
            }
        }
    }

    private fun appendString(out: StringBuilder, raw: String) {
        assertValidUnicode(raw)
        out.append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\u000C' -> out.append("\\f")
                '\r' -> out.append("\\r")
                else -> if (ch < ' ') {
                    out.append("\\u")
                    val code = ch.code
                    for (shift in intArrayOf(12, 8, 4, 0)) {
                        out.append("0123456789abcdef"[(code shr shift) and 0xF])
                    }
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private fun assertValidUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            when {
                code in 0xD800..0xDBFF -> {
                    val next = if (index + 1 < value.length) value[index + 1].code else 0
                    if (next !in 0xDC00..0xDFFF) {
                        throw datapackInvalid("dis-datapack/v1 字符串包含孤立高代理项", RUNTIME_DATAPACK_INVALID_UNICODE)
                    }
                    index += 2
                }
                code in 0xDC00..0xDFFF -> {
                    throw datapackInvalid("dis-datapack/v1 字符串包含孤立低代理项", RUNTIME_DATAPACK_INVALID_UNICODE)
                }
                else -> index += 1
            }
        }
    }

    private class Parser(private val text: String) {
        private var index: Int = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) fail("JSON 文档存在尾随内容")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("JSON 嵌套过深")
            if (index >= text.length) fail("JSON 意外结束")
            return when (val ch = text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> {
                    expectLiteral("true")
                    JsonValue.Bool(true)
                }
                'f' -> {
                    expectLiteral("false")
                    JsonValue.Bool(false)
                }
                'n' -> {
                    expectLiteral("null")
                    JsonValue.Null
                }
                '-', in '0'..'9' -> parseNumber()
                else -> fail("非法 JSON 起始字符: " + ch)
            }
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            index++
            skipWhitespace()
            val fields = LinkedHashMap<String, JsonValue>()
            if (consume('}')) return JsonValue.Obj(fields)
            while (true) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') fail("对象键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (!consume(':')) fail("对象键后缺少冒号")
                skipWhitespace()
                val value = parseValue(depth + 1)
                if (fields.put(key, value) != null) fail("对象包含重复键")
                skipWhitespace()
                when {
                    consume(',') -> {
                        skipWhitespace()
                        if (index < text.length && text[index] == '}') fail("对象存在尾随逗号")
                    }
                    consume('}') -> return JsonValue.Obj(fields)
                    else -> fail("对象缺少逗号或结束括号")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            index++
            skipWhitespace()
            val values = ArrayList<JsonValue>()
            if (consume(']')) return JsonValue.Arr(values)
            while (true) {
                skipWhitespace()
                values += parseValue(depth + 1)
                skipWhitespace()
                when {
                    consume(',') -> {
                        skipWhitespace()
                        if (index < text.length && text[index] == ']') fail("数组存在尾随逗号")
                    }
                    consume(']') -> return JsonValue.Arr(values)
                    else -> fail("数组缺少逗号或结束括号")
                }
            }
        }

        private fun parseString(): String {
            if (!consume('"')) fail("字符串缺少起始引号")
            val out = StringBuilder()
            while (index < text.length) {
                when (val ch = text[index++]) {
                    '"' -> {
                        val result = out.toString()
                        assertValidUnicode(result)
                        return result
                    }
                    '\\' -> out.append(parseEscape())
                    else -> {
                        if (ch < ' ') fail("字符串包含未转义控制字符")
                        out.append(ch)
                    }
                }
            }
            fail("字符串未闭合")
        }

        private fun parseEscape(): String {
            if (index >= text.length) fail("转义序列意外结束")
            return when (val escaped = text[index++]) {
                '"' -> "\""
                '\\' -> "\\"
                '/' -> "/"
                'b' -> "\b"
                'f' -> "\u000C"
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                'u' -> parseUnicodeEscape()
                else -> fail("不支持的转义字符: " + escaped)
            }
        }

        private fun parseUnicodeEscape(): String {
            if (index + 4 > text.length) fail("Unicode 转义未完成")
            val hex = text.substring(index, index + 4)
            val code = hex.toIntOrNull(16) ?: fail("Unicode 转义非法")
            index += 4
            return code.toChar().toString()
        }

        private fun parseNumber(): JsonValue.Num {
            val start = index
            if (text[index] == '-') index++
            if (index >= text.length || text[index] !in '0'..'9') fail("数字缺少整数部分")
            if (text[index] == '0') {
                index++
                if (index < text.length && text[index] in '0'..'9') fail("数字存在前导零")
            } else {
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && text[index] == '.') {
                index++
                if (index >= text.length || text[index] !in '0'..'9') fail("数字缺少小数部分")
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || text[index] !in '0'..'9') fail("数字指数字段非法")
                while (index < text.length && text[index] in '0'..'9') index++
            }
            return JsonValue.Num(text.substring(start, index))
        }

        private fun expectLiteral(literal: String) {
            if (!text.startsWith(literal, index)) fail("非法 JSON 字面量")
            index += literal.length
        }

        private fun consume(expected: Char): Boolean {
            if (index < text.length && text[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (index < text.length && (text[index] == ' ' || text[index] == '\n' || text[index] == '\r' || text[index] == '\t')) index++
        }

        private fun fail(message: String): Nothing = throw datapackInvalid("dis-datapack/v1 JSON 解析失败：" + message, RUNTIME_DATAPACK_INVALID)
    }
}

/** ECMAScript Number::toString-compatible finite IEEE-754 serializer required by RFC 8785. */
object EcmaScriptNumberSerializer {
    private data class Candidate(val distance: BigDecimal, val significand: BigInteger)

    fun serialize(value: Double): String {
        if (value.isNaN() || value.isInfinite()) {
            throw datapackInvalid("dis-datapack/v1 数字不是有限 IEEE-754 double", RUNTIME_DATAPACK_NON_FINITE_NUMBER)
        }
        if (value == 0.0) return "0"
        val sign = if (value < 0) "-" else ""
        val magnitude = kotlin.math.abs(value)
        val exact = BigDecimal(magnitude)
        val normalized = exact.stripTrailingZeros()
        val initialExponent = normalized.precision() - normalized.scale()

        for (digitCount in 1..17) {
            val scaled = exact.scaleByPowerOfTen(digitCount - initialExponent)
            val floor = scaled.setScale(0, RoundingMode.FLOOR).toBigInteger()
            val candidates = ArrayList<Candidate>()
            for (delta in -3L..3L) {
                val significand = floor.add(BigInteger.valueOf(delta))
                if (significand.signum() <= 0) continue
                val roundTrips = try {
                    (significand.toString() + "e" + (initialExponent - digitCount)).toDouble() == magnitude
                } catch (error: NumberFormatException) {
                    false
                }
                if (!roundTrips) continue
                candidates += Candidate(BigDecimal(significand).subtract(scaled).abs(), significand)
            }
            if (candidates.isEmpty()) continue
            val bestDistance = candidates.minOf { it.distance }
            val closest = candidates.filter { it.distance == bestDistance }.map { it.significand }
            val even = closest.filter { it.mod(BigInteger.valueOf(2)) == BigInteger.ZERO }
            val significand = (even.firstOrNull() ?: closest.first()).toString()
            val digitCountBeforeStrip = significand.length
            val exponent = initialExponent + digitCountBeforeStrip - digitCount
            val digits = significand.trimEnd('0').ifEmpty { "0" }
            return sign + formatDigits(digits, exponent)
        }
        throw datapackInvalid("无法为 IEEE-754 double 生成 RFC-8785 数字形式", RUNTIME_DATAPACK_NON_FINITE_NUMBER)
    }

    private fun formatDigits(digits: String, exponent: Int): String {
        val digitCount = digits.length
        return when {
            digitCount <= exponent && exponent <= 21 -> digits + "0".repeat(exponent - digitCount)
            exponent > 0 && exponent <= 21 -> digits.substring(0, exponent) + "." + digits.substring(exponent)
            exponent <= 0 && exponent > -6 -> "0." + "0".repeat(-exponent) + digits
            else -> {
                val mantissa = digits.substring(0, 1) + if (digitCount > 1) "." + digits.substring(1) else ""
                val scientificExponent = exponent - 1
                mantissa + "e" + (if (scientificExponent >= 0) "+" else "-") + kotlin.math.abs(scientificExponent).toString()
            }
        }
    }
}
