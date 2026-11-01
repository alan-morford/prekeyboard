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
) {
    /** Swiping needs the bar too: it is where the other guesses go. */
    val showsBar get() = suggestions || swipe

    /** Row heights in dp. Medium is close to a Pre key's proportions on a modern phone. */
    enum class Height(val dp: Int, val label: String) { SMALL(46, "Small"), MEDIUM(54, "Medium"), LARGE(62, "Large") }

    companion object {
        const val PREFS = "prekeyboard"

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
            )
        }

        fun write(context: Context, s: Settings) {
            prefs(context).edit()
                .putString("style", s.style.name).putString("height", s.height.name)
                .putBoolean("autocorrect", s.autocorrect).putBoolean("doubleSpacePeriod", s.doubleSpacePeriod)
                .putBoolean("suggestions", s.suggestions).putBoolean("swipe", s.swipe)
                .putBoolean("holdForMore", s.holdForMore)
                .apply()
        }

        fun update(context: Context, change: (Settings) -> Settings) = write(context, change(read(context)))

        // Words kept by undoing a correction, and recently used emoji: data, not choices.
        private const val LEARNED = "learned"
        private const val RECENT = "recentEmoji"

        fun learned(context: Context): Set<String> = prefs(context).getStringSet(LEARNED, emptySet()) ?: emptySet()
        fun addLearned(context: Context, word: String) =
            prefs(context).edit().putStringSet(LEARNED, learned(context) + word.lowercase()).apply()

        fun recentEmoji(context: Context): List<String> =
            (prefs(context).getString(RECENT, "") ?: "").split('\n').filter { it.isNotEmpty() }
        fun addRecentEmoji(context: Context, e: String) {
            val list = (listOf(e) + recentEmoji(context).filter { it != e }).take(EmojiPanel.RECENT_MAX)
            prefs(context).edit().putString(RECENT, list.joinToString("\n")).apply()
        }
    }
}
