package io.github.alan_morford.prekeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
        /** Long press on the globe: Orange to White and back. */
        fun onToggleStyle()
        /** Long press on the orange key: "Hold for more" on or off. */
        fun onToggleHold()
        /** Whether the field wants a capital here (the start of a sentence, say). */
        fun wantsCaps(): Boolean
        /** A swipe across the letters, in view pixels; [caps] is [CAPS_NONE], [CAPS_FIRST] or [CAPS_ALL]. */
        fun onSwipe(xs: FloatArray, ys: FloatArray, caps: Int) {}
        /** The swipe so far, every [LIVE_EVERY] ms while it goes on: for guesses in the bar as it grows. */
        fun onSwipeProgress(xs: FloatArray, ys: FloatArray, caps: Int) {}
        /** The [i]th word in the suggestion bar was tapped: 0 left, 1 middle, 2 right. */
        fun onPick(i: Int) {}
        /** The notice in the suggestion bar was tapped. */
        fun onNotice() {}
        /** Backspace went down, or let go: the bar can wait till it's done deleting. */
        fun onBackspaceHeld(held: Boolean) {}
        /** Speech input on or off: the period held, or tapped after the orange key. */
        fun onVoice() {}
        /** Return held on the Sym page: open the Pre Keyboard app. */
        fun onOpenApp() {}
        /** Whether [word], held in the bar, may be added to the dictionary (it isn't in it yet). */
        fun canAdd(word: String): Boolean = false
        /** The owner said yes to adding [word] to the dictionary. */
        fun onAddWord(word: String) {}
        /** The paste icon in the bar was tapped. */
        fun onPaste() {}
    }

    var settings = Settings.read(context)
        private set
    private val dp = resources.displayMetrics.density
    private val painter = Painter(dp)
    private val handler = Handler(Looper.getMainLooper())
    private val style get() = settings.style

    /** New settings, applied in place: a new style repaints, a new height (or bar) re-lays the keyboard. */
    fun apply(s: Settings) {
        val resize = s.height != settings.height || s.showsBar != settings.showsBar
        settings = s
        if (resize) { painter.clear(); requestLayout() }
        invalidate()
    }

    /** Runs [change], then re-lays the keyboard if it brought the bar up or took it away. */
    private inline fun barChange(change: () -> Unit) {
        val was = barShown
        change()
        if (barShown != was) { painter.clear(); requestLayout() }
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
    /** The suggestion bar, above the keys: when it is switched on, or there is a notice to show in it. */
    private val barShown get() = settings.showsBar || notice != null || voiceLine != null
    private val barHeight get() = if (barShown) Math.round(rowHeight * 0.72f).toFloat() else 0f
    private val keysHeight get() = barHeight + rowHeight * 4 + topBorder + bottomPad

    private val cellH get() = rowHeight * 0.85f
    private val popupPad get() = 5 * dp
    /** Room above the keys for the tallest popup: two lines of accents. */
    val headroom: Int get() = Math.ceil((2 * cellH + 2 * popupPad).toDouble()).toInt()

    /** Where the keyboard proper (the bar, if shown, then the keys) starts: above is the popups' room. */
    fun keyboardTop(): Int = height - keysHeight.toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        navInset = navOverlap()
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), headroom + keysHeight.toInt())
    }

    /**
     * Targeting Android 15 means the system may lay the navigation bar over the keyboard's
     * window rather than beside it; when it does, the keys move up out of its way. With gesture
     * navigation, Pixels draw the keyboard's own bar (hide ⌄, switch keyboard) inside the
     * keyboard's window and report it as a caption bar, not a navigation bar: that counts too.
     */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        dispatchedBars = barsOver(insets)
        val nav = Math.max(dispatchedBars, imeNavBar())
        if (nav != navInset) { navInset = nav; requestLayout() }
        return super.onApplyWindowInsets(insets)
    }

    private fun barsOver(insets: WindowInsets?): Int = if (Build.VERSION.SDK_INT >= 30 && insets != null)
        insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()).bottom else 0

    /**
     * Android shows and hides the keyboard's own navigation bar (InputMethodService's
     * NavigationBarFrame, a sibling of the keyboard in its window) as the caption bar inset
     * comes and goes, and those insets don't always reach this view: the bar was seen on a
     * Pixel sitting over the keys till the keyboard was switched away and back. So the bar
     * itself is looked at too, at every measure and whenever the window lays out.
     */
    private fun imeNavBar(): Int {
        val decor = rootView as? ViewGroup ?: return 0
        for (i in 0 until decor.childCount) {
            val ch = decor.getChildAt(i)
            if (ch.visibility == View.VISIBLE && ch.javaClass.name.endsWith("NavigationBarFrame")) return ch.height
        }
        return 0
    }

    /**
     * The bars over the keys as last dispatched to this view. Not [getRootWindowInsets]: that is
     * the whole window's, and Samsung's keyboard window already stands its content above the
     * navigation bar, so counting it there lifted the keys a bar's height too far.
     */
    private var dispatchedBars = 0

    private fun navOverlap(): Int = Math.max(dispatchedBars, imeNavBar())

    private val watchNavBar = ViewTreeObserver.OnGlobalLayoutListener {
        val nav = navOverlap()
        if (nav != navInset) { navInset = nav; requestLayout() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(watchNavBar)
        requestApplyInsets()
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
        val top = keyboardTop() + barHeight + topBorder + i * rowHeight
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
        painter.deck(c, RectF(0f, top + barHeight, width.toFloat(), height.toFloat()))
        if (barShown) drawBar(c, RectF(0f, top, width.toFloat(), top + barHeight))
        if (page == Page.EMOJI) {
            emoji.draw(c, painter, style, emoji.pressed == EmojiPanel.BACK, emoji.pressed == EmojiPanel.ABC) { cv, s, r ->
                functionLabel(cv, s, r)
            }
            return
        }
        for (z in zones) if (z.slot.drawn) drawKey(c, z)
        drawTrail(c)
        drawPreview(c)
        drawExtended(c)
        drawAsk(c)
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
        key.corner?.let { cornerIcon(c, it, box) }
        if (holds(key)) holdDot(c, key, box)
    }

    /**
     * The small icon in a key's top right corner, the size of a printed alternate, always
     * white. While listening, the microphone sits in a white disc.
     */
    private fun cornerIcon(c: Canvas, corner: Corner, r: RectF) {
        val size = letterSize() * 0.62f
        val i = inset(r)
        val cx = r.right - i - size / 2
        val cy = r.top + i + size / 2 - size * 0.08f
        when (corner) {
            Corner.MIC -> if (listening) {
                dotPaint.color = Color.WHITE
                c.drawCircle(cx, cy, size * 0.62f, dotPaint)
                painter.icon(c, Painter.Icon.MIC, cx, cy, size * 0.8f, Color.rgb(0x16, 0x18, 0x1A))
            } else painter.icon(c, Painter.Icon.MIC, cx, cy, size, Color.WHITE)
            Corner.GEAR -> painter.icon(c, Painter.Icon.GEAR, cx, cy, size * 0.9f, Color.WHITE)
        }
    }

    /** Whether holding [key] opens its list: only with "Hold for more" on. */
    private fun holds(key: Key) = settings.holdForMore && key.extended != null

    /**
     * The mark of a key with more under it: a small dot in the style's colour, in the top
     * corner the printed alternate leaves free (top right on a key without one).
     */
    private fun holdDot(c: Canvas, key: Key, r: RectF) {
        val radius = Math.max(2f * dp, r.height() * 0.045f)
        val i = inset(r) + radius
        val x = if (key.alt != null && key.altPos == AltPos.RIGHT) r.left + i else r.right - i
        dotPaint.color = style.accent
        c.drawCircle(x, r.top + i, radius, dotPaint)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
     * A character key's printing: a key with an alternate puts it in one top corner and the
     * letter low in the key, just off centre away from it; a key without one centres its glyph.
     */
    private fun drawCap(c: Canvas, key: Key, r: RectF) {
        val letter = if (page == Page.SYMBOLS) key.text else key.text.uppercase()
        val size = letterSize()
        val a = key.alt
        if (a == null && key.corner != null) {
            // The period, printed as I, K and M are, low and to the left of its corner icon.
            // It has no alternate, so the orange key doesn't dim it.
            text.textSize = size
            text.color = Color.WHITE
            text.getTextBounds("H", 0, 1, ink)
            var baseline = r.bottom - inset(r) - ink.bottom
            text.getTextBounds(letter, 0, letter.length, ink)
            baseline = Math.min(baseline, r.bottom - inset(r) - ink.bottom)
            val lx = r.centerX() - r.width() * LETTER_NUDGE - text.measureText(letter) / 2
            c.drawText(letter, lx, baseline, text)
            return
        }
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
        // Every letter sits on one baseline, taken from a flat-bottomed capital, so Q's tail
        // hangs below the line instead of lifting the Q. As on the Pre, a letter is centred
        // in its key by its advance (not its ink, which Q's tail widens), nudged a little
        // away from its alternate.
        // Only a letter hangs, though: a comma's tail or the @'s bowl would reach the key's
        // edge, so a symbol is lifted until its ink rests on the letters' bottom line.
        text.getTextBounds("H", 0, 1, ink)
        var baseline = r.bottom - i - ink.bottom
        if (!letter.all { it.isLetter() }) {
            text.getTextBounds(letter, 0, letter.length, ink)
            baseline = Math.min(baseline, r.bottom - i - ink.bottom)
        }
        val nudge = r.width() * LETTER_NUDGE * (if (altLeft) 1 else -1)
        val lx = r.centerX() + nudge - text.measureText(letter) / 2
        c.drawText(letter, lx, baseline, text)
        text.alpha = 255

        // The alternate, in the top corner: its edge meets the inset, and it is centred on a
        // capital's height there, so "-" and "'" line up with the digits.
        val asize = size * 0.7f
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

    // --- The suggestion bar -----------------------------------------------------------------

    private var words = listOf("", "", "")
    private var barPressed = -1

    /**
     * A line across the whole bar ("Pre Keyboard 1.0.2 is available"), shown while there are
     * no words to offer; tapping it tells [Host.onNotice]. With the bar switched off, the bar
     * comes up for the notice alone.
     */
    var notice: String? = null
        set(v) { if (v != field) barChange { field = v } }
    private val showingNotice get() = notice != null && words.all { it.isEmpty() } && voiceLine == null && !pasteOffered

    /** Speech input is on: the period's microphone is lit. */
    var listening = false
        set(v) { if (v != field) { field = v; invalidate() } }

    /**
     * A line across the whole bar while speech input is on ("Listening…"), or just after it
     * stopped for a reason worth telling; tapping it while listening stops it.
     */
    var voiceLine: String? = null
        set(v) { if (v != field) barChange { field = v } }

    /**
     * Something new is on the clipboard: the bar's right-hand place shows a paste icon in
     * place of a word, till it is tapped.
     */
    var pasteOffered = false
        set(v) { if (v != field) { field = v; invalidate() } }
    /** The bar's [i]th place holds the paste icon. */
    private fun pasteAt(i: Int) = i == 2 && pasteOffered

    /** Three words, left to right; the middle one is the likeliest. "" leaves a place empty. */
    fun setSuggestions(w: List<String>) {
        val next = List(3) { w.getOrNull(it) ?: "" }
        if (next != words) { words = next; invalidate() }
    }

    private val barPaint = Paint()

    private fun drawBar(c: Canvas, r: RectF) {
        barPaint.color = BAR
        c.drawRect(r, barPaint)
        val cw = r.width() / 3
        // One baseline for every word, from the font rather than each word's ink, so a word
        // doesn't jump when a letter with a descender ("hop") comes along.
        val size = r.height() * 0.46f
        text.textSize = size
        val fm = text.fontMetrics
        val baseline = r.centerY() - (fm.ascent + fm.descent) / 2
        voiceLine?.let { v ->
            if (barPressed >= 0) { barPaint.color = BAR_PRESSED; c.drawRect(r, barPaint) }
            text.color = Style.ORANGE.accent
            text.textSize = fitted(v, size * 0.85f, r.width() - 24 * dp)
            c.drawText(v, r.centerX() - text.measureText(v) / 2, baseline, text)
            return
        }
        val n = notice
        if (n != null && showingNotice) {
            if (barPressed >= 0) { barPaint.color = BAR_PRESSED; c.drawRect(r, barPaint) }
            text.color = Style.ORANGE.accent
            text.textSize = fitted(n, size * 0.85f, r.width() - 24 * dp)
            c.drawText(n, r.centerX() - text.measureText(n) / 2, baseline, text)
            return
        }
        for (i in 0..2) {
            val cell = RectF(r.left + i * cw, r.top, r.left + (i + 1) * cw, r.bottom)
            if (i == barPressed && (words[i].isNotEmpty() || pasteAt(i))) { barPaint.color = BAR_PRESSED; c.drawRect(cell, barPaint) }
            if (i > 0) {
                barPaint.color = BAR_RULE
                c.drawRect(cell.left, r.top + r.height() * 0.25f, cell.left + Math.max(1f, dp), r.bottom - r.height() * 0.25f, barPaint)
            }
            if (pasteAt(i)) {
                painter.icon(c, Painter.Icon.PASTE, cell.centerX(), cell.centerY(), r.height() * 0.56f, Color.WHITE)
                continue
            }
            val w = words[i]
            if (w.isEmpty()) continue
            // The likeliest word, in the middle, in the Pre's orange in either style.
            text.color = if (i == 1) Style.ORANGE.accent else Color.WHITE
            text.textSize = fitted(w, size, cw - 12 * dp)
            c.drawText(w, cell.centerX() - text.measureText(w) / 2, baseline, text)
        }
    }

    // --- Holding a word in the bar: add it to the dictionary -------------------------------

    /** The word the popup offers to add, while it is up. */
    private var asking: String? = null
    private val askBox = RectF()
    /** The popup's buttons, where they were last drawn (the tests press them too). */
    internal val askYes = RectF()
    internal val askNo = RectF()
    /** The touch now down began while the popup was up (not the hold that opened it). */
    private var askDown = false
    /** Where that touch began: 1 Yes, -1 Cancel, 0 elsewhere. */
    private var askPressed = 0

    private val barHold = Runnable {
        val i = barPressed
        if (i < 0 || showingNotice || voiceLine != null || pasteAt(i)) return@Runnable
        val w = words[i]
        if (w.isEmpty() || !host.canAdd(w)) return@Runnable
        barPressed = -1
        asking = w
        askDown = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Opens the popup for [word] from outside the bar (a long press on a corrected word in the text). */
    fun ask(word: String) {
        if (asking != null) return
        asking = word
        askDown = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun askHit(x: Float, y: Float) = when {
        askYes.contains(x, y) -> 1
        askNo.contains(x, y) -> -1
        else -> 0
    }

    /** While the popup is up, every touch is its: Yes adds, Cancel or anywhere else closes it. */
    private fun askTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                askDown = true
                askPressed = askHit(e.x, e.y)
                if (askPressed != 0) keyFeedback()
            }
            MotionEvent.ACTION_UP -> if (askDown) {
                val word = asking
                val hit = askHit(e.x, e.y)
                if (hit == askPressed) {
                    asking = null
                    if (hit == 1 && word != null) host.onAddWord(word)
                }
                askDown = false; askPressed = 0
            }
            MotionEvent.ACTION_CANCEL -> { askDown = false; askPressed = 0 }
        }
        invalidate()
        return true
    }

    /** A small popup over the top of the keyboard: Add "word" to dictionary? Cancel / Yes. */
    private fun drawAsk(c: Canvas) {
        val word = asking ?: return
        val top = keyboardTop().toFloat()
        barPaint.color = Color.argb(150, 0, 0, 0)
        c.drawRect(0f, top, width.toFloat(), height.toFloat(), barPaint)
        val pad = 10 * dp
        val w = Math.min(width - 24 * dp, 340 * dp)
        val buttonH = rowHeight * 0.72f
        val lineH = rowHeight * 0.8f
        val h = pad + lineH + buttonH + pad
        val left = (width - w) / 2
        val boxTop = top + Math.max(4 * dp, (barHeight + rowHeight - h) / 2 + 4 * dp)
        askBox.set(left, boxTop, left + w, boxTop + h)
        bubble(c, askBox)
        val question = "Add \u201c$word\u201d to dictionary?"
        text.textSize = fitted(question, rowHeight * 0.33f, w - 2 * pad)
        text.color = Color.WHITE
        centred(c, question, RectF(left, boxTop + pad, left + w, boxTop + pad + lineH))
        val bw = (w - 3 * pad) / 2
        val by = boxTop + pad + lineH
        askNo.set(left + pad, by, left + pad + bw, by + buttonH)
        askYes.set(left + 2 * pad + bw, by, left + 2 * pad + 2 * bw, by + buttonH)
        val rad = 6 * dp
        bubblePaint.style = Paint.Style.FILL
        bubblePaint.color = if (askDown && askPressed == -1) Color.rgb(96, 104, 110) else Color.rgb(64, 70, 75)
        c.drawRoundRect(askNo, rad, rad, bubblePaint)
        bubblePaint.color = if (askDown && askPressed == 1) Color.rgb(0xFF, 0x7A, 0x60) else Style.ORANGE.accent
        c.drawRoundRect(askYes, rad, rad, bubblePaint)
        for ((label, r) in listOf("Cancel" to askNo, "Yes" to askYes)) {
            text.textSize = fitted(label, rowHeight * 0.32f, r.width() - 8 * dp)
            text.color = Color.WHITE
            centred(c, label, r)
        }
    }

    private fun barCell(x: Float, y: Float): Int {
        val top = keyboardTop()
        if (!barShown || y < top || y >= top + barHeight) return -1
        return (x / (width / 3f)).toInt().coerceIn(0, 2)
    }

    // --- Swipe to type ----------------------------------------------------------------------

    /** Swiping may happen here: switched on, and a field for words (not a password, say). */
    var wordsAllowed = true
    private var swipeX = FloatArray(256)
    private var swipeY = FloatArray(256)
    private var swipeN = 0
    /** The touch started on a letter and may turn into a swipe... */
    private var swipeMaybe = false
    /** ...and it has. */
    private var swiping = false
    private var lastLive = 0L

    private fun canSwipe(z: Zone): Boolean = settings.swipe && wordsAllowed && page == Page.LETTERS &&
        alt == Alt.OFF && z.key.kind == Kind.CHAR && z.key.text.length == 1 && z.key.text[0] in 'a'..'z'

    private fun addSwipePoint(x: Float, y: Float) {
        if (swipeN == swipeX.size) { swipeX = swipeX.copyOf(swipeN * 2); swipeY = swipeY.copyOf(swipeN * 2) }
        swipeX[swipeN] = x; swipeY[swipeN] = y; swipeN++
    }

    private fun capsNow() = when (shift) { Shift.OFF -> CAPS_NONE; Shift.ONCE -> CAPS_FIRST; Shift.LOCK -> CAPS_ALL }

    /** Where the letters are, for the swipe decoder. */
    fun swipeLayout(): SwipeDecoder.Layout? {
        val x = FloatArray(26); val y = FloatArray(26)
        var found = 0
        var keyW = 0f
        for (z in zones) {
            val t = z.key.text
            if (z.key.kind != Kind.CHAR || t.length != 1 || t[0] !in 'a'..'z') continue
            x[t[0] - 'a'] = z.rect.centerX(); y[t[0] - 'a'] = z.rect.centerY()
            if (t == "q") keyW = z.rect.width()
            found++
        }
        return if (found == 26 && keyW > 0) SwipeDecoder.Layout(x, y, keyW, rowHeight) else null
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trail = Path()

    private fun drawTrail(c: Canvas) {
        if (!swiping || swipeN < 2) return
        trail.rewind()
        trail.moveTo(swipeX[0], swipeY[0])
        for (i in 1 until swipeN) trail.lineTo(swipeX[i], swipeY[i])
        trailPaint.strokeWidth = 6 * dp
        trailPaint.color = if (style == Style.WHITE) Color.WHITE else style.accent
        trailPaint.alpha = 200
        c.drawPath(trail, trailPaint)
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
                host.onToggleHold()
            }
            Kind.GLOBE -> {
                held = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                host.onToggleStyle()
            }
            else -> when {
                z.key.corner == Corner.MIC -> {
                    held = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    preview = null
                    host.onVoice()
                    invalidate()
                }
                z.key.corner == Corner.GEAR -> {
                    held = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    host.onOpenApp()
                }
                holds(z.key) -> { held = true; showExtended(z) }
            }
        }
    }

    private var repeats = 0
    /** Backspace is down, and the host has been told. */
    private var deleting = false

    private fun stopRepeat() {
        handler.removeCallbacks(repeat)
        if (deleting) { deleting = false; host.onBackspaceHeld(false) }
    }
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
        handler.removeCallbacks(hold); stopRepeat()
        pressed = z
        held = false
        preview = if (z?.key?.kind == Kind.CHAR) z else null
        if (z == null) { invalidate(); return }
        when (z.key.kind) {
            Kind.BACKSPACE -> {
                repeats = 0
                deleting = true
                host.onBackspaceHeld(true)
                host.onBackspace(false)
                handler.postDelayed(repeat, FIRST_REPEAT)
            }
            Kind.SPACE -> handler.postDelayed(hold, HOLD_FOR_CURSOR)
            Kind.ORANGE, Kind.GLOBE -> handler.postDelayed(hold, HOLD_FOR_SETTING)
            else -> when {
                z.key.corner == Corner.MIC -> handler.postDelayed(hold, HOLD_FOR_VOICE)
                z.key.corner == Corner.GEAR -> handler.postDelayed(hold, HOLD_FOR_SETTING)
                holds(z.key) -> handler.postDelayed(hold, HOLD_FOR_EXTENDED)
            }
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
                keyFeedback()
            }
            return emoji.onTouch(e)
        }
        if (asking != null) return askTouch(e)
        val x = e.x; val y = e.y
        // A touch that starts in the suggestion bar stays there.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) barPressed = -1
        if (barPressed >= 0 || (e.actionMasked == MotionEvent.ACTION_DOWN && barCell(x, y) >= 0)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    barPressed = barCell(x, y)
                    if (voiceLine != null || showingNotice || words[barPressed].isNotEmpty() || pasteAt(barPressed)) keyFeedback()
                    handler.postDelayed(barHold, HOLD_FOR_ADD)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(barHold)
                    val i = barPressed
                    barPressed = -1
                    if (voiceLine != null) { if (listening && barCell(x, y) >= 0) host.onVoice() }
                    else if (showingNotice) { if (barCell(x, y) >= 0) host.onNotice() }
                    else if (barCell(x, y) == i && pasteAt(i)) host.onPaste()
                    else if (barCell(x, y) == i && words[i].isNotEmpty()) host.onPick(i)
                }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(barHold); barPressed = -1 }
            }
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finger = e.getPointerId(0)
                if (!down(x, y)) { finger = NO_FINGER; return false }
            }
            // Typing quickly, the next finger lands before the last one lifts. The key under
            // the first is typed then, as if it had lifted, and the new finger's press begins;
            // a finger that lands during a swipe, a hold or the accents is ignored.
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (swiping || cursorMode || extended != null || held) return true
                val i = e.actionIndex
                lift(up = true)
                finger = e.getPointerId(i)
                if (!down(e.getX(i), e.getY(i))) finger = NO_FINGER
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(finger)
                if (i < 0) return true
                move(e, i)
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == finger) {
                val i = e.actionIndex
                if (swiping) addSwipePoint(e.getX(i), e.getY(i))
                lift(up = true)
                finger = NO_FINGER
            }
            MotionEvent.ACTION_UP -> {
                if (e.getPointerId(0) == finger) {
                    if (swiping) addSwipePoint(x, y)
                    lift(up = true)
                } else lift(up = false)
                finger = NO_FINGER
            }
            MotionEvent.ACTION_CANCEL -> { lift(up = false); finger = NO_FINGER }
        }
        return true
    }

    /** A key went down: a light tap of the vibrator, when the owner has asked for it. */
    private fun keyFeedback() { if (settings.vibrate) Haptics.key(context) }

    /** The finger whose press is being followed; another one down at the same time is someone else's tap. */
    private var finger = NO_FINGER
    /**
     * The press started on backspace and the finger slid off it, or slid onto it from another
     * key: nothing more happens till it lifts, so letting go over a letter doesn't type it.
     */
    private var spent = false

    /** A finger landed at [x], [y]: false when it isn't on a key. */
    private fun down(x: Float, y: Float): Boolean {
        val z = zoneAt(x, y) ?: return false
        downX = x; downY = y
        keyFeedback()
        spent = false
        press(z)
        swiping = false
        swipeMaybe = canSwipe(z)
        swipeN = 0
        if (swipeMaybe) addSwipePoint(x, y)
        return true
    }

    private fun move(e: MotionEvent, p: Int) {
        val x = e.getX(p); val y = e.getY(p)
        if (spent) return
        if (cursorMode) { moveCursor(x, y); return }
        if (swipeMaybe && extended == null && !held) {
            for (h in 0 until e.historySize) addSwipePoint(e.getHistoricalX(p, h), e.getHistoricalY(p, h))
            addSwipePoint(x, y)
            if (!swiping && Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > swipeStart()) {
                // Off the first key and on its way: a swipe, not a tap.
                swiping = true
                handler.removeCallbacks(hold)
                pressed = null; preview = null
            }
            if (swiping) {
                if (e.eventTime - lastLive >= LIVE_EVERY) {
                    lastLive = e.eventTime
                    host.onSwipeProgress(swipeX.copyOf(swipeN), swipeY.copyOf(swipeN), capsNow())
                }
                invalidate()
                return
            }
        }
        if (extended != null) {
            val i = extendedAt(x, y)
            if (i != extendedIndex) { extendedIndex = i; invalidate() }
            return
        }
        if (held) return
        val z = zoneAt(x, y)
        if (z?.key === pressed?.key) return
        // Off backspace, or onto it from another key: backspace only works where the finger
        // landed, and the press is over till the finger lifts.
        if (pressed?.key?.kind == Kind.BACKSPACE || z?.key?.kind == Kind.BACKSPACE) {
            handler.removeCallbacks(hold); stopRepeat()
            spent = true
            pressed = null; preview = null
            invalidate()
            return
        }
        // Sliding off a key onto another moves the press, as webOS allowed.
        press(z)
    }

    /** The finger being followed lifted ([up]), or the touch was called off: finish its press. */
    private fun lift(up: Boolean) {
        handler.removeCallbacks(hold); stopRepeat()
        if (swiping) {
            if (up) {
                host.onSwipe(swipeX.copyOf(swipeN), swipeY.copyOf(swipeN), capsNow())
                if (shift == Shift.ONCE) { shift = Shift.OFF; autoShift = false; lastShiftTap = NEVER }
            }
            pressed = null
        } else if (up && !spent) commit()
        swiping = false; swipeMaybe = false; swipeN = 0
        extended = null; extendedFrame = null
        cursorMode = false; held = false; spent = false
        pressed = null; preview = null
        invalidate()
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
        swiping = false; swipeMaybe = false; swipeN = 0; barPressed = -1
        asking = null; askDown = false
        if (deleting) { deleting = false; host.onBackspaceHeld(false) }
        handler.removeCallbacksAndMessages(null)
        updateCaps()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(watchNavBar)
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        painter.clear()
    }

    /** How far a finger goes from where it landed before a letter's touch becomes a swipe. */
    private fun swipeStart() = width / 10f * 0.6f

    companion object {
        const val CAPS_NONE = 0
        const val CAPS_FIRST = 1
        const val CAPS_ALL = 2
        /** How often the bar's guesses are brought up to date during a swipe. */
        const val LIVE_EVERY = 50L

        private val BAR = Color.rgb(36, 40, 43)
        private val BAR_PRESSED = Color.rgb(64, 70, 75)
        private val BAR_RULE = Color.rgb(86, 94, 99)

        // webOS's keyboard timings, from LunaCE's TabletKeyboard.cpp (Apache 2.0).
        private const val FIRST_REPEAT = 350L
        private const val LETTER_REPEAT = 120L
        private const val WORD_REPEAT = 275L
        private const val WORD_DELETE_AFTER = 1500L
        private const val DOUBLE_TAP = 500L
        private const val HOLD_FOR_EXTENDED = 400L
        // Our own: long enough that a slow tap never trips them.
        private const val HOLD_FOR_CURSOR = 450L
        private const val HOLD_FOR_SETTING = 600L
        private const val HOLD_FOR_VOICE = 450L
        /** Holding a word in the bar, to add it to the dictionary. */
        private const val HOLD_FOR_ADD = 500L
        private const val CURSOR_STEP_X = 12f

        private const val NO_FINGER = -1

        /** No tap yet: far enough back that the next tap is never the second of a pair. */
        private const val NEVER = Long.MIN_VALUE / 2

        /** Prelude's cap height, as a fraction of the font size. */
        private const val CAP_HEIGHT = 0.72f
        /** How far a letter sits from its key's centre, away from the alternate (off a Pre photo: E, H). */
        private const val LETTER_NUDGE = 0.07f

        /**
         * Prelude, the Pre's own face, when the build found it (see README: its licence keeps
         * it out of this repository); otherwise Android's bold sans.
         */
        fun typeface(context: Context, name: String): Typeface =
            try { Typeface.createFromAsset(context.assets, "fonts/$name") }
            catch (e: Exception) { Typeface.create("sans-serif", Typeface.BOLD) }
    }
}
