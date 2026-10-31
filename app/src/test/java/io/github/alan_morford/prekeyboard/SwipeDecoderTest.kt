package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Swipes drawn by a hand that is not quite steady, over the Pre's letters, read back against
 * the dictionary that ships. Keys 100 px wide and 130 px tall, as on a phone.
 */
class SwipeDecoderTest {

    companion object {
        lateinit var d: SwipeDecoder
        val layout: SwipeDecoder.Layout = run {
            val x = FloatArray(26); val y = FloatArray(26)
            fun row(s: String, col0: Float, r: Int) = s.forEachIndexed { i, c ->
                x[c - 'a'] = (col0 + i + 0.5f) * W; y[c - 'a'] = (r + 0.5f) * H
            }
            row("qwertyuiop", 0f, 0); row("asdfghjkl", 0f, 1); row("zxcvbnm", 1f, 2)  // z sits under s
            SwipeDecoder.Layout(x, y, W, H)
        }
        const val W = 100f
        const val H = 130f

        @BeforeClass @JvmStatic fun load() {
            val c = File("src/main/assets/dictionary.txt").bufferedReader().useLines { Corrector(it) }
            d = SwipeDecoder(c.entries())
        }
    }

    /** A path through [word]'s keys, each corner off by up to [wobble] keys, sampled every 8 px. */
    private fun swipe(word: String, wobble: Float, rnd: Random, smooth: Float = 0f): Pair<FloatArray, FloatArray> {
        val cx = ArrayList<Float>(); val cy = ArrayList<Float>()
        for (ch in word.lowercase().filter { it in 'a'..'z' }) {
            cx += layout.x[ch - 'a'] + (rnd.nextGaussian() * wobble * W).toFloat()
            cy += layout.y[ch - 'a'] + (rnd.nextGaussian() * wobble * H).toFloat()
        }
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        xs += cx[0]; ys += cy[0]
        for (i in 1 until cx.size) {
            val dx = cx[i] - cx[i - 1]; val dy = cy[i] - cy[i - 1]
            val steps = Math.max(1, (Math.hypot(dx.toDouble(), dy.toDouble()) / 8).toInt())
            for (s in 1..steps) { xs += cx[i - 1] + dx * s / steps; ys += cy[i - 1] + dy * s / steps }
        }
        // A finger rounds its corners: average each point with its neighbours, half a key's worth.
        val k = (smooth / 8).toInt()
        fun avg(v: List<Float>) = FloatArray(v.size) { i ->
            val lo = Math.max(0, i - k); val hi = Math.min(v.size - 1, i + k)
            var t = 0f; for (j in lo..hi) t += v[j]; t / (hi - lo + 1)
        }
        return avg(xs) to avg(ys)
    }

    private fun read(word: String, wobble: Float = 0f, seed: Long = 1, smooth: Float = 0f): List<String> {
        val (xs, ys) = swipe(word, wobble, Random(seed), smooth)
        return d.decode(xs, ys, layout)
    }

    private val words = listOf(
        "the", "and", "hello", "keyboard", "orange", "palm", "great", "this", "working", "quick",
        "brown", "jumps", "over", "lazy", "swipe", "typing", "android", "phone", "thanks", "what",
        "where", "people", "little", "about", "would", "could", "because", "something", "together",
        "tomorrow", "morning", "coffee", "please", "today", "time", "really", "think", "know", "want",
        "going", "good", "love", "white", "black", "music", "family", "friend", "message", "later",
    )

    @Test fun cleanSwipesReadExactly() {
        val wrong = words.filter { read(it).firstOrNull() != it }.map { "$it -> ${read(it)}" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun shakyHandsMostlyRight() {
        var right = 0; var total = 0
        val misses = ArrayList<String>()
        for (seed in 1L..5L) for (w in words) {
            val got = read(w, wobble = 0.22f, seed = seed, smooth = 50f)
            total++
            if (got.firstOrNull() == w) right++ else if (w !in got) misses += "$w -> $got"
        }
        println("swipe, shaky: $right / $total first; not offered at all: $misses")
        assertTrue("$right / $total", right >= total * 0.85)
        // Even when it is not the first guess, it is nearly always among those offered.
        assertTrue(misses.toString(), misses.size <= total * 0.05)
    }

    @Test fun doubleLettersAndShortWords() {
        assertEquals("hello", read("helo").first())          // one key for "ll"
        assertEquals("in", read("in").first())
        assertTrue(read("on").contains("on"))
    }

    @Test fun aTapIsNotASwipe() {
        assertEquals(emptyList<String>(), d.decode(floatArrayOf(50f, 55f), floatArrayOf(65f, 70f), layout))
    }

    @Test fun learnedWordsCanBeSwiped() {
        assertTrue("luneos" !in read("luneos"))
        d.add("LuneOS", 10)
        assertEquals("LuneOS", read("luneos").first())
    }

    @Test fun quickEnough() {
        val (xs, ys) = swipe("something", 0.2f, Random(3))
        d.decode(xs, ys, layout)
        val t = System.nanoTime()
        repeat(20) { d.decode(xs, ys, layout) }
        val ms = (System.nanoTime() - t) / 20 / 1e6
        println("swipe decode: %.1f ms".format(ms))
        assertTrue("$ms ms", ms < 40)
    }
}
