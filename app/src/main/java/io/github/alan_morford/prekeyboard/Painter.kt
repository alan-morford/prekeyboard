package io.github.alan_morford.prekeyboard

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader

/**
 * The Pre's key art, drawn in code: glossy near-black caps in slightly larger dark recesses
 * on a charcoal deck. The recipe is LuneOS's tools/gen_pre_keys.py, which drew it as 48 px
 * nine-patches; here every measurement is in dp, so it is drawn sharp at any density and any
 * key size, with nothing resampled.
 *
 * A nine-patch only has shape in its borders - the middle is flat colour that stretches - so
 * the cap's top glow sits in its top 12 dp and its rim in the bottom 12, whatever its height.
 */
class Painter(private val dp: Float) {

    private val caps = HashMap<Long, Bitmap>()

    /** One key cap, [w] x [h] device px, cached by size and state. */
    fun cap(w: Int, h: Int, pressed: Boolean): Bitmap {
        val id = (w.toLong() shl 32) or (h.toLong() shl 1) or (if (pressed) 1L else 0L)
        return caps.getOrPut(id) { drawCap(w, h, pressed) }
    }

    private fun drawCap(w: Int, h: Int, pressed: Boolean): Bitmap {
        val b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888)
        // A software canvas: BlurMaskFilter is ignored by hardware ones on older Android.
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // The recess the cap sits in.
        val wellPad = 1.5f * dp
        p.color = Color.rgb(8, 9, 10)
        p.maskFilter = BlurMaskFilter(0.3f * dp, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(wellPad, wellPad, w - wellPad, h - wellPad), 9 * dp, 9 * dp, p)
        p.maskFilter = null

