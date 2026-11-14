package io.github.alan_morford.prekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The other languages' dictionaries that ship, their keys, and what autocorrect makes of them. */
class LanguagesTest {

    companion object {
        val c = HashMap<String, Corrector>()
        @BeforeClass @JvmStatic fun load() {
            for (lang in Language.ALL) {
                if (lang.pinyin) continue
                val corr = Corrector(lang.dictionaries.asSequence().flatMap { File("src/main/assets/$it").readLines() }, lang)
                val nw = File("src/main/assets/" + lang.asset(Language.NEXT_WORDS)).bufferedReader()
                    .useLines { NextWords(it) }
                corr.follows = nw::follows
                c[lang.id] = corr
            }
        }
    }

    private fun fixes(lang: String, typed: String, want: String) = assertEquals("$lang $typed", want, c[lang]!!.correct(typed))
    private fun keeps(lang: String, typed: String) = assertNull("$lang $typed", c[lang]!!.correct(typed))

    @Test fun everyLanguageHasItsFiles() {
        for (lang in Language.ALL.filter { !it.pinyin }) for (name in listOf(Language.DICTIONARY, Language.NEXT_WORDS, Language.EMOJI_WORDS)) {
            assertTrue("${lang.id} $name", File("src/main/assets/" + lang.asset(name)).exists())
        }
        // Tatoeba has few sentences in some languages to confirm the subtitles' words with:
        // their dictionaries are smaller.
        val small = setOf("da", "no", "sv", "uk")
        for ((id, corr) in c) assertTrue("$id ${corr.size}", corr.size > if (id in small) 25000 else 50000)
        // A regional English is the US one, changed: colour in, color out.
        assertTrue(c["en_GB"]!!.isKnown("colour")); assertTrue(!c["en_GB"]!!.isKnown("color"))
    }

    @Test fun regionalEnglishSpells() {
        keeps("en_GB", "colour"); keeps("en_GB", "organise"); fixes("en_GB", "colur", "colour")
        keeps("en_CA", "colour"); keeps("en_CA", "organize")
        keeps("en_AU", "colour"); keeps("en_US", "color")
        // Contractions are English's, in every English.
        fixes("en_GB", "dont", "don't")
    }

    @Test fun accentsGoIn() {
        fixes("fr", "etre", "être"); fixes("fr", "deja", "déjà"); fixes("fr", "francais", "français")
        fixes("fr", "coeur", "cœur"); fixes("fr", "Etre", "Être")
        fixes("de", "fur", "für"); fixes("de", "strasse", "Straße"); fixes("de", "konnen", "können"); fixes("de", "uber", "über")
        fixes("es", "espanol", "español"); fixes("es", "manana", "mañana"); fixes("es", "tambien", "también")
        fixes("it", "perche", "perché"); fixes("it", "citta", "città"); fixes("it", "piu", "più")
        fixes("nl", "ideeen", "ideeën"); fixes("nl", "geinteresseerd", "geïnteresseerd")
    }

