package io.github.alan_morford.prekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import android.view.View
import java.nio.ByteBuffer

/**
 * One KLIPY tab of the emoji page: GIFs or stickers, trending or searched for, in columns
 * that scroll like the emoji grid. The pictures move (animated WebP from Android 9, GIFs
 * through [GifDrawable] before) and are kept only in memory; only those scrolled to are
 * loaded. The next page is asked for only once the owner scrolls near the end, since KLIPY
 * counts those calls.
 *
 * [EmojiPanel] owns it: gives it the grid's area, the scrolling, and the taps.
 */
class MediaPanel(
    private val context: Context,
    private val view: View,
    val kind: Klipy.Kind,
    typeface: Typeface,
    private val pick: (Klipy.Item, String?) -> Unit,
) {
    /** What was searched for, or null for what's trending. */
    var query: String? = null; private set
    private var items: List<Klipy.Item> = emptyList()
    private var page = 0
    private var hasNext = false
    private var loading = false
    private var failed = false
    /** What KLIPY said when it refused (a bad key, say), rather than not answering. */
    private var refusal: String? = null
    /** Answers to older requests are dropped when this has moved on. */
    private var generation = 0
    private var loadedAt = 0L

    /** Shows what's trending, unless it (or a search) is already showing and fresh. */
    fun open() {
        if (!Klipy.configured) return
        if (items.isNotEmpty() && SystemClock.elapsedRealtime() - loadedAt < FRESH_FOR) return
        if (loading) return
        search(query)
    }

    /** Starts again from the first page of [q] (trending when blank). */
    fun search(q: String?) {
        query = q?.trim()?.takeIf { it.isNotEmpty() }
        items = emptyList(); page = 0; hasNext = false; failed = false; refusal = null
        scrollY = 0f
        relayout()
        if (!Klipy.configured) { view.invalidate(); return }
        more()
    }

    private fun more() {
        if (loading) return
        loading = true
        val gen = ++generation
        Klipy.fetch(context, kind, query, page + 1) { r ->
            if (gen != generation) return@fetch
            loading = false
            r.onSuccess { p ->
                items = items + p.items
                page = p.page; hasNext = p.hasNext && p.items.isNotEmpty()
                loadedAt = SystemClock.elapsedRealtime()
                relayout()
            }.onFailure { failed = true; refusal = (it as? Klipy.Refused)?.message }
            view.invalidate()
        }
        view.invalidate()
    }

    /** Lets the moving pictures rest while the tab isn't showing. */
    fun close() {
        for (d in running) (d as? Animatable)?.stop()
        running.clear()
    }

    // --- Geometry ---------------------------------------------------------------------------

    private val dp = context.resources.displayMetrics.density
    private var grid = RectF()
    private var rects: List<RectF> = emptyList()
    private var contentHeight = 0f
    var scrollY = 0f
        set(v) { field = v.coerceIn(0f, maxScroll()) }
    private val gap get() = 3 * dp

    fun layout(r: RectF) {
        grid = RectF(r)
        relayout()
    }

    fun maxScroll() = Math.max(0f, contentHeight - grid.height())

    /** Shortest column first, in KLIPY's order; GIFs keep their shape, stickers are squares. */
    private fun relayout() {
        if (grid.width() <= 0) return
        // Big enough to see: two GIFs across a phone, three stickers.
        val target = if (kind == Klipy.Kind.GIF) 170 * dp else 110 * dp
        val cols = Math.max(2, (grid.width() / target).toInt())
        val colW = (grid.width() - gap * (cols + 1)) / cols
        val heights = FloatArray(cols) { gap }
        rects = items.map { item ->
            var c = 0
            for (i in 1 until cols) if (heights[i] < heights[c] - 0.5f) c = i
            val h = if (kind == Klipy.Kind.GIF) colW / item.aspect.coerceIn(0.6f, 2.4f) else colW
            val x = gap + c * (colW + gap)
            RectF(x, heights[c], x + colW, heights[c] + h).also { heights[c] += h + gap }
        }
        contentHeight = heights.maxOrNull() ?: 0f
        scrollY = scrollY
    }

    // --- Pictures ---------------------------------------------------------------------------

    private val cache = object : LruCache<String, Drawable>(CACHED) {
        override fun entryRemoved(evicted: Boolean, key: String, old: Drawable, new: Drawable?) {
            (old as? Animatable)?.stop(); running.remove(old)
        }
    }
    private val blurs = LruCache<String, Bitmap>(CACHED)
    private val pending = HashSet<String>()
    private val running = HashSet<Drawable>()
    private val main = Handler(Looper.getMainLooper())

    /**
     * The preview to show: a small animated WebP from Android 9, a small GIF before (WebP that
     * moves can't be decoded there); a still if there is neither.
     */
    private fun previewUrl(item: Klipy.Item): String? {
        val f = if (Build.VERSION.SDK_INT >= 28) item.first("webp", "sm", "xs", "md") ?: item.first("gif", "sm", "xs")
            else item.first("gif", "sm", "xs")
        return (f ?: item.first("jpg", "sm", "xs", "md", "hd") ?: item.first("png", "sm", "xs", "md", "hd"))?.url
    }

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = view.invalidate()
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { main.postAtTime(what, who, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = main.removeCallbacks(what, who)
    }

    private fun picture(url: String, width: Int): Drawable? {
        cache.get(url)?.let { return it }
        if (pending.add(url)) Klipy.background {
            val d = runCatching { decode(Klipy.get(url), width) }.getOrNull()
            main.post {
                pending.remove(url)
                if (d != null) { d.callback = callback; cache.put(url, d); view.invalidate() }
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray, width: Int): Drawable? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                // No bigger than the cell needs: a sm GIF is a few hundred pixels across.
                if (info.size.width > width * 1.3f) {
                    val s = width.toFloat() / info.size.width
                    d.setTargetSize(width, Math.max(1, Math.round(info.size.height * s)))
                }
            }
        }
        GifDrawable.from(bytes)?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= width) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return BitmapDrawable(context.resources, b)
    }

    private fun blur(item: Klipy.Item): Bitmap? {
        val data = item.blur ?: return null
        blurs.get(item.id)?.let { return it }
        val b = runCatching {
            val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        blurs.put(item.id, b)
        return b
    }

    // --- Drawing ----------------------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; color = Color.WHITE }
    private val clip = Path()
    private val drawn = HashSet<Drawable>()
    /** The item under a finger that is down, lit till it lifts. */
    var pressed = -1
    private var toast: String? = null
    private var toastUntil = 0L

    /** A line over the bottom of the grid for a moment ("Sending…", "This app doesn't take GIFs"). */
    fun say(s: String, ms: Long = 2500) {
        toast = s; toastUntil = SystemClock.uptimeMillis() + ms
        view.invalidate()
        view.postDelayed({ view.invalidate() }, ms + 20)
    }

    fun draw(c: Canvas) {
        c.save()
        c.clipRect(grid)
        drawn.clear()
        val r = 6 * dp
        for ((i, box) in rects.withIndex()) {
            val top = grid.top + box.top - scrollY
            if (top > grid.bottom || top + box.height() < grid.top) continue
            val cell = RectF(grid.left + box.left, top, grid.left + box.right, top + box.height())
            val item = items[i]
            val d = previewUrl(item)?.let { picture(it, cell.width().toInt()) }
            c.save()
            if (kind == Klipy.Kind.GIF) {
                clip.reset(); clip.addRoundRect(cell, r, r, Path.Direction.CW)
                c.clipPath(clip)
            }
            if (d == null) {
                val b = if (kind == Klipy.Kind.GIF) blur(item) else null
                if (b != null) c.drawBitmap(b, null, cell, bitmapPaint)
                else { fill.color = Color.argb(28, 255, 255, 255); c.drawRoundRect(cell, r, r, fill) }
            } else {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: cell.width().toInt()
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: cell.height().toInt()
                // GIFs fill their cell (it has their shape); stickers fit inside theirs.
                val s = if (kind == Klipy.Kind.GIF) Math.max(cell.width() / w, cell.height() / h)
                    else Math.min(cell.width() / w, cell.height() / h) * 0.92f
                val dw = w * s; val dh = h * s
                d.setBounds((cell.centerX() - dw / 2).toInt(), (cell.centerY() - dh / 2).toInt(),
                    (cell.centerX() + dw / 2).toInt(), (cell.centerY() + dh / 2).toInt())
                d.draw(c)
                drawn += d
            }
            if (i == pressed) { fill.color = Color.argb(70, 255, 255, 255); c.drawRect(cell, fill) }
            c.restore()
        }
        // Only what's on screen moves.
        for (d in drawn) if (d is Animatable && running.add(d)) d.start()
        val off = running.filter { it !in drawn }
        for (d in off) { (d as? Animatable)?.stop(); running.remove(d) }

        val message = when {
            !Klipy.configured -> "${kind.noun.replaceFirstChar { it.uppercase() }} need a KLIPY key in this build"
            items.isEmpty() && loading -> "Loading…"
            items.isEmpty() && refusal != null -> "KLIPY: $refusal"
            items.isEmpty() && failed -> "Can't reach KLIPY. Tap to try again."
            items.isEmpty() && query != null -> "No ${kind.noun} for “$query”"
            items.isEmpty() -> "No ${kind.noun} right now"
            else -> null
        }
        if (message != null) {
            label.textSize = 15 * dp
            label.alpha = 170
            val w = label.measureText(message)
            if (w > grid.width() - 24 * dp) label.textSize *= (grid.width() - 24 * dp) / w
            c.drawText(message, grid.centerX() - label.measureText(message) / 2, grid.centerY(), label)
            label.alpha = 255
        }
        // Scrolled to within a screen of the end: the next page.
        if (hasNext && !loading && !failed && scrollY > 0 && scrollY + grid.height() * 2 > contentHeight) more()
        c.restore()

        val t = toast
        if (t != null && SystemClock.uptimeMillis() < toastUntil) {
            label.textSize = 14 * dp
            val w = label.measureText(t) + 28 * dp
            val box = RectF(grid.centerX() - w / 2, grid.bottom - 40 * dp, grid.centerX() + w / 2, grid.bottom - 10 * dp)
            fill.color = Color.argb(225, 20, 22, 24)
            c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill)
            label.color = Color.WHITE
            c.drawText(t, box.centerX() - label.measureText(t) / 2, box.centerY() + label.textSize * 0.35f, label)
        } else toast = null
    }

    // --- Touch ------------------------------------------------------------------------------

    /** The item at [x], [y] in view pixels, or -1. */
    fun itemAt(x: Float, y: Float): Int {
        if (!grid.contains(x, y)) return -1
        val cx = x - grid.left; val cy = y - grid.top + scrollY
        return rects.indexOfFirst { it.contains(cx, cy) }
    }

    fun tap(x: Float, y: Float) {
        if (items.isEmpty()) { if (failed && !loading) search(query); return }
        val i = itemAt(x, y)
        if (i in items.indices) pick(items[i], query)
    }

    companion object {
        /** Pictures kept in memory, per tab: fewer before Android 9, where a GIF is held whole. */
        private val CACHED = if (Build.VERSION.SDK_INT >= 28) 60 else 30
        /** What showed stays as it was for this long between openings (as [Klipy] keeps answers). */
        private const val FRESH_FOR = 60 * 60_000L
    }
}
