package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Lyrics from SimpMusic's community database, keyed on the YouTube video id.
 *
 * That key is what makes it worth having: every other provider matches on
 * title and artist and can hand back a different edit of the same song, which
 * drifts out of sync a verse in. This one is looking up the exact track that
 * is playing.
 *
 * Two caveats, both seen in the wild:
 *  - the host geoblocks some regions outright, answering 403 with a "Access
 *    denied from your region" body rather than a network error, so a miss here
 *    can be permanent for a given user and the chain must carry on past it;
 *  - the rich sync is served HTML-escaped — see [EnhancedLrc.decodeEntities].
 */
object SimpMusicLyrics {

    private const val BASE = "https://api-lyrics.simpmusic.org/v1/"

    private const val FALLBACK = "https://musicfy-yt-music-server.onrender.com/v1/"

    /** Duration slack when the database holds several cuts of one video. */
    private const val DURATION_TOLERANCE_SECONDS = 10

    suspend fun lyrics(videoId: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            if (!YOUTUBE_ID.matches(videoId)) return@withContext null
            val seconds = (durationMs / 1000).toInt()
            for (host in listOf(BASE, FALLBACK)) {
                val body = lyricsGet(host + videoId, extended = true) ?: continue
                val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()
                if (response?.success != true) continue
                val tracks = response.data.orEmpty()
                    .filter { seconds <= 0 || abs((it.duration ?: 0) - seconds) <= DURATION_TOLERANCE_SECONDS }
                    .sortedBy { abs((it.duration ?: 0) - seconds) }
                for (track in tracks) {
                    val lines = track.richSyncLyrics?.let(::parseTimedLyrics)
                        ?: track.syncedLyrics?.let(::parseTimedLyrics)
                        ?: track.plainLyrics?.let(::plainLyricLines)
                    if (lines != null) return@withContext lines
                }
            }
            null
        }

    @Serializable
    data class Response(
        val success: Boolean = false,
        val data: List<Track>? = null,
    )

    @Serializable
    data class Track(
        val duration: Int? = null,
        val richSyncLyrics: String? = null,
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
    )
}
