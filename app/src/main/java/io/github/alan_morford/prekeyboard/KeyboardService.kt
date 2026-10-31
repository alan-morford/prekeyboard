package io.github.alan_morford.prekeyboard

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
        override fun after(n: Int): CharSequence = currentInputConnection?.getTextAfterCursor(n, 0) ?: ""
        override fun delete(n: Int) { currentInputConnection?.deleteSurroundingText(n, 0) }
        override fun commit(s: String) { currentInputConnection?.commitText(s, 1) }
        override fun batch(block: () -> Unit) {
            val ic = currentInputConnection
            ic?.beginBatchEdit()
            try { block() } finally { ic?.endBatchEdit() }
        }
    }, SystemClock::uptimeMillis)

    private var decoder: SwipeDecoder? = null
    /** The last swipe's guesses, best first, as typed (capitals and all). */
    private var swipeGuesses = emptyList<String>()
    /** What the suggestion bar shows, and whether its left word is the word as typed. */
    private var bar = listOf("", "", "")
    private var barVerbatim = false

    override fun onCreate() {
        super.onCreate()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        composer.onLearned = { Settings.addLearned(this, it); decoder?.add(it, LEARNED_SCORE) }
        // The dictionary is 88,000 words: read it off the main thread, and correct nothing until
        // it is in.
        val app = applicationContext
        Thread {
            val c = app.assets.open("dictionary.txt").bufferedReader(Charsets.UTF_8).useLines { Corrector(it) }
            val learned = Settings.learned(app)
            c.learnAll(learned)
            val d = SwipeDecoder(c.entries())
            for (w in learned) d.add(w, LEARNED_SCORE)
            main.post { composer.corrector = c; decoder = d; refreshBar() }
        }.apply { name = "dictionary"; isDaemon = true }.start()
    }

    override fun onDestroy() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    /** A setting changed - in the setup screen, or by long-pressing the orange key: show it now. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        view?.apply(Settings.read(this))
        refreshBar()
    }

    override fun onCreateInputView(): View = KeyboardView(this, this).also { view = it }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editor = info
        composer.reset()
        view?.apply(Settings.read(this))
        view?.wordsAllowed = prose()
        view?.reset()
        refreshBar()
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
        refreshBar()
    }

    // --- What the field allows --------------------------------------------------------------

    /** A field for words: not a password, an address or a URL, and not one that asked for no suggestions. */
    private fun prose(): Boolean {
        val type = editor?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        return type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            variation !in NOT_PROSE && type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
    }

    /** The owner's toggles, less what this field rules out. */
    private fun rules(): Composer.Rules {
        val s = view?.settings ?: Settings.read(this)
        val prose = prose()
        return Composer.Rules(autocorrect = s.autocorrect && prose, doubleSpacePeriod = s.doubleSpacePeriod && prose)
    }

    // --- The suggestion bar -----------------------------------------------------------------

    /**
     * What the bar shows. After a swipe: the word swiped, in the middle, between the next two
     * guesses. While a word is being typed: what space would make of it in the middle, the
     * word as typed on the left when that differs, and other likely words. Otherwise nothing.
     */
    private fun refreshBar() {
        val v = view ?: return
        barVerbatim = false
        bar = listOf("", "", "")
        if (v.settings.showsBar && prose()) {
            // The cursor moved away from the swiped word: it is no longer the one to replace.
            if (composer.swiped != null && composer.currentWord() != composer.swiped) composer.forget()
            val sw = composer.swiped
            if (sw != null) {
                val others = swipeGuesses.filter { it != sw }
                bar = listOf(others.getOrElse(0) { "" }, sw, others.getOrElse(1) { "" })
            } else {
                val typed = composer.currentWord()
                val c = composer.corrector
                if (typed.isNotEmpty() && c != null) {
                    val list = c.suggestions(typed)
                    val best = list.firstOrNull() ?: typed
                    val others = list.filter { it != best && it != typed }
                    bar = if (best != typed) {
                        barVerbatim = true
                        listOf(typed, best, others.getOrElse(0) { "" })
                    } else listOf(others.getOrElse(0) { "" }, best, others.getOrElse(1) { "" })
                }
            }
        }
        v.setSuggestions(bar)
    }

    override fun onPick(i: Int) {
        val word = bar.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return
        val learn = i == 0 && barVerbatim && composer.corrector?.isKnown(word) == false
        composer.pick(word, learn)
        refreshBar()
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray, caps: Int) {
        val d = decoder ?: return
        val layout = view?.swipeLayout() ?: return
        val start = SystemClock.uptimeMillis()
        val found = d.decode(xs, ys, layout)
        Log.d(TAG, "swipe: ${xs.size} points, ${SystemClock.uptimeMillis() - start} ms, $found")
        if (found.isEmpty()) return
        swipeGuesses = found.map {
            when (caps) {
                KeyboardView.CAPS_ALL -> it.uppercase()
                KeyboardView.CAPS_FIRST -> it.replaceFirstChar { c -> c.uppercaseChar() }
                else -> it
            }
        }
        composer.swipe(swipeGuesses[0])
        refreshBar()
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
        private const val TAG = "PreKeyboard"
        /** A word the owner kept: as likely, to the swipe decoder, as a fairly common word. */
        private const val LEARNED_SCORE = 10
        private val NOT_PROSE = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )
    }
}
