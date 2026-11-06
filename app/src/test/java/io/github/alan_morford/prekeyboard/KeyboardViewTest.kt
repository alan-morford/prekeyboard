package io.github.alan_morford.prekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The keyboard drawn and typed on, off any device: screenshots of both styles and both pages
 * into out/prekeyboard-shots, and taps checked against what the Pre would type.
 * A Pixel-sized screen: 1080 px wide at 420 dpi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-420dpi")
class KeyboardViewTest {

    private val typed = StringBuilder()
    private var caps = false
    private var picked = 0
    private val moves = StringBuilder()
    private var swipes = 0
    private var progress = 0
    private val picks = ArrayList<Int>()
    private val host = object : KeyboardView.Host {
        override fun onText(text: String) { typed.append(text) }
        override fun onBackspace(word: Boolean) { if (typed.isNotEmpty()) typed.setLength(typed.length - 1) }
        override fun onEnter() { typed.append('\n') }
        override fun onPickKeyboard() { picked++ }
        override fun onCursor(dx: Int, dy: Int, select: Boolean) { moves.append(if (dx > 0) "R" else if (dx < 0) "L" else if (dy > 0) "D" else "U") }
        override fun onToggleStyle() { Settings.update(context) { it.copy(style = if (it.style == Style.ORANGE) Style.WHITE else Style.ORANGE) } }
        override fun onToggleHold() { Settings.update(context) { it.copy(holdForMore = !it.holdForMore) } }
        override fun wantsCaps() = caps
        override fun onSwipe(xs: FloatArray, ys: FloatArray, caps: Int) { swipes++ }
        override fun onSwipeProgress(xs: FloatArray, ys: FloatArray, caps: Int) { progress++ }
        override fun onPick(i: Int) { picks += i }
        override fun onVoice() { voices++ }
        override fun onOpenApp() { opened++ }
        override fun onBackspaceHeld(held: Boolean) { heldEvents += held }
        override fun canAdd(word: String) = word == "zorp"
        override fun onAddWord(word: String) { added += word }
        override fun onPaste() { pastes++ }
    }
    private lateinit var context: Context

