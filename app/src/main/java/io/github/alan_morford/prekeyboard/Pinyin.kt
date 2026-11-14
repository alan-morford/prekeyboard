package io.github.alan_morford.prekeyboard

/**
 * Simplified Chinese typed as pinyin: the letters typed so far ("nihao") and the words they
 * may be (你好), likeliest first, from assets/zh/pinyin.txt (tools/build_pinyin.py), whose
 * lines are "pinyin<TAB>word<TAB>score" with the pinyin in plain letters (ü as v).
 *
 * A candidate may cover only the start of what was typed ("xianzai" offers 现在 for all of
 * it, and 现 and 先 for "xian"): picking it leaves the rest to go on with. When no one word
 * covers the whole, the likeliest run of words that does comes first ("jintiantianqihenhao":
 * 今天天气很好).
 *
 * Words picked are remembered ([learn]), so they come up sooner next time, and a run of words
 * picked whole becomes a word of its own.
 *
 * Plain Kotlin with no Android in it, so the tests run it directly.
 */
class Pinyin(lines: Sequence<String>) {

    /** A word for the first [consumed] letters of what was typed. */
    data class Candidate(val word: String, val consumed: Int)

    private class Word(val word: String, val score: Float)

    /** Each spelling's words, likeliest first. */
    private val words = HashMap<String, MutableList<Word>>()
    /** Every spelling, sorted, for finding those that start with what was typed. */
    private val keys: Array<String>
    private var longest = 0
    /** How many times the owner picked each word for each spelling: "nihao\t你好". */
    private val picked = HashMap<String, Int>()

    init {
        for (line in lines) {
            if (line.startsWith("#")) continue
            val t = line.split('\t')
            if (t.size != 3) continue
            val score = t[2].toFloatOrNull() ?: continue
            words.getOrPut(t[0]) { ArrayList(2) }.add(Word(t[1], score))
        }
        keys = words.keys.toTypedArray().also { it.sort() }
        longest = keys.maxOfOrNull { it.length } ?: 0
    }

    val size get() = words.values.sumOf { it.size }

    /** [w]'s score for spelling [key], with what the owner has picked counting for it. */
    private fun scoreOf(key: String, w: Word): Float {
        val n = picked["$key\t${w.word}"] ?: return w.score
        return w.score + LEARNED + Math.min(n, 8) * PER_PICK
    }

    /** [key]'s words, likeliest (to this owner) first. */
    private fun wordsFor(key: String): List<Word> {
        val list = words[key] ?: return emptyList()
        if (picked.isEmpty()) return list
        return list.sortedByDescending { scoreOf(key, it) }
    }

    /** What [typed] (lowercase letters) may be, likeliest first, at most [max]. */
    fun candidates(typed: String, max: Int = 60): List<Candidate> {
        if (typed.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()
        fun add(word: String, consumed: Int) {
            if (out.size < max && word !in out) out[word] = Candidate(word, consumed)
        }
        val exact = wordsFor(typed)
        val phrase = phrase(typed)
        // The whole of it: one word, or the likeliest run of words, then the other words.
        if (exact.isNotEmpty()) {
            add(exact[0].word, typed.length)
            if (phrase != null) add(phrase, typed.length)
            for (w in exact) add(w.word, typed.length)
        } else if (phrase != null) add(phrase, typed.length)
        // Longer words it may be the start of ("zhongg": 中国), the commonest few.
        for (w in completions(typed, COMPLETIONS)) add(w, typed.length)
        // Then words for its start, longest first, down to the first syllable's characters.
        for (end in typed.length - 1 downTo 1) {
            for (w in wordsFor(typed.substring(0, end))) add(w.word, end)
        }
        return out.values.toList()
    }

    /**
     * The likeliest run of words that spells the whole of [typed]: each word counts by how
     * rare it is (its score below the commonest's) and a cost of its own, so fewer, longer,
     * commoner words win. Null when it can't be spelt so, or it is one word.
     */
    private fun phrase(typed: String): String? {
        val n = typed.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        val word = arrayOfNulls<String>(n + 1)
        best[0] = 0f
        for (i in 1..n) {
            for (j in Math.max(0, i - longest) until i) {
                if (best[j] == Float.NEGATIVE_INFINITY) continue
                val key = typed.substring(j, i)
                val top = wordsFor(key).firstOrNull() ?: continue
                val v = best[j] + (scoreOf(key, top) - TOP_SCORE) - WORD_COST
                if (v > best[i]) { best[i] = v; from[i] = j; word[i] = top.word }
            }
        }
        if (best[n] == Float.NEGATIVE_INFINITY) return null
        val parts = ArrayList<String>()
        var i = n
        while (i > 0) { parts += word[i]!!; i = from[i] }
        if (parts.size < 2) return null
        return parts.reversed().joinToString("")
    }

    /** The commonest words whose spelling starts with [typed] and goes on past it. */
    private fun completions(typed: String, max: Int): List<String> {
        var i = keys.binarySearch(typed).let { if (it < 0) -it - 1 else it + 1 }
        val found = ArrayList<Pair<String, Float>>()
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(typed) && scanned < SCAN) {
            val k = keys[i]
            // A short start ("z") leads everywhere: only words a little longer are worth showing.
            if (k.length - typed.length <= MAX_MORE) {
                val w = wordsFor(k).first()
                found += w.word to scoreOf(k, w) - COMPLETION_COST
            }
            i++; scanned++
        }
        return found.sortedByDescending { it.second }.take(max).map { it.first }
    }

    /** The owner picked [word] for [key]: it comes up sooner from now on, and is a word if it wasn't. */
    fun learn(key: String, word: String) {
        if (key.isEmpty() || word.isEmpty()) return
        if (words[key]?.none { it.word == word } != false) {
            words.getOrPut(key) { ArrayList(1) }.add(Word(word, 0f))
            longest = Math.max(longest, key.length)
        }
        picked["$key\t$word"] = (picked["$key\t$word"] ?: 0) + 1
    }

    /** What was picked, as lines to keep: "pinyin<TAB>word<TAB>times". */
    fun picks(): List<String> = picked.map { (k, n) -> "$k\t$n" }

    /** Picks kept from before (the lines [picks] gave). */
    fun load(lines: Sequence<String>) {
        for (line in lines) {
            val t = line.split('\t')
            if (t.size != 3) continue
            val n = t[2].toIntOrNull() ?: continue
            if (words[t[0]]?.none { it.word == t[1] } != false) {
                words.getOrPut(t[0]) { ArrayList(1) }.add(Word(t[1], 0f))
                longest = Math.max(longest, t[0].length)
            }
            picked["${t[0]}\t${t[1]}"] = n
        }
    }

    companion object {
        /** A word picked before counts as this much commoner, and each pick a little more. */
        private const val LEARNED = 6f
        private const val PER_PICK = 1f
        /** What each word in a run costs, so a run of fewer, longer words wins. */
        private const val WORD_COST = 2f
        /** About the commonest word's score: a word's score less this is how rare it is. */
        private const val TOP_SCORE = 24f
        private const val COMPLETIONS = 6
        private const val COMPLETION_COST = 3f
        private const val MAX_MORE = 6
        private const val SCAN = 4000

        /** Chinese punctuation for what the keys type: full width, as Chinese text is written. */
        val PUNCTUATION = mapOf(
            "," to "，", "." to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；",
            "(" to "（", ")" to "）", "…" to "……",
        )
    }
}
