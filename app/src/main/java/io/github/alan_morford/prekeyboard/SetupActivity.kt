package io.github.alan_morford.prekeyboard

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The app's one screen, and the gear beside the keyboard in Android's keyboard settings:
 * switching the keyboard on and choosing it, its style, size and typing help, and a field to
 * try it in. Every change shows on the keyboard straight away, even while it is up.
 */
class SetupActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val unit get() = (16 * resources.displayMetrics.density).toInt()
    private val refreshers = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(unit * 2, unit * 2, unit * 2, unit * 2)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        column.addView(note("The Palm Pre's keyboard. Switch it on, then choose it; " +
            "both steps are in Android's own settings."))
        column.addView(wideButton("1. Switch it on") { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) })
        column.addView(wideButton("2. Choose it") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        column.addView(heading("Style"))
        column.addView(choices(Style.values().toList(), { if (it == Style.ORANGE) "Orange" else "White" },
            { Settings.read(this).style }) { v -> Settings.update(this) { it.copy(style = v) } })
        column.addView(note("Or hold the orange key to switch."))
        column.addView(heading("Height"))
        column.addView(choices(Settings.Height.values().toList(), { it.label },
            { Settings.read(this).height }) { v -> Settings.update(this) { it.copy(height = v) } })

        column.addView(heading("Typing"))
        column.addView(toggle("Autocorrect", "Fixes typos and puts the apostrophe in contractions " +
            "(dont → don't). Backspace straight after a correction undoes it.",
            { Settings.read(this).autocorrect }) { v -> Settings.update(this) { it.copy(autocorrect = v) } })
        column.addView(toggle("Double-space period", "Two quick spaces after a word type \". \".",
            { Settings.read(this).doubleSpacePeriod }) { v -> Settings.update(this) { it.copy(doubleSpacePeriod = v) } })
        column.addView(toggle("Suggestion bar", "Three words above the keys as you type, the likeliest " +
            "in the middle in orange. Tap one to use it. Always on with swipe to type.",
            { Settings.read(this).suggestions }, forcedOn = { Settings.read(this).swipe })
            { v -> Settings.update(this) { it.copy(suggestions = v) } })
        column.addView(toggle("Swipe to type", "Slide across the letters and lift at the end of the word. " +
            "Backspace straight after takes the whole word. Shows the suggestion bar too, with the " +
            "other guesses.",
            { Settings.read(this).swipe }) { v -> Settings.update(this) { it.copy(swipe = v) } })

        column.addView(EditText(this).apply {
            hint = "Try it here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x80, 0x80, 0x80))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = unit * 2 }
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x28, 0x2c, 0x2f))
            isFillViewport = true
            addView(column)
        }
        // From Android 15 an app targeting it is drawn edge to edge, under the status and
        // navigation bars; keep the content clear of them (and of the keyboard).
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        refreshers.forEach { it() }
    }

    override fun onPause() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /** The orange key's long press changes the style from the keyboard: show it here too. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = refreshers.forEach { it() }

    private fun heading(s: String) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, unit * 2, 0, unit / 2)
    }

    private fun note(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.rgb(0xb4, 0xb4, 0xb4))
        gravity = Gravity.CENTER
        setPadding(0, unit / 2, 0, unit / 2)
    }

    /** A row of buttons, the current one lit. */
    private fun <T> choices(values: List<T>, label: (T) -> String, current: () -> T, set: (T) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val buttons = values.map { v ->
            Button(this).apply {
                text = label(v)
                setOnClickListener { set(v) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { row.addView(it) }
        }
        val refresh = { val cur = current(); buttons.forEachIndexed { i, b -> b.alpha = if (values[i] == cur) 1f else 0.45f } }
        refreshers += refresh
        refresh()
        return row
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    /** A switch with a line of explanation. While [forcedOn] holds, it shows on and greyed out. */
    private fun toggle(title: String, detail: String, current: () -> Boolean,
                       forcedOn: () -> Boolean = { false }, set: (Boolean) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, unit / 2, 0, unit / 2) }
        val sw = Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val listener = CompoundButton.OnCheckedChangeListener { _, on -> set(on) }
        val note = TextView(this).apply { text = detail; setTextColor(Color.rgb(0xb4, 0xb4, 0xb4)) }
        box.addView(sw)
        box.addView(note)
        val refresh = {
            val forced = forcedOn()
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = forced || current()
            sw.isEnabled = !forced
            box.alpha = if (forced) 0.45f else 1f
            sw.setOnCheckedChangeListener(listener)
        }
        refreshers += refresh
        refresh()
        return box
    }

    private fun wideButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = unit / 2 }
    }
}
