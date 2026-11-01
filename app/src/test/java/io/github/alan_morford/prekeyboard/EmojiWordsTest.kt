package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The emoji the suggestion bar offers for a word. */
class EmojiWordsTest {

    private fun words(canDraw: (String) -> Boolean = { true }) =
        File("src/main/assets/emoji_words.txt").bufferedReader().useLines { EmojiWords(it, canDraw) }

    @Test fun commonWordsGetTheUsualEmoji() {
        val w = words()
        assertEquals("🍕", w.forWord("pizza"))
        assertEquals("❤️", w.forWord("Love"))
        assertEquals("🌮", w.forWord("tacos"))
        assertEquals("🍕", w.forWord("pizza's"))
        assertEquals("🇫🇷", w.forWord("France"))
        assertEquals(null, w.forWord("the"))
        assertEquals(null, w.forWord(""))
    }

    @Test fun onlyEmojiTheDeviceCanDraw() {
        assertEquals("🐈", words { it != "🐱" }.forWord("cat"))
        assertEquals(null, words { false }.forWord("pizza"))
    }
}
