package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapTest {

    private fun typed(rows: List<List<Slot>>): List<String> =
        rows.flatten().filter { it.drawn && it.key.kind == Kind.CHAR }.flatMap { s ->
            listOfNotNull(s.key.text, s.key.alt) + (s.key.extended ?: emptyList())
        }

    @Test
    fun symPageRepeatsNothingFromTheLettersPageButDigitsAndThePeriod() {
        val letters = typed(Keymap.letters).toSet()
        val repeated = typed(Keymap.symbols).filter { it in letters && it != "." && !(it.length == 1 && it[0].isDigit()) }
        assertEquals(emptyList<String>(), repeated)
    }

    @Test
    fun withTheNumberRowTheSymPageStillRepeatsOnlyTheDigitsRow() {
        val letters = Keymap.letters(Language.EN_US, numberRow = true)
        val symbols = Keymap.symbols(numberRow = true)
        assertEquals(5, letters.size)
        assertEquals(5, symbols.size)
        // The digits row is the same keys on both pages; nothing else repeats.
        assertTrue(letters[0] === symbols[0])
        val lettersTyped = typed(letters.drop(1)).toSet()
        val repeated = typed(symbols.drop(1)).filter { it in lettersTyped && it != "." && !(it.length == 1 && it[0].isDigit()) }
        assertEquals(emptyList<String>(), repeated)
    }

    @Test
    fun keysOnBothPagesSitInTheSamePlace() {
        for (numbered in listOf(false, true)) {
            val l = Keymap.letters(Language.EN_US, numbered)
            val s = Keymap.symbols(numbered)
            for (kind in listOf(Kind.BACKSPACE, Kind.RETURN, Kind.SPACE, Kind.SYM)) {
                fun place(rows: List<List<Slot>>) = rows.withIndex().firstNotNullOf { (r, row) ->
                    var x = 0f
                    row.firstNotNullOfOrNull { slot -> (if (slot.drawn && slot.key.kind == kind) r to x else null).also { x += slot.weight } }
                }
                assertEquals("$kind", place(l), place(s))
            }
        }
    }

    @Test
    fun padsForEachKindOfNumber() {
        for (kind in Keymap.Pad.values()) {
            val pad = Keymap.pad(kind)
            assertEquals(4, pad.size)
            assertTrue(pad.all { it.size == 3 })
            // Backspace, 0, Return along the bottom; nothing else but digits.
            assertEquals(listOf(Kind.BACKSPACE, Kind.CHAR, Kind.RETURN), pad[3].map { it.key.kind })
        }
        val pin = Keymap.pad(Keymap.Pad.NUMBER).flatten().filter { it.drawn && it.key.kind == Kind.CHAR }.map { it.key.text }
        assertEquals("1234567890".map { it.toString() }.toSet(), pin.toSet())
    }

    @Test
    fun symPageHasTheAsciiSymbolsTheLettersPageLacks() {
        val sym = typed(Keymap.symbols).toSet()
        for (ch in "^~`|\\<>[]{}") assertTrue("missing $ch", ch.toString() in sym)
    }

    @Test
    fun everyHoldListStartsWithItsKey() {
        for (s in (Keymap.letters + Keymap.symbols).flatten()) {
            val ext = s.key.extended ?: continue
            if (s.key.kind == Kind.CHAR) assertEquals(s.key.text, ext.first())
        }
    }
}
