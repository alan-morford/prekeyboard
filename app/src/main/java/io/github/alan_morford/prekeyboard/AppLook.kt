package io.github.alan_morford.prekeyboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Button

/**
 * The app screens' colours, on their dark grey page: the Pre's orange for what is chosen or the
 * screen's main action and for titles, a light grey for any other button, so it reads as one
 * whatever the phone's own theme draws (Samsung's are near black), and a darker grey for a
 * choice not taken.
 */
object AppLook {
    val ORANGE = Style.ORANGE.accent
    val PAGE = Color.rgb(0x28, 0x2c, 0x2f)
    private val ACTION = Color.rgb(0x5f, 0x64, 0x69)
    private val UNCHOSEN = Color.rgb(0x40, 0x44, 0x48)
    private val UNCHOSEN_TEXT = Color.rgb(0xb4, 0xb4, 0xb4)

    /** A button that does something: light grey, white text. */
    fun action(b: Button) = paint(b, ACTION, Color.WHITE)

    /** The screen's main action (Add, Install), or the choice that is on: orange, white text. */
    fun main(b: Button) = paint(b, ORANGE, Color.WHITE)

    /** A choice not taken: darker grey, grey text. */
    fun unchosen(b: Button) = paint(b, UNCHOSEN, UNCHOSEN_TEXT)

    private fun paint(b: Button, back: Int, text: Int) {
        b.backgroundTintList = ColorStateList.valueOf(back)
        b.setTextColor(text)
    }
}
