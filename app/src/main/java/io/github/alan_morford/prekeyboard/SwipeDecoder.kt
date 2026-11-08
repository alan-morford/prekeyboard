package io.github.alan_morford.prekeyboard

/**
 * Swipe typing: the words a finger's path across the letters most likely spells.
 *
 * Each dictionary word has an ideal path, straight lines through the centres of its letters'
 * keys. The finger's path is compared with it, and the closeness is traded against how common
 * the word is. A quick finger is sloppy in known ways: it rounds its corners, aims a little off
 * each letter, starts late and lifts early, and hurries along some stretches more than others.
 * So nothing a quick finger does throws a word out; it only costs it:
 *
 * - First a cheap pass over every word whose first and last letters are near the path's ends
 *   and whose path is about as long: how far each letter is from where the finger went, and
 *   how far the ends are off. Only the best few hundred go on.
 * - Then the paths are lined up elastically (dynamic time warping, as in Kristensson and Zhai's
 *   SHARK² "location channel" but letting either path run ahead of the other for a while), so
 *   a stretch swiped fast or a corner cut short doesn't count as being off the word.
 *
 * Distances are in keys: across in key widths, down in row heights. Plain Kotlin, so the
 * tests run it directly. Safe to use from two threads: the live guesses are worked out off
 * the main thread while a learned word may be added on it.
 */
class SwipeDecoder(entries: Sequence<Pair<String, Int>>) {

    /** Where the letters are: centres of the keys a to z, and a key's size, in pixels. */
    class Layout(val x: FloatArray, val y: FloatArray, val keyWidth: Float, val keyHeight: Float)

    private class Word(val word: String, val letters: CharArray, val score: Float, val learned: Boolean)

    /** By first and last letter. */
    private val buckets = HashMap<Int, ArrayList<Word>>()
    /** Words without an apostrophe, to tell "were" from "we're". */
    private val plain = HashSet<String>()

    init { for ((w, s) in entries) add(w, s, learned = false) }

    /** A word to offer, with its log2 frequency score (see the dictionary). */
    @Synchronized fun add(word: String, score: Int) = add(word, score, learned = true)

    @Synchronized private fun add(word: String, score: Int, learned: Boolean) {
        if (score < MIN_SCORE) return
        // An accent is no key of its own: "être" is swiped e-t-r-e, "straße" s-t-r-a-s-e.
        val bare = Language.fold(word.lowercase()).filter { it != '\'' }
        if (bare.length < 2 || bare.any { it !in 'a'..'z' }) return
        // A double letter is one key: "hello" is h-e-l-o to the finger.
        val letters = StringBuilder().apply { for (c in bare) if (isEmpty() || last() != c) append(c) }
        if (letters.length < 2) return
        if ('\'' !in word) plain += bare
        // A contraction can't be told from its bare form ("we'll", "well"): the bare one leads.
        val s = if ('\'' in word) score - 2f else score.toFloat()
        val key = bucket(letters[0], letters[letters.length - 1])
        val list = buckets.getOrPut(key) { ArrayList() }
        if (list.none { it.word == word }) list += Word(word, letters.toString().toCharArray(), s, learned)
    }

    /** A word the owner took out of their dictionary: gone, unless the dictionary has it too. */
    @Synchronized fun remove(word: String) {
        for (list in buckets.values) list.removeAll { it.learned && it.word.equals(word, ignoreCase = true) }
    }

    /** A word the owner deleted from the dictionary: gone, wherever it came from. */
    @Synchronized fun forget(word: String) {
        for (list in buckets.values) list.removeAll { it.word.equals(word, ignoreCase = true) }
    }

    private class Candidate(val w: Word, val letters: Int, val score: Float) {
        /** The word's ideal path, resampled; its point-for-point distance from the finger's. */
        val wx = FloatArray(POINTS); val wy = FloatArray(POINTS)
        var straight = 0f
        /** The score before the distance counts, and the best it can be once it does. */
        var base = 0f
        var bound = 0f
    }

