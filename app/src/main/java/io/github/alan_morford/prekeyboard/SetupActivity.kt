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
 * switching the keyboard on and choosing it, its languages, style, size and typing help, and a field to
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
            setTextColor(ORANGE)
            gravity = Gravity.CENTER
        })
        column.addView(note("The Palm Pre's keyboard. Switch it on, then choose it."))
        column.addView(wideButton("1. Switch it on") { startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)) })
        column.addView(wideButton("2. Choose it") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        column.addView(heading("Languages"))
        column.addView(TextView(this).apply {
            setTextColor(Color.rgb(0xb4, 0xb4, 0xb4))
            gravity = Gravity.CENTER
            setPadding(0, unit / 2, 0, unit / 2)
            val refresh = {
                val s = Settings.read(this@SetupActivity)
                text = if (s.languages.size == 1) "Typing in ${s.language.name}."
                    else s.languages.joinToString(", ") { it.name } + ". Flick the space bar to switch."
            }
            refreshers += refresh
            refresh()
        })
        column.addView(wideButton("Choose languages") { startActivity(Intent(this, LanguagesActivity::class.java)) })

        column.addView(heading("Style"))
        column.addView(choices(Style.values().toList(), { if (it == Style.ORANGE) "Orange" else "White" },
            { Settings.read(this).style }) { v -> Settings.update(this) { it.copy(style = v) } })
        column.addView(note("Or hold the globe on the Sym page."))
        column.addView(heading("Height"))
        column.addView(choices(Settings.Height.values().toList(), { it.label },
            { Settings.read(this).height }) { v -> Settings.update(this) { it.copy(height = v) } })

        column.addView(heading("Keys"))
        column.addView(toggle("Number row", "Digits always above the letters.",
            { Settings.read(this).numberRow }) { v -> Settings.update(this) { it.copy(numberRow = v) } })
        // Its sub-switch, set in under it and greyed out while the number row is off.
        val hideDigits = toggle("Hide digits on the letters", "No digits printed on E R T, D F G, X C V and @.",
            { Settings.read(this).hideKeyDigits }) { v -> Settings.update(this) { it.copy(hideKeyDigits = v) } }
        hideDigits.setPadding(unit * 2, hideDigits.paddingTop, 0, hideDigits.paddingBottom)
        column.addView(hideDigits)
        val dimDigits = {
            val on = Settings.read(this).numberRow
            hideDigits.alpha = if (on) 1f else 0.45f
            setEnabledAll(hideDigits, on)
        }
        refreshers += dimDigits
        dimDigits()
        column.addView(toggle("Lowercase letters", "Capitals only while shift is on.",
            { Settings.read(this).lowercase }) { v -> Settings.update(this) { it.copy(lowercase = v) } })

        column.addView(heading("Typing"))
        column.addView(toggle("Autocorrect", "Fixes typos as you type. Tap an underlined word to undo it.",
            { Settings.read(this).autocorrect }) { v -> Settings.update(this) { it.copy(autocorrect = v) } })
        column.addView(toggle("Double-space period", "Two quick spaces type \". \".",
            { Settings.read(this).doubleSpacePeriod }) { v -> Settings.update(this) { it.copy(doubleSpacePeriod = v) } })
        column.addView(toggle("Suggestion bar", "Word suggestions above the keys.",
            { Settings.read(this).suggestions }, forcedOn = { Settings.read(this).let { it.swipe || it.emojiSuggestions } })
            { v -> Settings.update(this) { it.copy(suggestions = v) } })
        column.addView(toggle("Swipe to type", "Slide across the letters to spell a word.",
            { Settings.read(this).swipe }) { v -> Settings.update(this) { it.copy(swipe = v) } })
        column.addView(toggle("Emoji suggestions", "Offers an emoji for the word (pizza → 🍕).",
            { Settings.read(this).emojiSuggestions }) { v -> Settings.update(this) { it.copy(emojiSuggestions = v) } })
        column.addView(toggle("Hold for more", "Hold a dotted key for accents and symbols.",
            { Settings.read(this).holdForMore }) { v -> Settings.update(this) { it.copy(holdForMore = v) } })
        column.addView(toggle("Vibrate on keys", "A light buzz on each key.",
            { Settings.read(this).vibrate }) { v -> Settings.update(this) { it.copy(vibrate = v) } })
        // Its strength, felt as it is chosen; greyed out while vibration is off.
        val strength = choices((1..Haptics.LEVELS).toList(), { listOf("Light", "Firmer", "Strong")[it - 1] },
            { Settings.read(this).vibrateLevel }) { v ->
            Settings.update(this) { it.copy(vibrateLevel = v) }
            Haptics.key(this, v)
        }
        column.addView(strength)
        val dim = { strength.alpha = if (Settings.read(this).vibrate) 1f else 0.45f }
        refreshers += dim
        dim()
        column.addView(toggle("Sound on keys", "A click on each key.",
            { Settings.read(this).sound }) { v -> Settings.update(this) { it.copy(sound = v) } })

        column.addView(heading("Dictionary"))
        column.addView(note("Words you've added."))
        column.addView(wideButton("Edit dictionary") { startActivity(Intent(this, DictionaryActivity::class.java)) })

        column.addView(heading("Updates"))
        column.addView(note("Version ${Updater.installedVersion(this)}. Checks daily."))
        column.addView(wideButton("Check now") { checkForUpdate(it) })

        val tryIt = EditText(this).apply {
            hint = "Try it here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(0x80, 0x80, 0x80))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = unit * 2 }
        }
        column.addView(tryIt)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(AppLook.PAGE)
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
            // The keyboard came up (or changed height) for the try-it field: bring the field
            // up above it, so what is typed can be seen.
            if (tryIt.hasFocus()) v.post { scrollToTryIt(scroll, tryIt) }
            insets
        }
        tryIt.setOnFocusChangeListener { _, focused -> if (focused) scroll.post { scrollToTryIt(scroll, tryIt) } }
        setContentView(scroll)
    }

    /** Scrolls until the bottom of [field] (the last thing on the screen) sits just above the keyboard. */
    private fun scrollToTryIt(scroll: ScrollView, field: View) {
        val visible = scroll.height - scroll.paddingTop - scroll.paddingBottom
        scroll.smoothScrollTo(0, Math.max(0, field.bottom + unit - visible))
    }

    override fun onResume() {
        super.onResume()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        refreshers.forEach { it() }
        // A crash since last time: the offer to report it, here as well as in the keyboard's bar.
        if (CrashLog.hasPending(this) && !crashOffered) {
            crashOffered = true
            startActivity(Intent(this, CrashActivity::class.java))
        }
    }

    /** The crash report was offered once while this screen was open. */
    private var crashOffered = false

    override fun onPause() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /** Long presses on the keyboard change the style and "Hold for more": show them here too. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) = refreshers.forEach { it() }

    private fun heading(s: String) = TextView(this).apply {
        text = s
        textSize = 16f
        setTextColor(ORANGE)
        gravity = Gravity.CENTER
        setPadding(0, unit * 2, 0, unit / 2)
    }

    private fun note(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.rgb(0xb4, 0xb4, 0xb4))
        gravity = Gravity.CENTER
        setPadding(0, unit / 2, 0, unit / 2)
    }

    /** A row of buttons, the current one lit in orange. */
    private fun <T> choices(values: List<T>, label: (T) -> String, current: () -> T, set: (T) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val buttons = values.map { v ->
            Button(this).apply {
                text = label(v)
                setOnClickListener { set(v) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { row.addView(it) }
        }
        val refresh = {
            val cur = current()
            buttons.forEachIndexed { i, b -> if (values[i] == cur) AppLook.main(b) else AppLook.unchosen(b) }
        }
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

    /** [v] and everything in it takes touches, or none does. */
    private fun setEnabledAll(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledAll(v.getChildAt(i), on)
    }

    /** Asks GitHub now: the update popup if there is something newer, a word on the button if not. */
    private fun checkForUpdate(button: Button) {
        button.isEnabled = false
        button.text = "Checking…"
        Thread {
            val r = Updater.checkNow(this)
            runOnUiThread {
                button.isEnabled = true
                when {
                    r == null -> button.text = "Couldn't reach GitHub. Check now"
                    Updater.isNewer(Updater.installedVersion(this), r.version) -> {
                        button.text = "Check now"
                        startActivity(Intent(this, UpdateActivity::class.java))
                    }
                    else -> button.text = "Up to date. Check now"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun wideButton(label: String, onClick: (Button) -> Unit) = Button(this).apply {
        text = label
        AppLook.action(this)
        setOnClickListener { onClick(this) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = unit / 2 }
    }

    companion object {
        private val ORANGE = AppLook.ORANGE
    }
}
