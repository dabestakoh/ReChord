package com.music.bitchord.data.lyrics

import java.lang.Character.UnicodeScript
import java.util.Locale

private val referenceSources = setOf(LyricsSource.YOUTUBE_MUSIC, LyricsSource.YOUTUBE_TRANSCRIPT)
private val chineseCatalogues = setOf(LyricsSource.NETEASE, LyricsSource.QQ_MUSIC, LyricsSource.KUGOU)
private val ignoredScripts = setOf(UnicodeScript.LATIN, UnicodeScript.COMMON, UnicodeScript.INHERITED, UnicodeScript.UNKNOWN)
private val latinEvidence = mapOf(
    "en" to setOf("the", "you", "your", "and", "don't", "with", "my", "myself", "you're"),
    "es" to setOf("que", "por", "para", "cuando", "donde", "contigo", "eres", "quiero"),
    "fr" to setOf("avec", "pour", "dans", "vous", "nous", "toujours", "suis", "comme"),
    "pt" to setOf("você", "não", "meu", "minha", "quando", "coração", "saudade", "pela"),
    "it" to setOf("che", "sono", "della", "nella", "perché", "questo", "quando", "anche"),
    "de" to setOf("und", "nicht", "ich", "dein", "weil", "für", "dich", "mein"),
    "id" to setOf("yang", "dengan", "untuk", "aku", "engkau", "kenapa", "tidak", "selalu"),
    "tl" to setOf("ang", "ay", "mga", "iyong", "ikaw", "kaya", "hindi", "bakit", "naman"),
    "hi-Latn" to setOf("mera", "meri", "tera", "teri", "tum", "hai", "hain", "dil", "kya"),
)
private val latinTokens = Regex("""[\p{L}']+""")
private val wordSpace = Regex("""\s+""")

private data class Candidate(
    val result: LyricsRepository.Result,
    val script: UnicodeScript?,
    val language: String?,
    val timing: Int,
    val voices: Int,
) {
    val native: UnicodeScript? get() = script?.takeUnless { it in ignoredScripts }
    val fromChineseCatalogue: Boolean get() = result.source in chineseCatalogues
    val languageKey: String? get() = language ?: script?.name
}

