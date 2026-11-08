package io.github.alan_morford.prekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller

/**
 * The emoji page: every emoji the device can draw, by Unicode's categories, in a grid that
 * scrolls; a row of category tabs along the bottom, then backspace. Tapping a tab jumps to its
 * category, and the tab lights as you scroll.
 *
 * Along the top, where the suggestion bar was: ABC, then Emoji, GIF and a sticker. The other
 * two are KLIPY's ([MediaPanel]): there a "Search KLIPY" field follows the tabs, and the GIFs
 * take everything below, down to the bottom of the keys; ABC is the only key left.
 *
 * [KeyboardView] owns it, gives it the keys' area, and hands it every touch while it shows.
 */
class EmojiPanel(private val context: Context, private val view: KeyboardView, typeface: Typeface, private val actions: Actions) {

    interface Actions {
        fun emoji(e: String)
        fun backspace()
        fun letters()
        /** The search field was tapped: type a search for [kind], starting from [query]. */
        fun search(kind: Klipy.Kind, query: String?) {}
        /** A GIF or sticker was tapped, found by searching for [query] (null: trending). */
        fun media(kind: Klipy.Kind, item: Klipy.Item, query: String?) {}
    }

    enum class Tab { EMOJI, GIF, STICKER }

    var tab = lastTab; private set
    /** What the GIF or sticker tab shows searched for (null: trending), for the tests. */
    internal fun query(kind: Klipy.Kind) = (if (kind == Klipy.Kind.GIF) gifs else stickers).query
    private val gifs = MediaPanel(context, view, Klipy.Kind.GIF, typeface) { item, q -> actions.media(Klipy.Kind.GIF, item, q) }
    private val stickers = MediaPanel(context, view, Klipy.Kind.STICKER, typeface) { item, q -> actions.media(Klipy.Kind.STICKER, item, q) }
    private val media: MediaPanel? get() = when (tab) { Tab.GIF -> gifs; Tab.STICKER -> stickers; Tab.EMOJI -> null }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }

    fun select(t: Tab) {
        if (t == tab) return
        media?.close()
        tab = t; lastTab = t
        scroller.forceFinished(true)
        media?.open()
        view.invalidate()
    }

    /** A search was typed for [kind]: its tab, showing what was found. */
    fun searched(kind: Klipy.Kind, q: String) {
        select(if (kind == Klipy.Kind.GIF) Tab.GIF else Tab.STICKER)
        media?.search(q)
    }

    /** The page is going away: the GIFs stop moving. */
    fun close() { gifs.close(); stickers.close() }

    /** A word for a moment over the GIFs or stickers. */
    fun say(kind: Klipy.Kind, s: String, ms: Long) = (if (kind == Klipy.Kind.GIF) gifs else stickers).say(s, ms)

    class Category(val id: String, val icon: String, val emoji: List<String>)

    private var categories: List<Category> = emptyList()

    /** Fills the page: every category is read once, the recent ones each time it opens. */
    fun open() {
        if (all.isEmpty()) all = load(context)
        val recent = Settings.recentEmoji(context)
        categories = listOf(Category("recent", "🕘", recent)) + all
        scroller.forceFinished(true)
        scrollY = 0f
        relayout()
        media?.open()
    }

    // --- Geometry ---------------------------------------------------------------------------

    private val dp = context.resources.displayMetrics.density
    private var grid = RectF()
    /** The GIFs' and stickers' grid: under the strip, all the way down. */
    private var mediaGrid = RectF()
    private var bar = RectF()
    /** The tabs (and on KLIPY's, the search field) above the grid. */
    private var strip = RectF()
    private var cols = 8
    private var cell = 48f
    /** Where each category's first line starts, in content px from the top of the grid. */
    private var starts = FloatArray(0)
    private var contentHeight = 0f
    private var scrollY = 0f

    /**
     * [top] is where the page starts (the suggestion bar's place, if it shows), [rows] the top
     * three rows of keys, [bar] the bottom row.
     */
    fun layout(top: Float, rows: RectF, bar: RectF) {
        val h = Math.round(rows.height() * 0.2f).toFloat()
        strip = RectF(rows.left, top, rows.right, top + h)
        grid = RectF(rows.left, strip.bottom, rows.right, rows.bottom)
        mediaGrid = RectF(rows.left, strip.bottom, rows.right, bar.bottom)
        this.bar = RectF(bar)
        gifs.layout(mediaGrid); stickers.layout(mediaGrid)
        relayout()
    }

    // The strip: ABC, three tabs, then the search field.
    private fun abcRect(): RectF {
        val pad = 2 * dp
        return RectF(strip.left + pad, strip.top + pad, strip.left + Math.min(strip.width() * 0.15f, 72 * dp), strip.bottom - pad)
    }
    private fun tabWidth() = Math.min(strip.width() * 0.15f, 70 * dp)
    private fun topTab(i: Int): RectF {
        val l = abcRect().right + 4 * dp
        return RectF(l + i * tabWidth(), strip.top, l + (i + 1) * tabWidth(), strip.bottom)
    }
    private fun searchRect(): RectF {
        val pad = strip.height() * 0.16f
        return RectF(topTab(2).right + 8 * dp, strip.top + pad, strip.right - 8 * dp, strip.bottom - pad)
    }
    private fun clearRect(): RectF { val s = searchRect(); return RectF(s.right - s.height() * 1.4f, s.top, s.right, s.bottom) }

    private fun relayout() {
        if (grid.width() <= 0) return
        cols = Math.max(6, (grid.width() / (grid.height() / 3.2f)).toInt())
        cell = grid.width() / cols
        val lineH = cellHeight()
        starts = FloatArray(categories.size)
        var y = 0f
        for ((i, c) in categories.withIndex()) {
            starts[i] = y
            y += ((c.emoji.size + cols - 1) / cols) * lineH
        }
        contentHeight = y
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    private fun cellHeight() = Math.min(cell, grid.height() / 3f)
    private fun maxScroll() = Math.max(0f, contentHeight - grid.height())

    // The bottom row: the tabs, backspace.
    private fun backRect() = RectF(bar.right - bar.width() * 0.15f, bar.top, bar.right, bar.bottom)
    private fun tabRect(i: Int): RectF {
        val l = bar.left; val r = backRect().left
        val w = (r - l) / categories.size
        return RectF(l + i * w, bar.top, l + (i + 1) * w, bar.bottom)
    }

    // --- Drawing ----------------------------------------------------------------------------

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Rect()

    fun draw(c: Canvas, painter: Painter, style: Style, pressedBack: Boolean, pressedAbc: Boolean, label: (Canvas, String, RectF) -> Unit) {
        val abc = abcRect()
        c.drawBitmap(painter.cap(abc.width().toInt(), abc.height().toInt(), pressedAbc), abc.left, abc.top, null)
        this.label.color = Color.WHITE
        this.label.textSize = Math.min(abc.height() * 0.46f, abc.width() * 0.3f)
        c.drawText("ABC", abc.centerX() - this.label.measureText("ABC") / 2, abc.centerY() + this.label.textSize * 0.36f + if (pressedAbc) dp else 0f, this.label)
        drawStrip(c, painter, style)
        val m = media
        if (m != null) { m.draw(c); return }
        // The grid, clipped to its area.
        c.save()
        c.clipRect(grid)
        val lineH = cellHeight()
        emojiPaint.textSize = lineH * 0.62f
        for ((ci, cat) in categories.withIndex()) {
            val top = grid.top + starts[ci] - scrollY
            val lines = (cat.emoji.size + cols - 1) / cols
            if (top > grid.bottom || top + lines * lineH < grid.top) continue
            for ((i, e) in cat.emoji.withIndex()) {
                val y = top + (i / cols) * lineH
                if (y + lineH < grid.top || y > grid.bottom) continue
                val x = grid.left + (i % cols) * cell
                emojiPaint.getTextBounds(e, 0, e.length, ink)
                c.drawText(e, x + cell / 2 - ink.exactCenterX(), y + lineH / 2 - ink.exactCenterY(), emojiPaint)
            }
            // A hairline between categories.
            if (ci > 0) {
                linePaint.color = Color.argb(70, 255, 255, 255)
                c.drawRect(grid.left + 8 * dp, top - 0.5f * dp, grid.right - 8 * dp, top + 0.5f * dp, linePaint)
            }
        }
        c.restore()

        // The bottom row, as keys.
        val back = backRect()
        c.drawBitmap(painter.cap(back.width().toInt(), back.height().toInt(), pressedBack), back.left, back.top, null)
        painter.icon(c, Painter.Icon.BACKSPACE, back.centerX(), back.centerY(), Math.min(back.width(), back.height()) * 0.42f)
        val current = currentCategory()
        val tabs = RectF(bar.left, bar.top, back.left, bar.bottom)
        c.drawBitmap(painter.cap(tabs.width().toInt(), tabs.height().toInt(), false), tabs.left, tabs.top, null)
        emojiPaint.textSize = Math.min(tabRect(0).width(), bar.height()) * 0.42f
        for (i in categories.indices) {
            val r = tabRect(i)
            emojiPaint.alpha = if (i == current) 255 else 120
            val e = categories[i].icon
            emojiPaint.getTextBounds(e, 0, e.length, ink)
            c.drawText(e, r.centerX() - ink.exactCenterX(), r.centerY() - ink.exactCenterY(), emojiPaint)
            if (i == current) {
                linePaint.color = if (style == Style.WHITE) Color.WHITE else style.accent
                c.drawRoundRect(RectF(r.centerX() - r.width() * 0.3f, r.bottom - 6.5f * dp, r.centerX() + r.width() * 0.3f, r.bottom - 4.5f * dp), dp, dp, linePaint)
            }
        }
        emojiPaint.alpha = 255
    }

    private fun drawStrip(c: Canvas, painter: Painter, style: Style) {
        val lit = if (style == Style.WHITE) Color.WHITE else style.accent
        for ((i, t) in Tab.values().withIndex()) {
            val r = topTab(i)
            val on = t == tab
            label.color = Color.WHITE
            label.alpha = if (on) 255 else 130
            when (t) {
                Tab.STICKER -> painter.icon(c, Painter.Icon.STICKER, r.centerX(), r.centerY(), r.height() * 0.58f,
                    Color.argb(if (on) 255 else 130, 255, 255, 255))
                else -> {
                    val s = if (t == Tab.EMOJI) "Emoji" else "GIF"
                    label.textSize = Math.min(r.height() * 0.42f, r.width() * 0.3f)
                    c.drawText(s, r.centerX() - label.measureText(s) / 2, r.centerY() + label.textSize * 0.36f, label)
                }
            }
            if (on) {
                linePaint.color = lit
                c.drawRoundRect(RectF(r.centerX() - r.width() * 0.28f, r.bottom - 3.5f * dp, r.centerX() + r.width() * 0.28f, r.bottom - 1.5f * dp), dp, dp, linePaint)
            }
        }
        label.alpha = 255
        val m = media ?: run {
            // A hairline under the strip, as between categories.
            linePaint.color = Color.argb(70, 255, 255, 255)
            c.drawRect(strip.left + 8 * dp, strip.bottom - 0.5f * dp, strip.right - 8 * dp, strip.bottom + 0.5f * dp, linePaint)
            return
        }
        // The search field: "Search KLIPY", as KLIPY asks, or what was searched for and an x.
        val s = searchRect()
        linePaint.color = if (pressed == SEARCH) Color.argb(70, 255, 255, 255) else Color.argb(38, 255, 255, 255)
        c.drawRoundRect(s, s.height() / 2, s.height() / 2, linePaint)
        val iconSize = s.height() * 0.56f
        painter.icon(c, Painter.Icon.SEARCH, s.left + s.height() * 0.6f, s.centerY(), iconSize, Color.argb(170, 255, 255, 255))
        val q = m.query
        label.textSize = s.height() * 0.46f
        label.alpha = if (q == null) 140 else 255
        val textLeft = s.left + s.height() * 1.1f
        val room = (if (q == null) s.right else clearRect().left) - textLeft - 4 * dp
        var t = q ?: "Search KLIPY"
        while (t.length > 1 && label.measureText(t) > room) t = t.dropLast(2) + "…"
        c.drawText(t, textLeft, s.centerY() + label.textSize * 0.36f, label)
        label.alpha = 255
        if (q != null) {
            val x = clearRect()
            painter.icon(c, Painter.Icon.CLOSE, x.centerX(), x.centerY(), s.height() * 0.42f, Color.argb(200, 255, 255, 255))
        }
    }

    private fun currentCategory(): Int {
        var cur = 0
        for (i in starts.indices) if (starts[i] <= scrollY + 1 && categories[i].emoji.isNotEmpty()) cur = i
        // Scrolled to the very end: the last tab, even if its category is short.
        if (scrollY >= maxScroll() - 1 && maxScroll() > 0) cur = categories.lastIndex
        return cur
    }

    // --- Touch ------------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var dragging = false
    private var downIn: Int = NONE
    var pressed: Int = NONE; private set

    fun onTouch(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = x; downY = y; lastY = y; dragging = false
                velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downIn = when {
                    strip.contains(x, y) -> when {
                        x < abcRect().right + 2 * dp -> ABC
                        media != null && searchRect().contains(x, y) -> SEARCH
                        else -> STRIP
                    }
                    media != null -> if (mediaGrid.contains(x, y)) GRID else NONE
                    grid.contains(x, y) -> GRID
                    backRect().contains(x, y) -> BACK
                    bar.contains(x, y) -> TABS
                    else -> NONE
                }
                pressed = downIn
                if (downIn == GRID) media?.let { it.pressed = it.itemAt(x, y) }
                if (downIn == BACK) actions.backspace()
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (downIn == GRID) {
                    if (!dragging && Math.abs(y - downY) > slop) { dragging = true; media?.pressed = -1 }
                    if (dragging) { scroll = scroll - (y - lastY); view.invalidate() }
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                when (downIn) {
                    GRID -> if (dragging) fling() else media?.tap(downX, downY) ?: tapGrid(downX, downY)
                    ABC -> if (strip.contains(x, y) && x < abcRect().right + 2 * dp) actions.letters()
                    STRIP -> Tab.values().indices.firstOrNull { topTab(it).contains(x, y) }?.let { select(Tab.values()[it]) }
                    SEARCH -> media?.let { m ->
                        if (m.query != null && clearRect().contains(x, y)) m.search(null)
                        else if (searchRect().contains(x, y)) actions.search(m.kind, m.query)
                    }
                    TABS -> if (media == null) (categories.indices).firstOrNull { tabRect(it).contains(x, y) }?.let { jumpTo(it) }
                }
                end()
            }
            MotionEvent.ACTION_CANCEL -> end()
        }
        return true
    }

    private fun end() {
        velocity?.recycle(); velocity = null
        pressed = NONE; dragging = false
        media?.pressed = -1
        view.invalidate()
    }

    private fun fling() {
        val v = velocity ?: return
        v.computeCurrentVelocity(1000)
        val vy = -v.yVelocity
        if (Math.abs(vy) < minFling) return
        scroller.fling(0, scroll.toInt(), 0, vy.toInt(), 0, 0, 0, maxScrollNow().toInt())
        view.postInvalidateOnAnimation()
    }

    /** Called from the view's computeScroll while a fling runs. */
    fun animate(): Boolean {
        if (!scroller.computeScrollOffset()) return false
        scroll = scroller.currY.toFloat()
        return true
    }

    /** The scroll of whichever grid shows. */
    private var scroll: Float
        get() = media?.scrollY ?: scrollY
        set(v) { val m = media; if (m != null) m.scrollY = v else scrollY = v.coerceIn(0f, maxScroll()) }
    private fun maxScrollNow() = media?.maxScroll() ?: maxScroll()

    private fun jumpTo(i: Int) {
        scroller.forceFinished(true)
        scrollY = starts[i].coerceIn(0f, maxScroll())
        view.invalidate()
    }

    private fun tapGrid(x: Float, y: Float) {
        val lineH = cellHeight()
        val contentY = y - grid.top + scrollY
        for ((ci, cat) in categories.withIndex()) {
            val lines = (cat.emoji.size + cols - 1) / cols
            if (contentY < starts[ci] || contentY >= starts[ci] + lines * lineH) continue
            val i = ((contentY - starts[ci]) / lineH).toInt() * cols + ((x - grid.left) / cell).toInt()
            if (i in cat.emoji.indices) {
                val e = cat.emoji[i]
                actions.emoji(e)
                Settings.addRecentEmoji(context, e)
            }
            return
        }
    }

    companion object {
        const val RECENT_MAX = 32
        private const val NONE = 0
        private const val GRID = 1
        const val ABC = 2
        const val BACK = 3
        private const val TABS = 4
        private const val STRIP = 5
        private const val SEARCH = 6

        /** The tab shown last, shown again next time. */
        internal var lastTab = Tab.EMOJI

        private var all: List<Category> = emptyList()

        private val ICONS = mapOf(
            "smileys" to "😀", "people" to "👋", "nature" to "🐻", "food" to "🍔", "travel" to "🚗",
            "activities" to "⚽", "objects" to "💡", "symbols" to "❤", "flags" to "🏁",
        )

        /** The newest emoji version each older Android can draw (before hasGlyph, API 23). */
        private fun maxVersionBefore23() = if (Build.VERSION.SDK_INT >= 22) 1.0 else 0.7

        /** Whether this device draws [e] (newer than [version] means no, before Android 6). */
        fun canDraw(paint: Paint, e: String, version: Double = 99.0) =
            if (Build.VERSION.SDK_INT >= 23) paint.hasGlyph(e) else version <= maxVersionBefore23()

        fun load(context: Context): List<Category> {
            val paint = Paint()
            val byGroup = LinkedHashMap<String, ArrayList<String>>()
            context.assets.open("emoji.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val parts = line.split('\t')
                    if (parts.size != 3) continue
                    val (group, version, e) = parts
                    if (canDraw(paint, e, version.toDoubleOrNull() ?: 99.0)) byGroup.getOrPut(group) { ArrayList() } += e
                }
            }
            return byGroup.filter { it.value.isNotEmpty() }.map { (g, list) -> Category(g, ICONS[g] ?: list[0], list) }
        }
    }
}
