package cn.edu.haut.campus.core.model.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalJsonTest {

    @Test
    fun 解析基本文档并读取字段() {
        val value = Json.parse("""{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}""")
        assertEquals(1, value.obj("a")?.intOrNull())
        assertEquals(3, value.arr("b")?.size)
        assertEquals("x", value.arr("b")?.get(2)?.stringOrNull())
        assertEquals("2.5", (value.obj("c")?.obj("d") as JsonValue.Num).literal)
    }

    @Test
    fun 转义与Unicode往返一致() {
        val source = "{\"text\":\"line\\nbreak \\u4e2d\\u6587 \\\"q\\\" \\\\\"}"
        val value = Json.parse(source)
        assertEquals("line\nbreak 中文 \"q\" \\", value.str("text"))
        val again = Json.parse(Json.canonical(value))
        assertEquals(value, again)
    }

    @Test
    fun 重复键与尾随逗号必须拒绝() {
        assertFails { Json.parse("""{"a":1,"a":2}""") }
        assertFails { Json.parse("""{"a":1,}""") }
        assertFails { Json.parse("""[1,2,]""") }
        assertFails { Json.parse("""{"a":01}""") }
        assertFails { Json.parse("""{"a":"\u12"}""") }
        assertFails { Json.parse("{\"a\":\"x") }
        assertFails { Json.parse("""{"a":1} extra""") }
    }

    @Test
    fun 嵌套深度受限() {
        val deep = "[".repeat(80) + "]".repeat(80)
        assertFails { Json.parse(deep) }
        val allowed = "[".repeat(10) + "]".repeat(10)
        assertTrue(Json.parse(allowed) is JsonValue.Arr)
    }

    @Test
    fun 规范形式按键排序且数字归一化() {
        val value = Json.parse("""{"b":1.50,"a":1e3,"c":{"y":0.10,"x":-0}}""")
        assertEquals("""{"a":1000,"b":1.5,"c":{"x":0,"y":0.1}}""", Json.canonical(value))
    }

    @Test
    fun 键顺序不同但内容等价时摘要一致() {
        val first = Json.parse("""{"school":"haut","terms":[{"id":"1","year":2026}]}""")
        val second = Json.parse("""{ "terms":[{"year":2026,"id":"1"}], "school":"haut" }""")
        assertEquals(Json.canonicalDigest(first), Json.canonicalDigest(second))
        assertNotEquals(
            Json.canonicalDigest(first),
            Json.canonicalDigest(Json.parse("""{"school":"haut","terms":[{"id":"1","year":2025}]}"""))
        )
    }

    @Test
    fun 字符串中的控制字符被转义且可回读() {
        val value = JsonValue.Obj(mapOf("k" to JsonValue.Str("a\u0001b\u001fc")))
        val encoded = Json.canonical(value)
        assertTrue(encoded.contains("\\u0001"))
        assertEquals("a\u0001b\u001fc", Json.parse(encoded).str("k"))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: JsonParseException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("预期解析失败但成功")
    }
}
