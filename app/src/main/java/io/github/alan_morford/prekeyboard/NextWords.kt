package io.github.alan_morford.prekeyboard

/**
 * The words likeliest to come next, for the suggestion bar before a word is typed: from
 * next_words.txt (what follows what in everyday English, see tools/build_next_words.py), and
 * ahead of that, what the owner has typed after the same word before.
 *
 * A context is a word in lower case, [START] (a sentence's start) or [COMMA] (after a comma).
 *
 * Plain Kotlin with no Android in it, so the tests run it directly.
 */
class NextWords(lines: Sequence<String>) {

    private val common = HashMap<String, List<String>>(16384)

    /** What the owner typed after each context, and how often. */
    private val owned = HashMap<String, HashMap<String, Int>>()
    private var ownedPairs = 0

    /** Something learned since the last [save]. */
    var dirty = false
        private set

    init {
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            common[line.substring(0, tab)] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
        }
    }

    /**
     * Up to [max] words for after [context], likeliest first: the owner's own habits (seen at
     * least twice, or once when nothing else is known) before the common ones. [ok] filters
     * out what shouldn't be offered (a word the dictionary doesn't know, say).
     */
    fun predict(context: String, max: Int = 3, ok: (String) -> Boolean = { true }): List<String> {
        val out = LinkedHashSet<String>()
        val mine = owned[context]?.entries?.sortedByDescending { it.value }.orEmpty()
        for ((w, n) in mine) if (n >= 2 && ok(w)) out += w
        for (w in common[context].orEmpty()) if (out.none { it.equals(w, ignoreCase = true) }) out += w
        for ((w, _) in mine) if (out.size < max && ok(w) && out.none { it.equals(w, ignoreCase = true) }) out += w
        return out.take(max)
    }

    /** The owner typed [word] after [context]. */
    fun learn(context: String, word: String) {
        val m = owned.getOrPut(context) { HashMap() }
        val had = m[word]
        m[word] = (had ?: 0) + 1
        if (had == null) ownedPairs++
        dirty = true
        if (ownedPairs > MAX_PAIRS) forgetRare()
    }

    /** Too much kept: let go of what was typed only once. */
    private fun forgetRare() {
        val it = owned.values.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.values.removeAll { n -> n < 2 }
            if (m.isEmpty()) it.remove()
        }
        ownedPairs = owned.values.sumOf { it.size }
    }

    /** The owner's pairs, one per line: context, word, count. */
    fun save(): String {
        dirty = false
        val sb = StringBuilder()
        for ((ctx, m) in owned) for ((w, n) in m) sb.append(ctx).append('\t').append(w).append('\t').append(n).append('\n')
        return sb.toString()
    }

    /** Pairs [save] wrote, back in. */
    fun load(saved: Sequence<String>) {
        for (line in saved) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val n = parts[2].toIntOrNull() ?: continue
            val m = owned.getOrPut(parts[0]) { HashMap() }
            if (m.put(parts[1], n) == null) ownedPairs++
        }
    }

    companion object {
        const val START = "<s>"
        const val COMMA = "<,>"
        private const val MAX_PAIRS = 20000
    }
}
