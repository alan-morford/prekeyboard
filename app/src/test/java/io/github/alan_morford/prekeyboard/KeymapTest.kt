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
