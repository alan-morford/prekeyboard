package io.github.alan_morford.prekeyboard

import android.app.Activity
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The languages to type in: a box to check for each. With more than one checked, a flick
 * left or right on the space bar goes from one to the next, in this screen's order, and the
 * language's name shows on the space bar as it changes. One is always checked: the last one
 * can't be taken away. The one being typed in now is marked.
 */
class LanguagesActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val unit get() = (16 * resources.displayMetrics.density).toInt()
    private val boxes = ArrayList<Pair<Language, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(unit * 2, unit * 2, unit * 2, unit * 2)
        }
        column.addView(TextView(this).apply {
            text = "Languages"
            textSize = 28f
            setTextColor(AppLook.ORANGE)
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = "Check the languages you type in. With more than one, flick the space bar left " +
                "or right to change language; its name shows on the space bar as it changes. Each " +
                "language has its own keys (AZERTY for French, QWERTZ for German), words and " +
                "suggestions. Autocorrect puts accents in for you (etre → être); Hold for more " +
                "puts them on the keys too."
            setTextColor(GREY)
            gravity = Gravity.CENTER
            setPadding(0, unit / 2, 0, unit)
        })
        for (lang in Language.ALL) {
            val box = CheckBox(this).apply {
                textSize = 18f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(AppLook.ORANGE)
                setPadding(unit / 2, unit / 2, 0, unit / 2)
                setOnClickListener { toggle(lang, isChecked) }
            }
            boxes += lang to box
            column.addView(box)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppLook.PAGE)
            isFillViewport = true
            addView(column)
        }
        // Drawn edge to edge from Android 15: keep clear of the bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        fill()
    }

    /** Checked or unchecked: the last language left stays checked. */
    private fun toggle(lang: Language, on: Boolean) {
        Settings.update(this) { s ->
            val now = if (on) s.languages + lang else s.languages - lang
            if (now.isEmpty()) s
            else {
                val ordered = Language.ALL.filter { it in now }
                s.copy(languages = ordered, language = if (s.language in ordered) s.language else ordered[0])
            }
        }
        fill()
    }

    private fun fill() {
        val s = Settings.read(this)
        for ((lang, box) in boxes) {
            box.isChecked = lang in s.languages
            box.text = if (lang == s.language && s.languages.size > 1) "${lang.name}  ·  typing now" else lang.name
        }
    }

    override fun onResume() {
        super.onResume()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        fill()
    }

    override fun onPause() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = fill()

    private companion object {
        val GREY = Color.rgb(0xb4, 0xb4, 0xb4)
    }
}
