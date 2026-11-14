package io.github.alan_morford.prekeyboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * After a crash: what was written down ([CrashLog]), and the offer to send it by email.
 * Send opens the owner's mail app with the report filled in, to the address the build was
 * given (`crash.email` in local.properties); they send it from there, or not. Either way
 * the report is then put away.
 */
class CrashActivity : Activity() {

    private val unit get() = (16 * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reports = CrashLog.pending(this)
        if (reports.isEmpty()) { finish(); return }
        setTitle("Pre Keyboard stopped")
        val text = reports.joinToString("\n\n========\n\n") { runCatching { it.readText() }.getOrDefault("") }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(unit * 3 / 2, unit, unit * 3 / 2, unit)
        }
        column.addView(TextView(this).apply {
            this.text = (if (reports.size == 1) "Pre Keyboard crashed. " else "Pre Keyboard crashed ${reports.size} times. ") +
                "Email the report so it can be fixed? It has nothing you typed."
            setTextColor(Color.rgb(0xb4, 0xb4, 0xb4))
            setPadding(0, 0, 0, unit / 2)
        })
        column.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.MONOSPACE
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(resources.displayMetrics.heightPixels / 2, unit * 20))
        })
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, unit / 2, 0, 0)
        }
        buttons.addView(button("Don't send") { CrashLog.clear(this); finish() }.apply { AppLook.action(this) })
        buttons.addView(button("Send") { send(text) }.apply { AppLook.main(this) })
        column.addView(buttons)
        setContentView(column)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        this.text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun send(report: String) {
        val to = BuildConfig.CRASH_EMAIL.takeIf { it.isNotBlank() }
        val subject = "Pre Keyboard ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) crash report"
        // Within what an Intent can carry.
        val body = if (report.length > MAX_BODY) report.take(MAX_BODY) + "\n…(cut short)" else report
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (to != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(mail)
        } catch (e: ActivityNotFoundException) {
            // No mail app answers mailto: any app that sends text will do.
            val any = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                if (to != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            try { startActivity(Intent.createChooser(any, "Send the report")) } catch (_: ActivityNotFoundException) { return }
        }
        CrashLog.clear(this)
        finish()
    }

    companion object {
        private const val MAX_BODY = 80_000
    }
}
