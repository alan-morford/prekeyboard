package io.github.alan_morford.prekeyboard

/**
 * The Palm Pre's keyboard, key for key: four rows of ten in matching columns, the orange key
 * and Sym, and each key's alternate character where the Pre prints it. Ported from LuneOS's
 * "Pre (Orange)" / "Pre (White)" layouts (webos-keyboard, Keyboard_en_phone_preorange.qml),
 * which were measured off a Pre.
 *
 * The real Pre's alternates:
 *   Q/ W+ E1 R2 T3 (printed top left)   Y( U) I% O" P= (top right)
 *   A& S- D4 F5 G6 (top left)           H$ J! K: L' (top right)
 *   Z* X7 C8 V9 (top left)              B# N? M; ,_ (top right)
 *   @0 (here the 0 is printed top left, as 1, 4 and 7 are above it).
 */

enum class Kind { CHAR, SHIFT, BACKSPACE, RETURN, ORANGE, SYM, SPACE, GLOBE, EMOJI }

/** A small icon printed in a key's top right corner, for what holding the key does. */
enum class Corner { MIC, GEAR }

/** Which top corner a key's printed alternate sits in; the letter takes the bottom corner opposite. */
enum class AltPos { LEFT, RIGHT }

class Key(
    val kind: Kind,
    /** What the key types, and what it shows (letters show as capitals, as printed on the Pre). */
    val text: String = "",
    /** The printed alternate, typed while the orange key is active. */
    val alt: String? = null,
    val altPos: AltPos = AltPos.RIGHT,
    /** The press-and-hold list; its first entry is always the key's own character. */
    val extended: List<String>? = null,
    /** Printed in the accent colour: the Sym page's digits. */
    val accent: Boolean = false,
    /** A label for a function key ("Sym", "ABC"). */
    val label: String? = null,
    /** Printed in the top right: the period's microphone (hold, or orange key then tap: speech), Return's gear. */
    val corner: Corner? = null,
)

/**
 * One place in a row. A slot with [drawn] false is a spacer: it draws nothing, but a touch on
 * it goes to [key], as webos-keyboard's SpacerKey { forwardTo } did. That is how the Pre's
 * bottom row is inset by about a key at each end and still easy to hit.
 */
class Slot(val weight: Float, val key: Key, val drawn: Boolean = true)

private fun c(text: String, alt: String? = null, pos: AltPos = AltPos.RIGHT, ext: String? = null, w: Float = 1f) =
    Slot(w, Key(Kind.CHAR, text, alt, pos, ext?.let { s -> s.split(' ') }))

private fun f(kind: Kind, w: Float = 1f, label: String? = null) = Slot(w, Key(kind, label = label))

private val L = AltPos.LEFT

object Keymap {

    /** The bottom row's two-column keys at each end: drawn this wide, after an inset of [EDGE] that still takes a touch. */
    private const val WIDE = 1.65f
    private const val EDGE = 0.35f

    private val shift = Key(Kind.SHIFT)
    private val sym = Key(Kind.SYM, label = "Sym")

    val letters: List<List<Slot>> = listOf(
        listOf(
            c("q", "/", L),
            c("w", "+", L),
            c("e", "1", L, "e è é ê ë ę ē € ě"),
            c("r", "2", L, "r ® ř ŕ"),
            c("t", "3", L, "t ™ þ ť ţ"),
            c("y", "(", ext = "y ý ÿ ¥"),
            c("u", ")", ext = "u ù ú û ü ű"),
            c("i", "%", ext = "i ì í î ï İ ı"),
            c("o", "\"", ext = "o ò ó ô õ ö ø ő œ º ω"),
            c("p", "=", ext = "p ¶ § π"),
        ),
        listOf(
            c("a", "&", L, "a à á â ã ä å æ ª"),
            c("s", "-", L, "s š ş ß σ $ ś — –"),
            c("d", "4", L, "d ð † ‡ ď đ"),
            c("f", "5", L),
            c("g", "6", L, "g ğ"),
            c("h", "$"),
            c("j", "!"),
            c("k", ":"),
            c("l", "'", ext = "l ł ĺ"),
            f(Kind.BACKSPACE),
        ),
        listOf(
            f(Kind.ORANGE),
            c("z", "*", L, "z ž ź ż"),
            c("x", "7", L),
            c("c", "8", L, "c ç ć © ¢ č"),
            c("v", "9", L),
            c("b", "#"),
            c("n", "?", ext = "n ñ ń ň"),
            c("m", ";", ext = "m µ"),
            // The comma holds what the period used to, now that holding the period is speech.
            c(",", "_", ext = ", … • ¿"),
            f(Kind.RETURN),
        ),
        // The Pre's bottom row, inset at each end. On the column grid above: shift and Sym
        // take two columns each (the same size, inset alike), @ is one key in the column of
        // E, D and X with its 0 where theirs are, the space bar 3 to 7, and the period the
        // column of M below I and K, with the microphone printed in its corner. The Sym
        // page's bottom row is the same, key for key, so nothing moves when the page changes.
        listOf(
            Slot(EDGE, shift, drawn = false),
            Slot(WIDE, shift),
            c("@", "0", L),
            Slot(4f, Key(Kind.SPACE, " ")),
            Slot(1f, Key(Kind.CHAR, ".", corner = Corner.MIC)),
            Slot(WIDE, sym),
            Slot(EDGE, sym, drawn = false),
        ),
    )

