package io.github.alan_morford.prekeyboard

/**
 * Autocorrect: turns a finished word into the word that was probably meant, or leaves it be.
 *
 * - Contractions typed without their apostrophe get it back: "dont" -> "don't", "im" -> "I'm".
 *   Only the ones whose bare form is not itself an everyday word: "well", "were", "its",
 *   "ill", "hell", "shell", "lets" and "wed" are left alone, but the suggestion bar offers
 *   their contraction beside them ("ill": "I'll"), and where the word before makes the
 *   contraction all but certain ("I think ill", "that its"), they get the apostrophe too.
 * - A lone "i", and a proper noun typed in lower case, get their capital.
 * - An unknown word becomes the dictionary word closest to it, where "close" is an edit
 *   distance that knows the Pre's keyboard: hitting the key next door, swapping two letters
 *   or doubling one costs less than any other slip. Distance is traded against how common the
 *   word is, so "teh" becomes "the" rather than "ten".
 * - Two words run together, the space missed or hit as a letter above it, come apart:
 *   "theyare" and "theynare" -> "they are", when that beats any one word.
 *
 * Plain Kotlin with no Android in it, so the tests run it directly.
 */
class Corrector(lines: Sequence<String>) {

    private class Entry(val word: String, val score: Int)

    /** Keyed by lower case; the value keeps the word's own case ("I", "London", "don't"). */
    private val words = HashMap<String, Entry>(131072)

