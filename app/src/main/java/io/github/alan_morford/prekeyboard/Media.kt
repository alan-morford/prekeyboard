package io.github.alan_morford.prekeyboard

/**
 * Which of a KLIPY item's files to send, given what the app's field says it takes: a GIF as a
 * GIF (a medium one, unless that is very large), a sticker as an animated WebP where taken (it
 * keeps its see-through edges), else a GIF, else a still.
 */
object Media {

    class Choice(val file: Klipy.File, val mime: String, val ext: String) {
        /** A name for the file sent: the item's id, made safe, and the format. */
        fun fileName(item: Klipy.Item): String {
            val base = (item.id.ifEmpty { item.slug }).filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(60)
            return base.ifEmpty { "media" } + "." + ext
        }
    }

    /** Above this, a GIF goes in its smaller size. */
    private const val BIG = 6L * 1024 * 1024

    fun choose(kind: Klipy.Kind, item: Klipy.Item, takes: (String) -> Boolean): Choice? {
        fun pick(format: String, mime: String, ext: String, vararg sizes: String): Choice? {
            if (!takes(mime)) return null
            val files = sizes.mapNotNull { item.file(it, format) }
            val f = files.firstOrNull { it.size in 1..BIG } ?: files.lastOrNull() ?: return null
            return Choice(f, mime, ext)
        }
        return when (kind) {
            Klipy.Kind.GIF -> pick("gif", "image/gif", "gif", "md", "sm", "xs")
                ?: pick("webp", "image/webp", "webp", "md", "sm")
                ?: pick("jpg", "image/jpeg", "jpg", "md", "sm")
                ?: pick("png", "image/png", "png", "md", "sm")
            Klipy.Kind.STICKER -> pick("webp", "image/webp", "webp", "md", "hd", "sm")
                ?: pick("gif", "image/gif", "gif", "md", "hd", "sm")
                ?: pick("png", "image/png", "png", "md", "hd", "sm")
        }
    }

    /** Where the item can be seen, for a field that takes no pictures. */
    fun link(item: Klipy.Item): String? =
        (item.first("gif", "md", "sm", "hd", "xs") ?: item.first("webp", "md", "sm", "hd")
            ?: item.first("png", "md", "sm", "hd"))?.url
}