    private var voices = 0
    private var opened = 0
    private var pastes = 0
    private val heldEvents = ArrayList<Boolean>()
    private val added = ArrayList<String>()
    private var listening: KeyboardView? = null
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        listening?.apply(Settings.read(context))
    }

    @Before fun setUp() { context = RuntimeEnvironment.getApplication() }

    private fun keyboard(style: Style = Style.ORANGE, hold: Boolean = true): KeyboardView {
        Settings.write(context, Settings(style, Settings.Height.MEDIUM, holdForMore = hold))
        val v = KeyboardView(context, host)
        // As the service does: settings changes reach the view while it shows.
        Settings.prefs(context).registerOnSharedPreferenceChangeListener(listener)
        listening = v
        val w = context.resources.displayMetrics.widthPixels
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.reset()
        return v
    }

    private fun shot(v: KeyboardView, name: String, keysOnly: Boolean = true) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(240, 240, 240))
        v.draw(c)
        val out = if (keysOnly) Bitmap.createBitmap(b, 0, v.keyboardTop(), v.width, v.height - v.keyboardTop()) else b
        val dir = File(System.getProperty("prekeyboard.shots") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Centre of the key in [row] (0-3) at column [col] in key units from the left (0.5 = first key). */
    private fun at(v: KeyboardView, row: Int, col: Float): Pair<Float, Float> {
        val rowH = (v.height - v.keyboardTop()) / 4.2f
        val y = v.keyboardTop() + rowH * 0.2f + rowH * (row + 0.5f)
        return v.width * col / 10f to y
    }

    private fun touch(v: KeyboardView, action: Int, x: Float, y: Float, t: Long) {
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    private fun hold(v: KeyboardView, row: Int, col: Float, ms: Long, then: (Float, Float, Long) -> Unit = { _, _, _ -> }) {
        val (x, y) = at(v, row, col); val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        then(x, y, t)
    }

    private fun tap(v: KeyboardView, row: Int, col: Float) {
        val (x, y) = at(v, row, col); val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t); touch(v, MotionEvent.ACTION_UP, x, y, t + 30)
        // Clear the double-tap window between taps unless the test is double tapping.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
    }

    private val letters = "qwertyuiop" + "asdfghjkl"
    private fun letter(v: KeyboardView, ch: Char) {
        val i = letters.indexOf(ch)
        when {
            i in 0..9 -> tap(v, 0, i + 0.5f)
            i >= 10 -> tap(v, 1, i - 10 + 0.5f)
            else -> tap(v, 2, "zxcvbnm".indexOf(ch) + 1.5f)
        }
    }

    @Test fun screenshots() {
        for (style in Style.values()) {
            val v = keyboard(style)
            shot(v, "${style.name.lowercase()}-letters")
            tap(v, 3, 8.9f)                        // Sym
            shot(v, "${style.name.lowercase()}-symbols")
        }
        shot(keyboard(hold = false), "orange-letters-no-hold")
    }

    @Test fun holdForMoreOff() {
        val v = keyboard(hold = false)
        val (x, y) = at(v, 0, 2.5f); val t = SystemClock.uptimeMillis()  // hold e: just e
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        touch(v, MotionEvent.ACTION_UP, x, y - 10, t + 650)
        assertEquals("e", typed.toString())
    }

    @Test fun shotsOfStates() {
        val v = keyboard()
        tap(v, 2, 0.5f)                            // orange once
        tap(v, 3, 1.7f)                            // shift once
        shot(v, "orange-alt-and-shift")
        // Press and hold "o": the balloon, shown with the room above the keys.
        val (x, y) = at(v, 0, 8.5f); val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        shot(v, "orange-hold-o", keysOnly = false)
        touch(v, MotionEvent.ACTION_UP, x, y, t + 600)
        // A plain press: the preview.
        val (x2, y2) = at(v, 1, 3.5f)
        touch(v, MotionEvent.ACTION_DOWN, x2, y2, t + 700)
        shot(v, "orange-preview-f", keysOnly = false)
        touch(v, MotionEvent.ACTION_UP, x2, y2, t + 750)
    }

    @Test fun typesLettersShiftAndPrintedAlternates() {
        val v = keyboard()
        "hello".forEach { letter(v, it) }
        assertEquals("hello", typed.toString())

        typed.setLength(0)
        tap(v, 3, 1.7f); letter(v, 'h'); letter(v, 'i')             // shift once: one capital
        assertEquals("Hi", typed.toString())

        typed.setLength(0)
        tap(v, 2, 0.5f); letter(v, 'e'); letter(v, 'e')             // orange once: one alternate
        assertEquals("1e", typed.toString())

        typed.setLength(0)
        tap(v, 2, 0.5f)
        val (x, y) = at(v, 2, 0.5f); val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t); touch(v, MotionEvent.ACTION_UP, x, y, t + 20)  // double tap: lock
        "rtk".forEach { letter(v, it) }
        tap(v, 2, 0.5f)                                               // tap while locked: off
        letter(v, 'q')
        assertEquals("23:q", typed.toString())

        typed.setLength(0)
        tap(v, 2, 0.5f); tap(v, 2, 8.5f)                              // orange + comma
        tap(v, 2, 0.5f); tap(v, 3, 2.4f)                             // orange + @: 0
        tap(v, 3, 2.4f); tap(v, 3, 5f); tap(v, 3, 7.6f)              // @, space, .
        assertEquals("_0@ .", typed.toString())
    }

    @Test fun symbolsPageAndBottomRowSpacers() {
        val v = keyboard()
        tap(v, 3, 0.3f)                            // the spacer left of shift is shift
        letter(v, 'a')
        tap(v, 3, 9.7f)                            // the spacer right of Sym is Sym
        tap(v, 0, 0.5f); tap(v, 0, 9.5f); tap(v, 1, 1.5f); tap(v, 2, 2.5f)
        tap(v, 3, 0.7f)                            // ABC
        letter(v, 'b')
        tap(v, 1, 9.5f)                            // backspace
        assertEquals("A10]°", typed.toString())
    }

    @Test fun autoCapsAndHoldForAccents() {
        caps = true
        val v = keyboard()
        letter(v, 'w')
        caps = false; v.updateCaps()
        letter(v, 'w')
        assertEquals("Ww", typed.toString())

        typed.setLength(0)
        val (x, y) = at(v, 0, 2.5f); val t = SystemClock.uptimeMillis()  // hold e, slide to é
        touch(v, MotionEvent.ACTION_DOWN, x, y, t)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        val cell = (v.width / 10f * 0.92f).coerceAtLeast(34 * context.resources.displayMetrics.density)
        touch(v, MotionEvent.ACTION_MOVE, x + cell * 2, y - 10, t + 600)
        touch(v, MotionEvent.ACTION_UP, x + cell * 2, y - 10, t + 650)
        assertEquals("é", typed.toString())
    }

    @Test fun emojiPageGlobeAndSettingsLive() {
        val v = keyboard()
        tap(v, 3, 8.9f)                            // Sym
        tap(v, 2, 0.5f)                            // globe: the picker, not the next keyboard
        assertEquals(1, picked)
        tap(v, 3, 2.2f)                            // the emoji key
        shot(v, "orange-emoji")
        // The first emoji of the grid (recent is empty, so it is the first smiley).
        val top = v.keyboardTop() + (v.height - v.keyboardTop()) * 0.08f
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, v.width / 20f, top, t); touch(v, MotionEvent.ACTION_UP, v.width / 20f, top, t + 30)
        assertEquals("😀", typed.toString())
        tap(v, 3, 0.5f)                            // ABC
        letter(v, 'q')
        assertEquals("😀q", typed.toString())

        // A new height while showing: the view asks to be laid out again, taller.
        val before = v.measuredHeight
        Settings.update(context) { it.copy(height = Settings.Height.LARGE) }
        assertEquals(true, v.isLayoutRequested)
        v.measure(View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(true, v.measuredHeight > before)
    }

    @Test fun holdOrangeTogglesHoldGlobeSwitchesStyleAndHoldSpaceMovesTheCursor() {
        val v = keyboard()
        hold(v, 2, 0.5f, 700) { x, y, t -> touch(v, MotionEvent.ACTION_UP, x, y, t + 700) }
        assertEquals(false, v.settings.holdForMore)
        // The hold switched the setting and did not also arm the orange key.
        letter(v, 'e')
        assertEquals("e", typed.toString())
        hold(v, 2, 0.5f, 700) { x, y, t -> touch(v, MotionEvent.ACTION_UP, x, y, t + 700) }
        assertEquals(true, v.settings.holdForMore)

        tap(v, 3, 8.9f)                            // Sym
        hold(v, 2, 0.5f, 700) { x, y, t -> touch(v, MotionEvent.ACTION_UP, x, y, t + 700) }
        assertEquals(Style.WHITE, v.settings.style)
        assertEquals(0, picked)            // the hold did not also open the picker
        tap(v, 3, 0.7f)                            // ABC
        shot(v, "white-after-hold")

        typed.setLength(0)
        val step = 12 * context.resources.displayMetrics.density
        hold(v, 3, 5f, 600) { x, y, t ->
            touch(v, MotionEvent.ACTION_MOVE, x + step * 3.5f, y, t + 650)
            touch(v, MotionEvent.ACTION_MOVE, x + step * 0.9f, y, t + 700)
            touch(v, MotionEvent.ACTION_UP, x + step * 0.9f, y, t + 750)
        }
        assertEquals("RRRLL", moves.toString())
        assertEquals("", typed.toString())         // a held space types nothing
    }

    @Test fun suggestionBarAndSwipe() {
        val v = keyboard()
        val plain = v.measuredHeight
        Settings.update(context) { it.copy(swipe = true) }
        v.measure(View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        assertEquals(true, v.measuredHeight > plain)   // the bar came with swipe
        v.setSuggestions(listOf("help", "hello", "hell"))   // as the service would after this swipe

        // Swipe h-e-l-o: the trail, then the path handed over; nothing typed by the keys.
        val k = v.swipeLayout()!!
        val t = SystemClock.uptimeMillis()
        val path = "helo".map { k.x[it - 'a'] to k.y[it - 'a'] }
        touch(v, MotionEvent.ACTION_DOWN, path[0].first, path[0].second, t)
        var time = t
        for (i in 1 until path.size) for (s in 1..6) {
            val (x0, y0) = path[i - 1]; val (x1, y1) = path[i]
            time += 10
            touch(v, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * s / 6, y0 + (y1 - y0) * s / 6, time)
        }
        shot(v, "orange-swipe")
        touch(v, MotionEvent.ACTION_UP, path.last().first, path.last().second, time + 10)
        assertEquals(1, swipes)
        // 18 moves 10 ms apart: the bar was brought up to date along the way, every 50 ms.
        assertEquals(true, progress in 3..4)
        assertEquals("", typed.toString())

        // The bar: tap the middle word, then the left.
        val barY = v.keyboardTop() + 10f
        for (i in listOf(1, 0)) {
            val x = v.width * (i + 0.5f) / 3
            touch(v, MotionEvent.ACTION_DOWN, x, barY, time + 100); touch(v, MotionEvent.ACTION_UP, x, barY, time + 130)
        }
        assertEquals(listOf(1, 0), picks)
        shot(v, "orange-bar")

        // A tap on a letter is still a tap.
        val (qx, qy) = k.x['q' - 'a'] to k.y['q' - 'a']
        touch(v, MotionEvent.ACTION_DOWN, qx, qy, time + 200); touch(v, MotionEvent.ACTION_UP, qx + 3, qy, time + 230)
        assertEquals("q", typed.toString())
        Settings.update(context) { it.copy(swipe = false) }
    }

    @Test fun periodSpeaksSymReturnOpensTheAppAndHeldBackspaceSaysSo() {
        val v = keyboard()
        hold(v, 3, 7.5f, 600) { x, y, t -> touch(v, MotionEvent.ACTION_UP, x, y, t + 600) }
        assertEquals(1, voices)
        assertEquals("", typed.toString())        // a held period types nothing
        tap(v, 2, 0.5f); tap(v, 3, 7.5f)            // orange, then the period: only a period
        assertEquals(1, voices)
        assertEquals(".", typed.toString())
        v.reset()
        v.listening = true; v.voiceLine = "Listening\u2026 tap here to stop"
        shot(v, "orange-listening")
        v.listening = false; v.voiceLine = null

        tap(v, 3, 8.9f)                            // Sym
        shot(v, "orange-symbols-gear")
        hold(v, 3, 8.9f, 700) { x, y, t -> touch(v, MotionEvent.ACTION_UP, x, y, t + 700) }
        assertEquals(1, opened)
        assertEquals(".", typed.toString())        // the hold did not also send Return
        tap(v, 3, 0.7f)                            // ABC

        "abc".forEach { letter(v, it) }
        hold(v, 1, 9.5f, 1000) { x, y, t -> touch(v, MotionEvent.ACTION_UP, x, y, t + 1000) }
        assertEquals(listOf(true, false), heldEvents)
    }

    @Test fun holdingAnUnknownWordInTheBarOffersToAddIt() {
        val v = keyboard()
        Settings.update(context) { it.copy(suggestions = true) }
        v.measure(View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.setSuggestions(listOf("zorp", "zoo", "zip"))
        val barY = v.keyboardTop() + 10f
        val t = SystemClock.uptimeMillis()
        // A known word held: nothing to offer, and letting go picks it as a tap would.
        touch(v, MotionEvent.ACTION_DOWN, v.width * 0.5f, barY, t)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(v, MotionEvent.ACTION_UP, v.width * 0.5f, barY, t + 700)
        assertEquals(listOf(1), picks)
        // The word as typed, held: the popup, and letting go picks nothing.
        touch(v, MotionEvent.ACTION_DOWN, v.width / 6f, barY, t + 800)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(v, MotionEvent.ACTION_UP, v.width / 6f, barY, t + 1500)
        assertEquals(listOf(1), picks)
        shot(v, "orange-add-word")
        // Cancel closes it; a key tapped after is a key again.
        touch(v, MotionEvent.ACTION_DOWN, v.askNo.centerX(), v.askNo.centerY(), t + 1600)
        touch(v, MotionEvent.ACTION_UP, v.askNo.centerX(), v.askNo.centerY(), t + 1650)
        assertEquals(emptyList<String>(), added)
        letter(v, 'q')
        assertEquals("q", typed.toString())
        // Again, and Yes.
        touch(v, MotionEvent.ACTION_DOWN, v.width / 6f, barY, t + 2000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        touch(v, MotionEvent.ACTION_UP, v.width / 6f, barY, t + 2700)
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        touch(v, MotionEvent.ACTION_DOWN, v.askYes.centerX(), v.askYes.centerY(), t + 2800)
        touch(v, MotionEvent.ACTION_UP, v.askYes.centerX(), v.askYes.centerY(), t + 2850)
        assertEquals(listOf("zorp"), added)
        Settings.update(context) { it.copy(suggestions = false) }
    }

    @Test fun thePasteIconTakesTheBarsRightHandPlace() {
        val v = keyboard()
        Settings.update(context) { it.copy(suggestions = true) }
        v.measure(View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.setSuggestions(listOf("The", "I", "He"))
        v.pasteOffered = true
        shot(v, "orange-paste")
        val barY = v.keyboardTop() + 10f
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, v.width * 5 / 6f, barY, t)
        touch(v, MotionEvent.ACTION_UP, v.width * 5 / 6f, barY, t + 80)
        assertEquals(1, pastes)
        assertEquals(emptyList<Int>(), picks)          // not the word underneath
        touch(v, MotionEvent.ACTION_DOWN, v.width * 0.5f, barY, t + 200)
        touch(v, MotionEvent.ACTION_UP, v.width * 0.5f, barY, t + 280)
        assertEquals(listOf(1), picks)                 // the other places are words still
        Settings.update(context) { it.copy(suggestions = false) }
    }

    /** One event of a touch with fingers [ids] at [xy]; [index] is the finger it is about. */
    private fun fingers(v: KeyboardView, action: Int, index: Int, ids: List<Int>, xy: List<Pair<Float, Float>>, t: Long) {
        val props = Array(ids.size) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { MotionEvent.PointerCoords().apply { x = xy[it].first; y = xy[it].second; pressure = 1f; size = 1f } }
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val e = MotionEvent.obtain(t, t, a, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }

    @Test fun slidingOffBackspaceDoesNothingTillTheFingerLifts() {
        val v = keyboard()
        "abc".forEach { letter(v, it) }
        val (bx, by) = at(v, 1, 9.5f)
        val (lx, ly) = at(v, 1, 5.5f)               // h
        val t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, bx, by, t)
        assertEquals("ab", typed.toString())         // one letter, on the way down
        touch(v, MotionEvent.ACTION_MOVE, lx, ly, t + 50)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertEquals("ab", typed.toString())         // off the key: no more repeats
        touch(v, MotionEvent.ACTION_MOVE, bx, by, t + 1600)
        touch(v, MotionEvent.ACTION_UP, bx, by, t + 1650)
        assertEquals("ab", typed.toString())         // and nothing on the way back or the lift
        assertEquals(listOf(true, false), heldEvents)
        touch(v, MotionEvent.ACTION_DOWN, lx, ly, t + 1700)
        touch(v, MotionEvent.ACTION_MOVE, bx, by, t + 1750)
        touch(v, MotionEvent.ACTION_UP, lx, ly, t + 1800)
        assertEquals("ab", typed.toString())         // a letter slid onto backspace isn't typed either
        letter(v, 'h')
        assertEquals("abh", typed.toString())
    }

    @Test fun aSecondFingerTypesTheFirstKeyAndIsNoSwipe() {
        Settings.write(context, Settings(swipe = true))
        val v = keyboard().also { it.apply(Settings.read(context).copy(swipe = true)) }
        val h = at(v, 1, 5.5f); val i = at(v, 0, 7.5f); val t = SystemClock.uptimeMillis()
        // h down, i down before h lifts, h up, i up: "hi", typed, and no swipe.
        fingers(v, MotionEvent.ACTION_DOWN, 0, listOf(0), listOf(h), t)
        fingers(v, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(0, 1), listOf(h, i), t + 40)
        assertEquals("h", typed.toString())
        fingers(v, MotionEvent.ACTION_MOVE, 0, listOf(0, 1), listOf(h, i), t + 50)
        fingers(v, MotionEvent.ACTION_POINTER_UP, 0, listOf(0, 1), listOf(h, i), t + 60)
        fingers(v, MotionEvent.ACTION_MOVE, 0, listOf(1), listOf(i), t + 70)
        fingers(v, MotionEvent.ACTION_UP, 0, listOf(1), listOf(i), t + 90)
        assertEquals("hi", typed.toString())
        assertEquals(0, swipes)
        // The other order: the second finger lifts first, then the first lifts and types nothing more.
        fingers(v, MotionEvent.ACTION_DOWN, 0, listOf(0), listOf(h), t + 200)
        fingers(v, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(0, 1), listOf(h, i), t + 240)
        fingers(v, MotionEvent.ACTION_POINTER_UP, 1, listOf(0, 1), listOf(h, i), t + 260)
        fingers(v, MotionEvent.ACTION_UP, 0, listOf(0), listOf(h), t + 280)
        assertEquals("hihi", typed.toString())
        assertEquals(0, swipes)
    }

    @Test fun aSwipeStillSwipes() {
        val v = keyboard().also { it.apply(Settings.read(context).copy(swipe = true)) }
        val k = v.swipeLayout()!!
        val t = SystemClock.uptimeMillis()
        val path = "helo".map { k.x[it - 'a'] to k.y[it - 'a'] }
        touch(v, MotionEvent.ACTION_DOWN, path[0].first, path[0].second, t)
        var time = t
        for (n in 1 until path.size) for (s in 1..6) {
            val (x0, y0) = path[n - 1]; val (x1, y1) = path[n]
            time += 10
            touch(v, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * s / 6, y0 + (y1 - y0) * s / 6, time)
        }
        // A finger brushing the screen mid-swipe changes nothing.
        fingers(v, MotionEvent.ACTION_POINTER_DOWN, 1, listOf(0, 1), listOf(path.last(), 10f to 10f), time + 5)
        fingers(v, MotionEvent.ACTION_POINTER_UP, 1, listOf(0, 1), listOf(path.last(), 10f to 10f), time + 8)
        touch(v, MotionEvent.ACTION_UP, path.last().first, path.last().second, time + 10)
        assertEquals(1, swipes)
        assertEquals("", typed.toString())
    }
}
