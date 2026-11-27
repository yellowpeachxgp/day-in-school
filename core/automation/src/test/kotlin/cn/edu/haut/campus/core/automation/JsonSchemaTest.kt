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

        assertEquals(emptyList<JsonSchemaViolation>(), schema.validate(Json.parse("{"count":1,"mode":"a"}")))

        val violations = schema.validate(Json.parse("{"mode":"c","extra":true}"))
        assertTrue(violations.any { it.rule == "required" })
        assertTrue(violations.any { it.rule == "enum" })
        assertTrue(violations.any { it.rule == "additionalProperties" })
    }

    @Test
    fun integerAndEnumUseCanonicalNumericEquality() {
        val schema = JsonSchema.parse(
            """{"type":"object","properties":{"count":{"type":"integer","enum":[1]}},"required":["count"]}"""
        )

        assertEquals(emptyList<JsonSchemaViolation>(), schema.validate(Json.parse("{"count":1.0}")))
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
    fun unsupportedEvaluationKeywordFailsBeforeRegistration() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonSchema.parse("{"type":"object","patternProperties":{"^x":{}}}")
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
