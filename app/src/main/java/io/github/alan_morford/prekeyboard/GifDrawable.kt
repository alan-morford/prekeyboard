package io.github.alan_morford.prekeyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/**
 * A moving GIF before Android 9, which has no decoder for moving pictures: Android's old
 * [Movie] plays it. Each frame is drawn into a bitmap of the GIF's own size, then that is
 * drawn scaled to the cell, so it shows the same on any canvas. Frames come at most
 * [FRAME] ms apart, which keeps an old tablet's work down with a screenful moving.
 */
@Suppress("DEPRECATION")
class GifDrawable private constructor(private val movie: Movie) : Drawable(), Animatable {

    private val frame = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frame)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var running = false
    private var startedAt = 0L
    private var drawnTime = -1
    private val next = Runnable { invalidateSelf() }

    override fun getIntrinsicWidth() = movie.width()
    override fun getIntrinsicHeight() = movie.height()

    override fun draw(c: Canvas) {
        val duration = movie.duration().takeIf { it > 0 } ?: 1000
        val t = if (running) ((SystemClock.uptimeMillis() - startedAt) % duration).toInt() else 0
        if (t != drawnTime) {
            movie.setTime(t)
            frame.eraseColor(0)
            movie.draw(frameCanvas, 0f, 0f)
            drawnTime = t
        }
        c.drawBitmap(frame, null, bounds, paint)
        if (running) scheduleSelf(next, SystemClock.uptimeMillis() + FRAME)
    }

    override fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun stop() {
        running = false
        unscheduleSelf(next)
    }

    override fun isRunning() = running
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val FRAME = 66L

        /** A moving GIF from [bytes], or null when it isn't one (a still, or not a GIF). */
        fun from(bytes: ByteArray): GifDrawable? {
            val m = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (m.width() <= 0 || m.height() <= 0 || m.duration() <= 0) return null
            return GifDrawable(m)
        }
    }
}
