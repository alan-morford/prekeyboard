package io.github.alan_morford.prekeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets

/**
 * The Palm Pre's keyboard as an Android input view, in one of two styles: Orange, with the
 * alternates and the orange key printed orange as on the Pre, or White, all printing white.
 *
 * The view is taller than its keys by [headroom], which is transparent: the key preview and
 * the press-and-hold balloon are drawn there. They belong to this view, not a popup window of
 * their own, because Android keeps a touch inside the window that took it - a finger sliding
 * up into a separate popup would never reach it. [KeyboardService.onComputeInsets] keeps the
 * app from being pushed up by that room, and sends taps in it to the app.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun onText(text: String)
        fun onBackspace(word: Boolean)
        fun onEnter()
        fun onPickKeyboard()
        /** One step of the cursor; [select] extends the selection instead. */
        fun onCursor(dx: Int, dy: Int, select: Boolean)
        /** Long press on the orange key: Orange to White and back. */
        fun onToggleStyle()
        /** Whether the field wants a capital here (the start of a sentence, say). */
        fun wantsCaps(): Boolean
    }

    var settings = Settings.read(context)
        private set
    private val dp = resources.displayMetrics.density
    private val painter = Painter(dp)
    private val handler = Handler(Looper.getMainLooper())
    private val style get() = settings.style

    /** New settings, applied in place: a new style repaints, a new height re-lays the keyboard. */
    fun apply(s: Settings) {
        val resize = s.height != settings.height
        settings = s
        if (resize) { painter.clear(); requestLayout() }
        invalidate()
    }

    // --- Sizes ------------------------------------------------------------------------------

    /** A row's height: the owner's choice, held to a share of the screen in landscape. */
    private val rowHeight: Float get() {
        val chosen = settings.height.dp * dp
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            Math.min(chosen, resources.displayMetrics.heightPixels * 0.105f) else chosen
    }
    private val topBorder get() = 3 * Math.max(1f, Math.round(dp).toFloat()) + 1 * dp
    /** Room under the keys: a little of our own, plus a navigation bar drawn over us, if any. */
    private var navInset = 0
    private val bottomPad get() = 2 * dp + navInset
    private val keysHeight get() = rowHeight * 4 + topBorder + bottomPad

    private val cellH get() = rowHeight * 0.85f
    private val popupPad get() = 5 * dp
    /** Room above the keys for the tallest popup: two lines of accents. */
    val headroom: Int get() = Math.ceil((2 * cellH + 2 * popupPad).toDouble()).toInt()

    /** Where the keyboard proper starts: everything above is the popups' room. */
    fun keyboardTop(): Int = height - keysHeight.toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), headroom + keysHeight.toInt())
    }

    /**
     * Targeting Android 15 means the system may lay the navigation bar over the keyboard's
     * window rather than beside it; when it does, the keys move up out of its way.
     */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val nav = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
        if (nav != navInset) { navInset = nav; requestLayout() }
        return super.onApplyWindowInsets(insets)
    }

    // --- State ------------------------------------------------------------------------------

    private enum class Shift { OFF, ONCE, LOCK }
    private var shift = Shift.OFF
    /** Shift was set by the field (auto-caps), not by a tap, so the field may take it back. */
    private var autoShift = false
    private var lastShiftTap = NEVER
    private var alt = Alt.OFF
    private var lastAltTap = NEVER

    private enum class Page { LETTERS, SYMBOLS, EMOJI }
    private var page = Page.LETTERS
    private val rows get() = if (page == Page.SYMBOLS) Keymap.symbols else Keymap.letters

    private val emoji = EmojiPanel(context, this, object : EmojiPanel.Actions {
        override fun emoji(e: String) { host.onText(e) }
        override fun backspace() { host.onBackspace(false) }
        override fun letters() { show(Page.LETTERS) }
    })

    private fun show(p: Page) {
        page = p
        alt = Alt.OFF
        if (p == Page.EMOJI) emoji.open()
        layoutKeys()
        invalidate()
    }

    // --- Layout -----------------------------------------------------------------------------

    private class Zone(val slot: Slot, val rect: RectF) {
        val key get() = slot.key
    }
    private val zones = ArrayList<Zone>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys()

    private fun rowRect(i: Int): RectF {
        val top = keyboardTop() + topBorder + i * rowHeight
        return RectF(0f, Math.round(top).toFloat(), width.toFloat(), Math.round(top + rowHeight).toFloat())
    }

    private fun layoutKeys() {
        zones.clear()
        if (width == 0) return
        if (page == Page.EMOJI) {
            emoji.layout(RectF(0f, rowRect(0).top, width.toFloat(), rowRect(2).bottom), rowRect(3))
            return
        }
        for ((i, row) in rows.withIndex()) {
            val r = rowRect(i)
            val units = row.sumOf { it.weight.toDouble() }.toFloat()
            var left = 0f
            var sofar = 0f
            for (slot in row) {
                sofar += slot.weight
                // Whole pixels, so neighbouring caps never share a blurred edge.
                val right = Math.round(width * sofar / units).toFloat()
                zones += Zone(slot, RectF(left, r.top, right, r.bottom))
                left = right
            }
        }
    }

    // --- Drawing ----------------------------------------------------------------------------

    private val bold = typeface(context, "Prelude-Bold.ttf")
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = bold }
    private val ink = Rect()

    private var pressed: Zone? = null

    override fun onDraw(c: Canvas) {
        val top = keyboardTop().toFloat()
        painter.deck(c, RectF(0f, top, width.toFloat(), height.toFloat()))
        if (page == Page.EMOJI) {
            emoji.draw(c, painter, style, emoji.pressed == EmojiPanel.BACK, emoji.pressed == EmojiPanel.ABC) { cv, s, r ->
                functionLabel(cv, s, r)
            }
            return
        }
        for (z in zones) if (z.slot.drawn) drawKey(c, z)
        drawPreview(c)
        drawExtended(c)
    }

    override fun computeScroll() {
        if (page == Page.EMOJI && emoji.animate()) postInvalidateOnAnimation()
    }

    private fun drawKey(c: Canvas, z: Zone) {
        val r = z.rect
        val down = pressed?.key === z.key
        c.drawBitmap(painter.cap(r.width().toInt(), r.height().toInt(), down), r.left, r.top, null)
        // The lettering moves down a touch while the key is held.
        val box = RectF(r)
        if (down) box.offset(0f, 1 * dp)
        val iconSize = Math.min(r.width(), r.height()) * 0.42f
        val key = z.key
        when (key.kind) {
            Kind.CHAR -> drawCap(c, key, box)
            Kind.SPACE -> if (cursorMode) painter.icon(c, Painter.Icon.CURSOR, box.centerX(), box.centerY(), iconSize)
            Kind.SHIFT -> painter.icon(c, when (shift) {
                Shift.OFF -> Painter.Icon.SHIFT
                Shift.ONCE -> Painter.Icon.SHIFT_ON
                Shift.LOCK -> Painter.Icon.SHIFT_LOCK
            }, box.centerX(), box.centerY(), iconSize)
            Kind.BACKSPACE -> painter.icon(c, Painter.Icon.BACKSPACE, box.centerX(), box.centerY(), iconSize)
            Kind.RETURN -> painter.icon(c, Painter.Icon.RETURN, box.centerX(), box.centerY(), iconSize)
            Kind.GLOBE -> painter.icon(c, Painter.Icon.GLOBE, box.centerX(), box.centerY(), iconSize)
            Kind.EMOJI -> painter.icon(c, Painter.Icon.EMOJI, box.centerX(), box.centerY(), iconSize)
            Kind.ORANGE -> painter.orange(c, box, style, alt)
            Kind.SYM -> functionLabel(c, key.label ?: "", box)
        }
    }

    private fun functionLabel(c: Canvas, s: String, box: RectF) {
        text.textSize = fitted(s, rowHeight * 0.34f, box.width() - 8 * dp)
        text.color = Color.WHITE
        centred(c, s, box)
    }

    /** The letter's size: big and bold, but held to the key's width and to half its height. */
    private fun letterSize(): Float = Math.min(rowHeight * 0.5f, width / 10f * 0.56f)

    /** Inset of the glyphs' ink from the key's edges. */
    private fun inset(r: RectF) = 2.5f * dp + r.width() * 0.08f

    /**
     * A character key's printing, placed by its ink: a key with an alternate puts its letter
     * in one bottom corner and the alternate in the top corner opposite; a key without one
     * centres its glyph.
     */
    private fun drawCap(c: Canvas, key: Key, r: RectF) {
        val letter = if (page == Page.SYMBOLS) key.text else key.text.uppercase()
        val size = letterSize()
        val a = key.alt
        if (a == null) {
            text.textSize = fitted(letter, size, r.width() - 2 * inset(r))
            text.color = if (key.accent) style.accent else Color.WHITE
            centred(c, letter, r)
            return
        }
        val i = inset(r)
        val altLeft = key.altPos == AltPos.LEFT
        // The letter steps back while the orange key says the alternate is what will be typed.
        text.textSize = size
        text.color = Color.WHITE
        text.alpha = if (alt != Alt.OFF) 89 else 255
        text.getTextBounds(letter, 0, letter.length, ink)
        val lx = if (altLeft) r.right - i - ink.right else r.left + i - ink.left
        c.drawText(letter, lx, r.bottom - i - ink.bottom, text)
        text.alpha = 255

        // The alternate, in the top corner: its edge meets the inset, and it is centred on a
        // capital's height there, so "-" and "'" line up with the digits.
        val asize = size * (if (key.altPos == AltPos.INLINE) 0.85f else 0.7f)
        text.textSize = asize
        text.color = if (a.length == 1 && a[0].isDigit()) style.accent else Color.WHITE
        text.getTextBounds(a, 0, a.length, ink)
        val ax = if (altLeft) r.left + i - ink.left else r.right - i - ink.right
        val ay = r.top + i + asize * CAP_HEIGHT / 2 - (ink.top + ink.bottom) / 2f
        c.drawText(a, ax, ay, text)
    }

    private fun centred(c: Canvas, s: String, r: RectF) {
        text.getTextBounds(s, 0, s.length, ink)
        c.drawText(s, r.centerX() - (ink.left + ink.right) / 2f, r.centerY() - (ink.top + ink.bottom) / 2f, text)
    }

    /** [size], shrunk until [s] fits [room]. */
    private fun fitted(s: String, size: Float, room: Float): Float {
        var px = size
        text.textSize = px
        while (room > 0 && text.measureText(s) > room && px > 6) { px -= 1; text.textSize = px }
        return px
    }

    // --- The key preview --------------------------------------------------------------------

    private var preview: Zone? = null

    /** What a character key would type right now. */
    private fun output(key: Key): String {
        if (alt != Alt.OFF && key.alt != null) return key.alt
        return if (shift != Shift.OFF) key.text.uppercase() else key.text
    }

    private fun drawPreview(c: Canvas) {
        val z = preview ?: return
        if (extended != null) return
        val s = output(z.key)
        val w = Math.max(z.rect.width() * 1.25f, rowHeight * 0.95f)
        val h = rowHeight * 1.15f
        val bottom = z.rect.top + rowHeight * 0.1f
        val left = (z.rect.centerX() - w / 2).coerceIn(0f, Math.max(0f, width - w))
        val r = RectF(left, bottom - h, left + w, bottom)
        bubble(c, r)
        text.textSize = fitted(s, rowHeight * 0.7f, w - 8 * dp)
        text.color = if (z.key.accent || (alt != Alt.OFF && s.length == 1 && s[0].isDigit())) style.accent else Color.WHITE
        centred(c, s, r)
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun bubble(c: Canvas, r: RectF) {
        val rad = 8 * dp
        bubblePaint.style = Paint.Style.FILL
        bubblePaint.color = Color.rgb(30, 34, 36)
        c.drawRoundRect(r, rad, rad, bubblePaint)
        bubblePaint.style = Paint.Style.STROKE
        bubblePaint.strokeWidth = 1 * dp
        bubblePaint.color = Color.rgb(86, 94, 99)
        c.drawRoundRect(RectF(r).apply { inset(dp / 2, dp / 2) }, rad, rad, bubblePaint)
    }

    // --- Press and hold: accents ------------------------------------------------------------

    private var extended: List<String>? = null
    private var extendedFrame: RectF? = null
    private var extendedIndex = 0

    private fun extendedFor(key: Key): List<String>? {
        val list = key.extended ?: return null
        return if (shift != Shift.OFF && page == Page.LETTERS) list.map { it.uppercase() } else list
    }

    private val cellW get() = Math.max(width / 10f * 0.92f, 34 * dp)
    private fun perLine(n: Int) = if (n > 6) (n + 1) / 2 else n

    private fun showExtended(z: Zone) {
        val chars = extendedFor(z.key) ?: return
        val per = perLine(chars.size)
        val lines = (chars.size + per - 1) / per
        val w = per * cellW + 2 * popupPad
        val h = lines * cellH + 2 * popupPad
        // The key's own character sits right above the finger, on the bottom line.
        val left = (z.rect.centerX() - cellW / 2 - popupPad).coerceIn(0f, Math.max(0f, width - w))
        val top = Math.max(0f, z.rect.top - h)
        extended = chars
        extendedFrame = RectF(left, top, left + w, top + h)
        extendedIndex = 0
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** The cell for the [i]th entry: line 0 is the bottom one, nearest the key. */
    private fun cell(i: Int, chars: List<String>, frame: RectF): RectF {
        val per = perLine(chars.size)
        val lines = (chars.size + per - 1) / per
        val x = frame.left + popupPad + (i % per) * cellW
        val y = frame.top + popupPad + (lines - 1 - i / per) * cellH
        return RectF(x, y, x + cellW, y + cellH)
    }

    private fun drawExtended(c: Canvas) {
        val chars = extended ?: return
        val frame = extendedFrame ?: return
        bubble(c, frame)
        for (i in chars.indices) {
            val r = cell(i, chars, frame)
            if (i == extendedIndex) {
                bubblePaint.style = Paint.Style.FILL
                bubblePaint.color = if (style == Style.WHITE) Color.WHITE else style.accent
                c.drawRoundRect(RectF(r).apply { inset(dp, dp) }, 6 * dp, 6 * dp, bubblePaint)
            }
            text.textSize = fitted(chars[i], rowHeight * 0.45f, r.width() - 6 * dp)
            text.color = if (i == extendedIndex && style == Style.WHITE) Color.BLACK else Color.WHITE
            centred(c, chars[i], r)
        }
    }

    private fun extendedAt(x: Float, y: Float): Int {
        val chars = extended ?: return 0
        val frame = extendedFrame ?: return 0
        val per = perLine(chars.size)
        val lines = (chars.size + per - 1) / per
        // A finger off the balloon picks the nearest cell, so sliding up from the key works.
        val col = ((x - frame.left - popupPad) / cellW).toInt().coerceIn(0, per - 1)
        val row = ((y - frame.top - popupPad) / cellH).toInt().coerceIn(0, lines - 1)
        return ((lines - 1 - row) * per + col).coerceIn(0, chars.size - 1)
    }

    // --- Press and hold: space moves the cursor ---------------------------------------------

    private var cursorMode = false
    private var cursorX = 0f
    private var cursorY = 0f

    private fun startCursor(x: Float, y: Float) {
        cursorMode = true
        cursorX = x; cursorY = y
        preview = null
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** One character per [CURSOR_STEP_X] dp across, one line per half a row up or down. */
    private fun moveCursor(x: Float, y: Float) {
        val select = shift != Shift.OFF
        val stepX = CURSOR_STEP_X * dp
        val stepY = rowHeight * 0.5f
        while (x - cursorX >= stepX) { cursorX += stepX; host.onCursor(1, 0, select) }
        while (cursorX - x >= stepX) { cursorX -= stepX; host.onCursor(-1, 0, select) }
        while (y - cursorY >= stepY) { cursorY += stepY; host.onCursor(0, 1, select) }
        while (cursorY - y >= stepY) { cursorY -= stepY; host.onCursor(0, -1, select) }
    }

    // --- Touch ------------------------------------------------------------------------------

    private fun zoneAt(x: Float, y: Float): Zone? {
        val inside = zones.firstOrNull { it.rect.contains(x, y) }
        if (inside != null || y < keyboardTop() || zones.isEmpty()) return inside
        // Below the last row (the bottom pad) or in a rounding gap: the key in that column.
        val yy = y.coerceIn(zones.first().rect.top, zones.last().rect.bottom - 1)
        return zones.firstOrNull { yy >= it.rect.top && yy < it.rect.bottom && x >= it.rect.left && x < it.rect.right }
    }

    /** The key's hold action has run, so letting go does nothing more. */
    private var held = false

    private val hold = Runnable {
        val z = pressed ?: return@Runnable
        when (z.key.kind) {
            Kind.SPACE -> { held = true; startCursor(downX, downY) }
            Kind.ORANGE -> {
                held = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.onToggleStyle()
            }
            else -> if (z.key.extended != null) { held = true; showExtended(z) }
        }
    }

    private var repeats = 0
    private val repeat = object : Runnable {
        override fun run() {
            if (pressed?.key?.kind != Kind.BACKSPACE) return
            repeats++
            val word = repeats * LETTER_REPEAT > WORD_DELETE_AFTER
            host.onBackspace(word)
            handler.postDelayed(this, if (word) WORD_REPEAT else LETTER_REPEAT)
        }
    }

    private fun press(z: Zone?) {
        handler.removeCallbacks(hold); handler.removeCallbacks(repeat)
        pressed = z
        held = false
        preview = if (z?.key?.kind == Kind.CHAR) z else null
        if (z == null) { invalidate(); return }
        when (z.key.kind) {
            Kind.BACKSPACE -> {
                repeats = 0
                host.onBackspace(false)
                handler.postDelayed(repeat, FIRST_REPEAT)
            }
            Kind.SPACE -> handler.postDelayed(hold, HOLD_FOR_CURSOR)
            Kind.ORANGE -> handler.postDelayed(hold, HOLD_FOR_STYLE)
            else -> if (z.key.extended != null) handler.postDelayed(hold, HOLD_FOR_EXTENDED)
        }
        invalidate()
    }

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (page == Page.EMOJI) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                if (e.y < keyboardTop()) return false
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            return emoji.onTouch(e)
        }
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val z = zoneAt(x, y) ?: return false
                downX = x; downY = y
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                press(z)
            }
            MotionEvent.ACTION_MOVE -> {
                if (cursorMode) { moveCursor(x, y); return true }
                if (extended != null) {
                    val i = extendedAt(x, y)
                    if (i != extendedIndex) { extendedIndex = i; invalidate() }
                    return true
                }
                if (held) return true
                // Sliding off a key onto another moves the press, as webOS allowed.
                val z = zoneAt(x, y)
                if (z?.key !== pressed?.key) press(z)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold); handler.removeCallbacks(repeat)
                if (e.actionMasked == MotionEvent.ACTION_UP) commit()
                extended = null; extendedFrame = null
                cursorMode = false; held = false
                pressed = null; preview = null
                invalidate()
            }
        }
        return true
    }

    private fun commit() {
        extended?.let { chars ->
            type(chars[extendedIndex], usedAlt = false)
            return
        }
        if (held) return
        val key = pressed?.key ?: return
        when (key.kind) {
            Kind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCK -> Shift.OFF
                    now - lastShiftTap < DOUBLE_TAP -> Shift.LOCK
                    shift == Shift.ONCE -> Shift.OFF
                    else -> Shift.ONCE
                }
                autoShift = false
                lastShiftTap = if (shift == Shift.OFF) NEVER else now
            }
            // Tap: the next key types its printed alternate. Double tap: locked on. Tap while
            // on: off again. The same rhythm as shift, as on the Pre.
            Kind.ORANGE -> {
                val now = SystemClock.uptimeMillis()
                alt = when {
                    alt == Alt.LOCK -> Alt.OFF
                    now - lastAltTap < DOUBLE_TAP -> Alt.LOCK
                    alt == Alt.ONCE -> Alt.OFF
                    else -> Alt.ONCE
                }
                lastAltTap = if (alt == Alt.OFF) NEVER else now
            }
            Kind.SYM -> show(if (page == Page.LETTERS) Page.SYMBOLS else Page.LETTERS)
            Kind.EMOJI -> show(Page.EMOJI)
            Kind.BACKSPACE -> {}           // sent on the way down, and repeated since
            Kind.RETURN -> host.onEnter()
            Kind.GLOBE -> host.onPickKeyboard()
            Kind.SPACE -> host.onText(" ")
            Kind.CHAR -> type(output(key), usedAlt = alt != Alt.OFF && key.alt != null)
        }
    }

    private fun type(s: String, usedAlt: Boolean) {
        host.onText(s)
        // A one-shot spent is not half of a double tap.
        if (shift == Shift.ONCE) { shift = Shift.OFF; autoShift = false; lastShiftTap = NEVER }
        if (usedAlt && alt == Alt.ONCE) { alt = Alt.OFF; lastAltTap = NEVER }
    }

    /** The field has moved on: offer a capital where it wants one, unless shift was chosen by hand. */
    fun updateCaps() {
        if (page != Page.LETTERS || shift == Shift.LOCK || (shift == Shift.ONCE && !autoShift)) return
        val caps = host.wantsCaps()
        val next = if (caps) Shift.ONCE else Shift.OFF
        if (next != shift) { shift = next; invalidate() }
        autoShift = caps
    }

    /** A new field: start from the letters, nothing held. */
    fun reset() {
        shift = Shift.OFF; autoShift = false; alt = Alt.OFF
        lastShiftTap = NEVER; lastAltTap = NEVER
        if (page != Page.LETTERS) show(Page.LETTERS)
        extended = null; extendedFrame = null; pressed = null; preview = null
        cursorMode = false; held = false
        handler.removeCallbacksAndMessages(null)
        updateCaps()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        painter.clear()
    }

    companion object {
        // webOS's keyboard timings, from LunaCE's TabletKeyboard.cpp (Apache 2.0).
        private const val FIRST_REPEAT = 350L
        private const val LETTER_REPEAT = 120L
        private const val WORD_REPEAT = 275L
        private const val WORD_DELETE_AFTER = 1500L
        private const val DOUBLE_TAP = 500L
        private const val HOLD_FOR_EXTENDED = 400L
        // Our own: long enough that a slow tap never trips them.
        private const val HOLD_FOR_CURSOR = 450L
        private const val HOLD_FOR_STYLE = 600L
        private const val CURSOR_STEP_X = 12f

        /** No tap yet: far enough back that the next tap is never the second of a pair. */
        private const val NEVER = Long.MIN_VALUE / 2

        /** Prelude's cap height, as a fraction of the font size. */
        private const val CAP_HEIGHT = 0.72f

        /**
         * Prelude, the Pre's own face, when the build found it (see README: its licence keeps
         * it out of this repository); otherwise Android's bold sans.
         */
        fun typeface(context: Context, name: String): Typeface =
            try { Typeface.createFromAsset(context.assets, "fonts/$name") }
            catch (e: Exception) { Typeface.create("sans-serif", Typeface.BOLD) }
    }
}
