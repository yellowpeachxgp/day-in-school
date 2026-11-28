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
    fun rfc8785NumberSerializationMatchesV8RandomBitPatternOracle() {
        val samples = listOf(
            "2ceaee21bf46bc00" to "2.58208751605928e-92",
            "aa80754d1a1a8d4f" to "-5.740821509257694e-104",
            "b3c4904a6d278932" to "-2.5593563771756513e-59",
            "bc69cf4276846d19" to "-1.1193133179981887e-17",
            "377b2fd56a5b15b4" to "1.9505608422923054e-41",
            "64d815deeaf29df3" to "6.100029174392375e+177",
            "f66e100db2d7d206" to "-2.9582464636439276e+262",
            "1069e6a57e06665d" to "1.3346556328235743e-229",
            "7be902917b70a2a8" to "7.616581843686657e+288",
            "68901baf16ad70d7" to "4.703515775273557e+195",
            "f2caa7c38002001a" to "-9.100190930283755e+244",
            "5a55db213cf06be1" to "1.4794775897261274e+127",
            "4c88c014892416dc" to "4.9715275161848454e+60",
            "a0c1ac9a9822a9fb" to "-6.749207487970695e-151",
            "9e5c77e75ae9e76e" to "-1.9774477274715477e-162",
            "37e83672575ac1a5" to "2.223583683950638e-39",
            "960fde3f09756650" to "-2.0328707678492119e-202",
            "d4076a092b5c2d5f" to "-6.251554101474824e+96",
            "f4a4cb6fed689c02" to "-7.622870347708557e+253",
            "f541557ffd59eba9" to "-6.506758911014046e+256",
            "4285a9af717bc504" to "2977317138296.627",
            "8e4d95dd2c3a1f03" to "-8.873827846752845e-240",
            "7b96dae083c071d6" to "2.1750882609617628e+287",
            "d9f321be6242aded" to "-2.0235516861171984e+125",
            "7df64fcfe859a6f8" to "5.836730832778691e+298",
            "f6453aa71756e2e7" to "-5.2224955651923635e+261",
            "92191efbd0b0fcea" to "-1.7374032930124759e-221",
            "d9895985202e0c71" to "-2.0946952857955814e+123",
            "68518d384170c02c" to "3.2031325444764186e+194",
            "aa7d7e42512b1d0b" to "-5.143791971352148e-104",
            "cf273511cacb113e" to "-2.050187736099803e+73",
            "138c146fcbbd4b35" to "1.629099415552217e-214",
            "57100d0a31d604a0" to "2.4125637496203283e+111",
            "2bb1b0131771b16f" to "3.234707592313174e-98",
            "d6e649d7b704c2d2" to "-4.187629500679232e+110",
            "a7fdd0bb3c1dee39" to "-4.7293526166204466e-116",
            "a95d2364f505a854" to "-1.938586525365622e-109",
            "3976bad3c0cbc413" to "7.00415841861272e-32",
            "5bc5a782b48d65a6" to "1.2296258999658661e+134",
            "4bcdebb24b4db97d" to "1.4673063339624034e+57",
            "a6480378d4d71f48" to "-2.8379738754033683e-124",
            "0653e779aea4b8f7" to "3.5088819091809414e-278",
            "cbad05ba5de18dba" to "-3.55813281943796e+56",
            "a433bde1129eb101" to "-2.7161067410797303e-134",
            "d1c2886248b11d7c" to "-7.200599263626944e+85",
            "8f2179789556341b" to "-8.587328318454213e-236",
            "860b0f2a531f0f0e" to "-1.4906978483082635e-279",
            "4f09bc2de77b18c5" to "5.6837428135043375e+72"
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
