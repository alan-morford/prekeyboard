package io.github.alan_morford.prekeyboard

import java.text.Normalizer

/** Where the letters go on the Pre's grid: the layout each language's typists know. */
enum class Layout(val rows: List<String>) {
    QWERTY(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
    /** German: Y and Z change places. */
    QWERTZ(listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),
    /** French: A and Q, Z and W change places; M stays on the bottom row, as on BlackBerry's AZERTY. */
    AZERTY(listOf("azertyuiop", "qsdfghjkl", "wxcvbnm")),
}

/**
 * A language the keyboard types: its keys, and the words it corrects to and suggests. The
 * owner checks the ones they want in the Languages screen and flicks the space bar to go
 * from one to the next.
 *
 * The four Englishes share everything but their dictionaries (colour or color): the owner's
 * own words, what they type after what, and the emoji. Each other language has its own.
 *
 * Plain Kotlin with no Android in it, so the tests run it directly.
 */
class Language(
    /** Stored in the settings: "en_US", "fr". */
    val id: String,
    /** In the Languages screen, in the language itself. */
    val name: String,
    /** Flashed on the space bar when it comes up. */
    val short: String,
    /** For speech input: a BCP 47 tag. */
    val tag: String,
    val layout: Layout,
    /** The folder in assets for its dictionary; null for the main one (US English). */
    private val dictionaryDir: String?,
    /** The folder for everything else (next words, emoji); null for the main ones (English). */
    private val dataDir: String?,
    /**
     * The language's own accented letters, commonest first: they lead each key's
     * press-and-hold list, and autocorrect puts them in for the plain letter.
     */
    val accents: String = "",
    /**
     * Words written against the next one with an apostrophe ("l'homme", "dell'anno"): the
     * word after the apostrophe is the one looked up, corrected and learned.
     */
    val elisions: Set<String> = emptySet(),
) {
    val english get() = id.startsWith("en")

    /** Words the owner adds, and what they type after what, are kept per family: "en", "fr". */
    val family get() = id.substringBefore('_')

    fun asset(name: String): String {
        val dir = if (name == DICTIONARY) dictionaryDir else dataDir
        return if (dir == null) name else "$dir/$name"
    }

    /**
     * The dictionary's files, read one after the other. A regional English's own file holds
     * only how it differs from the US one ("colour", "-color"), so it comes after that.
     */
    val dictionaries: List<String> get() =
        if (english && dictionaryDir != null) listOf(DICTIONARY, asset(DICTIONARY)) else listOf(asset(DICTIONARY))

    /** Whether there is a file [name] for this language (contractions are English only). */
    fun has(name: String) = name != CONTRACTIONS || english

    /** [letter]'s accented forms in this language ("e": é è ê ë), commonest first. */
    fun accentsOf(letter: Char): String = accents.filter { base(it) == letter }

    /**
     * [word] split at its elision: ("l'", "homme") for "l'homme"; null when it has none. The
     * prefix keeps its apostrophe and its case.
     */
    fun elided(word: String): Pair<String, String>? {
        if (elisions.isEmpty()) return null
        val at = word.indexOfFirst { it == '\'' || it == '’' }
        if (at <= 0 || at == word.length - 1) return null
        if (word.substring(0, at).lowercase() !in elisions) return null
        return word.substring(0, at + 1) to word.substring(at + 1)
    }

    /** The word to look [word] up by: without its elision. */
    fun bare(word: String): String = elided(word)?.second ?: word

    companion object {
        const val DICTIONARY = "dictionary.txt"
        const val NEXT_WORDS = "next_words.txt"
        const val EMOJI_WORDS = "emoji_words.txt"
        const val CONTRACTIONS = "contractions.txt"

        val EN_US = Language("en_US", "English (US)", "English (US)", "en-US", Layout.QWERTY, null, null)

        val ALL: List<Language> = listOf(
            EN_US,
            Language("en_GB", "English (UK)", "English (UK)", "en-GB", Layout.QWERTY, "en_GB", null),
            Language("en_CA", "English (Canada)", "English (Canada)", "en-CA", Layout.QWERTY, "en_CA", null),
            Language("en_AU", "English (Australia)", "English (Australia)", "en-AU", Layout.QWERTY, "en_AU", null),
            Language("fr", "Français", "Français", "fr-FR", Layout.AZERTY, "fr", "fr",
                accents = "éèàçêùâôîëïûüœæÿ",
                elisions = setOf("l", "d", "j", "m", "n", "s", "t", "c", "qu", "jusqu", "lorsqu",
                    "puisqu", "quoiqu", "quelqu", "presqu")),
            Language("de", "Deutsch", "Deutsch", "de-DE", Layout.QWERTZ, "de", "de",
                accents = "äöüß"),
            Language("es", "Español", "Español", "es-ES", Layout.QWERTY, "es", "es",
                accents = "ñéáóíúü"),
            Language("it", "Italiano", "Italiano", "it-IT", Layout.QWERTY, "it", "it",
                accents = "èàùòìéóí",
                elisions = setOf("l", "d", "dell", "all", "dall", "nell", "sull", "coll", "un",
                    "c", "quest", "quell", "bell", "sant", "tutt", "senz", "anch", "cos", "dov",
                    "com", "nient", "s", "m", "t", "v", "n")),
        )

        fun byId(id: String?): Language? = ALL.firstOrNull { it.id == id }

        /** [c] without its accent: é -> e, ß -> s; anything else as it is. */
        fun base(c: Char): Char {
            if (c < 'À') return c
            if (c == 'ß') return 's'
            if (c == 'œ' || c == 'æ') return if (c == 'œ') 'o' else 'a'
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            return d.firstOrNull()?.takeIf { d.length > 1 || it != c } ?: c
        }

        /** [word] in plain letters: "être" -> "etre", for finding its keys. */
        fun fold(word: String): String {
            val sb = StringBuilder(word.length)
            for (c in word) when (c) {
                'ß' -> sb.append("ss")
                'œ', 'Œ' -> sb.append(if (c == 'œ') "oe" else "OE")
                'æ', 'Æ' -> sb.append(if (c == 'æ') "ae" else "AE")
                else -> sb.append(if (c.isUpperCase()) base(c.lowercaseChar()).uppercaseChar() else base(c))
            }
            return sb.toString()
        }
    }
}