    @Test fun luneOsLanguages() {
        // Their own letters, and the keys keep their places: backspace and Return the size they are in English.
        assertEquals("qwertyuiopå", Keymap.letters(Language.byId("da")!!)[0].joinToString("") { it.key.text })
        assertEquals("йцукенгшщзх", Keymap.letters(Language.byId("ru")!!)[0].joinToString("") { it.key.text })
        for (lang in Language.ALL) {
            val rows = Keymap.letters(lang)
            assertEquals(lang.id, 10f, rows[1].sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
            assertEquals(lang.id, 1f, rows[1].last().weight)
            assertEquals(lang.id, 1f, rows[2].last().weight)
        }
        // Accents go in; й and ї are letters of their own, not accented и and і.
        fixes("cs", "dekuji", "děkuji"); fixes("pl", "dziekuje", "dziękuję"); fixes("pt", "nao", "não")
        fixes("sv", "hor", "hör")
        // Russian writes ё as е as often as not: both are right.
        keeps("ru", "еще"); keeps("ru", "ещё")
        assertEquals('й', Language.base('й')); assertEquals('l', Language.base('ł'))
        keeps("ru", "мой"); keeps("ru", "мои"); keeps("uk", "п'ять")
        keeps("ar", "شكرا"); keeps("he", "שלום")
    }

    @Test fun dutchTypos() {
        fixes("nl", "alstublief", "alstublieft"); fixes("nl", "hardstikke", "hartstikke")
        fixes("nl", "vandag", "vandaag"); fixes("nl", "gezelig", "gezellig")
        keeps("nl", "fiets"); keeps("nl", "een"); keeps("nl", "één")
    }

    @Test fun wordsThatDifferOnlyByAccentAreBothKept() {
        keeps("fr", "a"); keeps("fr", "à"); keeps("fr", "ou"); keeps("fr", "où")
        keeps("es", "si"); keeps("es", "sí"); keeps("es", "el"); keeps("es", "él")
        keeps("it", "e"); keeps("it", "è"); keeps("it", "si"); keeps("it", "sì")
    }

    @Test fun germanNounsGetTheirCapital() {
        fixes("de", "haus", "Haus"); fixes("de", "zeit", "Zeit")
        // Sie and sie are both words.
        keeps("de", "sie"); keeps("de", "Sie")
    }

    @Test fun elisions() {
        fixes("fr", "cest", "c'est"); fixes("fr", "jai", "j'ai"); fixes("fr", "lhomme", "l'homme")
        fixes("fr", "l'hommme", "l'homme")
        keeps("fr", "l'homme"); keeps("fr", "qu'il")
        assertTrue(c["fr"]!!.isKnown("l'homme"))
        assertEquals("homme", c["fr"]!!.spelling("l'homme"))
        fixes("it", "lanno", "l'anno"); keeps("it", "dell'anno")
        assertTrue(c["fr"]!!.suggestions("l'hom").all { it.startsWith("l'") })
    }

    @Test fun typosNearTheirKeys() {
        // On AZERTY, A is where Q is on QWERTY: "qvec" is a slip for "avec".
        fixes("fr", "qvec", "avec")
        fixes("de", "nicjt", "nicht")
        fixes("es", "qie", "que"); fixes("it", "cpme", "come")
        assertTrue(c["fr"]!!.adjacent('a', 'z')); assertTrue(c["fr"]!!.adjacent('q', 's'))
        assertTrue(c["de"]!!.adjacent('z', 't')); assertTrue(c["de"]!!.adjacent('y', 's'))
        assertTrue(!c["en_US"]!!.adjacent('z', 't'))
    }

    @Test fun noEnglishContractionsElsewhere() {
        keeps("fr", "dont")     // French for "whose"; English would make it "don't"
    }

    @Test fun layoutsHaveEveryLetterOnce() {
        for (lang in Language.ALL) {
            val keys = Keymap.letters(lang).flatten().filter { it.drawn && it.key.kind == Kind.CHAR }.map { it.key.text }
            assertEquals(lang.id, keys.size, keys.toSet().size)
            // The Latin layouts have all of a to z.
            val latin = keys.filter { it.length == 1 && it[0] in 'a'..'z' }
            if (lang.layout.rows[0][0] in 'a'..'z') assertEquals(lang.id, ('a'..'z').map { it.toString() }, latin.sorted())
        }
        assertEquals("azertyuiop", Keymap.letters(Language.byId("fr")!!)[0].joinToString("") { it.key.text })
        assertEquals("qwertzuiop", Keymap.letters(Language.byId("de")!!)[0].joinToString("") { it.key.text })
    }

    @Test fun alternatesStayInPlace() {
        val us = Keymap.letters(Language.EN_US)
        for (lang in Language.ALL) {
            val rows = Keymap.letters(lang)
            // As far as the Pre's own row goes; keys past it (å, æ, ø...) print none.
            for (r in rows.indices) {
                // The comma (whose alternate is _) aside: it keeps its place at the row's end.
                val ours = rows[r].filter { it.key.kind == Kind.CHAR && it.key.alt != "_" }.map { it.key.alt }
                val pre = us[r].filter { it.key.kind == Kind.CHAR && it.key.alt != "_" }.map { it.key.alt }
                assertEquals(lang.id, pre, ours.take(pre.size))
                assertTrue(lang.id, ours.drop(pre.size).all { it == null })
            }
        }
    }

    @Test fun ownAccentsLeadTheHolds() {
        fun hold(lang: String, letter: String) = Keymap.letters(Language.byId(lang)!!).flatten()
            .first { it.key.text == letter }.key.extended!!
        assertEquals(listOf("e", "é", "è", "ê", "ë"), hold("fr", "e").take(5))
        assertEquals(listOf("s", "ß"), hold("de", "s").take(2))
        assertEquals(listOf("n", "ñ"), hold("es", "n").take(2))
        assertEquals(listOf("e", "è", "é"), hold("it", "e").take(3))
        // US English keeps LuneOS's order.
        assertEquals(listOf("e", "è", "é"), hold("en_US", "e").take(3))
    }

    @Test fun symPageRepeatsNoLanguagesLetters() {
        val sym = Keymap.symbols.flatten().filter { it.drawn && it.key.kind == Kind.CHAR }
            .flatMap { listOf(it.key.text) + (it.key.extended ?: emptyList()) }
        for (lang in Language.ALL) {
            val letters = Keymap.letters(lang).flatten().filter { it.drawn && it.key.kind == Kind.CHAR }
                .flatMap { listOfNotNull(it.key.text, it.key.alt) + (it.key.extended ?: emptyList()) }.toSet()
            val repeated = sym.filter { it in letters && it != "." && !(it.length == 1 && it[0].isDigit()) }
            assertEquals(lang.id, emptyList<String>(), repeated)
        }
    }

    @Test fun swipesSpellAccentedWordsWithPlainLetters() {
        assertEquals("etre", Language.fold("être"))
        assertEquals("strasse", Language.fold("straße"))
        assertEquals("coeur", Language.fold("cœur"))
        assertEquals("Ecole", Language.fold("École"))
    }
}
