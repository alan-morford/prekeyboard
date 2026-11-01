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
        assertEquals("I don't think the cat is here. ", text.toString())
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
        assertEquals("Hi.  ", text.toString())     // the period's own space absorbs the first
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

    @Test fun swipedWordsSpaceThemselvesAndBackspaceTakesTheWord() {
        type("Hello")
        composer.swipe("world", on); composer.swipe("again", on)
        assertEquals("Hello world again ", text.toString())
        assertEquals(true, composer.backspace())          // the word and its space
        assertEquals("Hello world ", text.toString())
        type("!")                                        // world's own space is still the swipe's
        assertEquals("Hello world!", text.toString())
        // At the start of the text, or after a space or an opening bracket: no space in front.
        text.setLength(0); composer.reset()
        composer.swipe("one", on); type("("); composer.swipe("two", on)
        assertEquals("one (two ", text.toString())
    }

    @Test fun typingAndSwipingMixWithoutLosingSpaces() {
        composer.swipe("Going", on); type("to"); composer.swipe("town", on)
        assertEquals("Going to town ", text.toString())
        // A space out of habit after a swipe is absorbed, not doubled.
        text.setLength(0); composer.reset()
        composer.swipe("Going", on); type(" to")
        assertEquals("Going to", text.toString())
    }

    @Test fun aPeriodAfterASwipeLandsOnTheWord() {
        type("See you"); composer.swipe("soon", on); type(".")
        assertEquals("See you soon. ", text.toString())
        type("?")
        assertEquals("See you soon.? ".dropLast(1), text.toString())
    }

    @Test fun backspaceAfterASwipeTakesItsLeadingSpaceToo() {
        type("Hello"); composer.swipe("world", on)
        assertEquals("Hello world ", text.toString())
        assertEquals(true, composer.backspace())
        assertEquals("Hello", text.toString())
    }

    @Test fun aLoneIIsCapitalisedWhenASwipeFollowsIt() {
        type("i"); composer.swipe("think", on)
        assertEquals("I think ", text.toString())
        text.setLength(0); composer.reset()
        type("so i"); composer.swipe("went", on)
        assertEquals("so I went ", text.toString())
    }

    @Test fun aPeriodBringsASpaceThatBackspaceTakesAlone() {
        type("The end.")
        assertEquals("The end. ", text.toString())
        assertEquals(false, composer.backspace())         // an ordinary backspace: the space
        text.setLength(text.length - 1)
        assertEquals("The end.", text.toString())
        assertEquals(false, composer.backspace())
        // Not after a number, at a word's start, or in a field that isn't prose.
        text.setLength(0); composer.reset()
        type("pi is 3.14 .net")
        assertEquals("pi is 3.14 .net", text.toString())
        text.setLength(0); composer.reset()
        val url = Composer.Rules(autocorrect = false, doubleSpacePeriod = false, prose = false)
        type("example.com", url)
        assertEquals("example.com", text.toString())
        // Return doesn't leave it trailing; "..." stays together.
        text.setLength(0); composer.reset()
        type("mail me@site.com or www.site.org/a.b ")
        assertEquals("mail me@site.com or www.site.org/a.b ", text.toString())
        text.setLength(0); composer.reset()
        type("Wait..."); assertEquals("Wait... ", text.toString())
        composer.beforeEnter(on); assertEquals("Wait...", text.toString())
    }

    @Test fun pickingFromTheBar() {
        type("I like teh")
        assertEquals("teh", composer.currentWord())
        composer.pick("the", learn = false)
        assertEquals("I like the ", text.toString())
        composer.type(" ", on)                     // a quick space after a pick: the period
        assertEquals("I like the. ", text.toString())

        // After a swipe, a pick swaps the swiped word.
        text.setLength(0); composer.reset()
        composer.swipe("in", on); composer.pick("on", learn = false)
        assertEquals("on ", text.toString())
        assertEquals("on", composer.swiped)

        // The word as typed, picked over its correction, is learned and kept from then on.
        text.setLength(0); composer.reset()
        type("Zorp"); composer.pick("Zorp", learn = true)
        assertEquals(listOf("Zorp"), learned)
        type("zorp ")
        assertEquals("Zorp zorp ", text.toString())
    }

    @Test fun currentWordOnlyAtAWordsEnd() {
        type("say hel")
        assertEquals("hel", composer.currentWord())
        type(" ")
        assertEquals("", composer.currentWord())
        type("mail@exa")
        assertEquals("", composer.currentWord())
    }
}
