package io.github.alan_morford.prekeyboard

/**
 * What typing does to the text: autocorrect when a word is finished, undoing a correction
 * with the very next backspace, and a double-tapped space becoming ". ".
 *
 * A period and a swiped word each bring the space after them (an "auto space"). It stays the
 * keyboard's while it is the last thing typed: a space typed on top of it is absorbed rather
 * than doubled, and sentence punctuation goes against the word, so a period after a swiped
 * word lands on the word and "..." stays together. A backspace after a period takes only its
 * space; after a swipe it takes the word and its spaces.
 *
 * A space typed after a word (or the one a picked word brings) is the owner's, but sentence
 * punctuation typed straight after it still goes against the word, and the space moves to
 * after the punctuation: "word ," becomes "word, ".
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
    class Rules(val autocorrect: Boolean, val doubleSpacePeriod: Boolean, val prose: Boolean = true)

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

    /** The text an auto space ends, space included (". " or "word "), while it is the last thing typed. */
    private var autoTail: String? = null
    /** The auto space the last swipe followed, given back when a backspace takes the swipe. */
    private var swipedOn: String? = null
    /** A word's last character and the space typed after it, while that space is the last thing typed. */
    private var spaceTail: String? = null

    /** Remembers the space just typed, if it follows a word, for punctuation to swap with. */
    private fun markSpace() {
        val b = field.before(2).toString()
        spaceTail = b.takeIf { it.length == 2 && it[1] == ' ' && (it[0].isLetterOrDigit() || it[0] in CLOSERS) }
    }

    /** The space typed after a word is still the last thing typed. */
    private fun spaceTyped(): Boolean {
        val t = spaceTail ?: return false
        return field.before(t.length).toString() == t
    }

    /** The auto space is still there, with what it followed. */
    private fun autoSpaced(): Boolean {
        val t = autoTail ?: return false
        return field.before(t.length).toString() == t
    }

    fun reset() { last = null; swiped = null; autoTail = null; spaceTail = null; lastSpaceAt = Long.MIN_VALUE / 2 }

    /** Something typed on the keyboard. */
    fun type(text: String, rules: Rules) {
        val spaceAgain = text == " " && clock() - lastSpaceAt < DOUBLE_SPACE_MS
        val spaced = autoSpaced()
        val tail = spaceTail?.takeIf { spaceTyped() }
        val typedSpace = tail != null
        autoTail = null
        spaceTail = null
        swiped = null
        // A space on top of the keyboard's own: already there. It still counts toward a double space.
        if (text == " " && spaced) { last = null; lastSpaceAt = clock(); markSpace(); return }
        last = null
        if (text == " " && rules.doubleSpacePeriod && spaceAgain && endsWithWordAndSpace()) {
            field.batch { field.delete(1); field.commit(". ") }
            autoTail = ". "
            lastSpaceAt = Long.MIN_VALUE / 2
            return
        }
        // A period after a number and a space starts something (3 .5, 3.14 .net); after a word, it ends it.
        val afterNumber = typedSpace && text == "." && tail!![0].isDigit()
        val snap = (spaced || (typedSpace && !afterNumber)) && text.length == 1 && text[0] in SENTENCE
        field.batch {
            // Sentence punctuation goes against the word, not after the space.
            if (snap) field.delete(1)
            if (isSeparator(text) && rules.autocorrect) {
                val fix = correctWord(text)
                if (fix != null) { field.delete(fix.typed.length); field.commit(fix.fixed + text); last = fix }
                else field.commit(text)
            } else field.commit(text)
            if (text == "." && rules.prose && periodWantsSpace()) {
                field.commit(" ")
                autoTail = ". "
            } else if (snap && typedSpace) {
                // The owner's space moves to after the punctuation, where it belongs.
                field.commit(" ")
                autoTail = "$text "
            }
        }
        if (text == " ") markSpace()
        lastSpaceAt = if (text == " ") clock() else Long.MIN_VALUE / 2
    }

    /**
     * Not after a digit (3.14), after a space or at the start of the text (.net), or in an
     * address or a link (name@mail.com, www.site, a/b.c); and not when a space already
     * follows the cursor.
     */
    private fun periodWantsSpace(): Boolean {
        val b = field.before(MAX_WORD + 2).toString()
        if (b.length < 2) return false
        val prev = b[b.length - 2]
        if (prev.isWhitespace() || prev.isDigit()) return false
        val token = b.dropLast(1).substringAfterLast(' ').substringAfterLast('\n')
        if ('@' in token || '/' in token || token.startsWith("www", ignoreCase = true)) return false
        val next = field.after(1)
        return next.isEmpty() || !next[0].isWhitespace()
    }

    /** Return is about to be sent: finish the word first. Return itself is the caller's to send. */
    fun beforeEnter(rules: Rules) {
        last = null
        swiped = null
        lastSpaceAt = Long.MIN_VALUE / 2
        spaceTail = null
        // The keyboard's own space is not left trailing on the line.
        if (autoSpaced()) field.delete(1)
        autoTail = null
        if (!rules.autocorrect) return
        val fix = correctWord("") ?: return
        field.batch { field.delete(fix.typed.length); field.commit(fix.fixed) }
    }

    /**
     * Backspace. Straight after a correction it puts back what was typed, separator and all,
     * and remembers the word; returns true when it did that, false to delete as usual.
     */
    fun backspace(): Boolean {
        val spaced = autoSpaced()
        autoTail = null
        spaceTail = null
        swiped?.let { w ->
            swiped = null
            val tail = w + if (spaced) " " else ""
            if (field.before(tail.length).toString() != tail) return false
            field.delete(tail.length + if (swipedSpace) 1 else 0)
            autoTail = swipedOn
            return true
        }
        // After a period: just its space, here, before any undo of the word's correction.
        if (spaced) return false
        val fix = last ?: return false
        last = null
        lastSpaceAt = Long.MIN_VALUE / 2
        val done = fix.fixed + fix.separator
        if (field.before(done.length).toString() != done) return false
        field.batch { field.delete(done.length); field.commit(fix.typed + fix.separator) }
        if (fix.separator == " ") markSpace()
        corrector?.learn(fix.typed)
        onLearned(fix.typed)
        return true
    }

    /** Anything else that moves on from the last correction (the cursor moved, say). */
    fun forget() { last = null; swiped = null; autoTail = null; spaceTail = null }

    /** The swiped word is still the last thing typed (with its space, if that is still there). */
    fun swipedIsLast(): Boolean {
        val w = swiped ?: return false
        val tail = w + if (autoSpaced()) " " else ""
        if (field.before(tail.length).toString() != tail) return false
        val after = field.after(1)
        return after.isEmpty() || !after[0].isLetterOrDigit()
    }

    /**
     * A swiped word. It gets a space in front when it follows a word or a full stop, and an
     * auto space after, so typing or swiping on needs no space key and a period still lands
     * on the word. The word in front is finished as a typed space would finish it, so it is
     * corrected too ("i" becomes "I").
     */
    fun swipe(word: String, rules: Rules) {
        last = null
        lastSpaceAt = Long.MIN_VALUE / 2
        swipedOn = autoTail?.takeIf { autoSpaced() }
        autoTail = null
        spaceTail = null
        val b = field.before(1)
        swipedSpace = b.isNotEmpty() && !b[0].isWhitespace() && b[0] !in OPENERS
        field.batch {
            if (swipedSpace && rules.autocorrect) correctWord(" ")?.let { fix ->
                field.delete(fix.typed.length); field.commit(fix.fixed)
            }
            field.commit((if (swipedSpace) " " else "") + word + " ")
        }
        swiped = word
        autoTail = "$word "
    }

    /**
     * A word tapped in the suggestion bar. After a swipe it replaces the swiped word;
     * otherwise it replaces the word being typed and adds a space. [learn]: the owner chose
     * what they typed over the correction, so it is never corrected again.
     */
    fun pick(word: String, learn: Boolean) {
        last = null
        val sw = swiped
        if (sw != null && swipedIsLast()) {
            val spaced = autoSpaced()
            field.batch { field.delete(sw.length + if (spaced) 1 else 0); field.commit("$word ") }
            swiped = word
            autoTail = "$word "
            return
        }
        swiped = null
        autoTail = null
        val typed = currentWord()
        field.batch { field.delete(typed.length); field.commit("$word ") }
        markSpace()
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

    /**
     * What the next word follows, for guessing it before it is typed: the word before the
     * cursor in lower case, [NextWords.START] at the start of the text or a sentence, or
     * [NextWords.COMMA] after a comma. Null when the cursor isn't after a space (a word is
     * being typed, or something is glued on), or what is before it isn't a plain word.
     */
    fun nextContext(): String? {
        val after = field.after(1)
        if (after.isNotEmpty() && !after[0].isWhitespace()) return null
        val before = field.before(MAX_WORD + 2).toString()
        if (before.isNotEmpty() && !before.last().isWhitespace()) return null
        return contextOf(before)
    }

    /**
     * The word just finished (a space or sentence punctuation typed after it) and what it
     * followed, for learning what the owner types after what. Null when there is none.
     */
    fun finishedPair(): Pair<String, String>? {
        val before = field.before(2 * MAX_WORD + 4).toString()
        val end = before.trimEnd { it.isWhitespace() || it in SENTENCE }
        if (end.length == before.length) return null
        var i = end.length
        while (i > 0 && (end[i - 1].isLetter() || end[i - 1] == '\'')) i--
        val word = end.substring(i).trim('\'')
        if (word.isEmpty() || (i > 0 && !end[i - 1].isWhitespace() && end[i - 1] !in OPENERS)) return null
        // Only a word that ended the whole text before it is known to have run to its start.
        if (i == 0 && before.length >= 2 * MAX_WORD + 4) return null
        val ctx = contextOf(end.substring(0, i)) ?: return null
        return ctx to word
    }

    /** The context the text [before] ends in (see [nextContext]); [before] may end in spaces. */
    private fun contextOf(before: String): String? {
        val t = before.trimEnd()
        if (t.isEmpty()) return if (before.length < MAX_WORD) NextWords.START else null
        if (before.length > t.length && '\n' in before.substring(t.length)) return NextWords.START
        val last = t.last()
        if (last in ".!?") return NextWords.START
        if (last == ',') return NextWords.COMMA
        if (!last.isLetter() && last != '\'') return null
        var i = t.length
        while (i > 0 && (t[i - 1].isLetter() || t[i - 1] == '\'')) i--
        if (i > 0 && !t[i - 1].isWhitespace() && t[i - 1] !in OPENERS) return null
        if (i == 0 && t.length > MAX_WORD) return null
        return t.substring(i).trim('\'').lowercase().ifEmpty { null }
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
        /** Punctuation that goes against the word before it, never after a space. */
        private const val SENTENCE = ".,?!:;"

        fun isSeparator(text: String) = text.length == 1 && text[0] in SEPARATORS
    }
}
