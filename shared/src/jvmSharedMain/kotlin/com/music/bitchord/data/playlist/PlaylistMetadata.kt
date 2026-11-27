package com.music.bitchord.data.playlist

import com.music.bitchord.data.model.Song
import kotlin.math.abs

/**
 * Conservative hints from the YouTube Music browse header, not an authoritative
 * playlist revision. Missing or rounded metadata never proves a playlist unchanged.
 */
object PlaylistMetadata {
    private val countPattern = Regex("""(?i)\b([0-9][0-9,]*)\s+(?:songs?|tracks?)\b""")
    private val hoursPattern = Regex("""(?i)\b(\d+)\s*(?:hours?|hrs?|hr)\b""")
    private val minutesPattern = Regex("""(?i)\b(\d+)\s*(?:minutes?|mins?|min)\b""")
    private val approximatePattern = Regex("""(?i)(?:\babout\b|\bover\b|\bmore than\b|\d\s*\+)""")

    data class Summary(val count: Int?, val durationSeconds: Long?)

    fun fromHeader(subtitle: String?): Summary {
        if (subtitle.isNullOrBlank()) return Summary(null, null)
        val count = countPattern.find(subtitle)?.groupValues?.get(1)
            ?.replace(",", "")?.toIntOrNull()
        // YouTube sometimes shows approximations like '6+ hours'; ignore those.
        val approximate = approximatePattern.containsMatchIn(subtitle)
        val hours = hoursPattern.find(subtitle)?.groupValues?.get(1)?.toLongOrNull()
        val minutes = minutesPattern.find(subtitle)?.groupValues?.get(1)?.toLongOrNull()
        // An hours-only label is often rounded/truncated (e.g. "2 hours"
        // can mean nearly 3). Never treat it as an exact duration.
        val duration = if (!approximate && minutes != null && minutes < 60L) {
            (hours ?: 0L) * 3600L + (minutes ?: 0L) * 60L
        } else null
        return Summary(count, duration)
    }

    /**
     * Compare the fetched first page against the beginning of a complete cached
     * snapshot. Detects newly inserted songs and reordering even when headers
     * omit the count. Not a substitute for the six-hour complete verification:
     * edits beyond the first page may remain invisible.
     */
    fun firstPageChanged(
        cached: List<Song>,
        fetched: List<Song>,
        hasContinuation: Boolean = true,
    ): Boolean {
        if (fetched.isEmpty()) return false // Empty/ambiguous page is not proof of deletion.
        if (!hasContinuation && cached.size != fetched.size) return true
        if (cached.size < fetched.size) return true
        return fetched.indices.any { index ->
            playlistEntryKey(cached[index]) != playlistEntryKey(fetched[index])
        }
    }

    /** Avoid consecutive full scans after a quick app switch; tolerate clock resets. */
    fun shouldRunReturnCheck(now: Long, previous: Long?, cooldownMs: Long): Boolean =
        previous == null || now < previous || now - previous >= cooldownMs

    fun hasChanged(songs: List<Song>, headerSubtitle: String?): Boolean {
        val summary = fromHeader(headerSubtitle)
        if (summary.count != null && summary.count != songs.size) return true
        val duration = summary.durationSeconds ?: return false
        var total = 0L
        for (song in songs) {
            val parts = song.durationText?.split(':') ?: return false
            if (parts.size !in 2..3) return false
            val values = parts.map { it.toLongOrNull() ?: return false }
            if (values.drop(1).any { it >= 60L }) return false
            total += values.fold(0L) { acc, value -> acc * 60L + value }
        }
        // Header duration may be rounded to the minute; avoid false positives
        // from a few seconds of formatting differences.
        return abs(total - duration) >= 120L
    }

    /** Result of merging a freshly fetched Page 1 into an existing cached playlist snapshot. */
    data class HeadMergeResult(
        val merged: List<Song>,
        val anchored: Boolean,
        val complete: Boolean,
    )

    private fun entriesMatch(a: Song, b: Song): Boolean {
        if (playlistEntryKey(a) == playlistEntryKey(b)) return true
        // A locally liked/added song starts without YouTube's setVideoId and later
        // receives one from the server; treat them as the same entry.
        return (a.setVideoId.isNullOrBlank() != b.setVideoId.isNullOrBlank()) && a.videoId == b.videoId
    }

    /**
     * Merges a freshly fetched first page onto the front of [cached] so newly liked
     * or prepended tracks appear in < 1 s without waiting for all continuation pages.
     *
     * - If [!hasContinuation], [fetchedFirstPage] is the entire playlist (`complete = true`).
     * - Otherwise, searches for a contiguous overlap anchor of up to 3 tracks near the tail
     *   of [fetchedFirstPage] inside the head window of [cached], preserving all remaining
     *   cached tail tracks while deduplicating moved or locally-added entries.
     * - Returns `anchored = false` if no safe overlap is found (caller falls back to full scan).
     */
    fun mergeFirstPage(
        cached: List<Song>,
        fetchedFirstPage: List<Song>,
        hasContinuation: Boolean,
    ): HeadMergeResult {
        if (!hasContinuation) {
            return HeadMergeResult(merged = fetchedFirstPage, anchored = true, complete = true)
        }
        if (fetchedFirstPage.isEmpty() || cached.isEmpty()) {
            return HeadMergeResult(merged = cached, anchored = false, complete = false)
        }
        val anchorLen = minOf(3, fetchedFirstPage.size, cached.size)
        val searchLimit = minOf(cached.size, fetchedFirstPage.size + 60)
        var anchorEndInCached = -1
        outer@ for (fetchedStart in (fetchedFirstPage.size - anchorLen) downTo 0) {
            for (cachedStart in 0..(searchLimit - anchorLen)) {
                var matched = true
                for (offset in 0 until anchorLen) {
                    if (!entriesMatch(fetchedFirstPage[fetchedStart + offset], cached[cachedStart + offset])) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    // Advance anchorEndInCached to cover the end of fetchedFirstPage.
                    val remainingInFetched = fetchedFirstPage.size - (fetchedStart + anchorLen)
                    anchorEndInCached = (cachedStart + anchorLen - 1 + remainingInFetched)
                        .coerceAtMost(cached.lastIndex)
                    break@outer
                }
            }
        }
        if (anchorEndInCached < 0) {
            return HeadMergeResult(merged = cached, anchored = false, complete = false)
        }
        val headKeys = fetchedFirstPage.mapTo(HashSet(fetchedFirstPage.size)) { playlistEntryKey(it) }
        val headVideoIds = fetchedFirstPage.mapTo(HashSet(fetchedFirstPage.size)) { it.videoId }
        val tailStart = anchorEndInCached + 1
        val tail = if (tailStart < cached.size) {
            cached.subList(tailStart, cached.size).filterNot { entry ->
                playlistEntryKey(entry) in headKeys ||
                    (entry.setVideoId.isNullOrBlank() && entry.videoId in headVideoIds)
            }
        } else emptyList()
        return HeadMergeResult(
            merged = fetchedFirstPage + tail,
            anchored = true,
            complete = false,
        )
    }
}
