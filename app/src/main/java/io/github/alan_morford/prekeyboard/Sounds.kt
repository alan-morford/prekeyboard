package io.github.alan_morford.prekeyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * The click a key makes, when "Sound on keys" is on (res/raw/key_click.wav). A SoundPool keeps
 * it decoded and ready, so it plays the moment the key goes down. It plays as a sound of the
 * phone's interface, and not at all while the phone is set to silent or vibrate.
 */
object Sounds {

    private var pool: SoundPool? = null
    private var click = 0
    private var loaded = false

    /** Gets the click ready, so the first key needn't wait for it. */
    fun prepare(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .build()
        p.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
        click = p.load(context.applicationContext, R.raw.key_click, 1)
        pool = p
    }

    /** A key went down. */
    fun key(context: Context) {
        prepare(context)
        if (!loaded) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool?.play(click, VOLUME, VOLUME, 1, 0, 1f)
    }

    /** Typing quickly, clicks overlap: this many play at once. */
    private const val MAX_STREAMS = 4
    private const val VOLUME = 1f
}
