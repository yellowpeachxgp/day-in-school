package cn.edu.haut.campus.core.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalJsonTest {
    @Test
    fun canonicalizesObjectKeysAndWhitespace() {
        val canonical = CanonicalJson.of("{ \"z\": 1, \"a\": [ true, null, { \"b\": \"x\" } ] }")
        assertEquals("{\"a\":[true,null,{\"b\":\"x\"}],\"z\":1}", canonical.text)
    }

    @Test
    fun parsesEscapesAndWritesCanonicalStrings() {
        val canonical = CanonicalJson.of("{\"text\":\"quote \\\" slash \\\\ unicode \\uD83D\\uDE00 control \\u0001\"}")
        assertEquals("{\"text\":\"quote \\\" slash \\\\ unicode 😀 control \\u0001\"}", canonical.text)
    }

    @Test
    fun rejectsDuplicateObjectKeys() {
        val error = runCatching { CanonicalJson.of("{\"a\":1,\"a\":2}") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rejectsInvalidNumbersAndTrailingContent() {
        assertTrue(runCatching { CanonicalJson.of("01") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { CanonicalJson.of("{} true") }.exceptionOrNull() is IllegalArgumentException)
    }
}
