package io.github.alan_morford.prekeyboard

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The owner's own words: the ones added by holding a word in the suggestion bar, or kept by
 * undoing a correction, which autocorrect leaves alone and swiping can spell. Each can be
 * taken out, and a word can be typed in to add it. The keyboard follows along straight away.
 */
class DictionaryActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val unit get() = (16 * resources.displayMetrics.density).toInt()
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(unit * 2, unit * 2, unit * 2, unit * 2)
        }
        column.addView(TextView(this).apply {
            text = "Words you added by holding them in the suggestion bar, or kept by undoing a " +
                "correction. Autocorrect leaves them alone and swiping can spell them."
            setTextColor(GREY)
            gravity = Gravity.CENTER
            setPadding(0, unit / 2, 0, unit)
        })

        val field = EditText(this).apply {
            hint = "Add a word"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x80, 0x80, 0x80))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val add = Button(this).apply { text = "Add" }
        fun addTyped() {
            val w = field.text.toString().trim()
            if (!isWord(w)) { field.error = "Letters only, and an apostrophe"; return }
            Settings.addLearned(this, w)
            field.setText("")
        }
        add.setOnClickListener { addTyped() }
        field.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { addTyped(); true } else false
        }
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field)
            addView(add)
        })

        empty = TextView(this).apply {
            text = "No words yet."
            setTextColor(GREY)
            gravity = Gravity.CENTER
            setPadding(0, unit, 0, 0)
        }
        column.addView(empty)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, unit / 2, 0, 0) }
        column.addView(list)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x28, 0x2c, 0x2f))
            isFillViewport = true
            addView(column)
        }
        // Drawn edge to edge from Android 15: keep clear of the bars and the keyboard.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        fill()
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

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == Settings.LEARNED) fill()
    }

    /** One row a word, in alphabetical order, each with its Remove. */
    private fun fill() {
        val words = Settings.learned(this).sortedWith(String.CASE_INSENSITIVE_ORDER)
        empty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        for (w in words) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, unit / 4, 0, unit / 4)
                addView(TextView(this@DictionaryActivity).apply {
                    text = w
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(this@DictionaryActivity).apply {
                    text = "Remove"
                    contentDescription = "Remove $w"
                    setOnClickListener { Settings.removeLearned(this@DictionaryActivity, w) }
                })
            })
        }
    }

    companion object {
        private val GREY = Color.rgb(0xb4, 0xb4, 0xb4)

        /** What may go in: letters, with apostrophes inside ("rock'n'roll"), as the bar's words. */
        fun isWord(w: String) = w.isNotEmpty() && w.length <= 32 && w[0].isLetter() &&
            w.all { it.isLetter() || it == '\'' }
    }
}
