package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * How often fast, sloppy swipes come out right, over the commonest words. The decoder's
 * weights were tuned here; the floors below are a little under what it scored (2026-11-06:
 * careful 97% first, quick 88% first and 98% in the bar, very quick 70% and 88%).
 */
class SwipeBenchTest {

    private val W = SwipeDecoderTest.W
    private val H = SwipeDecoderTest.H
    private val layout get() = SwipeDecoderTest.layout

    /**
     * A quick finger: aims [aim] keys off each letter, rounds its corners over [round] keys,
     * starts and lifts [ends] keys off the first and last letters, and is sampled every
     * [step] keys of travel.
     */
    private fun fast(word: String, rnd: Random, aim: Float, round: Float, ends: Float, step: Float): Pair<FloatArray, FloatArray> {
        val letters = StringBuilder().apply { for (c in word.lowercase()) if (c in 'a'..'z' && (isEmpty() || last() != c)) append(c) }
        val cx = ArrayList<Float>(); val cy = ArrayList<Float>()
        for ((i, ch) in letters.withIndex()) {
            val end = i == 0 || i == letters.length - 1
            val s = if (end) ends else aim
            cx += layout.x[ch - 'a'] + (rnd.nextGaussian() * s * W).toFloat()
            cy += layout.y[ch - 'a'] + (rnd.nextGaussian() * s * H * 0.8f).toFloat()
        }
        // Dense first, rounded, then sampled sparsely as a fast finger's touch events are.
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        xs += cx[0]; ys += cy[0]
        for (i in 1 until cx.size) {
            val dx = cx[i] - cx[i - 1]; val dy = cy[i] - cy[i - 1]
            val n = Math.max(1, (Math.hypot(dx.toDouble(), dy.toDouble()) / 4).toInt())
            for (s in 1..n) { xs += cx[i - 1] + dx * s / n; ys += cy[i - 1] + dy * s / n }
        }
        val k = (round * W / 4).toInt()
        fun avg(v: List<Float>) = FloatArray(v.size) { i ->
            val lo = Math.max(0, i - k); val hi = Math.min(v.size - 1, i + k)
            var t = 0f; for (j in lo..hi) t += v[j]; t / (hi - lo + 1)
        }
        val ax = avg(xs); val ay = avg(ys)
        val ox = ArrayList<Float>(); val oy = ArrayList<Float>()
        var travelled = 0f; var next = 0f
        for (i in ax.indices) {
            if (i > 0) travelled += Math.hypot((ax[i] - ax[i - 1]).toDouble(), (ay[i] - ay[i - 1]).toDouble()).toFloat()
            if (travelled >= next || i == ax.size - 1) { ox += ax[i]; oy += ay[i]; next = travelled + step * W }
        }
        return ox.toFloatArray() to oy.toFloatArray()
    }

    private fun common(n: Int): List<String> {
        val c = File("src/main/assets/dictionary.txt").bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") }.map { it.split('\t') }.filter { it.size == 2 }
                .map { it[0] to (it[1].trim().toIntOrNull() ?: 0) }.toList()
        }
        return c.filter { (w, _) -> w.length >= 2 && w.all { it in 'a'..'z' } }
            .sortedByDescending { it.second }.map { it.first }.distinct().take(n)
    }

    @Test fun bench() {
        val d = SwipeDecoder(File("src/main/assets/dictionary.txt").bufferedReader().useLines { Corrector(it) }.entries())
        val words = common(400)
        for ((name0, floor) in listOf(
            Triple("careful", floatArrayOf(0.15f, 0.3f, 0.15f, 0.15f), intArrayOf(94, 98)),
            Triple("quick", floatArrayOf(0.28f, 0.6f, 0.3f, 0.35f), intArrayOf(85, 95)),
            Triple("very quick", floatArrayOf(0.38f, 0.9f, 0.4f, 0.5f), intArrayOf(65, 84)),
        ).map { (a, b, c) -> Pair(a, b) to c }) {
            val (name, p) = name0
            var first = 0; var offered = 0; var none = 0; var total = 0
            val misses = ArrayList<String>()
            var ms = 0L
            for (seed in 1L..4L) {
                val rnd = Random(seed * 7919)
                for (w in words) {
                    val (xs, ys) = fast(w, rnd, p[0], p[1], p[2], p[3])
                    val t = System.nanoTime()
                    val got = d.decode(xs, ys, layout).map { it.lowercase() }
                    ms += (System.nanoTime() - t) / 1_000_000
                    total++
                    if (got.isEmpty()) none++
                    if (got.firstOrNull() == w) first++ else if (misses.size < 25) misses += "$w>${got.take(2)}"
                    if (w in got.take(3)) offered++
                }
            }
            println("BENCH $name: first ${first * 100 / total}%  in bar ${offered * 100 / total}%  nothing ${none * 100 / total}%  avg ${ms.toFloat() / total} ms")
            println("  misses: $misses")
            assertTrue("$name: first ${first * 100 / total}%", first * 100 / total >= floor[0])
            assertTrue("$name: in bar ${offered * 100 / total}%", offered * 100 / total >= floor[1])
        }
    }

    /** Long straight swipes reach the most words: the slowest to decode. */
    @Test fun longStraightSwipesAreQuick() {
        val d = SwipeDecoder(File("src/main/assets/dictionary.txt").bufferedReader().useLines { Corrector(it) }.entries())
        for (pair in listOf("ao", "to", "hi", "qp", "zm")) {
            val a = pair[0] - 'a'; val b = pair[1] - 'a'
            val xs = FloatArray(23) { layout.x[a] + (layout.x[b] - layout.x[a]) * it / 22 }
            val ys = FloatArray(23) { layout.y[a] + (layout.y[b] - layout.y[a]) * it / 22 }
            repeat(5) { d.decode(xs, ys, layout) }
            val t = System.nanoTime(); repeat(20) { d.decode(xs, ys, layout) }
            val us = (System.nanoTime() - t) / 20 / 1000
            println("LONG $pair $us us ${d.decode(xs, ys, layout)}")
            assertTrue("$pair: $us us", us < 3000)
        }
    }
}
