#!/usr/bin/env python3
"""Build app/src/main/assets/emoji_words.txt, the emoji the suggestion bar offers for a word,
from Unicode CLDR's English emoji annotations
(https://github.com/unicode-org/cldr, common/annotations/en.xml and common/annotationsDerived/en.xml).

    python3 tools/build_emoji_words.py en.xml derived-en.xml app/src/main/assets/emoji.txt \\
        app/src/main/assets/dictionary.txt app/src/main/assets/emoji_words.txt
    python3 tools/build_emoji_words.py fr.xml derived-fr.xml app/src/main/assets/emoji.txt \
        app/src/main/assets/fr/dictionary.txt app/src/main/assets/fr/emoji_words.txt fr

Only emoji in emoji.txt (the panel's) are used. The commonest words (you, time, but) only get
an emoji named for them or one from FAVOURITES below, not CLDR's looser keyword matches. Each line is
    word<TAB>emoji emoji ...
best first: emoji named exactly the word ("pizza" -> pizza), then emoji with the word as a
keyword, then emoji with the word in their name; each in Unicode's order.
"""
import html, re, sys

ann, derived, panel, dictionary, out = sys.argv[1:6]
# Another language (fr, de, es, it): its CLDR files and its dictionary; no English favourites.
lang = sys.argv[6] if len(sys.argv) > 6 else "en"
COMMON_SCORE = 19
common = set()
with open(dictionary, encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 2 and not line.startswith("#") and int(parts[1]) >= COMMON_SCORE:
            common.add(parts[0].lower())
order = {}
with open(panel, encoding="utf-8") as f:
    for line in f:
        if line.startswith("#"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 3:
            order.setdefault(parts[2], len(order))

def bare(e):
    return e.replace("️", "")

by_bare = {bare(e): e for e in order}
names, keywords = {}, {}
for path in (ann, derived):
    with open(path, encoding="utf-8") as f:
        for m in re.finditer(r'<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>', f.read()):
            e = by_bare.get(bare(html.unescape(m.group(1))))
            if e is None:
                continue
            text = html.unescape(m.group(3)).lower()
            if m.group(2):
                names.setdefault(e, text)
            else:
                keywords.setdefault(e, [k.strip() for k in text.split("|")])

WORD = re.compile(r"^[a-z][a-z']*$") if lang == "en" else re.compile(r"^[^\W\d_]+$")
# Little words in emoji names ("face with tears of joy") that should not bring the emoji up.
SMALL = set("a an and as at be by down for from in into is it its me my no not of off on or out "
            "over the to up with without".split())
if lang != "en":
    # The language's own little words: its commonest hundred and fifty.
    SMALL = set()
    with open(dictionary, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            SMALL.add(line.split("\t")[0].lower())
            if len(SMALL) >= 150:
                break
best = {}  # word -> {emoji: rank}
def offer(word, e, rank):
    if not WORD.match(word) or len(word) < 2:
        return
    d = best.setdefault(word, {})
    d[e] = min(rank, d.get(e, 9))

for e, name in names.items():
    name = name.replace("flag: ", "")
    offer(name, e, 0)
    for w in re.split(r"[\s:,\-]+", name):
        if w not in SMALL:
            offer(w, e, 2)
for e, ks in keywords.items():
    for k in ks:
        offer(k, e, 1)

# The usual pick for common words, where CLDR's order gives something less likely.
FAVOURITES = {
    "love": "❤️", "heart": "❤️", "hearts": "❤️", "car": "🚗", "party": "🥳", "beer": "🍺",
    "yes": "👍", "no": "👎", "ok": "👌", "okay": "👌", "lol": "😂", "haha": "😂", "laugh": "😂",
    "funny": "😂", "sad": "😢", "happy": "😊", "cool": "😎", "thanks": "🙏", "thank": "🙏",
    "please": "🙏", "cat": "🐱", "dog": "🐶", "kiss": "😘", "wow": "😮", "omg": "😱",
    "angry": "😠", "mad": "😠", "cry": "😢", "crying": "😭", "sleep": "😴", "tired": "😴",
    "sick": "🤒", "think": "🤔", "hmm": "🤔", "wink": "😉", "hi": "👋", "hello": "👋",
    "bye": "👋", "wave": "👋", "clap": "👏", "strong": "💪", "money": "💰", "music": "🎵",
    "birthday": "🎂", "cake": "🎂", "gift": "🎁", "present": "🎁", "home": "🏠", "house": "🏠",
    "work": "💼", "phone": "📱", "star": "⭐", "rain": "🌧️", "snow": "❄️", "cold": "🥶",
    "flower": "🌸", "tree": "🌳", "baby": "👶", "eyes": "👀", "look": "👀",
    "fun": "🎉", "congrats": "🎉", "celebrate": "🎉", "food": "🍽️", "hungry": "🍽️", "eat": "🍽️",
    "drink": "🥤", "wine": "🍷", "smile": "😊", "good": "👍", "great": "👍", "nice": "👍",
    "bad": "👎", "perfect": "👌", "fire": "🔥", "hot": "🔥", "sun": "☀️", "moon": "🌙",
    "night": "🌙", "morning": "🌅", "sorry": "😔", "sleepy": "😴", "excited": "🤩",
}
# The same for the other languages' everyday words.
FAVOURITES_BY_LANG = {
    "fr": {"cœur": "❤️", "amour": "❤️", "bière": "🍺", "vin": "🍷", "merci": "🙏", "oui": "👍",
           "non": "👎", "chat": "🐱", "chien": "🐶", "bisous": "😘", "bisou": "😘", "fête": "🥳",
           "voiture": "🚗", "maison": "🏠", "soleil": "☀️", "lol": "😂", "mdr": "😂", "triste": "😢",
           "content": "😊", "contente": "😊", "anniversaire": "🎂", "bonjour": "👋", "salut": "👋"},
    "de": {"herz": "❤️", "liebe": "❤️", "bier": "🍺", "wein": "🍷", "danke": "🙏", "bitte": "🙏",
           "ja": "👍", "nein": "👎", "katze": "🐱", "hund": "🐶", "kuss": "😘", "party": "🥳",
           "auto": "🚗", "haus": "🏠", "sonne": "☀️", "lol": "😂", "traurig": "😢", "glücklich": "😊",
           "geburtstag": "🎂", "hallo": "👋", "tschüss": "👋"},
    "es": {"corazón": "❤️", "amor": "❤️", "cerveza": "🍺", "vino": "🍷", "gracias": "🙏", "sí": "👍",
           "no": "👎", "gato": "🐱", "perro": "🐶", "beso": "😘", "besos": "😘", "fiesta": "🥳",
           "coche": "🚗", "carro": "🚗", "casa": "🏠", "sol": "☀️", "jaja": "😂", "jajaja": "😂",
           "triste": "😢", "feliz": "😊", "cumpleaños": "🎂", "hola": "👋", "adiós": "👋"},
    "it": {"cuore": "❤️", "amore": "❤️", "birra": "🍺", "vino": "🍷", "grazie": "🙏", "sì": "👍",
           "no": "👎", "gatto": "🐱", "cane": "🐶", "bacio": "😘", "baci": "😘", "festa": "🥳",
           "macchina": "🚗", "casa": "🏠", "sole": "☀️", "ahah": "😂", "triste": "😢", "felice": "😊",
           "compleanno": "🎂", "ciao": "👋"},
    "nl": {"hart": "❤️", "liefde": "❤️", "bier": "🍺", "wijn": "🍷", "dank": "🙏", "bedankt": "🙏",
           "ja": "👍", "nee": "👎", "kat": "🐱", "hond": "🐶", "kus": "😘", "kusjes": "😘", "feest": "🥳",
           "auto": "🚗", "huis": "🏠", "zon": "☀️", "haha": "😂", "verdrietig": "😢", "blij": "😊",
           "gelukkig": "😊", "verjaardag": "🎂", "hallo": "👋", "hoi": "👋", "doei": "👋"},
}
for word, e in (FAVOURITES if lang == "en" else FAVOURITES_BY_LANG.get(lang, {})).items():
    if e in order:
        best.setdefault(word, {})[e] = -1

MAX = 3
n = 0
with open(out, "w", encoding="utf-8") as o:
    o.write(f"# Generated by tools/build_emoji_words.py from Unicode CLDR {lang} annotations (Unicode License v3).\n")
    for word in sorted(best):
        ranks = best[word]
        if word in common:
            ranks = {e: r for e, r in ranks.items() if r <= 0}
        if not ranks:
            continue
        es = sorted(ranks, key=lambda e: (ranks[e], order[e]))[:MAX]
        o.write(f"{word}\t{' '.join(es)}\n")
        n += 1
print(f"{n} words -> {out}")
