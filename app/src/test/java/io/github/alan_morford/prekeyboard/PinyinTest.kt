package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Simplified Chinese from pinyin, on the file that ships. */
class PinyinTest {

    companion object {
        lateinit var p: Pinyin
        @BeforeClass @JvmStatic fun load() {
            p = File("src/main/assets/zh/pinyin.txt").bufferedReader().useLines { Pinyin(it) }
        }
    }

    private fun first(typed: String) = p.candidates(typed).first()

    @Test fun wordsAndCharacters() {
        assertEquals("你好", first("nihao").word)
        assertEquals("我", first("wo").word)
        assertEquals("是", first("shi").word)
        assertEquals("我们", first("women").word)
        assertEquals("谢谢", first("xiexie").word)
        assertEquals("中国", first("zhongguo").word)
        assertEquals("绿", p.candidates("lv").take(8).firstOrNull { it.word == "绿" }?.word)
    }

    @Test fun aRunOfWordsForWhatNoOneWordSpells() {
        assertEquals("今天天气很好", first("jintiantianqihenhao").word)
        assertEquals("我爱你", first("woaini").word)
    }

    @Test fun theStartOfWhatWasTyped() {
        // Characters for the first syllable, leaving the rest.
        val c = p.candidates("nihao")
        val ni = c.first { it.word == "你" }
        assertEquals(2, ni.consumed)
        // A word half typed: its likely ends.
        assertTrue(p.candidates("zhongg").take(5).any { it.word == "中国" })
    }

    @Test fun pickedWordsComeFirstNextTime() {
        val q = File("src/main/assets/zh/pinyin.txt").bufferedReader().useLines { Pinyin(it) }
        val before = q.candidates("shi").map { it.word }
        val third = before[3]
        q.learn("shi", third)
        assertEquals(third, q.candidates("shi").first().word)
        // Kept, and read back.
        val r = File("src/main/assets/zh/pinyin.txt").bufferedReader().useLines { Pinyin(it) }
        r.load(q.picks().asSequence())
        assertEquals(third, r.candidates("shi").first().word)
        // A run picked whole becomes a word.
        q.learn("woxihuanmao", "我喜欢猫")
        assertEquals("我喜欢猫", q.candidates("woxihuanmao").first().word)
    }
}
