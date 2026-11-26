package cn.edu.haut.campus.core.textextract

import cn.edu.haut.campus.core.textextract.ocr.ModelConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConfigParserTest {
    @Test
    fun parsesPpOcrv6CharacterDictionary() {
        val characters = ModelConfigParser.parseCharacterList(FixtureLoader.recConfigYaml(), "fixture")
        assertTrue("字符表应包含足够字符", characters.size > 6000)
        assertTrue(characters.contains("知"))
        assertTrue(characters.contains("课"))
        assertTrue(characters.contains("通"))
        assertEquals(" ", characters.last())
    }
}
