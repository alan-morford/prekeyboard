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
 * - In other languages ([Language]): a missing accent is the cheapest slip of all ("etre" ->
 *   "être", "strasse" -> "straße"), the contraction rules are English only, and a word
 *   elided against the next ("l'homme", "dell'anno") is the word after its apostrophe,
 *   with the apostrophe put in where it was left out ("cest" -> "c'est").
 *
 * Plain Kotlin with no Android in it, so the tests run it directly.
 */
class Corrector(lines: Sequence<String>, val lang: Language = Language.EN_US) {

    private class Entry(val word: String, val score: Int)

    /** Keyed by lower case; the value keeps the word's own case ("I", "London", "don't"). */
    private val words = HashMap<String, Entry>(131072)

    /**
     * Words the owner has added or picked as typed over a correction: never corrected again.
     * Keyed by lower case; the value is the owner's spelling, capitals and all ("webOS").
     */
    // Read by the suggestion thread while the keyboard may add to it: synchronised.
    private val learned: MutableMap<String, String> = java.util.Collections.synchronizedMap(HashMap<String, String>())

    init {
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            // A regional dictionary's word that the one before it had and it hasn't: "-color".
            if (line[0] == '-') {
                val key = line.substring(1).lowercase()
                if (words[key]?.word == line.substring(1)) words.remove(key)
                continue
            }
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

    /**
     * For a language with accents: each word's spelling in plain letters ("deja"), and the
     * commonest word that is spelt so with its accents ("déjà"), where that differs.
     */
    private val plain: HashMap<String, Entry> = HashMap<String, Entry>().apply {
        if (lang.accents.isEmpty()) return@apply
        for ((k, e) in words) {
            val f = Language.fold(k)
            if (f == k) continue
            val had = this[f]
            if (had == null || e.score > had.score) this[f] = e
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
    fun entries(): Sequence<Pair<String, Int>> =
        words.values.asSequence().filter { it.word.lowercase() !in removed }.map { it.word to it.score }

    /**
     * Words the owner took out of the dictionary ("yest", "om"): never offered or corrected
     * to again, and corrected away from when typed, as if the word list didn't have them.
     */
    // Read by the suggestion thread while the keyboard may add to it: synchronised.
    private val removed: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The dictionary's entry for a lower-case [key], unless the owner took it out. */
    private fun entry(key: String): Entry? = words[key]?.takeIf { key !in removed }

    fun remove(word: String) { val k = word.lowercase(); removed += k; learned.remove(k) }
    fun removeAll(ws: Collection<String>) { for (w in ws) remove(w) }
    /** Put back in, from the dictionary screen. */
    fun restore(word: String) { removed -= word.lowercase() }

    /** The dictionary's own spelling and score for [word], taken out or not; null if it hasn't it. */
    fun listed(word: String): Pair<String, Int>? = words[word.lowercase()]?.let { it.word to it.score }

    /** [word] learned as spelt: one with capitals ("webOS") is what its lower case becomes. */
    fun learn(word: String) { learned[word.lowercase()] = word }
    fun learnAll(ws: Collection<String>) { for (w in ws) learn(w) }
    /** The owner took [word] out of their dictionary: corrected again, unless the dictionary has it. */
    fun unlearn(word: String) { learned.remove(word.lowercase()) }

    /** The owner's spelling of the word [lower] when it has capitals ("webOS"); null otherwise. */
    private fun cased(lower: String): String? = learned[lower]?.takeIf { it != lower }

    fun isKnown(word: String): Boolean {
        lang.elided(word)?.let { return isKnown(it.second) || word.lowercase() in learned.keys }
        return word.lowercase().let { entry(it) != null || it in learned.keys }
    }

    /**
     * [word] spelt as the dictionary spells it ("i'm" -> "I'm"), or null for a word it doesn't
     * know. An elided word ("l'homme") is spelt as the word after its apostrophe ("homme").
     */
    fun spelling(word: String): String? {
        lang.elided(word)?.let { return spelling(it.second) }
        val lower = word.lowercase()
        cased(lower)?.let { return it }
        return entry(lower)?.word ?: learned[lower]
    }

    /**
     * The correction for [typed], or null to leave it as typed. [context] is what it follows,
     * as NextWords has it (a word in lower case, [NextWords.START] or [NextWords.COMMA]).
     */
    fun correct(typed: String, context: String? = null): String? {
        if (typed.isEmpty() || typed.length > MAX_LENGTH) return null
        if (!typed.all { it.isLetter() || it == '\'' } || !typed[0].isLetter()) return null
        val lower = typed.lowercase()
        // The owner's own word: only its capitals can be wrong ("webos" for "webOS").
        cased(lower)?.let { own ->
            if (typed != lower && typed != lower.replaceFirstChar { it.uppercaseChar() }) return null
            return casedLike(typed, own).takeIf { it != typed }
        }
        if (lower in learned.keys) return null
        // A word typed all in capitals, beyond one letter, is meant that way (NASA, OK).
        if (typed.length > 1 && typed.all { it.isUpperCase() || it == '\'' }) return null
        // "l'hommme": the word after the apostrophe is the one to correct.
        lang.elided(typed)?.let { (pre, rest) -> return correct(rest, null)?.let { pre + it } }

        if (lang.english) {
            CONTRACTIONS[lower]?.let { return casedLike(typed, it).takeIf { r -> r != typed } }
            AMBIGUOUS[lower]?.let { if (contractionMeant(lower, context)) return casedLike(typed, it) }
        }

        entry(lower)?.let { e ->
            // Known. Only its capital can be wrong: "i", or "london" for "London".
            return if (typed == lower && e.word != lower) e.word else null
        }
        if (lower.length < 2 || '\'' in lower) return null

        val one = nearestScored(lower, 1).firstOrNull()
        val two = split(lower)
        val elided = elision(lower)
        val accented = accented(lower)
        val best = listOfNotNull(one, two, elided, accented).maxByOrNull { it.second }?.first ?: return null
        return casedLike(typed, best).takeIf { it != typed }
    }

    /** [w] typed without the accents of a word ("deja" for "déjà"), however many it has. */
    private fun accented(w: String): Pair<String, Float>? {
        val e = plain[Language.fold(w)]?.takeIf { it.word.lowercase() !in removed } ?: return null
        if (e.word.equals(w, ignoreCase = true)) return null
        return e.word to e.score - COST_WEIGHT * ACCENT
    }

    /**
     * [w] as an elided word with its apostrophe left out ("cest" -> "c'est", "lhomme" ->
     * "l'homme"), and how likely, on the same scale as [nearestScored]; null when it isn't one.
     */
    private fun elision(w: String): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        for (e in lang.elisions) {
            if (w.length < e.length + 2 || !w.startsWith(e)) continue
            val rest = entry(w.substring(e.length)) ?: continue
            // Only before a vowel or a silent h does a word elide.
            if (Language.base(rest.word[0].lowercaseChar()) !in "aeiouyh") continue
            val value = rest.score - COST_WEIGHT * MISSED_APOSTROPHE
            if (best == null || value > best.second) best = "$e'${rest.word}" to value
        }
        return best
    }

    /**
     * Words for the suggestion bar while [typed] is being typed, likeliest first: what
     * autocorrect would make of it (or the word itself, when it is a word), then the commonest
     * words it begins, then the nearest spellings.
     */
    fun suggestions(typed: String, context: String? = null, max: Int = 3): List<String> {
        if (typed.isEmpty() || typed.length > MAX_LENGTH || !typed[0].isLetter()) return emptyList()
        lang.elided(typed)?.let { (pre, rest) -> return suggestions(rest, null, max).map { pre + it } }
        val lower = typed.lowercase()
        val out = LinkedHashSet<String>()
        val fix = correct(typed, context)
        when {
            fix != null -> out += fix
            lower in learned.keys -> out += typed
            else -> entry(lower)?.let { out += casedLike(typed, it.word) }
        }
        // A word that is also a contraction without its apostrophe ("ill", "its"): the
        // contraction next, since nothing else here would find it; or, when the context made
        // it the contraction, the word as typed next.
        if (lang.english) {
            if (fix != null && lower in AMBIGUOUS) entry(lower)?.let { out += casedLike(typed, it.word) }
            out.firstOrNull()?.let { first -> AMBIGUOUS[first.lowercase()]?.let { out += casedLike(typed, it) } }
        }
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
        lang.elided(word)?.let { (pre, rest) -> return alternatives(rest, max).map { pre + it } }
        val lower = word.lowercase()
        val out = LinkedHashSet<String>()
        fun add(w: String) { if (!w.equals(word, ignoreCase = true) && out.size < max) out += casedLike(word, w) }
        if (lang.english) {
            CONTRACTIONS[lower]?.let { add(it) }
            AMBIGUOUS[lower]?.let { add(it) }
        }
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
            if (sorted[i].length > prefix.length) entry(sorted[i])?.let { found += it }
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
            val ea = entry(a) ?: return
            val eb = entry(b) ?: return
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
            val e = entry(s) ?: continue
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
        if (lang.accents.isNotEmpty()) accentEdits(w) { s -> add(s, ACCENT) }
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

    /**
     * Every spelling of [w] with one letter's accent put in, taken out or changed ("etre":
     * "être"), and the language's two-letter spellings joined ("strasse": "straße", "coeur":
     * "cœur"), to [add].
     */
    private fun accentEdits(w: String, add: (String) -> Unit) {
        val sb = StringBuilder(w.length)
        for (i in w.indices) {
            val b = Language.base(w[i])
            val forms = lang.accentsOf(b) + (if (b != w[i]) b.toString() else "")
            for (c in forms) {
                if (c == w[i]) continue
                sb.setLength(0); sb.append(w, 0, i).append(c).append(w, i + 1, w.length)
                add(sb.toString())
            }
        }
        for ((pair, one) in JOINED) {
            if (one !in lang.accents) continue
            var at = w.indexOf(pair)
            while (at >= 0) {
                add(w.substring(0, at) + one + w.substring(at + 2))
                at = w.indexOf(pair, at + 1)
            }
        }
    }

    private fun adjacentToNeighbour(w: String, i: Int) =
        (i > 0 && adjacent(w[i], w[i - 1])) || (i < w.length - 1 && adjacent(w[i], w[i + 1]))

    /**
     * Key positions on the Pre, whose rows sit in matching columns: A is under Q, and Z
     * (after the orange key) under S, in the language's layout. Neighbours are the keys
     * around a key, diagonals too.
     */
    private val position: Map<Char, Pair<Int, Int>> = HashMap<Char, Pair<Int, Int>>().apply {
        val (top, middle, bottom) = lang.layout.rows
        top.forEachIndexed { i, c -> put(c, i to 0) }
        middle.forEachIndexed { i, c -> put(c, i to 1) }
        bottom.forEachIndexed { i, c -> put(c, i + 1 to 2) }
    }

    fun adjacent(a: Char, b: Char): Boolean {
        val p = position[a] ?: return false
        val q = position[b] ?: return false
        return Math.abs(p.first - q.first) <= 1 && Math.abs(p.second - q.second) <= 1
    }

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

        /** Putting in, taking out or changing an accent: the cheapest slip of all. */
        private const val ACCENT = 0.15f
        /** Leaving out an elision's apostrophe ("cest"): next to nothing. */
        private const val MISSED_APOSTROPHE = 0.3f
        /** Two letters a language writes as one: "ss" as "ß", "oe" as "œ", "ae" as "æ". */
        private val JOINED = listOf("ss" to 'ß', "oe" to 'œ', "ae" to 'æ')

        /**
         * How a word the keyboard learns as it is used is kept: with its capitals when it has
         * one past the first letter ("webOS", "iPhone", "NASA"), otherwise in lower case, since a
         * lone first capital is most often just the start of a sentence.
         */
        fun ownSpelling(word: String): String =
            if (word.drop(1).any { it.isUpperCase() }) word else word.lowercase()

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
