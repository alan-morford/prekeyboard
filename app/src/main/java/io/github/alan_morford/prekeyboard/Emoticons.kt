package io.github.alan_morford.prekeyboard

/**
 * Text faces and the emoji each stands for (":)" → 🙂), for the suggestion bar to offer one
 * after a face is typed. Every emoji here draws on Android 5 and later.
 */
object Emoticons {

    /** A face at the end of the text: how many characters it takes (with a space after it), and its emoji. */
    class Face(val length: Int, val emoji: String, val lead: String = "")

    private val faces: Map<String, String> = buildMap {
        fun face(emoji: String, vararg texts: String) { for (t in texts) put(t, emoji) }
        face("🙂", ":)", ":-)", "=)", ":]", "(:")
        face("🙁", ":(", ":-(", "=(", ":[")
        face("😃", ":D", ":-D", "=D")
        face("😆", "XD", "xD")
        face("😉", ";)", ";-)", ";D", ";]")
        face("😛", ":P", ":-P", ":p", ":-p", "=P", ":b")
        face("😜", ";P", ";-P", ";p", ";-p")
        face("😮", ":O", ":-O", ":o", ":-o")
        face("😢", ":'(", ":'-(")
        face("😂", ":')", ":'D")
        face("😕", ":/", ":-/", ":\\", ":-\\")
        face("😐", ":|", ":-|")
        face("😘", ":*", ":-*")
        face("😖", ":S", ":s")
        face("😠", ">:(", ">:-(")
        face("😈", ">:)", ">:-)", ">:D")
        face("😇", "O:)", "O:-)", "0:)")
        face("😎", "B-)", "8-)")
        face("😳", ":$", "o_O", "O_o", "o.O", "O.o")
        face("😡", ":@")
        face("😶", ":X", ":x", ":-X", ":-x")
        face("😞", ":c")
        face("😺", ":3")
        face("😊", "^_^", "^^", "^.^")
        face("😑", "-_-")
        face("😭", "T_T", ";_;")
        face("❤️", "<3")
        face("💔", "</3")
    }
    private val longest = faces.keys.maxOf { it.length }

    /**
     * The face [before] ends in, a space after it allowed. It stands on its own (at the start
     * or after a space or an opening bracket), or is glued to a word when it is a colon face
     * that closes (":)", ":(", ":]"), as in "hi:)"; not "https:/".
     */
    fun at(before: CharSequence): Face? {
        val spaced = before.endsWith(" ")
        val b = if (spaced) before.subSequence(0, before.length - 1) else before
        for (n in minOf(longest, b.length) downTo 2) {
            val text = b.subSequence(b.length - n, b.length).toString()
            val emoji = faces[text] ?: continue
            val prev = b.getOrNull(b.length - n - 1)
            val alone = prev == null || prev.isWhitespace() || prev in "(\"[{“"
            val glued = prev != null && prev.isLetter() && text[0] in ":;" && text.last() in ")(]["
            if (alone || glued) return Face(n + if (spaced) 1 else 0, emoji)
        }
        return null
    }
}
