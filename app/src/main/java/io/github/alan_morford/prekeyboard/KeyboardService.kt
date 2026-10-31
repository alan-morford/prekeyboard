package io.github.alan_morford.prekeyboard

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * The Android side of the keyboard. [KeyboardView] decides what was pressed; [Composer] decides
 * what that does to the text; this class connects them to whatever field has focus, and keeps
 * the keyboard in step with the owner's settings while it is on screen.
 */
class KeyboardService : InputMethodService(), KeyboardView.Host,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var view: KeyboardView? = null
    private var editor: EditorInfo? = null
    private val main = Handler(Looper.getMainLooper())

    private val composer = Composer(object : Composer.Field {
        override fun before(n: Int): CharSequence = currentInputConnection?.getTextBeforeCursor(n, 0) ?: ""
        override fun delete(n: Int) { currentInputConnection?.deleteSurroundingText(n, 0) }
        override fun commit(s: String) { currentInputConnection?.commitText(s, 1) }
        override fun batch(block: () -> Unit) {
            val ic = currentInputConnection
            ic?.beginBatchEdit()
            try { block() } finally { ic?.endBatchEdit() }
        }
    }, SystemClock::uptimeMillis)

    override fun onCreate() {
        super.onCreate()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        composer.onLearned = { Settings.addLearned(this, it) }
        // The dictionary is 88,000 words: read it off the main thread, and correct nothing until
        // it is in.
        val app = applicationContext
        Thread {
            val c = app.assets.open("dictionary.txt").bufferedReader(Charsets.UTF_8).useLines { Corrector(it) }
            c.learnAll(Settings.learned(app))
            main.post { composer.corrector = c }
        }.apply { name = "dictionary"; isDaemon = true }.start()
    }

    override fun onDestroy() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    /** A setting changed - in the setup screen, or by long-pressing the orange key: show it now. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        view?.apply(Settings.read(this))
    }

    override fun onCreateInputView(): View = KeyboardView(this, this).also { view = it }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editor = info
        composer.reset()
        view?.apply(Settings.read(this))
        view?.reset()
    }

    /** The Pre's keyboard never took over the screen. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * The view carries transparent room above the keys for the preview and the press-and-hold
     * balloon. Only the keys push the app up, and only the keys take touches.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = view ?: return
        if (v.width == 0 || v.height == 0) return
        val origin = IntArray(2).also { v.getLocationInWindow(it) }
        val keysTop = origin[1] + v.keyboardTop()
        outInsets.contentTopInsets = keysTop
        outInsets.visibleTopInsets = keysTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, keysTop, v.width, origin[1] + v.height)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        view?.updateCaps()
    }

    // --- What the field allows --------------------------------------------------------------

    /** The owner's toggles, less what this field rules out: no correcting passwords, addresses or URLs. */
    private fun rules(): Composer.Rules {
        val s = view?.settings ?: Settings.read(this)
        val type = editor?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        val prose = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            variation !in NOT_PROSE && type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        return Composer.Rules(autocorrect = s.autocorrect && prose, doubleSpacePeriod = s.doubleSpacePeriod && prose)
    }

    // --- What the keyboard reports ----------------------------------------------------------

    override fun onText(text: String) = composer.type(text, rules())

    override fun onBackspace(word: Boolean) {
        if (!word && composer.backspace()) return
        val ic = currentInputConnection ?: return
        if (!word) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        // Held long enough, backspace takes the word before the cursor, and the spaces after it.
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val start = trimmed.lastIndexOfAny(charArrayOf(' ', '\n', '\t')) + 1
        if (before.isNotEmpty()) ic.deleteSurroundingText(before.length - start, 0)
    }

    override fun onEnter() {
        composer.beforeEnter(rules())
        val info = editor
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val plain = action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED ||
            options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (plain) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        else currentInputConnection?.performEditorAction(action)
    }

    /** The globe asks which keyboard to use, rather than jumping to the next one. */
    override fun onPickKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursor(dx: Int, dy: Int, select: Boolean) {
        composer.forget()
        val code = when {
            dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dy > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> KeyEvent.KEYCODE_DPAD_UP
        }
        val ic = currentInputConnection ?: return
        val meta = if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    override fun onToggleStyle() =
        Settings.update(this) { it.copy(style = if (it.style == Style.ORANGE) Style.WHITE else Style.ORANGE) }

    override fun wantsCaps(): Boolean {
        val info = editor ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    companion object {
        private val NOT_PROSE = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )
    }
}
