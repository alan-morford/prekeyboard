package io.github.alan_morford.prekeyboard

/**
 * What typing does to the text: autocorrect when a word is finished, undoing a correction
 * with the very next backspace, and a double-tapped space becoming ". ".
 *
 * It works on a [Field] rather than on Android's InputConnection, so the tests can drive it
 * with a plain string.
 */
class Composer(private val field: Field, private val clock: () -> Long) {

    /** The text being edited, as much of it as the keyboard needs. */
    interface Field {
        fun before(n: Int): CharSequence
        fun delete(n: Int)
        fun commit(s: String)
        fun batch(block: () -> Unit) = block()
    }

    /** What may happen in the field at hand: the owner's toggles, less what the field rules out. */
    class Rules(val autocorrect: Boolean, val doubleSpacePeriod: Boolean)

    var corrector: Corrector? = null
    /** Told about a word the owner kept by undoing its correction, so it can be remembered. */
    var onLearned: (String) -> Unit = {}

    private class Correction(val typed: String, val fixed: String, val separator: String)
    private var last: Correction? = null
    private var lastSpaceAt = Long.MIN_VALUE / 2

    fun reset() { last = null; lastSpaceAt = Long.MIN_VALUE / 2 }

    /** Something typed on the keyboard. */
    fun type(text: String, rules: Rules) {
        val spaceAgain = text == " " && clock() - lastSpaceAt < DOUBLE_SPACE_MS
        last = null
        if (text == " " && rules.doubleSpacePeriod && spaceAgain && endsWithWordAndSpace()) {
            field.batch { field.delete(1); field.commit(". ") }
            lastSpaceAt = Long.MIN_VALUE / 2
            return
        }
        if (isSeparator(text) && rules.autocorrect) {
            val fix = correctWord(text)
            if (fix != null) { field.batch { field.delete(fix.typed.length); field.commit(fix.fixed + text) }; last = fix }
            else field.commit(text)
        } else field.commit(text)
        lastSpaceAt = if (text == " ") clock() else Long.MIN_VALUE / 2
    }

    /** Return is about to be sent: finish the word first. Return itself is the caller's to send. */
    fun beforeEnter(rules: Rules) {
        last = null
        lastSpaceAt = Long.MIN_VALUE / 2
        if (!rules.autocorrect) return
        val fix = correctWord("") ?: return
        field.batch { field.delete(fix.typed.length); field.commit(fix.fixed) }
    }

    /**
     * Backspace. Straight after a correction it puts back what was typed, separator and all,
     * and remembers the word; returns true when it did that, false to delete as usual.
     */
    fun backspace(): Boolean {
        val fix = last ?: return false
        last = null
        lastSpaceAt = Long.MIN_VALUE / 2
        val done = fix.fixed + fix.separator
        if (field.before(done.length).toString() != done) return false
        field.batch { field.delete(done.length); field.commit(fix.typed + fix.separator) }
        corrector?.learn(fix.typed)
        onLearned(fix.typed)
        return true
    }

    /** Anything else that moves on from the last correction (the cursor moved, say). */
    fun forget() { last = null }

    private fun correctWord(separator: String): Correction? {
        val c = corrector ?: return null
        val before = field.before(MAX_WORD + 1)
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        // A word glued to something else (a URL, an address, a number) is not ours to touch.
        if (i > 0 && !before[i - 1].isWhitespace() && before[i - 1] !in OPENERS) return null
        val word = before.substring(i).trimEnd('\'')
        if (word.isEmpty() || word.length != before.length - i) return null
        val fixed = c.correct(word) ?: return null
        return Correction(word, fixed, separator)
    }

    private fun endsWithWordAndSpace(): Boolean {
        val b = field.before(2)
        return b.length == 2 && b[1] == ' ' && (b[0].isLetterOrDigit() || b[0] in CLOSERS)
    }

    companion object {
        const val DOUBLE_SPACE_MS = 1000L
        private const val MAX_WORD = 32
        private const val OPENERS = "(\"'[{“‘¿¡"
        private const val CLOSERS = ")\"']}”’"
        private const val SEPARATORS = " .,!?;:)]}\"…"

        fun isSeparator(text: String) = text.length == 1 && text[0] in SEPARATORS
    }
}
