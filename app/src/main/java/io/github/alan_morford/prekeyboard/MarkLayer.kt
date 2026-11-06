package io.github.alan_morford.prekeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The orange dashed line under each word autocorrect changed. A keyboard can't draw inside
 * another app's text field, so this transparent view fills the keyboard's window up over the
 * app, and draws where the field says the word is (CursorAnchorInfo, which native fields,
 * Chrome and the WebView report). It never takes a touch: the keyboard's insets leave
 * everything above the keys to the app. The way of drawing over the field is the one
 * LunaKeyboard (github.com/webOSArchive/Lunacy) worked out for webOS's dotted line.
 */
@SuppressLint("ViewConstructor")
class MarkLayer(context: Context) : View(context) {

    /**
     * A corrected word: where it is in the text, and its box in the field's own coordinates
     * (left, top, right, and the line to draw on as bottom), which [fieldMatrix] takes to the screen.
     */
    class Mark(val start: Int, val text: String, val local: RectF) {
        val end get() = start + text.length
    }

    val marks = ArrayList<Mark>()

    /** The field's own coordinates to the screen's, as it last said. */
    var fieldMatrix: Matrix? = null
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    // Drawn dash by dash: before Android 9 the hardware renderer ignores a dash effect on a line.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Style.ORANGE.accent }
    private val here = IntArray(2)
    private val box = RectF()

    fun add(m: Mark) {
        marks += m
        while (marks.size > MAX) marks.removeAt(0)
        invalidate()
    }

    fun remove(gone: Collection<Mark>) { if (marks.removeAll(gone.toSet())) invalidate() }

    fun clear() { if (marks.isNotEmpty()) { marks.clear(); invalidate() } }

    override fun onDraw(c: Canvas) {
        val m = fieldMatrix ?: return
        if (marks.isEmpty()) return
        getLocationOnScreen(here)
        for (mark in marks) {
            box.set(mark.local)
            m.mapRect(box)
            val y = box.bottom - here[1]
            val dash = DASH * dp; val gap = GAP * dp; val thick = THICK * dp
            val left = box.left - here[0]; val right = box.right - here[0]
            // As many whole dashes as fit, spread to meet both ends of the word.
            val n = Math.max(1, ((right - left + gap) / (dash + gap)).toInt())
            val step = if (n > 1) (right - left - dash) / (n - 1) else 0f
            for (i in 0 until n) {
                val x = left + i * step
                c.drawRect(x, y - thick / 2, Math.min(right, x + dash), y + thick / 2, paint)
            }
        }
    }

    companion object {
        /** The most marks kept: each costs a look at the text when the cursor nears it. */
        const val MAX = 8
        // In dp.
        private const val DASH = 4f
        private const val GAP = 2.5f
        private const val THICK = 2f
    }
}
