package com.kienhoang.dualsubreplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BilingualTranslateScreenTest {
    @Test
    fun automaticallyDetectsEnglishAndChinese() {
        assertEquals("en", bilingualSourceLanguage("Thank you very much."))
        assertEquals("zh", bilingualSourceLanguage("非常感谢。"))
        assertEquals("zh", bilingualSourceLanguage("Thanks, 谢谢!"))
    }

    @Test
    fun chineseTokensRemainSelectableBeforeDictionaryIsLoaded() {
        val sentence = "谢谢你"
        val tokens = bilingualWordTokens(sentence, "zh")
        assertTrue(tokens.isNotEmpty())
        assertEquals(0, tokens.first().startIndex)
        assertTrue(tokens.all { it.startIndex >= 0 && it.endIndex <= sentence.length })
    }
}
