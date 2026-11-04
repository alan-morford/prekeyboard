# Pre Keyboard

The Palm Pre's keyboard, for Android. Key for key the Pre's layout - four rows of ten in
matching columns, the orange key, Sym, and every key's alternate printed where the Pre printed
it - in two styles:

| Orange | White |
|---|---|
| ![Orange](docs/orange.png) | ![White](docs/white.png) |

**Orange** prints the digits and the orange key in orange, as on the Pre. **White** prints
everything white. Hold the globe on the Sym page to switch between them at any time.

It began as the "Pre (Orange)" and "Pre (White)" layouts of
[LuneOS's keyboard](https://github.com/webOS-ports/webos-keyboard), and the idea of bringing a
webOS keyboard to Android comes from [Lunacy](https://github.com/webOSArchive/Lunacy)'s
TouchPad keyboard. It shares no code with either.

## Features

- The Pre's layout key for key: four rows in matching columns, the orange key, Sym, and every
  key's alternate printed where the Pre printed it
- Two styles, **Orange** and **White**: hold the globe on the Sym page to switch on the fly
- **Swipe to type**, with the word you would get shown as you swipe, and the next likeliest
  guesses one tap away
- **Suggestion bar**: three words above the keys, the likeliest in the middle in orange, and
  if you like, an emoji for the word on the right (`pizza` → 🍕)
- **Next-word suggestions**: before you start a word, the bar offers the three likeliest next
  words (`I` → `don't`, `think`, `know`), learning the ones you use
- **Your own words**: hold a word in the bar to add it to the dictionary
- **Speech input**: hold the period and talk
- **Paste**: copy or cut something and a clipboard icon waits in the bar's right-hand place
  until you tap it to paste
- **Fix a word**: tap a word in the text and the bar offers what else it might be (`ball` →
  `bell`, `bill`, `bull`); tap one and it takes the word's place
- **Autocorrect** for typos and for contractions (`dont` → `don't`), undone by the next
  backspace, and learning the words you keep
- **Double-space period**, and a period that brings its own space
- **Emoji**: every emoji your phone can draw, by category, with your recent ones first
- **Hold for more**: accents and extra symbols on a long press, each such key marked with a
  dot (switch it on in the app, or hold the orange key)
- **Cursor control**: hold the space bar and slide; with shift on, it selects
- The **globe** asks which keyboard to use; hold Return on the Sym page to open the app
- Shift lights orange whenever the next letter will be a capital
- Small, Medium and Large heights
- Every setting takes effect at once, with the keyboard still on screen
- **Updates itself**: it looks for a new release once a day and offers it above the keys
- Built for Android 15, runs on Android 5.0 and later

![Swipe to type, with the suggestion bar](docs/swipe.png)

## Typing on it

- **Orange key**: tap, and the next key types its printed alternate (orange + E = 1). Double tap
  to lock it on; tap again to let go. **Shift** works the same way, and lights orange while
  the next letter will be a capital. Hold the orange key to turn **Hold for more** on or off.
- **Sym** opens the digits and the symbols the letters page doesn't have: `[ ] { } < > ^ ~ | \`,
  `` ` ° £ × ÷ ± ≠ ¡ `` and `—`, nothing repeated from the letters. With Hold for more on,
  hold a key for more still: curly quotes on `` ` ``, `« » ≤ ≥` on `<` and `>`, currencies on
  `£`, `√ ∞` on `÷`, superscripts and fractions on the digits. On that page, the **globe** asks
  which keyboard to use (hold it to switch between the Orange and White styles) and the
  **smiley** opens the emoji (with Hold for more on, hold it for the text emoticons, `:-)` and
  friends). The bottom row keeps its shape between the two pages, so no key moves under your
  thumb: ABC where shift is, the smiley where `@` is, `—` where the period is, return where
  Sym is. Hold that return (the one with the gear) to open the Pre Keyboard app.
- **Period**: it brings a space with it. Backspace straight after takes just the space; a
  space typed out of habit isn't doubled; `?`, `,` or another period go against the word.
  No space in numbers (`3.14`), web or email addresses, or fields that aren't for words.
  It sits in the column of I, K and M, with a microphone in its corner.
- **Speech input**: hold the period (a tap is always just a period). The
  microphone lights orange and the bar says "Listening…"; the words go into the field as you
  say them. Tap the bar (or hold the period again) to stop; a pause stops it too, and typing
  any key does. The first time, Android asks to let Pre Keyboard use the microphone. It uses
  the phone's own speech recogniser (Google's, on most phones).
- **Punctuation after a space**: typed a space, then decided on a comma? `, . ? ! : ;` typed
  straight after the space go against the word and the space moves after them (`word ,` →
  `word, `).
- **Emoji**: every emoji your phone can draw, by category, with your recent ones first.
  Scroll the grid, or tap a category along the bottom.
- **Hold for more** (off at first; switch it on in the app, or hold the orange key): hold a
  key for its accents or extra symbols, slide to the one you want and let go. Keys that have
  them carry a small dot in the style's colour.
- **Hold the space bar** and slide: the cursor follows your finger, left and right a character
  at a time, up and down a line at a time. With shift on, it selects as it goes.
- **Hold backspace** and it repeats, then starts taking whole words. The suggestion bar waits
  while it does, so deleting never slows down.
- **The comma**, with Hold for more on, holds `… • ¿` (the period's list, before the period
  became speech input).
- **Swipe** (when switched on): slide across the letters and lift at the end of the word. The
  word goes in with a space either side, so you can swipe or tap the next word straight away,
  and a period lands on the word. While you swipe, the bar shows the word you would
  get if you lifted now, in orange; after, it offers the next likeliest
  guesses, and tapping one swaps it in. Backspace straight after a swipe takes the whole word
  and its space. Taps still type letters as usual, and the word before a swipe is corrected
  as a space would correct it (a lone `i` becomes `I`). Swiping works in search boxes too.

## Settings

Open **Pre Keyboard** from the app drawer. Everything there takes effect at once, even with
the keyboard on screen.

- **Style**: Orange or White. Holding the globe on the Sym page switches it too.
- **Height**: Small, Medium or Large rows.
- **Autocorrect** (on by default): fixes typos when you finish a word, and puts the apostrophe
  in contractions - `dont` becomes `don't`, `im` becomes `I'm`, a lone `i` becomes `I`. Words
  that are real words as they are (`well`, `were`, `its`, `ill`) are left alone. Backspace
  straight after a correction puts back what you typed, and the keyboard remembers that word
  from then on. It stays out of passwords, email addresses and web addresses.
- **Double-space period** (on by default): two quick spaces after a word type `. `.
- **Suggestion bar** (off by default): three words above the keys as you type. The middle one,
  in orange, is the likeliest - what the space bar would make of your word. On the left is the
  word as you typed it, when that differs; tap it to keep it, and it is never corrected again,
  or hold it for **Add to dictionary?** (Yes keeps it from then on, as tapping it would).
  Before you start a word, the bar offers the three likeliest next words, from everyday
  English and from what you usually type after the word before (kept on the phone; not in
  incognito fields). Tap a word already in the text and the bar offers the nearest other
  words, the likeliest in the middle; tap one to swap it in, and the word it replaced is
  offered back in case. When something new is copied or cut, a paste icon (a clipboard with
  a page) takes the right-hand place, through typing and from field to field, until you tap
  it to paste. That copy isn't offered again; the next copy or cut is.
- **Swipe to type** (off by default): see above. It brings the suggestion bar with it.
- **Emoji suggestions** (off by default): the bar's right-hand place shows an emoji for the
  word, when there is one (`pizza` → 🍕, `love` → ❤️). Tap it to swap the word for the emoji.
  It brings the suggestion bar with it.
- **Hold for more** (off by default): hold a key for its accents and extra symbols; keys that
  have them show a small dot. Holding the orange key turns it on or off too.
- **Updates**: shows the version, with **Check now**. The keyboard itself looks for a new
  release on GitHub once a day, when it comes up, and says so in the bar above the keys; tap
  that for the release notes, then **Install** or **Later** (asked again a day later). The
  first install asks you to let Pre Keyboard install apps.

Swipe matching follows the location channel of Kristensson and Zhai's SHARK² (2004): the
finger's path is compared, point for point, with each candidate word's path through its keys,
and the distance is weighed against how common the word is.

## Installing

Pre Keyboard isn't on an app store. Download the APK from
[Releases](https://github.com/alan-morford/prekeyboard/releases), or build it (below), then:

1. Install the APK (allow your browser or file manager to install apps when Android asks).
2. Open **Pre Keyboard** and follow its two steps: switch it on in Android's keyboard settings,
   then choose it.

Over `adb`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable io.github.alan_morford.prekeyboard/.KeyboardService
adb shell ime set io.github.alan_morford.prekeyboard/.KeyboardService
```

Android 5.0 and later; built for Android 15.

## Building

JDK 17 or later and the Android SDK (platform 35):

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # typing, autocorrect and rendering checks
```

The tests run on the JVM with Robolectric, which draws the real keyboard: they leave
screenshots of every page in `app/build/shots/`.

The word list and the emoji lists are generated and checked in, so a build needs no network.
To regenerate them:

```sh
python3 tools/build_dictionary.py <scowl>/final en_50k.txt app/src/main/assets/dictionary.txt
python3 tools/build_emoji.py emoji-test.txt app/src/main/assets/emoji.txt
python3 tools/build_emoji_words.py annotations/en.xml annotationsDerived/en.xml \
    app/src/main/assets/emoji.txt app/src/main/assets/dictionary.txt app/src/main/assets/emoji_words.txt
python3 tools/build_next_words.py eng_sentences.tsv.bz2 app/src/main/assets/dictionary.txt \
    app/src/main/assets/next_words.txt
```

(SCOWL from http://wordlist.aspell.net/, `en_50k.txt` from
[FrequencyWords](https://github.com/hermitdave/FrequencyWords), and `emoji-test.txt` from
https://unicode.org/Public/emoji/; the two `en.xml` from
[CLDR](https://github.com/unicode-org/cldr), `common/annotations` and `common/annotationsDerived`;
`eng_sentences.tsv.bz2` from https://downloads.tatoeba.org/exports/per_language/eng/.)

## The typeface

The Pre printed its keys in **Prelude**, and this keyboard uses it when it is there - but
Prelude belongs to HP, and its licence allows it on webOS devices only, so it is not in this
repository and the keyboard falls back to Android's bold sans. To build with Prelude for your
own use:

```sh
tools/fetch-prelude.sh                # puts Prelude-Bold.ttf in app/src/main/assets/fonts/
```

An APK built that way contains the font, so keep it to yourself.

## Licence

Apache 2.0 - see [LICENSE](LICENSE). The word list is CC BY-SA 4.0, the next-word list CC BY
2.0 FR and the emoji lists are under the Unicode License; see [NOTICE](NOTICE).
