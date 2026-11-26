package cn.edu.haut.campus.core.events

/**
 * 确定性 JSON 文本：对象键按 Unicode 码点排序、去除无意义空白，字符串只使用必要转义。
 *
 * 这是 W1-01a 的最小 canonical envelope 基础，不引入 kotlinx.serialization 或 Android 依赖。
 * 数字会按 JSON 语法校验，但保留输入词素；业务编码器应始终生成同一数字格式。
 */
@JvmInline
value class CanonicalJson private constructor(val text: String) {
    init {
        require(text.isNotBlank()) { "CanonicalJson 不能为空" }
    }

    override fun toString(): String = text

    companion object {
        val EMPTY_OBJECT: CanonicalJson = CanonicalJson("{}")

        fun of(raw: String): CanonicalJson = CanonicalJson(JsonCanonicalizer.canonicalize(raw))

        internal fun trusted(raw: String): CanonicalJson = CanonicalJson(raw)

        internal fun ofValue(value: JsonValue): CanonicalJson = CanonicalJson(JsonCanonicalizer.write(value))
    }
}

internal sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Number(val lexeme: String) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val values: List<JsonValue>) : JsonValue
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
}

internal fun CanonicalJson.toJsonValue(): JsonValue = JsonCanonicalizer.parseValue(text)

internal object JsonCanonicalizer {
    fun canonicalize(raw: String): String = write(parseValue(raw))

    fun parseValue(raw: String): JsonValue = Parser(raw).parseDocument()

    fun write(value: JsonValue): String = when (value) {
        JsonValue.Null -> "null"
        is JsonValue.Bool -> if (value.value) "true" else "false"
        is JsonValue.Number -> value.lexeme
        is JsonValue.Str -> quote(value.value)
        is JsonValue.Arr -> value.values.joinToString(prefix = "[", postfix = "]", separator = ",") { write(it) }
        is JsonValue.Obj -> value.entries.toSortedMap().entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, child) ->
            quote(key) + ":" + write(child)
        }
    }

    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\u000C' -> out.append("\\f")
                '\r' -> out.append("\\r")
                else -> {
                    if (ch < ' ') {
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
        }
        out.append('"')
        return out.toString()
    }

    private class Parser(private val raw: String) {
        private var index: Int = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != raw.length) fail("JSON 文档存在尾随内容")
            return value
        }

        private fun parseValue(): JsonValue {
            if (index >= raw.length) fail("JSON 值意外结束")
            return when (raw[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
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
                '-', in '0'..'9' -> JsonValue.Number(parseNumber())
                else -> fail("不支持的 JSON 值起始字符")
            }
        }

        private fun parseObject(): JsonValue.Obj {
            expectChar('{')
            skipWhitespace()
            val entries = LinkedHashMap<String, JsonValue>()
            if (consume('}')) return JsonValue.Obj(entries)
            while (true) {
                skipWhitespace()
                if (index >= raw.length || raw[index] != '"') fail("对象键必须是字符串")
                val key = parseString()
                if (entries.containsKey(key)) fail("对象包含重复键: " + key)
                skipWhitespace()
                expectChar(':')
                skipWhitespace()
                entries[key] = parseValue()
                skipWhitespace()
                when {
                    consume(',') -> continue
                    consume('}') -> return JsonValue.Obj(entries)
                    else -> fail("对象缺少逗号或结束括号")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expectChar('[')
            skipWhitespace()
            val values = ArrayList<JsonValue>()
            if (consume(']')) return JsonValue.Arr(values)
            while (true) {
                skipWhitespace()
                values += parseValue()
                skipWhitespace()
                when {
                    consume(',') -> continue
                    consume(']') -> return JsonValue.Arr(values)
                    else -> fail("数组缺少逗号或结束括号")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val out = StringBuilder()
            while (index < raw.length) {
                val ch = raw[index++]
                when (ch) {
                    '"' -> return out.toString()
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
            if (index >= raw.length) fail("转义序列意外结束")
            return when (val escaped = raw[index++]) {
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
            val first = parseHex4()
            val firstChar = first.toChar()
            if (Character.isHighSurrogate(firstChar)) {
                if (index + 1 >= raw.length || raw[index] != '\\' || raw[index + 1] != 'u') {
                    fail("高代理项后缺少低代理项")
                }
                index += 2
                val second = parseHex4().toChar()
                if (!Character.isLowSurrogate(second)) fail("高代理项后不是低代理项")
                return String(charArrayOf(firstChar, second))
            }
            if (Character.isLowSurrogate(firstChar)) fail("孤立低代理项")
            return firstChar.toString()
        }

        private fun parseHex4(): Int {
            if (index + 4 > raw.length) fail("Unicode 转义不足 4 位")
            var value = 0
            repeat(4) {
                val digit = hexValue(raw[index++]) ?: fail("Unicode 转义含非十六进制字符")
                value = value * 16 + digit
            }
            return value
        }

        private fun hexValue(ch: Char): Int? = when (ch) {
            in '0'..'9' -> ch.code - '0'.code
            in 'a'..'f' -> ch.code - 'a'.code + 10
            in 'A'..'F' -> ch.code - 'A'.code + 10
            else -> null
        }

        private fun parseNumber(): String {
            val start = index
            consume('-')
            when {
                consume('0') -> Unit
                index < raw.length && raw[index] in '1'..'9' -> {
                    index++
                    while (index < raw.length && raw[index].isDigit()) index++
                }
                else -> fail("数字格式非法")
            }
            if (consume('.')) {
                if (index >= raw.length || !raw[index].isDigit()) fail("小数点后缺少数字")
                while (index < raw.length && raw[index].isDigit()) index++
            }
            if (index < raw.length && (raw[index] == 'e' || raw[index] == 'E')) {
                index++
                if (index < raw.length && (raw[index] == '+' || raw[index] == '-')) index++
                if (index >= raw.length || !raw[index].isDigit()) fail("指数缺少数字")
                while (index < raw.length && raw[index].isDigit()) index++
            }
            return raw.substring(start, index)
        }

        private fun expectLiteral(literal: String) {
            if (!raw.startsWith(literal, index)) fail("期望字面量 " + literal)
            index += literal.length
        }

        private fun expectChar(expected: Char) {
            if (index >= raw.length || raw[index] != expected) fail("期望字符 " + expected)
            index++
        }

        private fun consume(expected: Char): Boolean {
            if (index < raw.length && raw[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (index < raw.length) {
                when (raw[index]) {
                    ' ', '\t', '\n', '\r' -> index++
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing {
            throw IllegalArgumentException(message + "（offset=" + index + "）")
        }
    }
}
