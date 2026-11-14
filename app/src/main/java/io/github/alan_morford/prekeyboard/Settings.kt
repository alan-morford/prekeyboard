package io.github.alan_morford.prekeyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * The owner's choices. The keyboard listens for changes, so a change made in the setup screen
 * (or by long-pressing the orange key or the globe) shows at once, with the keyboard still up.
 */
data class Settings(
    val style: Style = Style.ORANGE,
    val height: Height = Height.MEDIUM,
    val autocorrect: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val suggestions: Boolean = false,
    val swipe: Boolean = false,
    /** Press and hold a key for its extra characters (accents, more symbols), each such key dotted. */
    val holdForMore: Boolean = false,
    /** An emoji for the word in the suggestion bar's right-hand place, when there is one. */
    val emojiSuggestions: Boolean = false,
    /** A light vibration as each key goes down. */
    val vibrate: Boolean = false,
    /** How strong that vibration is: 1 (the light tap it always was), 2 or 3. */
    val vibrateLevel: Int = 1,
    /** A click as each key goes down. */
    val sound: Boolean = false,
    /** The letters print in lowercase while shift is off, capitals only when it is on. */
    val lowercase: Boolean = false,
    /** A row of digits over the letters, always there. */
    val numberRow: Boolean = false,
    /** With the number row, the digits printed on E R T, D F G, X C V and @ are left off. */
    val hideKeyDigits: Boolean = false,
    /** The languages checked in the Languages screen, in its order: a flick on the space bar goes through them. */
    val languages: List<Language> = listOf(Language.EN_US),
    /** The one typed in now, always one of [languages]. */
    val language: Language = Language.EN_US,
) {
    /** Swiping and emoji suggestions need the bar too: it is where they show. */
    val showsBar get() = suggestions || swipe || emojiSuggestions

    /** Row heights in dp. Medium is close to a Pre key's proportions on a modern phone. */
    enum class Height(val dp: Int, val label: String) { SMALL(46, "Small"), MEDIUM(54, "Medium"), LARGE(62, "Large") }

    companion object {
        const val PREFS = "prekeyboard"
        /** Set when the microphone was just allowed: the keyboard starts listening when it is back. */
        const val VOICE_PENDING = "voicePending"

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun read(context: Context): Settings {
            val p = prefs(context)
            val d = Settings()
            return Settings(
                style = runCatching { Style.valueOf(p.getString("style", null) ?: "") }.getOrDefault(d.style),
                height = runCatching { Height.valueOf(p.getString("height", null) ?: "") }.getOrDefault(d.height),
                autocorrect = p.getBoolean("autocorrect", d.autocorrect),
                doubleSpacePeriod = p.getBoolean("doubleSpacePeriod", d.doubleSpacePeriod),
                suggestions = p.getBoolean("suggestions", d.suggestions),
                swipe = p.getBoolean("swipe", d.swipe),
                holdForMore = p.getBoolean("holdForMore", d.holdForMore),
                emojiSuggestions = p.getBoolean("emojiSuggestions", d.emojiSuggestions),
                vibrate = p.getBoolean("vibrate", d.vibrate),
                vibrateLevel = p.getInt("vibrateLevel", d.vibrateLevel).coerceIn(1, Haptics.LEVELS),
                sound = p.getBoolean("sound", d.sound),
                lowercase = p.getBoolean("lowercase", d.lowercase),
                numberRow = p.getBoolean("numberRow", d.numberRow),
                hideKeyDigits = p.getBoolean("hideKeyDigits", d.hideKeyDigits),
            ).let { s ->
                // The screen's order, whatever order they were checked in; never none.
                val ids = (p.getString("languages", null) ?: "").split(',')
                val langs = Language.ALL.filter { it.id in ids }.ifEmpty { d.languages }
                val now = Language.byId(p.getString("language", null))?.takeIf { it in langs } ?: langs[0]
                s.copy(languages = langs, language = now)
            }
        }

        fun write(context: Context, s: Settings) {
            prefs(context).edit()
                .putString("style", s.style.name).putString("height", s.height.name)
                .putBoolean("autocorrect", s.autocorrect).putBoolean("doubleSpacePeriod", s.doubleSpacePeriod)
                .putBoolean("suggestions", s.suggestions).putBoolean("swipe", s.swipe)
                .putBoolean("holdForMore", s.holdForMore).putBoolean("emojiSuggestions", s.emojiSuggestions)
                .putBoolean("vibrate", s.vibrate).putInt("vibrateLevel", s.vibrateLevel).putBoolean("sound", s.sound)
                .putBoolean("lowercase", s.lowercase).putBoolean("numberRow", s.numberRow)
                .putBoolean("hideKeyDigits", s.hideKeyDigits)
                .putString("languages", s.languages.joinToString(",") { it.id })
                .putString("language", s.language.id)
                .apply()
        }

        fun update(context: Context, change: (Settings) -> Settings) = write(context, change(read(context)))

        // Words the owner added, and recently used emoji: data, not choices.
        const val LEARNED = "learned"
        private const val RECENT = "recentEmoji"

        /** Where [lang]'s family keeps the owner's words: English where it always has, "learned_fr" and so on. */
        fun learnedKey(lang: Language) = if (lang.english) LEARNED else "${LEARNED}_${lang.family}"

        fun learned(context: Context, lang: Language): Set<String> =
            prefs(context).getStringSet(learnedKey(lang), emptySet()) ?: emptySet()
        /** The owner's words but [word], however it is capitalised. */
        private fun learnedBut(context: Context, lang: Language, word: String): Set<String> =
            learned(context, lang).filterTo(HashSet()) { !it.equals(word, ignoreCase = true) }

        /**
         * [word] added, spelt as given ("webOS" keeps its capitals, in place of any other
         * spelling of it): no longer among the deleted, if it was.
         */
        fun addLearned(context: Context, lang: Language, word: String) =
            prefs(context).edit()
                .putStringSet(learnedKey(lang), learnedBut(context, lang, word) + word)
                .putStringSet(removedKey(lang), removed(context, lang) - word.lowercase())
                .apply()
        fun removeLearned(context: Context, lang: Language, word: String) =
            prefs(context).edit().putStringSet(learnedKey(lang), learnedBut(context, lang, word)).apply()

        /** Where [lang]'s family keeps the dictionary's words the owner deleted: "removed", "removed_fr". */
        fun removedKey(lang: Language) = if (lang.english) REMOVED else "${REMOVED}_${lang.family}"
        const val REMOVED = "removed"

        fun removed(context: Context, lang: Language): Set<String> =
            prefs(context).getStringSet(removedKey(lang), emptySet()) ?: emptySet()

        /** [word] deleted: out of the owner's own words too, if it was one. */
        fun addRemoved(context: Context, lang: Language, word: String) {
            val w = word.lowercase()
            prefs(context).edit()
                .putStringSet(removedKey(lang), removed(context, lang) + w)
                .putStringSet(learnedKey(lang), learnedBut(context, lang, w))
                .apply()
        }
        fun restoreRemoved(context: Context, lang: Language, word: String) =
            prefs(context).edit().putStringSet(removedKey(lang), removed(context, lang) - word.lowercase()).apply()

        fun recentEmoji(context: Context): List<String> =
            (prefs(context).getString(RECENT, "") ?: "").split('\n').filter { it.isNotEmpty() }
        fun addRecentEmoji(context: Context, e: String) {
            val list = (listOf(e) + recentEmoji(context).filter { it != e }).take(EmojiPanel.RECENT_MAX)
            prefs(context).edit().putString(RECENT, list.joinToString("\n")).apply()
        }
    }
}