    /**
     * The likeliest words for the path ([px], [py] in pixels), best first; empty for a tap.
     * [likely] are words that often follow the word before, likeliest first (see [NextWords]):
     * where the path can't tell two words apart ("of", "if"), they lean that way. [stale] says
     * the answer is no longer wanted (a live guess the swipe has moved on from): it gives up.
     */
    @Synchronized fun decode(px: FloatArray, py: FloatArray, layout: Layout, max: Int = 4,
                             likely: List<String> = emptyList(), stale: () -> Boolean = { false }): List<String> {
        val n = px.size
        if (n < 2) return emptyList()
        // Into key units.
        val ux = FloatArray(n) { px[it] / layout.keyWidth }
        val uy = FloatArray(n) { py[it] / layout.keyHeight }
        val kx = FloatArray(26) { layout.x[it] / layout.keyWidth }
        val ky = FloatArray(26) { layout.y[it] / layout.keyHeight }
        val length = pathLength(ux, uy)
        if (length < MIN_LENGTH) return emptyList()
        val rx = FloatArray(POINTS); val ry = FloatArray(POINTS)
        resample(ux, uy, rx, ry)

        // What a letter costs depends only on its key: worked out once for all 26. How far
        // past [FREE] each key is from the nearest point the finger passed, and how far past
        // [CORNER_FREE] each sharp turn is from each key.
        val keyMiss = FloatArray(26) { k ->
            var best = Float.MAX_VALUE
            for (j in 0 until POINTS) { val d = dist2(rx[j], ry[j], kx[k], ky[k]); if (d < best) best = d }
            val over = Math.sqrt(best.toDouble()).toFloat() - FREE
            if (over > 0) over * over else 0f
        }
        val corners = corners(rx, ry)
        val turnAt = Array(corners.size) { c ->
            FloatArray(26) { k ->
                val over = Math.sqrt(dist2(rx[corners[c]], ry[corners[c]], kx[k], ky[k]).toDouble()).toFloat() - CORNER_FREE
                if (over > 0) over * over else 0f
            }
        }
        val starts = near(ux[0], uy[0], kx, ky)
        val ends = near(ux[n - 1], uy[n - 1], kx, ky)
        val tx = FloatArray(MAX_LETTERS); val ty = FloatArray(MAX_LETTERS)
        val pool = ArrayList<Candidate>()
        for (s in starts) for (e in ends) {
            if (stale()) return emptyList()
            val list = buckets[bucket('a' + s, 'a' + e)] ?: continue
            for (w in list) {
                val m = w.letters.size
                if (m > MAX_LETTERS) continue
                for (i in 0 until m) { val k = w.letters[i] - 'a'; tx[i] = kx[k]; ty[i] = ky[k] }
                val ideal = pathLength(tx, ty, m)
                // A quick finger cuts corners, so its path may be well short of the word's;
                // but not this short, nor wander this far.
                if (ideal > length * 1.6f + 1.2f || length > ideal * 2f + 2.5f) continue
                // How far each letter is from where the finger went.
                var miss = 0f
                for (i in 1 until m - 1) miss += keyMiss[w.letters[i] - 'a']
                val endMiss = dist2(rx[0], ry[0], tx[0], ty[0]) + dist2(rx[POINTS - 1], ry[POINTS - 1], tx[m - 1], ty[m - 1])
                // Every sharp turn the finger made should be at one of the word's letters.
                var turn = 0f
                for (t in turnAt) {
                    var best = Float.MAX_VALUE
                    for (i in 0 until m) { val d = t[w.letters[i] - 'a']; if (d < best) best = d }
                    turn += best
                }
                val cost = MISS_WEIGHT * miss + END_WEIGHT * endMiss + CORNER_WEIGHT * turn
                pool += Candidate(w, m, w.score * FREQ_WEIGHT - cost)
            }
        }
        if (pool.isEmpty()) return emptyList()
        pool.sortByDescending { it.score }
        val shortlist = pool.subList(0, Math.min(SHORTLIST, pool.size))
        // Each word's best possible score, from its point-for-point distance alone: the elastic
        // one is never less than nothing, so this is as well as the word can do.
        for ((n, c) in shortlist.withIndex()) {
            if (n % 64 == 0 && stale()) return emptyList()
            val w = c.w
            val m = c.letters
            for (i in 0 until m) { val k = w.letters[i] - 'a'; tx[i] = kx[k]; ty[i] = ky[k] }
            resample(tx, ty, c.wx, c.wy, m)
            var straight = 0f
            for (i in 0 until POINTS) straight += Math.sqrt(dist2(rx[i], ry[i], c.wx[i], c.wy[i]).toDouble()).toFloat()
            c.straight = straight / POINTS
            // A path well longer than the word's went somewhere the word doesn't ("there", not "the").
            val longer = Math.max(0f, length - pathLength(tx, ty, m) * 1.1f - 0.4f)
            var score = c.score - LONG_WEIGHT * longer * longer
            // "were", not "we're", when the path can't say which.
            if ('\'' in w.word && w.word.lowercase().filter { it != '\'' } in plain) score -= 2.5f
            val rank = likely.indexOfFirst { it.equals(w.word, ignoreCase = true) }
            if (rank >= 0) score += CONTEXT_BOOST * (1f - rank / (likely.size + 1f))
            c.base = score
            val atBest = STRAIGHT * c.straight
            c.bound = score - atBest * atBest * LOC_WEIGHT
        }
        // Then lined up elastically, likeliest first, till no word left could make the list.
        shortlist.sortByDescending { it.bound }
        val best = ArrayList<Pair<String, Float>>()
        for ((n, c) in shortlist.withIndex()) {
            if (n % 32 == 0 && stale()) return emptyList()
            if (best.size >= max && c.bound < best[max - 1].second) break
            val warped = warp(rx, ry, c.wx, c.wy)
            // Half point for point, as the word's shape, half lined up elastically.
            val loc = STRAIGHT * c.straight + (1 - STRAIGHT) * warped
            val score = c.base - loc * loc * LOC_WEIGHT
            if (best.any { it.first == c.w.word }) continue
            best += c.w.word to score
            best.sortByDescending { it.second }
        }
        best.sortByDescending { it.second }
        return best.asSequence().map { it.first }.distinct().take(max).toList()
    }

