package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rfc8785JsonTest {

    @Test
    fun rfc8785NumberSerializationMatchesAppendixBSamples() {
        val samples = listOf(
            "0000000000000000" to "0",
            "8000000000000000" to "0",
            "0000000000000001" to "5e-324",
            "8000000000000001" to "-5e-324",
            "7fefffffffffffff" to "1.7976931348623157e+308",
            "ffefffffffffffff" to "-1.7976931348623157e+308",
            "4340000000000000" to "9007199254740992",
            "c340000000000000" to "-9007199254740992",
            "4430000000000000" to "295147905179352830000",
            "44b52d02c7e14af5" to "9.999999999999997e+22",
            "44b52d02c7e14af6" to "1e+23",
            "44b52d02c7e14af7" to "1.0000000000000001e+23",
            "444b1ae4d6e2ef4e" to "999999999999999700000",
            "444b1ae4d6e2ef4f" to "999999999999999900000",
            "444b1ae4d6e2ef50" to "1e+21",
            "3eb0c6f7a0b5ed8c" to "9.999999999999997e-7",
            "3eb0c6f7a0b5ed8d" to "0.000001",
            "41b3de4355555553" to "333333333.3333332",
            "41b3de4355555554" to "333333333.33333325",
            "41b3de4355555555" to "333333333.3333333",
            "41b3de4355555556" to "333333333.3333334",
            "41b3de4355555557" to "333333333.33333343",
            "becbf647612f3696" to "-0.0000033333333333333333",
            "43143ff3c1cb0959" to "1424953923781206.2"
        )
        for ((hex, expected) in samples) {
            val bits = java.lang.Long.parseUnsignedLong(hex, 16)
            assertEquals(hex, expected, EcmaScriptNumberSerializer.serialize(java.lang.Double.longBitsToDouble(bits)))
        }
    }

    @Test
    fun objectKeysSortByRawUtf16CodeUnits() {
        val input = JsonValue.Obj(linkedMapOf(
            "€" to JsonValue.Str("Euro Sign"),
            "\r" to JsonValue.Str("Carriage Return"),
            "דּ" to JsonValue.Str("Hebrew Letter Dalet With Dagesh"),
            "1" to JsonValue.Str("One"),
            "😀" to JsonValue.Str("Emoji: Grinning Face"),
            String(charArrayOf(0x80.toChar())) to JsonValue.Str("Control"),
            "ö" to JsonValue.Str("Latin Small Letter O With Diaeresis")
        ))
        val canonical = Rfc8785Json.canonicalize(input)
        val orderedKeys = listOf("\r", "1", String(charArrayOf(0x80.toChar())), "ö", "€", "😀", "דּ")
        val values = mapOf(
            "\r" to "Carriage Return",
            "1" to "One",
            String(charArrayOf(0x80.toChar())) to "Control",
            "ö" to "Latin Small Letter O With Diaeresis",
            "€" to "Euro Sign",
            "😀" to "Emoji: Grinning Face",
            "דּ" to "Hebrew Letter Dalet With Dagesh"
        )
        val expected = orderedKeys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            Rfc8785Json.canonicalize(JsonValue.Str(key)) + ":" + Rfc8785Json.canonicalize(JsonValue.Str(values.getValue(key)))
        }
        assertEquals(expected, canonical)
        assertEquals("\"\\u000f\"", Rfc8785Json.canonicalize(JsonValue.Str("\u000f")))
    }

    @Test
    fun exactByteChainAcceptsSharedFixtureAndRejectsSemanticEquivalentVariants() {
        val bytes = fixtureBytes()
        val document = Rfc8785Json.parseExactCanonical(bytes)
        assertEquals(bytes.size, document.canonicalBytes.size)
        assertTrue(document.canonicalBytes.contentEquals(bytes))

        val pretty = String(bytes, Charsets.UTF_8).replace("{\"format\":\"dis-datapack/v1\"", "{\n  \"format\": \"dis-datapack/v1\"")
        assertRuntimeError(RUNTIME_DATAPACK_EXACT_BYTES) { Rfc8785Json.parseExactCanonical(pretty.toByteArray(Charsets.UTF_8)) }

        val numberVariant = String(bytes, Charsets.UTF_8).replace("\"estimatedMinutes\":15", "\"estimatedMinutes\":15.0")
        assertRuntimeError(RUNTIME_DATAPACK_EXACT_BYTES) { Rfc8785Json.parseExactCanonical(numberVariant.toByteArray(Charsets.UTF_8)) }

        val duplicateKeys = "{\"a\":1,\"a\":1}".toByteArray(Charsets.UTF_8)
        assertRuntimeError(RUNTIME_DATAPACK_INVALID) { Rfc8785Json.parseExactCanonical(duplicateKeys) }
    }

    @Test
    fun invalidUtf8NonFiniteAndLoneSurrogatesFailClosed() {
        assertRuntimeError(RUNTIME_DATAPACK_INVALID_UTF8) { Rfc8785Json.parseExactCanonical(byteArrayOf(0x7b, 0x80.toByte(), 0x7d)) }
        assertRuntimeError(RUNTIME_DATAPACK_NON_FINITE_NUMBER) { Rfc8785Json.parseExactCanonical("{\"a\":1e999}".toByteArray(Charsets.UTF_8)) }
        assertRuntimeError(RUNTIME_DATAPACK_INVALID_UNICODE) { Rfc8785Json.parseExactCanonical("{\"\\udead\":1}".toByteArray(Charsets.UTF_8)) }
    }

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/datapack-fixtures/valid-procedure.json")).use { it.readBytes() }

    private fun assertRuntimeError(runtimeCode: String, block: () -> Unit) {
        try {
            block()
        } catch (error: PackageException) {
            assertEquals(runtimeCode, error.runtimeValidationCode)
            return
        }
        throw AssertionError("expected PackageException with runtime code " + runtimeCode)
    }
}
