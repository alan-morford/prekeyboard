package io.github.alan_morford.prekeyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.text.InputType
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import java.io.File

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
    /** The bar's right-hand place holds an emoji for the word, not a word. */
    private var barEmoji = false
    private var emojiWords: EmojiWords? = null
    private var nextWords: NextWords? = null
    /** Backspace is down: the bar waits till it lets go, so deleting stays quick. */
    private var deleting = false
    /** The last pair of words learned, so one finished word isn't counted twice. */
    private var lastPair: Pair<String, String>? = null
    private val voice: Voice by lazy { Voice(this, voiceOut) }

    override fun onCreate() {
        super.onCreate()
        Settings.prefs(this).registerOnSharedPreferenceChangeListener(this)
        clipboard.addPrimaryClipChangedListener(clipChanged)
        composer.onLearned = { Settings.addLearned(this, it); decoder?.add(it, LEARNED_SCORE) }
        composer.onCorrected = { fixed, tail ->
            Haptics.corrected(this)
            // Once the whole key's work is done: then the word is marked.
            main.post { markCorrection(fixed, tail + newlineAfter); newlineAfter = 0 }
        }
        // The dictionary is 88,000 words: read it off the main thread, and correct nothing until
        // it is in.
        val app = applicationContext
        Thread {
            val c = app.assets.open("dictionary.txt").bufferedReader(Charsets.UTF_8).useLines { Corrector(it) }
            app.assets.open("contractions.txt").bufferedReader(Charsets.UTF_8).useLines { c.loadContractions(it) }
            val learned = Settings.learned(app)
            c.learnAll(learned)
            val d = SwipeDecoder(c.entries())
            for (w in learned) d.add(w, LEARNED_SCORE)
            // Before Android 6 there is no asking whether an emoji draws: the panel's own list says.
            val drawable = if (Build.VERSION.SDK_INT >= 23) null
                else EmojiPanel.load(app).flatMap { it.emoji }.toHashSet()
            val paint = Paint()
            val ew = app.assets.open("emoji_words.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                EmojiWords(lines) { e -> drawable?.contains(e) ?: EmojiPanel.canDraw(paint, e) }
            }
            val nw = app.assets.open("next_words.txt").bufferedReader(Charsets.UTF_8).useLines { NextWords(it) }
            runCatching { File(app.filesDir, NEXT_WORDS_FILE).takeIf { it.exists() }?.useLines { nw.load(it) } }
            main.post {
                c.follows = nw::follows
                composer.corrector = c; decoder = d; emojiWords = ew; nextWords = nw
                learnedNow = learned
                syncLearned()
                refreshBar()
            }
        }.apply { name = "dictionary"; isDaemon = true }.start()
    }

    /** The owner's words the corrector and the decoder were last given. */
    private var learnedNow: Set<String> = emptySet()

    /** Words added or taken out in the dictionary screen (or added here): the keyboard follows. */
    private fun syncLearned() {
        val c = composer.corrector ?: return
        val d = decoder ?: return
        val now = Settings.learned(this)
        for (w in learnedNow - now) { c.unlearn(w); d.remove(w) }
        for (w in now - learnedNow) { c.learn(w); d.add(w, LEARNED_SCORE) }
        learnedNow = now
        refreshBar()
    }

    override fun onDestroy() {
        Settings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        clipboard.removePrimaryClipChangedListener(clipChanged)
        voice.stop(now = true)
        saveNextWords()
        live.quitSafely()
        suggestThread.quitSafely()
        super.onDestroy()
    }

    /** A setting changed - in the setup screen, or by long-pressing the orange key or globe: show it now. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == Settings.VOICE_PENDING) return
        if (key == Settings.LEARNED) { syncLearned(); return }
        view?.apply(Settings.read(this))
        refreshBar()
    }

    /**
     * The keys at the bottom of a view as tall as the window allows, under the transparent
     * layer that draws the corrected words' dashes over the app. Only the keys take touches,
     * and only they push the app up ([onComputeInsets]).
     */
    override fun onCreateInputView(): View {
        val kb = KeyboardView(this, this)
        val layer = MarkLayer(this)
        val frame = object : FrameLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val h = MeasureSpec.getSize(heightSpec).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
        }
        frame.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(kb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        view = kb
        marks = layer
        return frame
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editor = info
        composer.reset()
        clearMarks()
        // Where the cursor is as the field opens, the owner put it (or left it): a word it
        // touches gets alternatives, as a word tapped would.
        tapped = true
        view?.apply(Settings.read(this))
        view?.wordsAllowed = prose()
        view?.reset()
        refreshBar()
        offerPaste()
        showUpdateNotice()
        Updater.checkIfDue(this) { showUpdateNotice() }
        // Back from allowing the microphone: start listening, as was asked for.
        val prefs = Settings.prefs(this)
        if (prefs.getBoolean(Settings.VOICE_PENDING, false)) {
            prefs.edit().remove(Settings.VOICE_PENDING).apply()
            main.post { if (!voice.on) onVoice() }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clearMarks()
        voice.stop(now = true)
        deleting = false
        saveNextWords()
        super.onFinishInputView(finishingInput)
    }

    /** What the owner types after what, kept on a thread of its own. */
    private fun saveNextWords() {
        val nw = nextWords ?: return
        if (!nw.dirty) return
        val text = nw.save()
        val file = File(filesDir, NEXT_WORDS_FILE)
        Thread { runCatching { File(file.path + ".new").apply { writeText(text) }.renameTo(file) } }.start()
    }

    // --- Corrected words: the orange dashes ------------------------------------------------

    private var marks: MarkLayer? = null
    /** Return went in as a new line after the word it finished: one more character past it. */
    private var newlineAfter = 0

    /** A correction marked as composing text, waiting for the field to say where it is. */
    private class Pending(val start: Int, val text: String, val timeout: Runnable)
    private var pending: Pending? = null

    /**
     * Autocorrect just made [fixed], [tail] characters before the cursor. To learn where it is
     * on screen, it is made the composing text for a moment: a field says where its composing
     * text is (CursorAnchorInfo), and nothing else. Once it has said, or after a short wait,
     * the word is plain text again ([settle]).
     */
    private fun markCorrection(fixed: String, tail: Int) {
        settle()
        val ic = currentInputConnection ?: return
        if (marks == null) return
        val before = ic.getTextBeforeCursor(fixed.length + tail, 0)?.toString() ?: return
        if (before.length != fixed.length + tail || !before.startsWith(fixed)) return
        val cursor = cursorPosition(ic) ?: return
        val start = cursor - tail - fixed.length
        if (start < 0) return
        ownEdit()
        if (!ic.setComposingRegion(start, start + fixed.length)) return
        val timeout = Runnable { settle() }
        pending = Pending(start, fixed, timeout)
        ic.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR)
        main.postDelayed(timeout, ANCHOR_WAIT_MS)
    }

    /** Where the cursor is in the whole text: the one thing the keyboard isn't told for sure. */
    private fun cursorPosition(ic: InputConnection): Int? {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { ic.getSurroundingText(0, 0, 0) }.getOrNull()?.let { if (it.offset >= 0) return it.offset + it.selectionEnd }
        }
        val et = runCatching { ic.getExtractedText(ExtractedTextRequest(), 0) }.getOrNull() ?: return null
        if (et.selectionEnd < 0) return null
        return et.startOffset + et.selectionEnd
    }

    /**
     * The correction being measured is plain text again, and marked if [box] says where it is.
     * Called before anything else the keyboard does to the text, since text committed while
     * a word is composing would replace it.
     */
    private fun settle(box: RectF? = null) {
        val p = pending ?: return
        pending = null
        main.removeCallbacks(p.timeout)
        ownEdit()
        currentInputConnection?.finishComposingText()
        if (box != null) marks?.add(MarkLayer.Mark(p.start, p.text, box))
        else if (marks?.marks.isNullOrEmpty()) stopFollowing()
    }

    /** No marks left to keep in place: the field needn't report every move. */
    private fun stopFollowing() { currentInputConnection?.requestCursorUpdates(0) }

    private fun clearMarks() {
        pending?.let { main.removeCallbacks(it.timeout) }
        pending = null
        if (marks?.marks?.isNotEmpty() == true) { marks?.clear(); stopFollowing() }
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val layer = marks ?: return
        val p = pending
        if (p != null && info.composingTextStart == p.start && info.composingText?.toString() == p.text) {
            var box: RectF? = null
            for (i in p.text.indices) {
                val r = info.getCharacterBounds(p.start + i) ?: continue
                if (box == null) box = RectF(r) else box.union(r)
            }
            if (box != null) {
                // The line under the letters: the baseline when the field says where it is on
                // this line (Chrome reports the line's bottom instead), else a fifth of the
                // line up from its bottom, where fonts put it; then a hair below.
                val base = info.insertionMarkerBaseline
                val sameLine = !base.isNaN() && Math.abs(info.insertionMarkerBottom - box.bottom) < 1f &&
                    base < info.insertionMarkerBottom - 0.5f
                val line = box.height()
                box.bottom = (if (sameLine) base else box.bottom - line * 0.2f) + line * 0.08f
            }
            layer.fieldMatrix = Matrix(info.matrix)
            settle(box)
            return
        }
        // The field moved or scrolled: the marks follow it.
        if (layer.marks.isNotEmpty()) layer.fieldMatrix = Matrix(info.matrix)
    }

    /**
     * A mark the cursor is at or before may have been typed over, deleted or moved along by
     * new text: each such one is checked against the text, and dropped unless it still holds
     * its word. Marks well behind the cursor can't have changed, since editing happens at it.
     */
    private fun checkMarks(cursor: Int) {
        val layer = marks ?: return
        if (layer.marks.isEmpty() || pending != null) return
        val ic = currentInputConnection ?: return
        val gone = layer.marks.filter { m ->
            if (m.end < cursor - 1) return@filter false
            val ok = when {
                m.end - cursor > MAX_MARK_REACH || cursor - m.start > MAX_MARK_REACH -> false
                cursor >= m.end -> ic.getTextBeforeCursor(cursor - m.start, 0)?.toString()
                    ?.let { it.length == cursor - m.start && it.startsWith(m.text) } == true
                cursor <= m.start -> ic.getTextAfterCursor(m.end - cursor, 0)?.toString()
                    ?.let { it.length == m.end - cursor && it.endsWith(m.text) } == true
                else -> (ic.getTextBeforeCursor(cursor - m.start, 0)?.toString() +
                    ic.getTextAfterCursor(m.end - cursor, 0)?.toString()) == m.text
            }
            !ok
        }
        if (gone.isNotEmpty()) {
            layer.remove(gone)
            if (layer.marks.isEmpty()) stopFollowing()
        }
    }

    // --- Paste ------------------------------------------------------------------------------

    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
    // Copied or cut, even the same text again: a new offer.
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener {
        clipPrefs.edit().remove(CLIP_DONE).apply()
        offerPaste()
    }

    /**
     * Which copy is on the clipboard: when it was made, from Android 8 (so copying or cutting
     * the same words again counts as new while the keyboard wasn't running), else a hash of
     * the text. Never the text itself.
     */
    private fun clipKey(text: String): String {
        val stamp = if (Build.VERSION.SDK_INT >= 26) runCatching { clipboard.primaryClipDescription?.timestamp }.getOrNull() else null
        return if (stamp != null && stamp > 0) "t$stamp" else "h${text.hashCode()}"
    }

    /** What is on the clipboard, as text; null when nothing (or nothing that reads as text). */
    private fun clipText(): String? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        (item.text ?: item.coerceToText(this))?.toString()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * The clipboard is offered while it holds something not yet pasted: the paste icon stays
     * in the bar, through typing and from field to field, till it is tapped. What was pasted
     * is remembered (see [clipKey]), so it isn't offered again, even after the keyboard
     * restarts; a new copy or cut always is.
     */
    private fun offerPaste() {
        val v = view ?: return
        val t = clipText()
        v.pasteOffered = t != null && clipKey(t) != clipPrefs.getString(CLIP_DONE, null)
    }

    private val clipPrefs by lazy { getSharedPreferences("clip", MODE_PRIVATE) }

    /** Pasted: the clipboard's offer is over, till something new is copied or cut. */
    private fun pasted() {
        val v = view ?: return
        if (!v.pasteOffered) return
        v.pasteOffered = false
        clipText()?.let { clipPrefs.edit().putString(CLIP_DONE, clipKey(it)).apply() }
    }

    override fun onPaste() {
        settle()
        val t = clipText()
        pasted()
        if (t == null) return
        ownEdit()
        composer.forget()
        currentInputConnection?.commitText(t, 1)
        refreshBar()
    }

    // --- Updates ----------------------------------------------------------------------------

    private fun showUpdateNotice() {
        view?.notice = Updater.pending(this)?.let { "Pre Keyboard ${it.version} is available. Tap to see what's new." }
    }

    override fun onNotice() {
        requestHideSelf(0)
        startActivity(Intent(this, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        swipeLikely = null
        checkMarks(newSelStart)
        // A selection, or the cursor taken elsewhere: the keyboard's last space is now ordinary.
        if (newSelStart != newSelEnd) composer.forget()
        // The cursor moved, and not by anything the keyboard did: the owner tapped the text.
        // Tapped on a word, the bar offers what else it might have been.
        if (newSelStart != newSelEnd) tapped = false
        else if (newSelStart != oldSelStart && SystemClock.uptimeMillis() - ownEditAt > OWN_EDIT_MS) tapped = true
        view?.updateCaps()
        refreshBar()
    }

    // --- What the field allows --------------------------------------------------------------

    /**
     * A field for words: not a password, an address or a URL. Swiping and the suggestion bar
     * work in it even when it asks for no suggestions: the Google search box asks that because
     * it shows its own, and other keyboards still swipe there.
     */
    private fun prose(): Boolean {
        val type = editor?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        return type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && variation !in NOT_PROSE
    }

    /**
     * The owner's toggles, less what this field rules out. A field that asked for no
     * suggestions is never corrected behind the owner's back (a user name, a code); a word
     * picked from the bar still is.
     */
    private fun rules(): Composer.Rules {
        val s = view?.settings ?: Settings.read(this)
        val prose = prose()
        val quiet = (editor?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        return Composer.Rules(autocorrect = s.autocorrect && prose && !quiet,
            doubleSpacePeriod = s.doubleSpacePeriod && prose, prose = prose)
    }

    // --- The suggestion bar -----------------------------------------------------------------

    /**
     * What the bar shows. After a swipe: the word swiped, in the middle, between the next two
     * guesses. While a word is being typed: what space would make of it in the middle, the
     * word as typed on the left when that differs, and other likely words. Otherwise nothing.
     */
    private fun refreshBar() {
        val v = view ?: return
        // Whatever is still being worked out for the bar is out of date now.
        val gen = ++barGeneration
        barWord = null
        // While backspace is down, every deletion would look the word up again: wait till it
        // lets go. The bar stays as it was for a single tap, and empties once it repeats.
        if (deleting) {
            if (deletes > 1) show(emptyList(), verbatim = false, emoji = false)
            return
        }
        if (!v.settings.showsBar || !prose()) { show(emptyList(), verbatim = false, emoji = false); return }
        // The cursor moved away from the swiped word: it is no longer the one to replace.
        if (composer.swiped != null && !composer.swipedIsLast()) composer.forget()
        val at = wordAtCursor()
        val c0 = composer.corrector
        // A word tapped on, or the cursor inside one: other words it might have been.
        if (at != null && c0 != null && (tapped || at.after.isNotEmpty())) {
            suggestHandler.post {
                if (gen != barGeneration) return@post
                val alt = c0.alternatives(at.word)
                main.post {
                    if (gen != barGeneration) return@post
                    barWord = at
                    show(listOf(alt.getOrElse(1) { "" }, alt.getOrElse(0) { "" }, alt.getOrElse(2) { "" }),
                        verbatim = false, emoji = false)
                }
            }
            return
        }
        val sw = composer.swiped
        val emojiOn = v.settings.emojiSuggestions
        if (sw != null) {
            val others = swipeGuesses.filter { it != sw }
            withEmoji(listOf(others.getOrElse(0) { "" }, sw, others.getOrElse(1) { "" }), false, emojiOn)
            return
        }
        val typed = composer.currentWord()
        val typedAfter = composer.wordContext()
        val c = composer.corrector
        if (c == null) { show(emptyList(), verbatim = false, emoji = false); return }
        if (typed.isEmpty()) { show(predict() ?: emptyList(), verbatim = false, emoji = false); return }
        // Looking a word up can take a good part of a second on an old tablet: do it on a
        // thread of its own, so typing never waits for it. The bar keeps what it shows till then.
        suggestHandler.post {
            if (gen != barGeneration) return@post
            val list = c.suggestions(typed, typedAfter)
            val best = list.firstOrNull() ?: typed
            val others = list.filter { it != best && it != typed }
            val verbatim = best != typed
            val words = if (verbatim) listOf(typed, best, others.getOrElse(0) { "" })
                else listOf(others.getOrElse(0) { "" }, best, others.getOrElse(1) { "" })
            val e = if (emojiOn && words[1].isNotEmpty()) emojiWords?.forWord(words[1]) else null
            main.post {
                if (gen != barGeneration) return@post
                if (e != null) show(listOf(words[0], words[1], e), verbatim, emoji = true)
                else show(words, verbatim, emoji = false)
            }
        }
    }

    /** The bar's words, with an emoji for the middle one in the right-hand place when there is one. */
    private fun withEmoji(words: List<String>, verbatim: Boolean, emojiOn: Boolean) {
        val e = if (emojiOn && words[1].isNotEmpty()) emojiWords?.forWord(words[1]) else null
        if (e != null) show(listOf(words[0], words[1], e), verbatim, emoji = true) else show(words, verbatim, emoji = false)
    }

    /** A word in the text around the cursor: the part before it and the part after. */
    private class WordAt(val before: String, val after: String) { val word get() = before + after }

    /** The word the cursor is in or touching, when it is a plain word standing on its own. */
    private fun wordAtCursor(): WordAt? {
        val ic = currentInputConnection ?: return null
        val b = ic.getTextBeforeCursor(MAX_TAPPED + 1, 0)?.toString() ?: return null
        val a = ic.getTextAfterCursor(MAX_TAPPED + 1, 0)?.toString() ?: return null
        fun part(ch: Char) = ch.isLetter() || ch == '\''
        var i = b.length
        while (i > 0 && part(b[i - 1])) i--
        var j = 0
        while (j < a.length && part(a[j])) j++
        if (i == b.length && j == 0) return null
        if (i > 0 && (b[i - 1].isLetterOrDigit() || b[i - 1] in "@#_/.-")) return null
        if (i == 0 && b.length > MAX_TAPPED) return null
        if (j < a.length && (a[j].isDigit() || a[j] in "@_/")) return null
        if (j == a.length && a.length > MAX_TAPPED) return null
        val w = WordAt(b.substring(i), a.substring(0, j))
        return w.takeIf { it.word.trim('\'').isNotEmpty() && it.word[0].isLetter() }
    }

    /** The cursor moved by the owner's hand, not the keyboard's: [refreshBar] looks at the word there. */
    private var tapped = false
    /** When the keyboard last changed the text, to tell its own cursor moves from the owner's. */
    private var ownEditAt = 0L
    /** The tapped word the bar is offering others for, to swap when one is picked. */
    private var barWord: WordAt? = null

    private fun ownEdit() {
        ownEditAt = SystemClock.uptimeMillis()
        tapped = false
    }

    private fun show(words: List<String>, verbatim: Boolean, emoji: Boolean) {
        bar = List(3) { words.getOrElse(it) { "" } }
        barVerbatim = verbatim
        barEmoji = emoji
        view?.setSuggestions(bar)
    }

    private val suggestThread = HandlerThread("suggest").apply { start() }
    private val suggestHandler = Handler(suggestThread.looper)
    @Volatile private var barGeneration = 0
    /** Deletions since backspace went down. */
    private var deletes = 0

    /**
     * Before a word is typed: the three likeliest next words, the likeliest in the middle,
     * with a capital where the field wants one. Null when there is nothing to go on.
     */
    private fun predict(): List<String>? {
        val nw = nextWords ?: return null
        val c = composer.corrector ?: return null
        val ctx = composer.nextContext() ?: return null
        val found = nw.predict(ctx) { c.isKnown(it) }
        if (found.isEmpty()) return null
        val caps = wantsCaps()
        val w = found.map { if (caps) it.replaceFirstChar { ch -> ch.uppercaseChar() } else it }
        return listOf(w.getOrElse(1) { "" }, w[0], w.getOrElse(2) { "" })
    }

    /** A word was just finished: remember what it followed, so it can be offered there next time. */
    private fun learnPair() {
        val nw = nextWords ?: return
        val c = composer.corrector ?: return
        if (!prose() || (editor?.imeOptions ?: 0) and NO_LEARNING != 0) return
        val (ctx, word) = composer.finishedPair() ?: return
        val spelt = c.spelling(word) ?: return
        if (ctx to spelt == lastPair) return
        lastPair = ctx to spelt
        nw.learn(ctx, spelt)
    }

    override fun onPick(i: Int) {
        settle()
        val word = bar.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: return
        val at = barWord
        if (at != null) { replaceTapped(at, word); return }
        ownEdit()
        val learn = i == 0 && barVerbatim && composer.corrector?.isKnown(word) == false
        composer.pick(word, learn)
        if (i == 2 && barEmoji) Settings.addRecentEmoji(this, word)
        learnPair()
        refreshBar()
    }

    /**
     * A word picked for a tapped one takes its place, the cursor at its end. The bar goes on
     * offering others for it, the word it replaced among them, in case it wasn't the one.
     */
    private fun replaceTapped(at: WordAt, word: String) {
        val ic = currentInputConnection ?: return
        val now = wordAtCursor()
        if (now == null || now.before != at.before || now.after != at.after) { refreshBar(); return }
        ownEditAt = SystemClock.uptimeMillis()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(at.before.length, at.after.length)
        ic.commitText(word, 1)
        ic.endBatchEdit()
        tapped = true
        composer.forget()
        refreshBar()
    }

    /** A word held in the bar can be added when it is a word (letters) the dictionary lacks. */
    override fun canAdd(word: String): Boolean {
        val c = composer.corrector ?: return false
        return word.isNotEmpty() && word.all { it.isLetter() || it == '\'' } && word[0].isLetter() && !c.isKnown(word)
    }

    override fun onAddWord(word: String) {
        composer.corrector?.learn(word)
        Settings.addLearned(this, word)
        decoder?.add(word.lowercase(), LEARNED_SCORE)
        refreshBar()
    }

    override fun onBackspaceHeld(held: Boolean) {
        deleting = held
        deletes = 0
        if (!held) refreshBar() else barGeneration++
    }

    override fun onOpenApp() {
        requestHideSelf(0)
        startActivity(Intent(this, SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    /**
     * Guesses while the finger is still moving: the word it would be if lifted now, in the
     * middle, between the next two. Worked out on a thread of its own so the trail never
     * stutters; a newer piece of the swipe, or the swipe's end, makes an older one moot.
     */
    private val live = HandlerThread("swipe").apply { start() }
    private val liveHandler = Handler(live.looper)
    @Volatile private var liveGeneration = 0

    override fun onSwipeProgress(xs: FloatArray, ys: FloatArray, caps: Int) {
        val d = decoder ?: return
        val layout = view?.swipeLayout() ?: return
        val gen = ++liveGeneration
        val likely = swipeContext()
        liveHandler.removeCallbacksAndMessages(null)
        liveHandler.post {
            if (gen != liveGeneration) return@post
            val found = d.decode(xs, ys, layout, likely = likely, stale = { gen != liveGeneration }).map { cased(it, caps) }
            main.post {
                if (gen != liveGeneration || found.isEmpty()) return@post
                view?.setSuggestions(listOf(found.getOrElse(1) { "" }, found[0], found.getOrElse(2) { "" }))
            }
        }
    }

    override fun onSwipe(xs: FloatArray, ys: FloatArray, caps: Int) {
        settle()
        liveGeneration++
        liveHandler.removeCallbacksAndMessages(null)
        val d = decoder ?: run { swipeLikely = null; return }
        val layout = view?.swipeLayout() ?: run { swipeLikely = null; return }
        val start = SystemClock.uptimeMillis()
        val found = d.decode(xs, ys, layout, likely = swipeContext())
        swipeLikely = null
        Log.d(TAG, "swipe: ${xs.size} points, ${SystemClock.uptimeMillis() - start} ms, $found")
        if (found.isEmpty()) { refreshBar(); return }
        ownEdit()
        swipeGuesses = found.map { cased(it, caps) }
        composer.swipe(swipeGuesses[0], rules())
        learnPair()
        refreshBar()
    }

    /**
     * The words that often come next after the text before the cursor, for the swipe decoder:
     * looked up once a swipe, as it starts, since the text can't change while it goes on.
     */
    private var swipeLikely: List<String>? = null

    private fun swipeContext(): List<String> = swipeLikely ?: run {
        val nw = nextWords
        val ctx = composer.nextContext()
        (if (nw == null || ctx == null) emptyList() else nw.predict(ctx, max = 8)).also { swipeLikely = it }
    }

    private fun cased(word: String, caps: Int) = when (caps) {
        KeyboardView.CAPS_ALL -> word.uppercase()
        KeyboardView.CAPS_FIRST -> word.replaceFirstChar { c -> c.uppercaseChar() }
        else -> word
    }

    // --- What the keyboard reports ----------------------------------------------------------

    override fun onText(text: String) {
        settle()
        if (voice.on) voice.stop(now = true)
        ownEdit()
        composer.type(text, rules())
        if (text == " " || (text.length == 1 && text[0] in ".,?!:;")) learnPair()
    }

    override fun onBackspace(word: Boolean) {
        settle()
        if (voice.on) voice.stop(now = true)
        ownEdit()
        deletes++
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
        settle()
        if (voice.on) voice.stop(now = true)
        ownEdit()
        composer.beforeEnter(rules())
        val info = editor
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val plain = action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED ||
            options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (plain) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            // For the correction's mark, queued before this; gone again if there was none.
            newlineAfter = 1
            main.post { newlineAfter = 0 }
        }
        else currentInputConnection?.performEditorAction(action)
    }

    /** The globe asks which keyboard to use, rather than jumping to the next one. */
    override fun onPickKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursor(dx: Int, dy: Int, select: Boolean) {
        settle()
        composer.forget()
        ownEdit()
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

    override fun onToggleHold() = Settings.update(this) { it.copy(holdForMore = !it.holdForMore) }

    // --- Speech input ------------------------------------------------------------------------

    /** Where the words being heard begin: a space goes in front when they follow a word. */
    private var voicePrefix = ""
    private var voiceCaps = false
    private var voiceComposing = false

    /** Works out how the next thing said joins on to the text before it. */
    private fun voiceStart() {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        voicePrefix = if (before.isEmpty() || before[0].isWhitespace() || before[0] in "([{\"'“‘") "" else " "
        voiceCaps = wantsCaps()
    }

    private fun voiceText(t: String): String {
        val s = t.trim()
        return voicePrefix + if (voiceCaps) s.replaceFirstChar { it.uppercaseChar() } else s
    }

    private val voiceOut: Voice.Out = object : Voice.Out {
        override fun partial(text: String) {
            settle()
            val ic = currentInputConnection ?: return
            if (text.isEmpty()) {
                if (voiceComposing) { ic.setComposingText("", 1); ic.finishComposingText(); voiceComposing = false }
                return
            }
            ownEdit()
            if (!voiceComposing) { voiceStart(); composer.forget(); voiceComposing = true }
            ic.setComposingText(voiceText(text), 1)
        }

        override fun final(text: String) {
            settle()
            val ic = currentInputConnection ?: return
            ownEdit()
            if (!voiceComposing) { voiceStart(); composer.forget() }
            ic.commitText(voiceText(text), 1)
            voiceComposing = false
        }

        override fun finishing() {
            view?.listening = false
            view?.voiceLine = null
        }

        override fun state(listening: Boolean, problem: String?) {
            if (!listening && voiceComposing) { currentInputConnection?.finishComposingText(); voiceComposing = false }
            view?.listening = listening
            view?.voiceLine = if (listening) "Listening\u2026 tap here to stop" else problem
            if (!listening && problem != null) main.postDelayed({ if (!voice.on) view?.voiceLine = null }, 2500)
            refreshBar()
        }
    }

    /**
     * The period held, or tapped after the orange key: speech input on or off. The
     * microphone is asked for the first time; with no recogniser on the device, a voice
     * keyboard (Google voice typing, say) is switched to instead, if there is one.
     */
    override fun onVoice() {
        settle()
        if (voice.on) { voice.stop(); return }
        if (!voice.available()) {
            if (!switchToVoiceKeyboard()) view?.voiceLine = "No speech input on this device"
            main.postDelayed({ if (!voice.on) view?.voiceLine = null }, 2500)
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        voice.start()
    }

    private fun switchToVoiceKeyboard(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == packageName) continue
            val sub = imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == "voice" } ?: continue
            if (Build.VERSION.SDK_INT >= 28) switchInputMethod(imi.id, sub)
            else @Suppress("DEPRECATION") imm.setInputMethodAndSubtype(window.window!!.attributes.token, imi.id, sub)
            return true
        }
        return false
    }

    override fun wantsCaps(): Boolean {
        val info = editor ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
    }

    companion object {
        private const val TAG = "PreKeyboard"
        /** A word the owner kept: as likely, to the swipe decoder, as a fairly common word. */
        private const val LEARNED_SCORE = 10
        /** The longest word looked at around a tapped cursor. */
        private const val MAX_TAPPED = 32
        /** A cursor move this soon after the keyboard changed the text is the keyboard's own. */
        private const val OWN_EDIT_MS = 400L
        private const val CLIP_DONE = "passed"
        private const val NEXT_WORDS_FILE = "next_words_mine.txt"
        /** How long a correction waits to be told where it is on screen, before it goes unmarked. */
        private const val ANCHOR_WAIT_MS = 400L
        /** Farther than this from the cursor, a mark isn't looked for in the text: it's dropped. */
        private const val MAX_MARK_REACH = 2000
        /** EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING (API 26): an incognito field. */
        private const val NO_LEARNING = 0x1000000
        private val NOT_PROSE = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )
    }
}
