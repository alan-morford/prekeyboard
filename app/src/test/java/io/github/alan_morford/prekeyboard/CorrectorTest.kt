package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The dictionary that ships, on the JVM: no Android needed. */
class CorrectorTest {

    companion object {
        lateinit var c: Corrector
        @BeforeClass @JvmStatic fun load() {
            c = File("src/main/assets/dictionary.txt").bufferedReader().useLines { Corrector(it) }
            val nw = File("src/main/assets/next_words.txt").bufferedReader().useLines { NextWords(it) }
            c.follows = nw::follows
            File("src/main/assets/contractions.txt").bufferedReader().useLines { c.loadContractions(it) }
        }
    }

    private fun fixes(typed: String, want: String) = assertEquals(typed, want, c.correct(typed))
    private fun keeps(typed: String) = assertNull(typed, c.correct(typed))

    @Test fun contractions() {
        fixes("dont", "don't"); fixes("cant", "can't"); fixes("wont", "won't"); fixes("im", "I'm")
        fixes("Dont", "Don't"); fixes("youre", "you're"); fixes("didnt", "didn't"); fixes("thats", "that's")
        // Everyday words that look like contractions stay.
        listOf("well", "were", "its", "ill", "hell", "shell", "lets", "wed", "shed").forEach { keeps(it) }
    }

    @Test fun contractionsThatAreAlsoWordsAreOfferedNotForced() {
        for ((w, con) in listOf("ill" to "I'll", "its" to "it's", "well" to "we'll", "were" to "we're",
                "lets" to "let's", "wed" to "we'd", "hell" to "he'll", "shell" to "she'll", "shed" to "she'd")) {
            keeps(w)
            assertEquals(w, listOf(w, con), c.suggestions(w).take(2))
            assertEquals(w, con, c.alternatives(w).first())
        }
        assertEquals(listOf("Its", "It's"), c.suggestions("Its").take(2))
        // Where the word before all but settles it, the apostrophe goes in.
        assertEquals("I'll", c.correct("ill", "think")); assertEquals("I'll", c.correct("Ill", NextWords.START))
        assertEquals("it's", c.correct("its", "that")); assertEquals("we're", c.correct("were", "think"))
        assertEquals(listOf("I'll", "ill"), c.suggestions("ill", "think").take(2))
        // Where it doesn't, the word stays: "of its", "was ill", "Well, ...", "Were you ...".
        assertNull(c.correct("its", "of")); assertNull(c.correct("ill", "was"))
        assertNull(c.correct("Well", NextWords.START)); assertNull(c.correct("Were", NextWords.START))
        assertNull(c.correct("ill", "zebra"))
        fixes("hed", "he'd"); fixes("wholl", "who'll")
    }

    @Test fun capitals() {
        fixes("i", "I"); keeps("I"); keeps("NASA"); keeps("OK")
    }

    @Test fun typos() {
        fixes("teh", "the"); fixes("hte", "the"); fixes("recieve", "receive"); fixes("adress", "address")
        fixes("becuase", "because"); fixes("tomorow", "tomorrow"); fixes("thsi", "this")
        fixes("Teh", "The"); fixes("keybaord", "keyboard"); fixes("helo", "hello")
    }

    @Test fun runTogetherWords() {
        // The space missed, or hit as a letter just above the space bar.
        fixes("theyare", "they are"); fixes("theynare", "they are"); fixes("inthe", "in the")
        fixes("iwent", "I went"); fixes("thebdog", "the dog"); fixes("sonmuch", "so much")
        fixes("Ifnyou", "If you"); fixes("alot", "a lot")
        // Two little words that don't commonly pair: when no one word is near, or only a rare one.
        fixes("nownin", "now in"); fixes("onnit", "on it"); fixes("wasnit", "was it")
        fixes("asnit", "as it"); fixes("sonit", "so it"); fixes("tobme", "to me")
        // A slip in one word stays one word, and a word stays a word.
        fixes("basrball", "baseball"); fixes("toon", "too"); fixes("abvout", "about")
        listOf("welcome", "ashore", "nowhere", "together", "inbox", "notice").forEach { keeps(it) }
    }

    @Test fun knownWordsAndOddities() {
        listOf("hello", "the", "keyboard", "a", "ten", "receive", "don't").forEach { keeps(it) }
        keeps("x2"); keeps("http"); keeps("qzxj")
    }

    @Test fun learnedWordsStay() {
        val local = File("src/main/assets/dictionary.txt").bufferedReader().useLines { Corrector(it) }
        assertEquals("the", local.correct("teh"))
        local.learn("teh")
        assertNull(local.correct("teh"))
    }

    @Test fun suggestions() {
        assertEquals("the", c.suggestions("teh").first())       // what space would make of it
        assertEquals("hello", c.suggestions("hello").first())   // a word is its own best
        assertEquals(true, "keyboard" in c.suggestions("keyboa"))
        assertEquals("Hello", c.suggestions("Hello").first())
        assertEquals(3, c.suggestions("th").size)
        assertEquals(emptyList<String>(), c.suggestions(""))
    }

    @Test fun alternativesForATappedWord() {
        val ball = c.alternatives("ball")
        assertEquals(true, "bell" in ball)
        assertEquals(false, "ball" in ball)
        assertEquals(3, ball.size)
        assertEquals(true, c.alternatives("Ball").all { it[0].isUpperCase() })
        assertEquals("don't", c.alternatives("dont").first())
    }
}
