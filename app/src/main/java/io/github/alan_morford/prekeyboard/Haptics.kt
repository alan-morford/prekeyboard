package io.github.alan_morford.prekeyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The keyboard's own vibrations: a light tap for a key (when "Vibrate on keys" is on) and a
 * slightly firmer one when autocorrect changes a word. Android's own keyboard feedback
 * (performHapticFeedback) follows a system setting that many phones keep off, so the
 * vibrator is driven directly. A device without one (the Nexus 7) simply stays still.
 */
object Haptics {

    private var vibrator: Vibrator? = null

    private fun vibrator(context: Context): Vibrator? = vibrator ?: runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.takeIf { it.hasVibrator() }
    }.getOrNull().also { vibrator = it }

    /** A key went down; [level] is the owner's strength, 1 to [LEVELS]. */
    fun key(context: Context, level: Int) {
        val i = level.coerceIn(1, LEVELS) - 1
        buzz(context, KEY_MS[i], KEY_STRENGTH[i])
    }

    /** Autocorrect changed the word just finished. */
    fun corrected(context: Context) = buzz(context, CORRECTED_MS, CORRECTED_STRENGTH)

    private fun buzz(context: Context, ms: Long, strength: Int) {
        val v = vibrator(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val amp = if (v.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    // Strengths out of 255, where the motor can be told one.
    // A key's tap at each level: 1 is the light one it always was; each after it a step firmer,
    // and a little longer, for a motor (or an Android before 8) that can't be told a strength.
    const val LEVELS = 3
    private val KEY_MS = longArrayOf(12L, 15L, 18L)
    private val KEY_STRENGTH = intArrayOf(70, 130, 200)
    private const val CORRECTED_MS = 28L
    private const val CORRECTED_STRENGTH = 110
}
