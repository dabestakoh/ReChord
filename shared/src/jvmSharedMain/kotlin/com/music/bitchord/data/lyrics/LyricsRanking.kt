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

private data class Candidate(
    val result: LyricsRepository.Result,
    val script: UnicodeScript?,
    val language: String?,
    val timing: Int,
    val duet: Boolean,
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
                rows.any { it.isWordSynced } -> 2
                rows.any { it.timeMs > 0L || it.sungUntilMs != null } -> 1
                else -> 0
            },
            rows.any { it.alignment == LyricAlignment.End } && rows.any { it.alignment != LyricAlignment.End },
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

    val majority = expected ?: majority(pool.mapNotNull { it.languageKey })
    val byLanguage = compareBy<Candidate> { candidate ->
        when {
            majority == null -> if (candidate.native != null) 2 else 1
            candidate.languageKey == majority -> 3
            reference == UnicodeScript.KATAKANA && candidate.script == UnicodeScript.HAN -> 2
            candidate.script == UnicodeScript.LATIN && candidate.language == null && majority in latinEvidence -> 2
            candidate.script == null -> 1
            else -> 0
        }
    }.thenBy { it.timing }.thenBy { it.duet }
    return pool.maxWithOrNull(byLanguage)?.result
}

private fun majority(values: List<String>): String? {
    val counts = values.groupingBy { it }.eachCount()
    val highest = counts.values.maxOrNull() ?: return null
    return counts.entries.singleOrNull { it.value == highest }?.key
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
    val native = counts.filterKeys { it !in ignoredScripts }.maxByOrNull { it.value }
    if (native != null && native.value * 4 >= (counts[UnicodeScript.LATIN] ?: 0)) return native.key
    return counts.maxByOrNull { it.value }?.key
}

private fun latinLanguage(text: String): String? {
    val tokens = latinTokens.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toSet()
    val scores = latinEvidence.mapValues { (_, evidence) -> evidence.count { it in tokens } }
    val best = scores.values.maxOrNull()?.takeIf { it >= 2 } ?: return null
    return scores.entries.singleOrNull { it.value == best }?.key
}
