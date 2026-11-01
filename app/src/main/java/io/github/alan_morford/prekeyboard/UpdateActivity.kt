package io.github.alan_morford.prekeyboard

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * The update popup: the new version's notes, and Install or Later. Install downloads
 * the APK straight into Android's package installer, which asks the owner to confirm; no
 * storage permission is needed. Opened from the notice in the keyboard, or "Check now".
 */
class UpdateActivity : Activity() {

    private lateinit var release: Updater.Release
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    /** Sent to Android's "install unknown apps" page; carry on when the owner comes back. */
    private var awaitingPermission = false
    private var busy = false

    private val unit get() = (16 * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The installer's answer, after Android closed the popup: pass on its confirmation.
        if (intent?.action == ACTION_STATUS) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_PENDING_USER_ACTION)
                confirmation(intent)?.let { startActivity(it) }
            finish(); return
        }
        release = Updater.newer(this) ?: run { finish(); return }
        setTitle("Pre Keyboard ${release.version}")

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(unit * 3 / 2, unit, unit * 3 / 2, unit)
        }
        column.addView(TextView(this).apply {
            text = "Pre Keyboard ${release.version} is available. You have ${Updater.installedVersion(this@UpdateActivity)}."
            setTextColor(Color.rgb(0xb4, 0xb4, 0xb4))
            setPadding(0, 0, 0, unit / 2)
        })
        column.addView(ScrollView(this).apply {
            addView(TextView(this@UpdateActivity).apply {
                text = release.notes.ifEmpty { "No release notes." }
                setTextColor(Color.WHITE)
                textSize = 15f
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(resources.displayMetrics.heightPixels / 2, unit * 22))
        })
        status = TextView(this).apply {
            setTextColor(Style.ORANGE.accent)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, unit / 2, 0, 0)
        }
        column.addView(status)
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, unit / 2, 0, 0)
        }
        buttons.addView(button("Later") { Updater.later(this); finish() })
        // Install in the Pre's orange, so it stands out.
        buttons.addView(button("Install") { install() }.apply {
            backgroundTintList = ColorStateList.valueOf(Style.ORANGE.accent)
            setTextColor(Color.WHITE)
        })
        column.addView(buttons)
        setContentView(column)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingPermission && canInstall()) { awaitingPermission = false; install() }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun show(s: String) { status.text = s; status.visibility = View.VISIBLE }

    private fun install() {
        if (busy) return
        if (!canInstall()) {
            // Android 8 and later: allowed once, in Android's own settings.
            awaitingPermission = true
            show("Allow Pre Keyboard to install apps, then come back.")
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        busy = true
        buttons.alpha = 0.45f
        for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = false
        show("Downloading…")
        val url = release.apkUrl
        Thread {
            val error = try { download(url); null } catch (e: Exception) { e.message ?: e.toString() }
            runOnUiThread {
                if (error != null) {
                    busy = false
                    buttons.alpha = 1f
                    for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = true
                    show("The download failed: $error")
                } else show("Installing…")
            }
        }.apply { name = "update-download"; isDaemon = true }.start()
    }

    /** Streams the APK into an install session and commits it; the answer comes to [onNewIntent]. */
    private fun download(url: String) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            // GitHub answers with a redirect to its file host, which this follows (https to https).
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "PreKeyboard-Android")
            try {
                if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                val total = conn.contentLength.toLong()
                session.openWrite("prekeyboard.apk", 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastShown) { lastShown = pct; runOnUiThread { show("Downloading… $pct%") } }
                            }
                        }
                    }
                    session.fsync(out)
                }
            } finally { conn.disconnect() }
            val back = Intent(this, UpdateActivity::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getActivity(this, 0, back, flags).intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally { session.close() }
    }

    /** The installer's answer: confirm with the owner, done, or why not. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_STATUS) return
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirmation(intent)?.let { startActivity(it) }
            PackageInstaller.STATUS_SUCCESS -> finish()
            else -> {
                busy = false
                buttons.alpha = 1f
                for (i in 0 until buttons.childCount) buttons.getChildAt(i).isEnabled = true
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                show(if (code == PackageInstaller.STATUS_FAILURE_ABORTED) "Not installed." else "Not installed: ${why ?: code}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmation(intent: Intent): Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)

    companion object {
        private const val ACTION_STATUS = "io.github.alan_morford.prekeyboard.INSTALL_STATUS"
    }
}
