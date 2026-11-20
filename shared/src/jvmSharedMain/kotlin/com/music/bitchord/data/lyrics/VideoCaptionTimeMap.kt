package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.VersionTimeMap
import kotlin.math.abs

private val CaptionWords = Regex("[\\p{L}\\p{M}\\p{N}]+")
private data class TimedWords(val timeMs: Long, val words: List<String>)

private fun List<LyricLine>.timedWords(): List<TimedWords> = asSequence()
    .filter { !it.isGap }
    .sortedBy { it.timeMs }
    .take(240)
    .mapNotNull { line ->
        val words = CaptionWords.findAll(line.text.lowercase()).take(32).map { it.value }.toList()
        words.takeIf { it.isNotEmpty() }?.let { TimedWords(line.timeMs, it) }
    }.toList()

/** Build anchors from distinctive lyric phrases at the start of video captions. */
fun videoCaptionTimeMap(
    lyrics: List<LyricLine>,
    captions: List<LyricLine>,
    audioDurationMs: Long,
    lyricsOffsetMs: Int,
): VersionTimeMap? {
    if (lyrics.none { it.timeMs > 0 } || captions.none { it.timeMs > 0 }) return null
    val source = lyrics.timedWords()
    val target = captions.timedWords()
    val duplicates = source.groupingBy { it.words }.eachCount()
    val allCaptionWords = target.flatMap { it.words }
    val starts = buildList {
        var index = 0
        for (cue in target) {
            add(cue to index)
            index += cue.words.size
        }
    }.groupBy { it.first.words.first() }

    val anchors = buildList<Pair<Long, Long>> {
        for (line in source) {
            if (line.words.size < 2 || line.words.sumOf { it.length } < 10 || duplicates[line.words] != 1) continue
            val matches = starts[line.words.first()].orEmpty().asSequence().filter { (_, start) ->
                start + line.words.size <= allCaptionWords.size &&
                    line.words.indices.all { allCaptionWords[start + it] == line.words[it] }
            }.take(2).toList()
            val cue = matches.singleOrNull()?.first ?: continue
            val audio = (line.timeMs + lyricsOffsetMs).coerceAtLeast(0L)
            val latest = lastOrNull()
            if (abs(cue.timeMs - audio) > 60_000L ||
                (latest != null && (audio <= latest.first || cue.timeMs <= latest.second))
            ) continue
            add(audio to cue.timeMs)
        }
    }
    if (anchors.size < 3 || anchors.last().first - anchors.first().first < 8_000L) return null

    val segments = buildList {
        val (firstAudio, firstVideo) = anchors.first()
        // Extend the first measured offset backwards, down to a valid timestamp.
        val sharedLead = minOf(firstAudio, firstVideo)
        if (sharedLead > 0L) add(VersionTimeMap.Segment(firstAudio - sharedLead, firstVideo - sharedLead, sharedLead))
        anchors.zipWithNext().forEach { (start, end) ->
            // Extra footage between anchors is skipped at the following anchor.
            val length = minOf(end.first - start.first, end.second - start.second)
            add(VersionTimeMap.Segment(start.first, start.second, length))
        }
        val (lastAudio, lastVideo) = anchors.last()
        add(VersionTimeMap.Segment(lastAudio, lastVideo, (audioDurationMs - lastAudio).coerceAtLeast(1L)))
    }
    return VersionTimeMap(segments)
}
