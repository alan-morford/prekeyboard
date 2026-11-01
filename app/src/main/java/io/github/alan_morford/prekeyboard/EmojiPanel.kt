package io.github.alan_morford.prekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller

/**
 * The emoji page: every emoji the device can draw, by Unicode's categories, in a grid that
 * scrolls; a row of category tabs along the bottom between ABC and backspace, as on the Sym
 * page's bottom row. Tapping a tab jumps to its category, and the tab lights as you scroll.
 *
 * [KeyboardView] owns it, gives it the keys' area, and hands it every touch while it shows.
 */
class EmojiPanel(private val context: Context, private val view: KeyboardView, private val actions: Actions) {

    interface Actions {
        fun emoji(e: String)
        fun backspace()
        fun letters()
    }

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
    }

    // --- Geometry ---------------------------------------------------------------------------

    private val dp = context.resources.displayMetrics.density
    private var grid = RectF()
    private var bar = RectF()
    private var cols = 8
    private var cell = 48f
    /** Where each category's first line starts, in content px from the top of the grid. */
    private var starts = FloatArray(0)
    private var contentHeight = 0f
    private var scrollY = 0f

    fun layout(grid: RectF, bar: RectF) {
        this.grid = RectF(grid); this.bar = RectF(bar)
        relayout()
    }

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

    // The bottom row: ABC, the tabs, backspace.
    private fun abcRect() = RectF(bar.left, bar.top, bar.left + bar.width() * 0.15f, bar.bottom)
    private fun backRect() = RectF(bar.right - bar.width() * 0.15f, bar.top, bar.right, bar.bottom)
    private fun tabRect(i: Int): RectF {
        val l = abcRect().right; val r = backRect().left
        val w = (r - l) / categories.size
        return RectF(l + i * w, bar.top, l + (i + 1) * w, bar.bottom)
    }

    // --- Drawing ----------------------------------------------------------------------------

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Rect()

    fun draw(c: Canvas, painter: Painter, style: Style, pressedBack: Boolean, pressedAbc: Boolean, label: (Canvas, String, RectF) -> Unit) {
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
        val abc = abcRect(); val back = backRect()
        c.drawBitmap(painter.cap(abc.width().toInt(), abc.height().toInt(), pressedAbc), abc.left, abc.top, null)
        label(c, "ABC", abc)
        c.drawBitmap(painter.cap(back.width().toInt(), back.height().toInt(), pressedBack), back.left, back.top, null)
        painter.icon(c, Painter.Icon.BACKSPACE, back.centerX(), back.centerY(), Math.min(back.width(), back.height()) * 0.42f)
        val current = currentCategory()
        val tabs = RectF(abc.right, bar.top, back.left, bar.bottom)
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
                    grid.contains(x, y) -> GRID
                    abcRect().contains(x, y) -> ABC
                    backRect().contains(x, y) -> BACK
                    bar.contains(x, y) -> TABS
                    else -> NONE
                }
                pressed = downIn
                if (downIn == BACK) actions.backspace()
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (downIn == GRID) {
                    if (!dragging && Math.abs(y - downY) > slop) dragging = true
                    if (dragging) { scrollY = (scrollY - (y - lastY)).coerceIn(0f, maxScroll()); view.invalidate() }
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                when (downIn) {
                    GRID -> if (dragging) fling() else tapGrid(downX, downY)
                    ABC -> if (abcRect().contains(x, y)) actions.letters()
                    TABS -> (categories.indices).firstOrNull { tabRect(it).contains(x, y) }?.let { jumpTo(it) }
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
        view.invalidate()
    }

    private fun fling() {
        val v = velocity ?: return
        v.computeCurrentVelocity(1000)
        val vy = -v.yVelocity
        if (Math.abs(vy) < minFling) return
        scroller.fling(0, scrollY.toInt(), 0, vy.toInt(), 0, 0, 0, maxScroll().toInt())
        view.postInvalidateOnAnimation()
    }

    /** Called from the view's computeScroll while a fling runs. */
    fun animate(): Boolean {
        if (!scroller.computeScrollOffset()) return false
        scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll())
        return true
    }

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
