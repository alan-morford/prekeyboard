package io.github.alan_morford.prekeyboard

/**
 * The emoji the suggestion bar offers for a word ("pizza" → 🍕), from emoji_words.txt
 * (tools/build_emoji_words.py): for each word, the likeliest emoji first. The first one the
 * device can draw is the one offered; the emoji page's search shows them all.
 */
class EmojiWords(lines: Sequence<String>, canDraw: (String) -> Boolean) {

    private val byWord = HashMap<String, List<String>>()
    /** Every word, shortest first: a search's nearest words come first. */
    private val words: List<String>

    init {
        for (line in lines) {
            if (line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val list = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() && canDraw(it) }
            if (list.isNotEmpty()) byWord[line.substring(0, tab)] = list
        }
        words = byWord.keys.sortedWith(compareBy({ it.length }, { it }))
    }

    /** The emoji for [word], or for the word it is the plural or possessive of; null if none. */
    fun forWord(word: String): String? = allFor(clean(word))?.first()

    private fun clean(word: String) = word.lowercase().trim().trimEnd('\'', '’')

    private fun allFor(w: String): List<String>? {
        if (w.isEmpty()) return null
        byWord[w]?.let { return it }
        for (end in ENDINGS) if (w.length > end.length + 1 && w.endsWith(end)) byWord[w.dropLast(end.length)]?.let { return it }
        return null
    }

    /**
     * Every emoji for what's typed in the emoji page's search: the word's own first, as the
     * suggestion bar would offer them, then those of the words it starts ("pi" → pizza, pig…).
     * Several words: the emoji all of them share, or failing that, any of them's.
     */
    fun search(query: String, limit: Int = 160): List<String> {
        val parts = query.split(' ', '\t', '\n').map { clean(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val each = parts.map { w ->
            val found = LinkedHashSet<String>()
            allFor(w)?.let { found += it }
            for (k in words) if (k.startsWith(w)) found += byWord.getValue(k)
            found
        }
        val shared = each.drop(1).fold(LinkedHashSet(each[0])) { acc, s -> acc.retainAll(s); acc }
        val result = if (shared.isNotEmpty()) shared else each.flatMapTo(LinkedHashSet()) { it }
        return result.take(limit)
    }

    private companion object {
        val ENDINGS = listOf("'s", "’s", "es", "s")
    }
}
