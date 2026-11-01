package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reading a GitHub release and comparing versions (Robolectric for Android's org.json). */
@RunWith(RobolectricTestRunner::class)
class UpdaterTest {

    @Test fun readsARelease() {
        val r = Updater.parse("""
            {"tag_name":"v1.0.2","body":"## What's new\r\n\r\n- **Faster** swipes\r\n- Install `app.apk`",
             "assets":[{"browser_download_url":"https://x/notes.txt"},
                       {"browser_download_url":"https://x/prekeyboard-1.0.2-debug.apk"}]}
        """)!!
        assertEquals("1.0.2", r.version)
        assertEquals("https://x/prekeyboard-1.0.2-debug.apk", r.apkUrl)
        assertEquals("What's new\n\n• Faster swipes\n• Install app.apk", r.notes)
    }

    @Test fun noApkNoRelease() {
        assertNull(Updater.parse("""{"tag_name":"v1.0.2","body":"","assets":[]}"""))
    }

    @Test fun comparesVersions() {
        assertTrue(Updater.isNewer("1.0.1", "1.0.2"))
        assertTrue(Updater.isNewer("1.0.9", "1.1"))
        assertTrue(Updater.isNewer("1.0.1", "1.0.10"))
        assertFalse(Updater.isNewer("1.0.1", "1.0.1"))
        assertFalse(Updater.isNewer("1.1.0", "1.0.9"))
        assertFalse(Updater.isNewer("1.0.1", "1.0.1-debug"))
    }
}
