package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSchemaTest {
    @Test
    fun objectRequiredTypeEnumAndAdditionalPropertiesAreValidated() {
        val schema = JsonSchema.parse(
            """{
              "type":"object",
              "properties":{
                "count":{"type":"integer","minimum":1},
                "mode":{"enum":["a","b"]}
              },
              "required":["count"],
              "additionalProperties":false
            }""".trimIndent()
        )

        assertEquals(emptyList<JsonSchemaViolation>(), schema.validate(Json.parse("""{"count":1,"mode":"a"}""")))

        val violations = schema.validate(Json.parse("""{"mode":"c","extra":true}"""))
        assertTrue(violations.any { it.rule == "required" })
        assertTrue(violations.any { it.rule == "enum" })
        assertTrue(violations.any { it.rule == "additionalProperties" })
    }

    @Test
    fun integerAndEnumUseCanonicalNumericEquality() {
        val schema = JsonSchema.parse(
            """{"type":"object","properties":{"count":{"type":"integer","enum":[1]}},"required":["count"]}"""
        )

        assertEquals(emptyList<JsonSchemaViolation>(), schema.validate(Json.parse("""{"count":1.0}""")))
    }

    @Test
    fun arrayLengthAndItemsAreValidated() {
        val schema = JsonSchema.parse(
            """{"type":"array","minItems":2,"maxItems":2,"items":{"type":"string","minLength":2}}"""
        )

        assertEquals(
            emptyList<JsonSchemaViolation>(),
            schema.validate(JsonValue.Arr(listOf(JsonValue.Str("ab"), JsonValue.Str("cd"))))
        )
        val violations = schema.validate(JsonValue.Arr(listOf(JsonValue.Str("a"))))
        assertTrue(violations.any { it.rule == "minItems" })
        assertTrue(violations.any { it.rule == "minLength" })
    }

    @Test
    fun decimalLengthBoundsAndUnicodeCodePointsAreValidated() {
        val arraySchema = JsonSchema.parse("""{"type":"array","minItems":2.0,"maxItems":2e0}""")
        assertTrue(arraySchema.validate(JsonValue.Arr(listOf(JsonValue.Str("a")))).any { it.rule == "minItems" })
        assertTrue(
            arraySchema.validate(JsonValue.Arr(listOf(JsonValue.Str("a"), JsonValue.Str("b"), JsonValue.Str("c"))))
                .any { it.rule == "maxItems" }
        )

        val stringSchema = JsonSchema.parse("""{"type":"string","minLength":2.0,"maxLength":2e0}""")
        assertEquals(emptyList<JsonSchemaViolation>(), stringSchema.validate(JsonValue.Str("😀😀")))
        assertTrue(stringSchema.validate(JsonValue.Str("😀")).any { it.rule == "minLength" })
        assertTrue(stringSchema.validate(JsonValue.Str("😀😀😀")).any { it.rule == "maxLength" })
    }

    @Test
    fun unsupportedEvaluationKeywordFailsBeforeRegistration() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonSchema.parse("""{"type":"object","patternProperties":{"^x":{}}}""")
        }
    }

    @Test
    fun malformedOrOutOfRangeNumericKeywordsFailAtSchemaConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonSchema.of(
                JsonValue.Obj(
                    mapOf(
                        "type" to JsonValue.Str("number"),
                        "minimum" to JsonValue.Num("not-a-number")
                    )
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonSchema.of(
                JsonValue.Obj(
                    mapOf(
                        "type" to JsonValue.Str("array"),
                        "maxItems" to JsonValue.Num("2147483648")
                    )
                )
            )
        }
    }

    @Test
    fun anyOfAndNotAreEvaluated() {
        val schema = JsonSchema.parse(
            """{
              "type":"string",
              "anyOf":[{"const":"yes"},{"const":"no"}],
              "not":{"const":"maybe"}
            }"""
        )

        assertEquals(emptyList<JsonSchemaViolation>(), schema.validate(JsonValue.Str("yes")))
        assertTrue(schema.validate(JsonValue.Str("maybe")).isNotEmpty())
        assertTrue(schema.validate(JsonValue.Str("unknown")).any { it.rule == "anyOf" })
    }
}
