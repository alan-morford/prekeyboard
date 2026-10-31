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

    @Test fun capitals() {
        fixes("i", "I"); keeps("I"); keeps("NASA"); keeps("OK")
    }

    @Test fun typos() {
        fixes("teh", "the"); fixes("hte", "the"); fixes("recieve", "receive"); fixes("adress", "address")
        fixes("becuase", "because"); fixes("tomorow", "tomorrow"); fixes("thsi", "this")
        fixes("Teh", "The"); fixes("keybaord", "keyboard"); fixes("helo", "hello")
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
}
