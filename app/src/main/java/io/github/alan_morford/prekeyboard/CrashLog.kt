package io.github.alan_morford.prekeyboard

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * When the keyboard (or one of its screens) crashes, what happened is written down before
 * Android ends it: the error and where it came from, the phone and the version, and the
 * app's own recent log. The next time the keyboard comes up its bar offers to send it by
 * email ([CrashActivity]); nothing is sent unless the owner says so.
 *
 * Nothing typed goes in: the log's lines about swipes (which name the words) are left out.
 */
object CrashLog {

    private const val DIR = "crashes"
    /** Reports kept at most, the newest. */
    private const val KEEP = 3
    /** Recent log lines of the app's own, read as it goes down. */
    private const val LOG_LINES = 400

    fun install(app: Application) {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            // Then as Android would have: the "has stopped" and the end of the process.
            before?.uncaughtException(thread, e)
        }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun write(context: Context, thread: Thread, e: Throwable) {
        val d = dir(context).apply { mkdirs() }
        val now = Date()
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            append("Pre Keyboard ").append(BuildConfig.VERSION_NAME).append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Crashed: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread: ").append(thread.name).append("\n\n")
            append(trace)
            append("\n--- Recent log ---\n")
            append(recentLog())
        }
        File(d, "crash-${now.time}.txt").writeText(report)
        d.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** The app's last lines of log, minus any that name words typed. Given two seconds at most. */
    private fun recentLog(): String {
        var out = ""
        val t = Thread {
            out = runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", LOG_LINES.toString())
                    .redirectErrorStream(true).start()
                // An app reads only its own lines of the log (Android 4.1 on): no need to pick them out.
                p.inputStream.bufferedReader().readLines()
                    .filterNot { it.contains(" swipe: ") }
                    .joinToString("\n")
            }.getOrElse { "(log unavailable: ${it.message})" }
        }
        t.start()
        t.join(2000)
        return out
    }

    /** Reports not yet sent or put away, newest first. */
    fun pending(context: Context): List<File> =
        dir(context).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: emptyList()

    fun hasPending(context: Context) = pending(context).isNotEmpty()

    /** Sent, or the owner said not to: gone. */
    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }
}

/** The app's process: crashes are written down from its start, whichever part of it runs first. */
class PreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