/** Evidence from the recording and its lyric candidates, with input order as the final tiebreaker. */
internal fun chooseLyrics(
    title: String,
    artist: String,
    album: String?,
    results: List<LyricsRepository.Result>,
): LyricsRepository.Result? {
    val candidates = results.map { result ->
        val rows = result.lines.filterNot { it.isGap }
        val sample = rows.joinToString(" ") { it.text }
        val script = lyricScript(sample)
        Candidate(
            result, script, if (script == UnicodeScript.LATIN) latinLanguage(sample) else null,
            when {
                rows.any { it.isWordSynced } -> if (looksSyllableSplit(rows)) 1 else 2
                rows.any { it.timeMs > 0L || it.sungUntilMs != null } -> 1
                else -> 0
            },
            when {
                rows.any { it.alignment == LyricAlignment.End } && rows.any { it.alignment != LyricAlignment.End } -> 2
                rows.any { it.alignment == LyricAlignment.End || it.background != null } -> 1
                else -> 0
            },
        )
    }.filter { candidate -> candidate.result.lines.any { !it.isGap } }
    val exact = candidates.filter { it.result.source in referenceSources }
    val catalogue = candidates.filterNot { it.fromChineseCatalogue }
    val metadataScript = lyricScript(listOfNotNull(title, artist, album).joinToString(" "))
    var reference = metadataScript?.takeUnless { it in ignoredScripts }
        ?: exact.firstNotNullOfOrNull { it.native }
    if (reference == UnicodeScript.HAN && candidates.any { it.script == UnicodeScript.KATAKANA }) {
        reference = UnicodeScript.KATAKANA
    }

    val expected = reference?.name ?: majority(catalogue.mapNotNull { it.languageKey })
    val chinese = expected == UnicodeScript.HAN.name
    val pool = candidates.filter { candidate ->
        !candidate.fromChineseCatalogue || chinese ||
            (candidate.timing == 2 && candidate.script != UnicodeScript.HAN &&
                expected != null && candidate.languageKey == expected)
    }.ifEmpty { candidates.filter { it.fromChineseCatalogue && it.script != UnicodeScript.HAN } }

    val majority = reference?.name ?: majority(pool.mapNotNull { it.languageKey }, minVotes = 2)
    val nativeVotes = pool.mapNotNull { it.native }.groupingBy { it }.eachCount()
    val hindi = candidates.any { it.script == UnicodeScript.DEVANAGARI || it.language == "hi-Latn" } ||
        reference == UnicodeScript.DEVANAGARI
    fun Candidate.matchesHindi() = hindi && (
        script == UnicodeScript.DEVANAGARI || language == "hi-Latn" ||
            (script == UnicodeScript.LATIN && language == null)
        )
    val byLanguage = compareBy<Candidate> { candidate ->
        when {
            candidate.matchesHindi() -> 3
            majority == null -> when (candidate.native) {
                null -> 1
                UnicodeScript.KATAKANA, UnicodeScript.HANGUL -> 2
                else -> if ((nativeVotes[candidate.native] ?: 0) >= 2) 2 else 0
            }
            candidate.languageKey == majority -> 3
            reference == UnicodeScript.KATAKANA && candidate.script == UnicodeScript.HAN -> 2
            candidate.script == UnicodeScript.LATIN && candidate.language == null && majority in latinEvidence -> 2
            candidate.script == null -> 1
            else -> 0
        }
    }.thenBy {
        if (it.matchesHindi() && it.result.source in setOf(LyricsSource.BETTER_LYRICS, LyricsSource.LYRICS_PLUS)) 1 else 0
    }.thenBy { it.timing }.thenBy { it.voices }.thenBy {
        if (chinese && it.fromChineseCatalogue) 1 else 0
    }
    return pool.maxWithOrNull(byLanguage)?.result
}

private fun looksSyllableSplit(lines: List<LyricLine>): Boolean {
    var checked = 0
    var flagged = 0
    for (line in lines) {
        val letters = line.text.filter(Char::isLetter)
        if (letters.isEmpty() || letters.count { it.code < 0x250 } < letters.length * 0.9) continue
        val lengths = line.text.split(wordSpace).map { token -> token.count(Char::isLetter) }.filter { it > 0 }
        if (lengths.size < 4) continue
        checked++
        if (lengths.count { it <= 3 } >= lengths.size * 0.8 && lengths.average() < 2.8) flagged++
    }
    return checked >= 4 && flagged >= checked * 0.65
}

private fun majority(values: List<String>, minVotes: Int = 1): String? {
    val counts = values.groupingBy { it }.eachCount()
    val highest = counts.values.maxOrNull() ?: return null
    return counts.entries.singleOrNull { it.value == highest && it.value >= minVotes }?.key
}

private fun lyricScript(text: String): UnicodeScript? {
    val counts = mutableMapOf<UnicodeScript, Int>()
    text.codePoints().forEach { point ->
        if (Character.isLetter(point)) {
            val script = when (val value = UnicodeScript.of(point)) {
                UnicodeScript.HIRAGANA -> UnicodeScript.KATAKANA
                else -> value
            }
            if (script != UnicodeScript.COMMON && script != UnicodeScript.INHERITED) {
                counts[script] = (counts[script] ?: 0) + 1
            }
        }
    }
    if (UnicodeScript.KATAKANA in counts) return UnicodeScript.KATAKANA
    if ((counts[UnicodeScript.HANGUL] ?: 0) * 4 >= counts.values.sum() && counts.isNotEmpty()) {
        return UnicodeScript.HANGUL
    }
    return counts.maxByOrNull { it.value }?.key
}

private fun latinLanguage(text: String): String? {
    val tokens = latinTokens.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()
    val scores = latinEvidence.mapValues { (_, evidence) -> evidence.count { it in tokens } }
    val best = scores.values.maxOrNull()?.takeIf { it >= 2 } ?: return null
    return scores.entries.singleOrNull { it.value == best }?.key
}