    /** A digit in the accent colour; held, its superscript and fractions. */
    private fun digit(d: Char, ext: String) =
        Slot(1f, Key(Kind.CHAR, d.toString(), extended = "$d $ext".split(' '), accent = true))

    /** A symbol; [ext] is its press-and-hold list after itself. */
    private fun s(t: String, ext: String? = null) =
        Slot(1f, Key(Kind.CHAR, t, extended = ext?.let { "$t $it".split(' ') }))

    /**
     * The Sym page: digits in the accent colour, then only symbols the letters page doesn't
     * already type (its printed alternates, @ , . and their hold lists; the period is repeated
     * so it stays under the thumb), so the page adds to
     * the letters instead of repeating them. The digits stay, since a row of them is what
     * people reach for.
     */
    val symbols: List<List<Slot>> = run {
        val back = Key(Kind.BACKSPACE)
        listOf(
            listOf(
                digit('1', "¹ ½ ⅓ ¼ ⅛"),
                digit('2', "² ⅔"),
                digit('3', "³ ¾ ⅜"),
                digit('4', "⁴"),
                digit('5', "⁵ ⅝"),
                digit('6', "⁶"),
                digit('7', "⁷ ⅞"),
                digit('8', "⁸"),
                digit('9', "⁹"),
                digit('0', "⁰ ⁿ ∅"),
            ),
            listOf(
                s("["), s("]"), s("{"), s("}"),
                s("<", "‹ « ≤"), s(">", "› » ≥"),
                s("^"), s("~", "≈"), s("|", "¦ ‖ ¬"), s("\\"),
            ),
            // Where LuneOS has its language key, Android gets the keyboard picker.
            listOf(
                f(Kind.GLOBE),
                s("`", "´ ‘ ’ “ ” ‚ „"),
                s("°", "′ ″ ‰"),
                s("£", "₹ ₩ ₽ ₺ ₪ ₱ ₿ ¤ ƒ"),
                s("×", "· ∗"),
                s("÷", "√ ∞ ∆ ∑"),
                s("±", "∓"),
                s("≠", "≡"),
                s("¡", "‽"),
                Slot(1f, back),
            ),
            // Key for key the letters page's bottom row (ABC where shift is, emoji where @ is,
            // the period, microphone and all, where the period is, Return where Sym is), so nothing
            // on it moves when the page changes. The dashes are held on S, and ¬ on |.
            run {
                val abc = Key(Kind.SYM, label = "ABC")
                // Hold Return here for the Pre Keyboard app.
                val ret = Key(Kind.RETURN, corner = Corner.GEAR)
                listOf(
                    Slot(EDGE, abc, drawn = false),
                    Slot(WIDE, abc),
                    // Tap for the emoji page; hold for LuneOS's text emoticons.
                    Slot(1f, Key(Kind.EMOJI, extended = listOf(":-)", ";-)", ":-(", ":'(", ":-P", ":-O", "<3"))),
                    Slot(4f, Key(Kind.SPACE, " ")),
                    Slot(1f, Key(Kind.CHAR, ".", corner = Corner.MIC)),
                    Slot(WIDE, ret),
                    Slot(EDGE, ret, drawn = false),
                )
            },
        )
    }
}
