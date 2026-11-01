package io.github.alan_morford.prekeyboard

/**
 * The emoji the suggestion bar offers for a word ("pizza" → 🍕), from emoji_words.txt
 * (tools/build_emoji_words.py): for each word, the likeliest emoji first. The first one the
 * device can draw is the one offered.
 */
class EmojiWords(lines: Sequence<String>, canDraw: (String) -> Boolean) {

    private val byWord = HashMap<String, String>()

    init {
        for (line in lines) {
            if (line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val e = line.substring(tab + 1).split(' ').firstOrNull { it.isNotEmpty() && canDraw(it) } ?: continue
            byWord[line.substring(0, tab)] = e
        }
    }

    /** The emoji for [word], or for the word it is the plural or possessive of; null if none. */
    fun forWord(word: String): String? {
        val w = word.lowercase().trimEnd('\'', '’')
        if (w.isEmpty()) return null
        byWord[w]?.let { return it }
        for (end in ENDINGS) if (w.length > end.length + 1 && w.endsWith(end)) byWord[w.dropLast(end.length)]?.let { return it }
        return null
    }

    private companion object {
        val ENDINGS = listOf("'s", "’s", "es", "s")
    }
}