        // The cap: a glow under its top edge, darkest through the middle, lifting a little
        // towards the bottom rim. Pressed, every stop is lighter.
        val capPad = 2.5f * dp
        val cap = RectF(capPad, capPad, w - capPad, h - capPad)
        val capR = Math.min(8 * dp, Math.min(cap.width(), cap.height()) / 2)
        val lift = if (pressed) 26 else 0
        fun col(r: Int, g: Int, bl: Int) = Color.rgb(Math.min(255, r + lift), Math.min(255, g + lift), Math.min(255, bl + lift))
        val half = 12 * dp
        fun fromTop(y: Float) = (Math.min(y, h / 2f) / h)
        fun fromBottom(y: Float) = (Math.max(h - y, h / 2f) / h)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(col(64, 71, 76), col(40, 46, 51), col(22, 27, 30), col(20, 24, 27), col(25, 29, 32), col(38, 42, 46)),
            floatArrayOf(0f, fromTop(0.36f * half), fromTop(0.8f * half), fromBottom(0.8f * half), fromBottom(0.32f * half), 1f),
            Shader.TileMode.CLAMP)
        c.drawRoundRect(cap, capR, capR, p)
        p.shader = null

        // A thin specular line just inside the top edge, short of the corners.
        c.save()
        val clip = Path().apply { addRoundRect(cap, capR, capR, Path.Direction.CW) }
        c.clipPath(clip)
        p.color = Color.argb(if (pressed) 150 else 235, 220, 228, 232)
        p.maskFilter = BlurMaskFilter(0.25f * dp, BlurMaskFilter.Blur.NORMAL)
        val hy = capPad + 0.7f * dp
        c.drawRoundRect(RectF(capPad + 4 * dp, hy, w - capPad - 4 * dp, hy + 0.55f * dp), 0.5f * dp, 0.5f * dp, p)
        p.maskFilter = null
        c.restore()

        // A faint lighter rim along the bottom edge, fading in over its last 7 dp.
        val rim = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val rc = Canvas(rim)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.45f * dp
        p.color = Color.argb(70, 120, 128, 134)
        rc.drawRoundRect(cap, capR, capR, p)
        p.style = Paint.Style.FILL
        p.color = Color.BLACK
        p.shader = LinearGradient(0f, h - 7.2f * dp, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        rc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.xfermode = null; p.shader = null
        c.drawBitmap(rim, 0f, 0f, null)
        rim.recycle()
        return b
    }

    /** The deck behind the keys, and the dark-to-light stripes along its top edge. */
    fun deck(c: Canvas, r: RectF) {
        val p = Paint()
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.rgb(74, 81, 86), Color.rgb(58, 64, 68), Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = null
        val stripe = Math.max(1f, Math.round(dp).toFloat())
        for ((i, col) in intArrayOf(Color.rgb(14, 16, 17), Color.rgb(30, 34, 36), Color.rgb(86, 94, 99)).withIndex()) {
            p.color = col
            c.drawRect(r.left, r.top + i * stripe, r.right, r.top + (i + 1) * stripe, p)
        }
    }

    // --- Icons: the Pre's printed function-key arrows, on a 64-unit grid ---------------------

    private fun poly(vararg pts: Int): Path = Path().apply {
        moveTo(pts[0].toFloat(), pts[1].toFloat())
        var i = 2
        while (i < pts.size) { lineTo(pts[i].toFloat(), pts[i + 1].toFloat()); i += 2 }
        close()
    }

    private val up = poly(32, 6, 56, 32, 42, 32, 42, 50, 22, 50, 22, 32, 8, 32)
    private val left = poly(4, 32, 28, 10, 28, 24, 60, 24, 60, 40, 28, 40, 28, 54)
    private val ret = poly(46, 6, 60, 6, 60, 44, 28, 44, 28, 58, 4, 37, 28, 16, 28, 30, 46, 30)

    enum class Icon { SHIFT, SHIFT_ON, SHIFT_LOCK, BACKSPACE, RETURN, GLOBE, EMOJI, CURSOR, MIC, GEAR, PASTE, STICKER, SEARCH, CLOSE }

    /** Shift, when the next letter (or every letter) will be a capital: the Pre's orange, in either style. */
    private val shiftLit = Style.ORANGE.accent

    /** Draws an icon [size] px square, centred on ([cx], [cy]); [color] overrides its usual one. */
    fun icon(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.translate(Math.round(cx - size / 2).toFloat(), Math.round(cy - size / 2).toFloat())
        c.scale(size / 64f, size / 64f)
        p.color = color ?: if (icon == Icon.SHIFT_ON || icon == Icon.SHIFT_LOCK) shiftLit else Color.WHITE
        when (icon) {
            Icon.SHIFT, Icon.SHIFT_ON -> c.drawPath(up, p)
            Icon.SHIFT_LOCK -> { c.drawPath(up, p); c.drawRect(18f, 54f, 46f, 59f, p) }
            Icon.BACKSPACE -> c.drawPath(left, p)
            Icon.RETURN -> c.drawPath(ret, p)
            Icon.GLOBE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4f
                c.drawCircle(32f, 32f, 24f, p)
                c.drawOval(RectF(20f, 8f, 44f, 56f), p)
                c.drawLine(32f, 8f, 32f, 56f, p)
                c.drawLine(8f, 32f, 56f, 32f, p)
                c.drawLine(13f, 20f, 51f, 20f, p)
                c.drawLine(13f, 44f, 51f, 44f, p)
            }
            Icon.EMOJI -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4.5f
                c.drawCircle(32f, 32f, 25f, p)
                c.drawArc(RectF(18f, 22f, 46f, 47f), 20f, 140f, false, p)
                p.style = Paint.Style.FILL
                c.drawCircle(23.5f, 25f, 3.6f, p); c.drawCircle(40.5f, 25f, 3.6f, p)
            }
            // Long-pressed space: the cursor follows the finger, any way it goes.
            Icon.CURSOR -> {
                c.drawPath(poly(4, 32, 16, 22, 16, 42), p); c.drawPath(poly(60, 32, 48, 22, 48, 42), p)
                c.drawPath(poly(32, 4, 22, 16, 42, 16), p); c.drawPath(poly(32, 60, 22, 48, 42, 48), p)
                c.drawRect(15f, 30f, 49f, 34f, p); c.drawRect(30f, 15f, 34f, 49f, p)
            }
            // The period's corner: speech input.
            Icon.MIC -> {
                c.drawRoundRect(RectF(22f, 2f, 42f, 38f), 10f, 10f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.strokeCap = Paint.Cap.ROUND
                c.drawArc(RectF(13f, 16f, 51f, 48f), 0f, 180f, false, p)
                c.drawLine(32f, 48f, 32f, 58f, p)
                c.drawLine(21f, 60f, 43f, 60f, p)
            }
            // Return's corner on the Sym page: the app's settings.
            Icon.GEAR -> {
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 32f, 32f)
                    c.drawRoundRect(RectF(26f, 3f, 38f, 16f), 2f, 2f, p)
                    c.restore()
                }
                p.style = Paint.Style.STROKE; p.strokeWidth = 10f
                c.drawCircle(32f, 32f, 15f, p)
            }
            // The bar's paste: a clipboard with a page on it, its corner turned down.
            Icon.PASTE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeJoin = Paint.Join.ROUND
                c.drawRoundRect(RectF(10f, 9f, 54f, 61f), 6f, 6f, p)
                c.drawPath(Path().apply {
                    moveTo(20f, 22f); lineTo(36f, 22f); lineTo(44f, 30f); lineTo(44f, 52f); lineTo(20f, 52f); close()
                }, p)
                c.drawPath(Path().apply { moveTo(36f, 22f); lineTo(36f, 30f); lineTo(44f, 30f) }, p)
                p.strokeWidth = 3.5f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(25f, 37f, 39f, 37f, p)
                c.drawLine(25f, 44f, 35f, 44f, p)
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(22f, 3f, 42f, 15f), 3f, 3f, p)
            }
            // The emoji page's sticker tab: a smiling sticker, its corner peeling up.
            Icon.STICKER -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4.5f; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
                c.drawPath(Path().apply {
                    moveTo(16f, 6f); lineTo(48f, 6f); quadTo(58f, 6f, 58f, 16f); lineTo(58f, 38f)
                    lineTo(38f, 58f); lineTo(16f, 58f); quadTo(6f, 58f, 6f, 48f); lineTo(6f, 16f); quadTo(6f, 6f, 16f, 6f); close()
                }, p)
                c.drawPath(Path().apply { moveTo(58f, 38f); lineTo(44f, 38f); quadTo(38f, 38f, 38f, 44f); lineTo(38f, 58f) }, p)
                c.drawArc(RectF(18f, 22f, 42f, 44f), 25f, 110f, false, p)
                p.style = Paint.Style.FILL
                c.drawCircle(23f, 23f, 3.4f, p); c.drawCircle(38f, 23f, 3.4f, p)
            }
            // The search field's magnifying glass.
            Icon.SEARCH -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.strokeCap = Paint.Cap.ROUND
                c.drawCircle(27f, 27f, 17f, p)
                p.strokeWidth = 8f
                c.drawLine(40f, 40f, 56f, 56f, p)
            }
            // Clearing a search.
            Icon.CLOSE -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(12f, 12f, 52f, 52f, p); c.drawLine(52f, 12f, 12f, 52f, p)
            }
        }
        c.restore()
    }

    /**
     * The Pre's orange key: a printed square, lit with a rim while active and a heavier rim
     * when locked. On the White style it is white with a grey rim, so it still reads as lit.
     */
    fun orange(c: Canvas, key: RectF, style: Style, state: Alt) {
        val w = key.width() * 0.5f
        val h = Math.min(w * 1.3f, key.height() * 0.62f)
        val r = RectF(key.centerX() - w / 2, key.centerY() - h / 2, key.centerX() + w / 2, key.centerY() + h / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val on = state != Alt.OFF
        val (top, bottom) = if (style == Style.WHITE)
            Color.WHITE to (if (on) Color.rgb(0xE8, 0xEC, 0xEE) else Color.rgb(0xD0, 0xD4, 0xD6))
        else
            (if (on) Color.rgb(0xFF, 0x7A, 0x60) else Color.rgb(0xFF, 0x5A, 0x40)) to
                (if (on) Color.rgb(0xFF, 0x4A, 0x30) else Color.rgb(0xE0, 0x30, 0x1E))
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        val rad = w * 0.16f
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
        val rim = when (state) { Alt.LOCK -> 2f * dp; Alt.ONCE -> 1f * dp; Alt.OFF -> 0f }
        if (rim > 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = rim
            p.color = if (style == Style.WHITE) Color.rgb(0x7A, 0x80, 0x85) else Color.WHITE
            r.inset(rim / 2, rim / 2)
            c.drawRoundRect(r, rad, rad, p)
        }
    }

    fun clear() {
        for (b in caps.values) b.recycle()
        caps.clear()
    }
}

/** Orange or White: the colour of the printed alternates, the orange key and the Sym page's digits. */
enum class Style(val accent: Int) {
    ORANGE(Color.rgb(0xF2, 0x40, 0x2E)),
    WHITE(Color.WHITE),
}

enum class Alt { OFF, ONCE, LOCK }
