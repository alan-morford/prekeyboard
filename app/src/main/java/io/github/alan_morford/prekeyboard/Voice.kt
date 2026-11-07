package io.github.alan_morford.prekeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech input, on until it is toggled off or the speaker stops: Android's speech recogniser
 * (Google's, on most phones) listens, the words so far show in the field as they are heard,
 * and each sentence is committed as it is finished. Listening starts again after each one,
 * so a long message can be spoken in one go; a stretch of silence ends it.
 */
class Voice(private val context: Context, private val out: Out,
            /** The language to listen for, as a BCP 47 tag ("fr-FR"): the one being typed in. */
            private val language: () -> String) : RecognitionListener {

    interface Out {
        /** The words heard so far of what is being said, to show in place. */
        fun partial(text: String)
        /** What was said, finished. */
        fun final(text: String)
        /** Listening started or stopped; [problem] says why it stopped, when it wasn't asked to. */
        fun state(listening: Boolean, problem: String? = null)
        /** Toggled off: no longer listening, though the last words may still come in [final]. */
        fun finishing() {}
    }

    private var recognizer: SpeechRecognizer? = null
    var on = false
        private set
    /** Something was heard this session: silence afterwards just means the speaker is done. */
    private var heard = false

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (on) return
        // Still finishing the last time: what it heard so far is kept, and it starts afresh.
        if (recognizer != null) { recognizer?.cancel(); end(null) }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this); recognizer = it
        }
        on = true
        heard = false
        listen(r)
        out.state(true)
    }

    private fun listen(r: SpeechRecognizer) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
        r.startListening(i)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { end(null) }

    /**
     * Toggled off. Unless [now], the recogniser gets a moment to finish what it was hearing,
     * so the last words aren't lost; what was heard so far is kept either way.
     */
    fun stop(now: Boolean = false) {
        val r = recognizer ?: return
        if (on && !now) {
            on = false
            r.stopListening()
            handler.postDelayed(giveUp, FINISH_MS)
            out.finishing()
            return
        }
        r.cancel()
        end(null)
    }

    private fun end(problem: String?) {
        handler.removeCallbacks(giveUp)
        on = false
        val r = recognizer ?: return
        recognizer = null
        r.destroy()
        out.state(false, problem)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        if (t.isNotBlank()) { heard = true; out.partial(t) }
    }

    override fun onResults(results: Bundle?) {
        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!t.isNullOrBlank()) { heard = true; out.final(t) } else out.partial("")
        val r = recognizer ?: return
        if (on) listen(r) else end(null)
    }

    override fun onError(error: Int) {
        if (recognizer == null) return
        if (!on) { end(null); return }
        out.partial("")
        val problem = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (heard) null else "Didn't catch that"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Pre Keyboard needs the microphone"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> "Speech input needs a connection"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech input is busy"
            else -> null
        }
        end(problem)
    }

    companion object {
        /** How long a stop waits for the last words. */
        private const val FINISH_MS = 2500L
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
