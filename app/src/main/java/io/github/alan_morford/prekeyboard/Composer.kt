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
        fun after(n: Int): CharSequence = ""
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

    /** The word the last swipe typed, while it is still the last thing typed. */
    var swiped: String? = null
        private set
    /** The swipe put a space in front of its word, which a backspace takes too. */
    private var swipedSpace = false

    fun reset() { last = null; swiped = null; lastSpaceAt = Long.MIN_VALUE / 2 }

    /** Something typed on the keyboard. */
    fun type(text: String, rules: Rules) {
        val spaceAgain = text == " " && clock() - lastSpaceAt < DOUBLE_SPACE_MS
        last = null
        swiped = null
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
        swiped = null
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
        swiped?.let { w ->
            swiped = null
            if (field.before(w.length).toString() != w) return false
            field.delete(w.length + if (swipedSpace) 1 else 0)
            return true
        }
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
    fun forget() { last = null; swiped = null }

    /**
     * A swiped word. It gets a space in front when it follows a word or a full stop; none
     * after, so punctuation can follow it, and the next swipe brings its own space.
     */
    fun swipe(word: String) {
        last = null
        lastSpaceAt = Long.MIN_VALUE / 2
        val b = field.before(1)
        swipedSpace = b.isNotEmpty() && !b[0].isWhitespace() && b[0] !in OPENERS
        field.commit((if (swipedSpace) " " else "") + word)
        swiped = word
    }

    /**
     * A word tapped in the suggestion bar. After a swipe it replaces the swiped word;
     * otherwise it replaces the word being typed and adds a space. [learn]: the owner chose
     * what they typed over the correction, so it is never corrected again.
     */
    fun pick(word: String, learn: Boolean) {
        last = null
        val sw = swiped
        if (sw != null && field.before(sw.length).toString() == sw) {
            field.batch { field.delete(sw.length); field.commit(word) }
            swiped = word
            return
        }
        swiped = null
        val typed = currentWord()
        field.batch { field.delete(typed.length); field.commit("$word ") }
        // A space after a picked word counts as the first of a double-tapped space.
        lastSpaceAt = clock()
        if (learn) { corrector?.learn(word); onLearned(word) }
    }

    /**
     * The word the cursor is at the end of, as far as typed: "" when there is none, or when
     * the cursor is inside a word or the word is glued to something that isn't a word.
     */
    fun currentWord(): String {
        val after = field.after(1)
        if (after.isNotEmpty() && (after[0].isLetterOrDigit() || after[0] == '\'')) return ""
        val before = field.before(MAX_WORD + 1)
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        if (i > 0 && !before[i - 1].isWhitespace() && before[i - 1] !in OPENERS) return ""
        if (before.length - i > MAX_WORD) return ""
        return before.substring(i)
    }

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
