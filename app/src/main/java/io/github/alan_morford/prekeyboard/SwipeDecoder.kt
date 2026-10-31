package io.github.alan_morford.prekeyboard

/**
 * Swipe typing: the words a finger's path across the letters most likely spells.
 *
 * Each dictionary word has an ideal path, straight lines through the centres of its letters'
 * keys. The finger's path is compared with it point for point, both cut into the same number
 * of evenly spaced points (the "location channel" of Kristensson and Zhai's SHARK² paper),
 * and the closeness is traded against how common the word is. Before that, cheap checks
 * throw out nearly every word: the path must start near the word's first letter, end near
 * its last, be about as long as the word's path, and pass near every letter in order.
 *
 * Distances are in keys: across in key widths, down in row heights. Plain Kotlin, so the
 * tests run it directly. Safe to use from two threads: the live guesses are worked out off
 * the main thread while a learned word may be added on it.
 */
class SwipeDecoder(entries: Sequence<Pair<String, Int>>) {

    /** Where the letters are: centres of the keys a to z, and a key's size, in pixels. */
    class Layout(val x: FloatArray, val y: FloatArray, val keyWidth: Float, val keyHeight: Float)

    private class Word(val word: String, val letters: CharArray, val score: Float)

    /** By first and last letter. */
    private val buckets = HashMap<Int, ArrayList<Word>>()

    init { for ((w, s) in entries) add(w, s) }

    /** A word to offer, with its log2 frequency score (see the dictionary). */
    @Synchronized fun add(word: String, score: Int) {
        if (score < MIN_SCORE) return
        val plain = word.lowercase().filter { it != '\'' }
        if (plain.length < 2 || plain.any { it !in 'a'..'z' }) return
        // A double letter is one key: "hello" is h-e-l-o to the finger.
        val letters = StringBuilder().apply { for (c in plain) if (isEmpty() || last() != c) append(c) }
        if (letters.length < 2) return
        // A contraction can't be told from its bare form ("we'll", "well"): the bare one leads.
        val s = if ('\'' in word) score - 2f else score.toFloat()
        val key = bucket(letters[0], letters[letters.length - 1])
        val list = buckets.getOrPut(key) { ArrayList() }
        if (list.none { it.word == word }) list += Word(word, letters.toString().toCharArray(), s)
    }

    /** The likeliest words for the path ([px], [py] in pixels), best first; empty for a tap. */
    @Synchronized fun decode(px: FloatArray, py: FloatArray, layout: Layout, max: Int = 4): List<String> {
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

        val starts = near(ux[0], uy[0], kx, ky)
        val ends = near(ux[n - 1], uy[n - 1], kx, ky)
        val best = ArrayList<Pair<String, Float>>()
        val tx = FloatArray(MAX_LETTERS); val ty = FloatArray(MAX_LETTERS)
        val wx = FloatArray(POINTS); val wy = FloatArray(POINTS)
        for (s in starts) for (e in ends) {
            val list = buckets[bucket('a' + s, 'a' + e)] ?: continue
            word@ for (w in list) {
                val m = w.letters.size
                if (m > MAX_LETTERS) continue
                for (i in 0 until m) { val k = w.letters[i] - 'a'; tx[i] = kx[k]; ty[i] = ky[k] }
                val ideal = pathLength(tx, ty, m)
                // The finger may wander, but it can't cut the word's path short by much.
                if (ideal > length * 1.35f + 0.8f || length > ideal * 1.8f + 2.5f) continue
                // Every letter passed near, in order.
                var j = 0
                for (i in 1 until m - 1) {
                    while (j < POINTS && dist2(rx[j], ry[j], tx[i], ty[i]) > LETTER_R2) j++
                    if (j == POINTS) continue@word
                }
                resample(tx, ty, wx, wy, m)
                var sum = 0f
                for (i in 0 until POINTS) sum += Math.sqrt(dist2(rx[i], ry[i], wx[i], wy[i]).toDouble()).toFloat()
                val loc = sum / POINTS
                best += w.word to (w.score * FREQ_WEIGHT - loc * loc * LOC_WEIGHT)
            }
        }
        best.sortByDescending { it.second }
        return best.asSequence().map { it.first }.distinct().take(max).toList()
    }

    private fun bucket(first: Char, last: Char) = (first - 'a') * 26 + (last - 'a')

    /** Keys within reach of a path's end, nearest first; the nearest one at least. */
    private fun near(x: Float, y: Float, kx: FloatArray, ky: FloatArray): List<Int> {
        val order = (0 until 26).sortedBy { dist2(x, y, kx[it], ky[it]) }
        return order.filterIndexed { i, k -> i == 0 || dist2(x, y, kx[k], ky[k]) < END_R2 }
    }

    companion object {
        private const val POINTS = 48
        private const val MAX_LETTERS = 32
        /** Shorter than this, in keys, is a tap that slipped, not a swipe. */
        private const val MIN_LENGTH = 0.6f
        /** Words rarer than this are left out: swiping them is guesswork anyway. */
        private const val MIN_SCORE = 5
        private const val END_R2 = 1.1f * 1.1f
        private const val LETTER_R2 = 0.95f * 0.95f
        /** log2 frequency to natural log, and a Gaussian on the distance: sigma 0.3 keys. */
        private const val FREQ_WEIGHT = 0.69f
        private const val LOC_WEIGHT = 1f / (2 * 0.12f * 0.12f)

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
