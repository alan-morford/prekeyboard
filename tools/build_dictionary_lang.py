#!/usr/bin/env python3
"""Build a dictionary.txt for a language other than English (fr, de, es, it).

How common each word is comes from Hermit Dave's FrequencyWords (OpenSubtitles 2018,
CC BY-SA 4.0). Subtitles hold typos and odd spellings as well as words, so a word is kept
when it is common there, or when it is less common but Tatoeba's sentences in the language
(CC BY 2.0 FR) use it too: two sources that agree. Tatoeba also gives each word its case:
one written mostly with a capital in mid-sentence (a German noun, a name) is kept that way,
since FrequencyWords is all lower case.

Subtitles of English films also leave English words and names behind. A word the English
dictionary (app/src/main/assets/dictionary.txt) has is dropped unless Tatoeba uses it in this
language too, and kept with its capital if English writes it so (a name). A spelling that
differs from a much commoner one only by its accents ("pubblicita" beside "pubblicità") is
a slip, and dropped, so autocorrect can put the accent in.

    curl -LO https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_full.txt
    curl -LO https://downloads.tatoeba.org/exports/per_language/deu/deu_sentences.tsv.bz2
    python3 tools/build_dictionary_lang.py de de_full.txt deu_sentences.tsv.bz2 \
        app/src/main/assets/dictionary.txt app/src/main/assets/de/dictionary.txt

Output as tools/build_dictionary.py's: word<TAB>score, most common first, score being log2
of the word's subtitle count (7-25). The generated file is CC BY-SA 4.0.
"""
import bz2, collections, math, re, sys, unicodedata

lang, freq_path, sentences, english_path, out = sys.argv[1:6]

english = {}
with open(english_path, encoding="utf-8") as f:
    for line in f:
        if line.startswith("#") or "\t" not in line:
            continue
        w = line.split("\t")[0]
        k = w.lower()
        if k not in english or w == k:
            english[k] = w

LETTERS = {
    "fr": "a-zàâæçéèêëîïôœùûüÿ",
    "de": "a-zäöüß",
    "es": "a-záéíñóúü",
    "it": "a-zàèéìíîòóùú",
}[lang]
# One-letter words people type alone.
SINGLE = {"fr": "aày", "de": "", "es": "aeouy", "it": "aeièo"}[lang]
WORD = re.compile(rf"[{LETTERS}]+", re.IGNORECASE)
ONLY = re.compile(rf"^[{LETTERS}]+$")
# Kept on the subtitles alone at this count; with Tatoeba agreeing, at this lower one.
ALONE = 400
WITH_TATOEBA = 4
MAX_WORDS = 120000
# A spelling this many times rarer than another differing only in accents is a slip.
ACCENT_SLIP = 8
# Words written against the next with an apostrophe (keep in step with Language.kt). Typed
# without it ("cest", "jai") they turn up in subtitles too: dropped, so autocorrect puts it in.
ELISIONS = {
    "fr": "l d j m n s t c qu jusqu lorsqu puisqu quoiqu quelqu presqu",
    "it": "l d dell all dall nell sull coll un c quest quell bell sant tutt senz anch cos dov com nient s m t v n",
}.get(lang, "").split()

# Tatoeba: which words it uses, and how often each is capitalised away from a sentence's start.
seen = collections.Counter()
upper, lower = collections.Counter(), collections.Counter()
with bz2.open(sentences, "rt", encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        text = parts[2]
        # Sentence starts: the first word, and words after . ! ? or an opening quote.
        prev_end = True
        for m in re.finditer(rf"[{LETTERS}]+|[.!?¡¿:«»\"“”„]", text, re.IGNORECASE):
            t = m.group(0)
            if not t[0].isalpha():
                prev_end = True
                continue
            k = t.lower()
            seen[k] += 1
            if not prev_end:
                (upper if t[0].isupper() else lower)[k] += 1
            prev_end = False

counts = {}
with open(freq_path, encoding="utf-8") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, n = parts[0], int(parts[1])
        if n < WITH_TATOEBA:
            break                              # the file is most common first
        if not ONLY.match(w) or w != w.lower():
            continue
        if len(w) == 1 and w not in SINGLE:
            continue
        if seen[w] == 0 and (n < ALONE or w in english):
            continue
        # Mostly capitalised in mid-sentence (a noun in German, a name): kept so. "Sie" and
        # "sie" are both common, so it takes a strong lead.
        if upper[w] > 0 and upper[w] > 4 * lower[w]:
            cased = w[0].upper() + w[1:]
        elif upper[w] + lower[w] == 0 and english.get(w, w) != w:
            cased = english[w]
        else:
            cased = w
        counts[cased] = n
        if len(counts) >= MAX_WORDS:
            break

def fold(w):
    """In plain letters, as Language.fold: "être" -> "etre", "cœur" -> "coeur", "straße" -> "strasse"."""
    w = w.lower().replace("ß", "ss").replace("œ", "oe").replace("æ", "ae")
    return "".join(c for c in unicodedata.normalize("NFD", w) if not unicodedata.combining(c))

# Per spelling without accents: the commonest form's subtitle count, and its Tatoeba count.
best, best_seen = collections.defaultdict(int), collections.defaultdict(int)
for w, n in counts.items():
    best[fold(w)] = max(best[fold(w)], n)
    best_seen[fold(w)] = max(best_seen[fold(w)], seen[w.lower()])
rows = []
for w, n in counts.items():
    k, f = w.lower(), fold(w)
    # Far rarer than its accented twin in the subtitles, or in Tatoeba's careful sentences.
    if n * ACCENT_SLIP < best[f] and (seen[k] < 3 or seen[k] * 200 < best_seen[f]):
        continue
    if best_seen[f] >= 5 and seen[k] < 3 and seen[k] * 20 < best_seen[f]:
        continue
    # "cest" beside "est": an elision without its apostrophe ("dont" beside "ont" is a word).
    if any(k.startswith(e) and len(k) > len(e) + 1 and counts.get(k[len(e):], 0) > 20 * n
           and seen[k] < 3 for e in ELISIONS):
        continue
    rows.append((max(7, min(25, int(round(math.log2(n))))), w))

rows.sort(key=lambda t: (-t[0], t[1]))
with open(out, "w", encoding="utf-8") as f:
    f.write(f"# Generated by tools/build_dictionary_lang.py ({lang}) from FrequencyWords and Tatoeba. CC BY-SA 4.0.\n")
    for s, w in rows:
        f.write(f"{w}\t{s}\n")
print(f"{len(rows)} words -> {out}")
