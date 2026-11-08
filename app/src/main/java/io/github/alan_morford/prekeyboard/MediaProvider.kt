package io.github.alan_morford.prekeyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands a GIF or sticker to the app it is sent to. Android passes pictures from a keyboard
 * as a content: address the app is allowed to read for a while; this serves those, read-only,
 * from one folder of the cache. Only the last few sent are kept, and none past an hour, as
 * KLIPY asks that its pictures not be stored.
 */
class MediaProvider : ContentProvider() {

    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (!SAFE.matches(name)) throw FileNotFoundException()
        val f = File(dir(ctx), name)
        if (!f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String? = mimeFor(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        val AUTHORITY = BuildConfig.APPLICATION_ID + ".media"
        private val SAFE = Regex("[A-Za-z0-9_-]{1,80}\\.(gif|webp|png|jpg)")
        private const val KEEP = 60 * 60_000L

        private fun dir(context: Context) = File(context.cacheDir, "media")

        fun mimeFor(name: String): String? = when (name.substringAfterLast('.', "")) {
            "gif" -> "image/gif"; "webp" -> "image/webp"; "png" -> "image/png"; "jpg" -> "image/jpeg"
            else -> null
        }

        /** Writes [bytes] as [name] (a safe name with its extension), clearing out older ones; its address. */
        fun save(context: Context, name: String, bytes: ByteArray): Uri {
            require(SAFE.matches(name))
            val d = dir(context).apply { mkdirs() }
            val now = System.currentTimeMillis()
            // The one sent before may still be being read: kept a while, never past the hour.
            d.listFiles()?.forEach { if (now - it.lastModified() > KEEP || it.name == name) it.delete() }
            File(d, name).writeBytes(bytes)
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()
        }
    }
}
