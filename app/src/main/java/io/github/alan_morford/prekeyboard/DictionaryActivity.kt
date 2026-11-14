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
 * tapping it as typed there, which autocorrect leaves alone and swiping can spell. Each can be
 * taken out, and a word can be typed in to add it. The keyboard follows along straight away.
 *
 * Below them, the dictionary's own words the owner deleted (held in the bar: "yest"), each
 * with Put back, in case one went by mistake.
 *
 * Each language keeps its own (the Englishes share one); with more than one checked, a row
 * of buttons picks whose words are shown, starting with the one being typed in.
 */
class DictionaryActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val unit get() = (16 * resources.displayMetrics.density).toInt()
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var deletedTitle: TextView
    private lateinit var deleted: LinearLayout
    /** Whose words are shown. */
    private var lang = Language.EN_US
    private val pickers = ArrayList<Pair<Language, Button>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(unit * 2, unit * 2, unit * 2, unit * 2)
        }
        column.addView(TextView(this).apply {
            text = "Your dictionary"
            textSize = 28f
            setTextColor(AppLook.ORANGE)
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = "Words autocorrect leaves alone."
            setTextColor(GREY)
            gravity = Gravity.CENTER
            setPadding(0, unit / 2, 0, unit)
        })

        // One button per language family checked, when there is more than one.
        val settings = Settings.read(this)
        lang = settings.language
        // Chinese keeps no list of words: what is picked for each pinyin is remembered on its own.
        val families = settings.languages.filter { !it.pinyin }.distinctBy { it.family }
        if (lang.pinyin) lang = families.firstOrNull() ?: Language.EN_US
        if (families.size > 1) {
            if (families.none { it.family == lang.family }) lang = families[0]
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, unit / 2)
                for (l in families) {
                    val b = Button(this@DictionaryActivity).apply {
                        text = if (l.english) "English" else l.short
                        isAllCaps = false
                        setOnClickListener { lang = l; paintPickers(); fill() }
                    }
                    pickers += l to b
                    addView(b)
                }
            })
            paintPickers()
        }

        val field = EditText(this).apply {
            hint = "Add a word"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x80, 0x80, 0x80))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Add in the Pre's orange, like Install in the update popup: the screen's one action.
        val add = Button(this).apply { text = "Add"; AppLook.main(this) }
        fun addTyped() {
            val w = field.text.toString().trim()
            if (!isWord(w)) { field.error = "Letters only, and an apostrophe"; return }
            Settings.addLearned(this, lang, w)
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
        deletedTitle = TextView(this).apply {
            text = "Deleted words"
            textSize = 20f
            setTextColor(AppLook.ORANGE)
            setPadding(0, unit * 2, 0, 0)
        }
        column.addView(deletedTitle)
        column.addView(TextView(this).apply {
            tag = "deletedNote"
            text = "Words never suggested or corrected to."
            setTextColor(GREY)
            setPadding(0, unit / 4, 0, unit / 4)
        })
        deleted = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(deleted)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppLook.PAGE)
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
        if (key == Settings.learnedKey(lang) || key == Settings.removedKey(lang)) fill()
    }

    private fun paintPickers() {
        for ((l, b) in pickers) if (l.family == lang.family) AppLook.main(b) else AppLook.unchosen(b)
    }

    /** One row a word, in alphabetical order, each with its Remove; then the deleted ones, each with Put back. */
    private fun fill() {
        val words = Settings.learned(this, lang).sortedWith(String.CASE_INSENSITIVE_ORDER)
        empty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        for (w in words) list.addView(row(w, "Remove") { Settings.removeLearned(this, lang, w) })
        val gone = Settings.removed(this, lang).sortedWith(String.CASE_INSENSITIVE_ORDER)
        val show = if (gone.isEmpty()) View.GONE else View.VISIBLE
        deletedTitle.visibility = show
        (deletedTitle.parent as LinearLayout).findViewWithTag<View>("deletedNote")?.visibility = show
        deleted.removeAllViews()
        for (w in gone) deleted.addView(row(w, "Put back") { Settings.restoreRemoved(this, lang, w) })
    }

    private fun row(w: String, action: String, act: () -> Unit): View =
            LinearLayout(this).apply {
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
                    text = action
                    AppLook.action(this)
                    contentDescription = "$action $w"
                    setOnClickListener { act() }
                })
            }

    companion object {
        private val GREY = Color.rgb(0xb4, 0xb4, 0xb4)

        /** What may go in: letters, with apostrophes inside ("rock'n'roll"), as the bar's words. */
        fun isWord(w: String) = w.isNotEmpty() && w.length <= 32 && w[0].isLetter() &&
            w.all { it.isLetter() || it == '\'' }
    }
}