    /** Words the owner has added or picked as typed over a correction: never corrected again. */
    // Read by the suggestion thread while the keyboard may add to it: synchronised.
    private val learned: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    init {
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val w = line.substring(0, tab)
            val score = line.substring(tab + 1).trim().toIntOrNull() ?: continue
            val key = w.lowercase()
            val had = words[key]
            // Prefer the lower-case spelling when a word comes both ways ("bill" and "Bill").
            if (had == null || (w == key && had.word != key) || (had.word != key && score > had.score))
                words[key] = Entry(w, score)
        }
    }

    /** Every key, sorted, for finding the words a typed prefix begins. */
    private val sorted: Array<String> = words.keys.toTypedArray().also { it.sort() }

    val size get() = words.size

    /** For each [AMBIGUOUS] word after a context ("ill" after "think"): how often it is the word, and how often the contraction. */
    private val odds = HashMap<String, IntArray>()

    /** contractions.txt (tools/build_contractions.py): word, context, times as the word, times as the contraction. */
    fun loadContractions(lines: Sequence<String>) {
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val f = line.split('\t')
            if (f.size < 4) continue
            val a = f[2].toIntOrNull() ?: continue
            val b = f[3].toIntOrNull() ?: continue
            odds[f[0] + '\t' + f[1]] = intArrayOf(a, b)
        }
    }

    /**
     * After [context], [word] (an [AMBIGUOUS] one) is all but certainly its contraction:
     * "ill" after "think" (3 times against 609), but not "well" at a sentence's start, which
     * is "we'll" only 3 times in 4.
     */
    private fun contractionMeant(word: String, context: String?): Boolean {
        val n = odds[word + '\t' + (context ?: return false)] ?: return false
        return n[1] >= MIN_EVIDENCE && n[1] + 1 >= CERTAIN * (n[0] + 1)
    }

    /** Whether one word commonly follows another (from NextWords), to tell run-together words apart. */
    @Volatile var follows: (String, String) -> Boolean = { _, _ -> false }

    /** Every word with its score, for the swipe decoder. */
    fun entries(): Sequence<Pair<String, Int>> = words.values.asSequence().map { it.word to it.score }

    fun learn(word: String) { learned += word.lowercase() }
    fun learnAll(ws: Collection<String>) { for (w in ws) learn(w) }
    /** The owner took [word] out of their dictionary: corrected again, unless the dictionary has it. */
    fun unlearn(word: String) { learned -= word.lowercase() }

    fun isKnown(word: String): Boolean = word.lowercase().let { it in words || it in learned }

    /** [word] spelt as the dictionary spells it ("i'm" -> "I'm"), or null for a word it doesn't know. */
    fun spelling(word: String): String? {
        val lower = word.lowercase()
        return words[lower]?.word ?: lower.takeIf { it in learned }
    }

    /**
     * The correction for [typed], or null to leave it as typed. [context] is what it follows,
     * as NextWords has it (a word in lower case, [NextWords.START] or [NextWords.COMMA]).
     */
    fun correct(typed: String, context: String? = null): String? {
        if (typed.isEmpty() || typed.length > MAX_LENGTH) return null
        if (!typed.all { it.isLetter() || it == '\'' } || !typed[0].isLetter()) return null
        val lower = typed.lowercase()
        if (lower in learned) return null
        // A word typed all in capitals, beyond one letter, is meant that way (NASA, OK).
        if (typed.length > 1 && typed.all { it.isUpperCase() || it == '\'' }) return null

        CONTRACTIONS[lower]?.let { return casedLike(typed, it).takeIf { r -> r != typed } }
        AMBIGUOUS[lower]?.let { if (contractionMeant(lower, context)) return casedLike(typed, it) }

        words[lower]?.let { e ->
            // Known. Only its capital can be wrong: "i", or "london" for "London".
            return if (typed == lower && e.word != lower) e.word else null
        }
        if (lower.length < 2 || '\'' in lower) return null

        val one = nearestScored(lower, 1).firstOrNull()
        val two = split(lower)
        val best = if (two != null && (one == null || two.second > one.second)) two.first else one?.first ?: return null
        return casedLike(typed, best).takeIf { it != typed }
    }

    /**
     * Words for the suggestion bar while [typed] is being typed, likeliest first: what
     * autocorrect would make of it (or the word itself, when it is a word), then the commonest
     * words it begins, then the nearest spellings.
     */
    fun suggestions(typed: String, context: String? = null, max: Int = 3): List<String> {
        if (typed.isEmpty() || typed.length > MAX_LENGTH || !typed[0].isLetter()) return emptyList()
        val lower = typed.lowercase()
        val out = LinkedHashSet<String>()
        val fix = correct(typed, context)
        when {
            fix != null -> out += fix
            lower in learned -> out += typed
            else -> words[lower]?.let { out += casedLike(typed, it.word) }
        }
        // A word that is also a contraction without its apostrophe ("ill", "its"): the
        // contraction next, since nothing else here would find it; or, when the context made
        // it the contraction, the word as typed next.
        if (fix != null && lower in AMBIGUOUS) words[lower]?.let { out += casedLike(typed, it.word) }
        out.firstOrNull()?.let { first -> AMBIGUOUS[first.lowercase()]?.let { out += casedLike(typed, it) } }
        for (w in completions(lower, 2)) out += casedLike(typed, w)
        if (lower.length >= 2 && '\'' !in lower && typed.all { it.isLetter() })
            for (w in nearest(lower, max)) out += casedLike(typed, w)
        for (w in completions(lower, max + 2)) out += casedLike(typed, w)
        return out.take(max)
    }

    /**
     * Other words [word] might have been meant as, for a word tapped in the text, best guess
     * first: the commonest words a slip or two away (`ball` -> `bell`, `call`, `bull`), then
     * longer words it begins. Never [word] itself.
     */
    fun alternatives(word: String, max: Int = 3): List<String> {
        if (word.isEmpty() || word.length > MAX_LENGTH || !word[0].isLetter()) return emptyList()
        val lower = word.lowercase()
        val out = LinkedHashSet<String>()
        fun add(w: String) { if (!w.equals(word, ignoreCase = true) && out.size < max) out += casedLike(word, w) }
        CONTRACTIONS[lower]?.let { add(it) }
        AMBIGUOUS[lower]?.let { add(it) }
        val one = HashMap<String, Float>()
        edits(lower, 0f, one, full = true)
        one.remove(lower)
        // A word tapped to be fixed was most likely the wrong key or vowel, or a swipe that
        // came out a near neighbour: the same length, and closeness counts for more than
        // how common a word is.
        val near = HashMap<String, Float>()
        for ((s, cost) in one) near[s] = when {
            s.length != lower.length -> cost + 0.6f
            vowelSwap(s, lower) -> 0.4f      // ball, bell, bill, bull: said differently, swiped alike
            else -> cost
        }
        for (w in pick(near, maxCost = 1.9f, minScore = 3, max + 1, weight = 2 * COST_WEIGHT)) add(w)
        if (out.size < max && lower.length >= 4) {
            val two = HashMap<String, Float>()
            for ((s, c) in one) edits(s, c, two, full = false)
            two.remove(lower)
            for (w in pick(two, maxCost = 1.9f, minScore = 9, max + 1)) add(w)
        }
        for (w in completions(lower, max + 1)) add(w)
        return out.toList()
    }

    /** [a] and [b] differ in one place only, a vowel for a vowel. */
    private fun vowelSwap(a: String, b: String): Boolean {
        var at = -1
        for (i in a.indices) if (a[i] != b[i]) { if (at >= 0) return false; at = i }
        return at >= 0 && a[at] in VOWELS && b[at] in VOWELS
    }

    /** The commonest words that start with [prefix] and are longer than it. */
    private fun completions(prefix: String, max: Int): List<String> {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (sorted[mid] < prefix) lo = mid + 1 else hi = mid }
        val found = ArrayList<Entry>()
        var i = lo
        var looked = 0
        while (i < sorted.size && sorted[i].startsWith(prefix) && looked < COMPLETION_SCAN) {
            if (sorted[i].length > prefix.length) words[sorted[i]]?.let { found += it }
            i++; looked++
        }
        return found.sortedByDescending { it.score }.take(max).map { it.word }
    }

    // --- Finding the nearest word ------------------------------------------------------------

    private fun nearest(w: String, max: Int): List<String> = nearestScored(w, max).map { it.first }

    /** The likeliest words [w] was a slip or two from, with how likely (higher is likelier). */
    private fun nearestScored(w: String, max: Int): List<Pair<String, Float>> {
        val one = HashMap<String, Float>()
        edits(w, 0f, one, full = true)
        pickScored(one, maxCost = if (w.length <= 3) 0.6f else 1.3f, minScore = if (w.length <= 3) 12 else 5, max)
            .let { if (it.isNotEmpty()) return it }
        if (w.length < 5) return emptyList()
        // Two slips: grow every one-slip spelling once more, with a narrower set of edits so a
        // long word stays quick even on an old phone.
        val two = HashMap<String, Float>()
        for ((s, c) in one) edits(s, c, two, full = false)
        return pickScored(two, maxCost = 1.9f, minScore = 9, max)
    }

    /**
     * [w] as two words whose space was missed ("theyare") or hit as a letter just above the
     * space bar ("theynare"), and how likely, on the same scale as [nearestScored]. Both
     * words must be everyday ones, so a typo is never taken apart into rare bits.
     */
    private fun split(w: String): Pair<String, Float>? {
        if (w.length < 3) return null
        var best: Pair<String, Float>? = null
        fun consider(a: String, b: String, cost: Float) {
            val ea = words[a] ?: return
            val eb = words[b] ?: return
            if (ea.score < halfScore(a) || eb.score < halfScore(b)) return
            val paired = follows(a, b)
            // Two little words that don't go together ("to on" for "toon") are likelier a slip
            // in one word, so such a split costs more and loses to a common word nearby ("too"),
            // but beats a rare one ("innit" for "onnit"), and stands when nothing is near
            // ("nownin" -> "now in"). A longer pair ("I went", "the dog") has no extra cost.
            val little = !paired && a.length + b.length < 6 && maxOf(a.length, b.length) < 4
            val pair = (if (paired) 0f else UNPAIRED) + (if (little) LITTLE_PAIR else 0f)
            val value = minOf(ea.score, eb.score) - COST_WEIGHT * cost - pair
            if (best == null || value > best!!.second) best = "${ea.word} ${eb.word}" to value
        }
        for (i in 1 until w.length) consider(w.substring(0, i), w.substring(i), MISSED_SPACE)
        for (i in 1 until w.length - 1) if (w[i] in ABOVE_SPACE) consider(w.substring(0, i), w.substring(i + 1), SPACE_AS_LETTER)
        return best
    }

    /** How common a word must be to be one half of a split: very, when it is short. */
    private fun halfScore(w: String) = when (w.length) { 1 -> 24; 2 -> 18; 3 -> 14; else -> 10 }

    /** The best known words among [cands]: common, and cheap to reach. Best first. */
    private fun pick(cands: Map<String, Float>, maxCost: Float, minScore: Int, max: Int,
                     weight: Float = COST_WEIGHT): List<String> =
        pickScored(cands, maxCost, minScore, max, weight).map { it.first }

    private fun pickScored(cands: Map<String, Float>, maxCost: Float, minScore: Int, max: Int,
                           weight: Float = COST_WEIGHT): List<Pair<String, Float>> {
        val found = ArrayList<Pair<Entry, Float>>()
        for ((s, cost) in cands) {
            if (cost > maxCost) continue
            val e = words[s] ?: continue
            if (e.score < minScore) continue
            found += e to (e.score - weight * cost)
        }
        return found.sortedByDescending { it.second }.take(max).map { it.first.word to it.second }
    }

    /** Every spelling one slip away from [w], with what the slip costs, into [out]. */
    private fun edits(w: String, base: Float, out: HashMap<String, Float>, full: Boolean) {
        fun add(s: String, c: Float) {
            val total = base + c
            val had = out[s]
            if (had == null || total < had) out[s] = total
        }
        val n = w.length
        val sb = StringBuilder(n + 1)
        for (i in 0 until n) {
            // An extra letter: cheaper when it doubles its neighbour.
            sb.setLength(0); sb.append(w, 0, i).append(w, i + 1, n)
            add(sb.toString(), if (i > 0 && w[i] == w[i - 1]) 0.5f else if (adjacentToNeighbour(w, i)) 0.7f else 1f)
            // Two letters swapped.
            if (i < n - 1 && w[i] != w[i + 1]) {
                sb.setLength(0); sb.append(w, 0, i).append(w[i + 1]).append(w[i]).append(w, i + 2, n)
                add(sb.toString(), 0.6f)
            }
            // The wrong key.
            for (c in ALPHABET) {
                if (c == w[i]) continue
                val cost = when {
                    adjacent(w[i], c) -> 0.6f
                    w[i] in VOWELS && c in VOWELS -> 0.9f
                    else -> if (full) 1.3f else continue
                }
                sb.setLength(0); sb.append(w, 0, i).append(c).append(w, i + 1, n)
                add(sb.toString(), cost)
            }
        }
        // A missed letter.
        for (i in 0..n) {
            for (c in ALPHABET) {
                val doubling = (i > 0 && w[i - 1] == c) || (i < n && w[i] == c)
                if (!full && !doubling && c !in VOWELS &&
                    !(i > 0 && adjacent(w[i - 1], c)) && !(i < n && adjacent(w[i], c))) continue
                sb.setLength(0); sb.append(w, 0, i).append(c).append(w, i, n)
                add(sb.toString(), if (doubling) 0.5f else 1f)
            }
        }
    }

    private fun adjacentToNeighbour(w: String, i: Int) =
        (i > 0 && adjacent(w[i], w[i - 1])) || (i < w.length - 1 && adjacent(w[i], w[i + 1]))

    companion object {
        private const val MAX_LENGTH = 24
        /** An [AMBIGUOUS] word becomes its contraction after a context that makes it this many times likelier... */
        private const val CERTAIN = 10
        /** ...seen this often at least. */
        private const val MIN_EVIDENCE = 10
        /** A one-letter prefix begins thousands of words: look through this many at most. */
        private const val COMPLETION_SCAN = 15000
        /** How many log2-frequency points one unit of edit cost is worth. */
        private const val COST_WEIGHT = 4f
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        private const val VOWELS = "aeiouy"
        /** The letters right above the space bar, which a thumb reaching for it hits instead. */
        private const val ABOVE_SPACE = "cvbn"
        /** Running two words together costs about what a slip onto the next key does... */
        private const val MISSED_SPACE = 0.6f
        /** ...and hitting a letter for the space a little more, since it adds a wrong letter... */
        private const val SPACE_AS_LETTER = 0.8f
        /** ...and more again when the second word isn't one that commonly follows the first. */
        private const val UNPAIRED = 1.5f
        /** Taken off a split of two little words that don't go together. */
        private const val LITTLE_PAIR = 3f

        /**
         * Key positions on the Pre, whose rows sit in matching columns: A is under Q, and Z
         * (after the orange key) under S. Neighbours are the keys around a key, diagonals too.
         */
        private val position: Map<Char, Pair<Int, Int>> = HashMap<Char, Pair<Int, Int>>().apply {
            "qwertyuiop".forEachIndexed { i, c -> put(c, i to 0) }
            "asdfghjkl".forEachIndexed { i, c -> put(c, i to 1) }
            "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1 to 2) }
        }

        fun adjacent(a: Char, b: Char): Boolean {
            val p = position[a] ?: return false
            val q = position[b] ?: return false
            return Math.abs(p.first - q.first) <= 1 && Math.abs(p.second - q.second) <= 1
        }

        /** [word] with the capitals of [typed]: "Teh" -> "The". A word's own capitals stay. */
        fun casedLike(typed: String, word: String): String =
            if (typed[0].isUpperCase() && word[0].isLowerCase()) word.replaceFirstChar { it.uppercaseChar() } else word

        val CONTRACTIONS: Map<String, String> = mapOf(
            "dont" to "don't", "cant" to "can't", "wont" to "won't", "isnt" to "isn't",
            "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "hasnt" to "hasn't",
            "havent" to "haven't", "hadnt" to "hadn't", "doesnt" to "doesn't", "didnt" to "didn't",
            "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't",
            "mustnt" to "mustn't", "neednt" to "needn't", "aint" to "ain't",
            "im" to "I'm", "ive" to "I've", "id" to "I'd",
            "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
            "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
            "weve" to "we've", "hes" to "he's", "shes" to "she's",
            "thats" to "that's", "whats" to "what's", "wheres" to "where's", "whos" to "who's",
            "hows" to "how's", "theres" to "there's", "heres" to "here's", "itll" to "it'll",
            "thatll" to "that'll", "wouldve" to "would've", "couldve" to "could've",
            "shouldve" to "should've", "mightve" to "might've", "mustve" to "must've",
            "hed" to "he'd", "itd" to "it'd", "thatd" to "that'd", "whod" to "who'd",
            "wholl" to "who'll", "whatll" to "what'll",
            "yall" to "y'all", "oclock" to "o'clock", "whatre" to "what're", "whove" to "who've",
        )

        /** Everyday words that are also a contraction typed without its apostrophe: never corrected, only offered. */
        val AMBIGUOUS: Map<String, String> = mapOf(
            "ill" to "I'll", "its" to "it's", "well" to "we'll", "were" to "we're", "lets" to "let's",
            "wed" to "we'd", "hell" to "he'll", "shell" to "she'll", "shed" to "she'd",
        )
    }
}
