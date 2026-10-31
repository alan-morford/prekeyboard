package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/** Autocorrect, its undo, and the double-space period, on a plain string. */
class ComposerTest {

    private val text = StringBuilder()
    private var now = 10_000L
    private val field = object : Composer.Field {
        override fun before(n: Int): CharSequence = text.substring(Math.max(0, text.length - n))
        override fun delete(n: Int) { text.setLength(Math.max(0, text.length - n)) }
        override fun commit(s: String) { text.append(s) }
    }
    private val composer = Composer(field) { now }
    private val on = Composer.Rules(autocorrect = true, doubleSpacePeriod = true)
    private val off = Composer.Rules(autocorrect = false, doubleSpacePeriod = false)
    private val learned = ArrayList<String>()

    @Before fun setUp() {
        composer.corrector = File("src/main/assets/dictionary.txt").bufferedReader().useLines { Corrector(it) }
        composer.onLearned = { learned += it }
    }

    private fun type(s: String, rules: Composer.Rules = on) {
        for (ch in s) { composer.type(ch.toString(), rules); now += 300 }
    }

    @Test fun correctsWhenTheWordEnds() {
        type("i dont think teh cat is here.")
        assertEquals("I don't think the cat is here.", text.toString())
    }

    @Test fun nothingWhenSwitchedOff() {
        type("i dont think teh cat", off)
        assertEquals("i dont think teh cat", text.toString())
    }

    @Test fun backspaceUndoesAndLearns() {
        type("teh ")
        assertEquals("the ", text.toString())
        assertEquals(true, composer.backspace())
        assertEquals("teh ", text.toString())
        assertEquals(listOf("teh"), learned)
        type("teh ")
        assertEquals("teh teh ", text.toString())
        // A second backspace is an ordinary one.
        assertEquals(false, composer.backspace())
    }

    @Test fun doubleSpacePeriod() {
        type("Hello")
        composer.type(" ", on); now += 200; composer.type(" ", on)
        assertEquals("Hello. ", text.toString())
        // Slow spaces stay spaces; so do spaces after punctuation.
        text.setLength(0); type("Hi"); composer.type(" ", on); now += 1500; composer.type(" ", on)
        assertEquals("Hi  ", text.toString())
        text.setLength(0); type("Hi."); composer.type(" ", on); now += 200; composer.type(" ", on)
        assertEquals("Hi.  ", text.toString())
        text.setLength(0); type("Hello", off); composer.type(" ", off); now += 200; composer.type(" ", off)
        assertEquals("Hello  ", text.toString())
    }

    @Test fun leavesAddressesAndNumbersAlone() {
        type("mail teh@exmaple.com 3teh ")
        assertEquals("mail teh@exmaple.com 3teh ", text.toString())
    }

    @Test fun enterFinishesTheWord() {
        type("teh"); composer.beforeEnter(on)
        assertEquals("the", text.toString())
    }
}