    /**
     * Where the path turns sharply (by more than [TURN] degrees over a few points), as indexes
     * into the resampled path. A quick finger rounds a corner, but it still turns there.
     */
    private fun corners(x: FloatArray, y: FloatArray): List<Int> {
        val out = ArrayList<Int>()
        val turn = FloatArray(POINTS)
        val k = 2
        for (i in k until POINTS - k) {
            val ax = x[i] - x[i - k]; val ay = y[i] - y[i - k]
            val bx = x[i + k] - x[i]; val by = y[i + k] - y[i]
            val la = Math.hypot(ax.toDouble(), ay.toDouble()); val lb = Math.hypot(bx.toDouble(), by.toDouble())
            if (la < 0.15 || lb < 0.15) continue
            turn[i] = Math.toDegrees(Math.acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0))).toFloat()
        }
        for (i in k until POINTS - k) {
            if (turn[i] < TURN) continue
            if ((i - k until i).any { turn[it] > turn[i] } || (i + 1..i + k).any { turn[it] >= turn[i] }) continue
            out += i
        }
        return out
    }

    /** Scratch rows for [warp]. */
    private val prev = FloatArray(POINTS)
    private val row = FloatArray(POINTS)

    /**
     * The average distance between the two paths, each point of one matched to a point of the
     * other, in order, where either may run ahead of the other by up to [BAND] points.
     */
    private fun warp(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Float {
        val inf = Float.MAX_VALUE / 4
        for (i in 0 until POINTS) {
            val lo = Math.max(0, i - BAND); val hi = Math.min(POINTS - 1, i + BAND)
            for (j in 0 until POINTS) {
                if (j < lo || j > hi) { row[j] = inf; continue }
                val d = Math.sqrt(dist2(ax[i], ay[i], bx[j], by[j]).toDouble()).toFloat()
                val from = when {
                    i == 0 && j == 0 -> 0f
                    i == 0 -> row[j - 1]
                    j == 0 -> prev[j]
                    else -> Math.min(prev[j - 1], Math.min(prev[j], row[j - 1]))
                }
                row[j] = from + d
            }
            System.arraycopy(row, 0, prev, 0, POINTS)
        }
        return prev[POINTS - 1] / POINTS
    }

    private fun bucket(first: Char, last: Char) = (first - 'a') * 26 + (last - 'a')

    /** Keys within reach of a path's end, nearest first; the nearest one at least. */
    private fun near(x: Float, y: Float, kx: FloatArray, ky: FloatArray): List<Int> {
        val order = (0 until 26).sortedBy { dist2(x, y, kx[it], ky[it]) }
        return order.filterIndexed { i, k -> i == 0 || dist2(x, y, kx[k], ky[k]) < END_R2 }
    }

    companion object {
        private const val POINTS = 40
        private const val TURN = 50f
        private const val MAX_LETTERS = 32
        /** Shorter than this, in keys, is a tap that slipped, not a swipe. */
        private const val MIN_LENGTH = 0.6f
        /** Words rarer than this are left out: swiping them is guesswork anyway. */
        private const val MIN_SCORE = 5
        private const val END_R2 = 1.45f * 1.45f
        /** How far, in keys, a letter may be from the path before it starts to cost. */
        private const val FREE = 0.35f
        private const val MISS_WEIGHT = 2.5f
        private const val END_WEIGHT = 2f
        /** The same for a sharp turn in the path, from the nearest of the word's letters. */
        private const val CORNER_FREE = 0.45f
        private const val CORNER_WEIGHT = 4f
        private const val LONG_WEIGHT = 1f
        /** How many of the cheap pass's best are lined up properly. */
        private const val SHORTLIST = 250
        /** How far (in points) one path may run ahead of the other when they are lined up. */
        private const val BAND = 8
        /** The share of the distance measured point for point; the rest is elastic. */
        private const val STRAIGHT = 0.5f
        /**
         * log2 frequency to natural log, and a Gaussian on the distance: sigma 0.15 keys. These
         * weights were tuned on SwipeBenchTest's simulated quick swipes over the 400 commonest
         * words.
         */
        private const val FREQ_WEIGHT = 0.69f
        /** What following the word before is worth, at most: about a step and a half of frequency. */
        private const val CONTEXT_BOOST = 1f
        private const val LOC_WEIGHT = 1f / (2 * 0.15f * 0.15f)

        private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx; val dy = ay - by
            return dx * dx + dy * dy
        }

        private fun pathLength(x: FloatArray, y: FloatArray, n: Int = x.size): Float {
            var l = 0f
            for (i in 1 until n) l += Math.sqrt(dist2(x[i - 1], y[i - 1], x[i], y[i]).toDouble()).toFloat()
            return l
        }

        /** The polyline ([x], [y], first [n] points) as [ox].size points evenly spaced along it. */
        private fun resample(x: FloatArray, y: FloatArray, ox: FloatArray, oy: FloatArray, n: Int = x.size) {
            val total = pathLength(x, y, n)
            val count = ox.size
            if (total == 0f) { ox.fill(x[0]); oy.fill(y[0]); return }
            val step = total / (count - 1)
            ox[0] = x[0]; oy[0] = y[0]
            var out = 1
            var travelled = 0f   // along the path up to point i-1
            var i = 1
            while (i < n && out < count) {
                val seg = Math.sqrt(dist2(x[i - 1], y[i - 1], x[i], y[i]).toDouble()).toFloat()
                while (out < count && out * step <= travelled + seg) {
                    val t = if (seg == 0f) 0f else (out * step - travelled) / seg
                    ox[out] = x[i - 1] + (x[i] - x[i - 1]) * t
                    oy[out] = y[i - 1] + (y[i] - y[i - 1]) * t
                    out++
                }
                travelled += seg
                i++
            }
            while (out < count) { ox[out] = x[n - 1]; oy[out] = y[n - 1]; out++ }
        }
    }
}
