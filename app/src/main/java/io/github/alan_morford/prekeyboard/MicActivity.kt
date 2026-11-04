package io.github.alan_morford.prekeyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle

/**
 * Asks for the microphone, which a keyboard can't do itself: Android only asks from an
 * activity. Invisible but for Android's own question; once it is answered, the keyboard comes
 * back and, if the answer was yes, starts listening.
 */
class MicActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish(); return
        }
        if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            Settings.prefs(this).edit().putBoolean(Settings.VOICE_PENDING, true).apply()
        finish()
    }
}
