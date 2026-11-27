package cn.edu.haut.campus.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextNormalizerTest {
    @Test
    fun advancedMathBodyGeneratesOverlappingBigramsAndKeepsSingleCharAndAscii() {
        val normalized = SearchTextNormalizer.normalize("高等数学A（一）")

        assertEquals("高等数学a 一", normalized.normalizedText)
        assertEquals(listOf("高等", "等数", "数学", "a", "一"), normalized.tokens)
    }

    @Test
    fun aliasEntersTokenStreamIndependentlyWithoutPollutingBody() {
        val bodyTokens = SearchTextNormalizer.normalize("高等数学A（一）").tokens
        val aliasTokens = SearchTextNormalizer.normalize("高数").tokens

        assertTrue("高数" !in bodyTokens)
        assertEquals(listOf("高数"), aliasTokens)
    }

    @Test
    fun discreteMathQueryRewriteKeepsOrderedBigramPhrase() {
        assertEquals(""离散"", SearchTextNormalizer.rewriteQuery("离散").ftsQuery)
        assertEquals(""数学"", SearchTextNormalizer.rewriteQuery("数学").ftsQuery)
        assertEquals(""离散 散数 数学"", SearchTextNormalizer.rewriteQuery("离散数学").ftsQuery)
        assertEquals(""离数"", SearchTextNormalizer.rewriteQuery("离数").ftsQuery)
    }

    @Test
    fun fullWidthAsciiCaseAndZeroWidthCharactersAreNormalized() {
        val normalized = SearchTextNormalizer.normalize("Ｗ１１１​　F０１")

        assertEquals("w111 f01", normalized.normalizedText)
        assertEquals(listOf("w111", "f01"), normalized.tokens)
    }

    @Test
    fun mixedQueryUsesScriptRunAndTailAsciiPrefix() {
        val rewrite = SearchTextNormalizer.rewriteQuery("莲花街 W11")

        assertEquals(""莲花 花街" AND "w11"", rewrite.ftsQuery)

        val prefix = SearchTextNormalizer.rewriteQuery("莲花街 W111")
        assertEquals(""莲花 花街" AND w111*", prefix.ftsQuery)
    }

    @Test
    fun singleChineseCharacterUsesFallbackInsteadOfFtsMatch() {
        val rewrite = SearchTextNormalizer.rewriteQuery("数")

        assertNull(rewrite.ftsQuery)
        assertEquals(listOf("数"), rewrite.singleCharFallbacks)
    }

    @Test
    fun emptyQueryDoesNotGenerateMatch() {
        val rewrite = SearchTextNormalizer.rewriteQuery(" ​（ ） ")

        assertTrue(rewrite.isEmpty)
        assertNull(rewrite.ftsQuery)
    }

    @Test
    fun ftsOperatorsAndPunctuationAreLiteralOrSeparatorsOnly() {
        val rewrite = SearchTextNormalizer.rewriteQuery("OR AND NOT NEAR * - "")

        assertEquals("or and not near", rewrite.normalizedQuery)
        assertEquals(""or" AND "and" AND "not" AND nea*", rewrite.ftsQuery)
    }

    @Test
    fun longChineseQueryPhraseOrderIsStable() {
        val rewrite = SearchTextNormalizer.rewriteQuery("高等数学")

        assertEquals(""高等 等数 数学"", rewrite.ftsQuery)
    }

    @Test
    fun multiFieldTokenStreamConcatenationIsDeterministic() {
        val stream = SearchTextNormalizer.tokenStreamFor("高等数学A（一）", null, "W111", "高数")

        assertEquals("高等 等数 数学 a 一 w111 高数", stream)
    }
}
