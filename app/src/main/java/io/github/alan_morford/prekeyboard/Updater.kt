package io.github.alan_morford.prekeyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * New releases, from the GitHub repository's latest release. The keyboard asks at most once a
 * day, when it comes up, so nothing runs in the background between uses and the app never has
 * to be opened. What it finds is kept until it is installed; Later puts the notice off a day.
 */
object Updater {

    class Release(val version: String, val notes: String, val apkUrl: String)

    private const val TAG = "PreKeyboard"
    private const val LATEST = "https://api.github.com/repos/alan-morford/prekeyboard/releases/latest"
    private const val PREFS = "updates"
    private const val DAY = 24L * 60 * 60 * 1000
    private const val HOUR = 60L * 60 * 1000

    // Kept apart from the settings, so finding a release doesn't look like a change of settings.
    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

    /** The release last found, if it is newer than this one: what the popup offers. */
    fun newer(context: Context): Release? {
        val p = prefs(context)
        val version = p.getString("version", null) ?: return null
        if (!isNewer(installedVersion(context), version)) return null
        return Release(version, p.getString("notes", "") ?: "", p.getString("apkUrl", "") ?: "")
    }

    /** A newer release the owner hasn't put off: what the notice in the keyboard is for. */
    fun pending(context: Context): Release? {
        val r = newer(context) ?: return null
        val p = prefs(context)
        if (System.currentTimeMillis() < p.getLong("laterUntil", 0)) return null
        return r
    }

    /** Not now: the notice comes back in a day. */
    fun later(context: Context) =
        prefs(context).edit().putLong("laterUntil", System.currentTimeMillis() + DAY).apply()


    /**
     * Asks GitHub, if the last answer is a day old, and calls [onFound] on the main thread
     * when that turns up a release to offer.
     */
    fun checkIfDue(context: Context, onFound: () -> Unit) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastCheck", 0) < DAY) return
        // Marked before asking, so the keyboard coming up again meanwhile doesn't ask twice.
        p.edit().putLong("lastCheck", now).apply()
        val app = context.applicationContext
        Thread {
            val r = fetch()
            // No answer (offline, say): try again in an hour rather than a day.
            if (r == null) { p.edit().putLong("lastCheck", now - DAY + HOUR).apply(); return@Thread }
            remember(app, r)
            if (pending(app) != null) Handler(Looper.getMainLooper()).post(onFound)
        }.apply { name = "update-check"; isDaemon = true }.start()
    }

    /** Asks now, whatever the time since the last check: for "Check now" in the setup screen. Off the main thread. */
    fun checkNow(context: Context): Release? {
        val r = fetch() ?: return null
        remember(context, r)
        prefs(context).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        return r
    }

    private fun remember(context: Context, r: Release) {
        prefs(context).edit().putString("version", r.version).putString("notes", r.notes)
            .putString("apkUrl", r.apkUrl).apply()
    }

    /** The latest release, or null when there's no answer or it has no APK to install. */
    private fun fetch(): Release? = try {
        val conn = Net.open(LATEST) {
            it.connectTimeout = 10_000
            it.readTimeout = 10_000
            it.setRequestProperty("Accept", "application/vnd.github+json")
            it.setRequestProperty("User-Agent", "PreKeyboard-Android")
        }
        try {
            if (conn.responseCode != 200) { Log.w(TAG, "update check: HTTP ${conn.responseCode}"); null }
            else parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "update check failed: ${e.message}")
        null
    }

    /** A GitHub release, as JSON: its version (tag without the "v"), notes and APK. */
    fun parse(json: String): Release? {
        val o = JSONObject(json)
        val version = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets")
        var apk = ""
        if (assets != null) for (i in 0 until assets.length()) {
            val url = assets.getJSONObject(i).optString("browser_download_url")
            if (url.endsWith(".apk", ignoreCase = true)) { apk = url; break }
        }
        if (version.isEmpty() || apk.isEmpty()) return null
        return Release(version, readable(o.optString("body")), apk)
    }

    /** Release notes are Markdown; the popup shows them as plain text. */
    fun readable(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { line ->
        line.replace("`", "").replace("**", "")
            .replaceFirst(Regex("^(\\s*)[-*] "), "$1• ")
            .replaceFirst(Regex("^#+\\s*"), "")
    }.trim()

    /** [remote] is a later major.minor.patch than [local]. */
    fun isNewer(local: String, remote: String): Boolean {
        val l = local.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val r = remote.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until Math.max(l.size, r.size)) {
            val a = l.getOrElse(i) { 0 }; val b = r.getOrElse(i) { 0 }
            if (a != b) return b > a
        }
        return false
    }
}
