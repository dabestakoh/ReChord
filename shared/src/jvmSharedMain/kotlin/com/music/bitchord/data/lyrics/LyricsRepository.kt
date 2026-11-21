package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Concurrent catalogue lookup with recording identity and staged fallbacks. */
object LyricsRepository {

    /** Lyrics, and which source they turned out to come from. */
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    /** The saved [order] breaks ties between equally suitable results from enabled [sources]. */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.offered.toSet(),
        order: List<LyricsSource> = LyricsSource.offered,
        manualSelection: Boolean = false,
        isrc: String? = null,
        /** Called from the provider job itself, including for lazily-started providers. */
        onSourceStarted: ((LyricsSource) -> Unit)? = null,
        /**
         * Reports a completed provider attempt. A null result is a genuine miss;
         * a cancelled race loser is deliberately not reported as one.
         */
        onSourceResult: ((LyricsSource, Result?) -> Unit)? = null,
        /** Lets callers turn a cancelled race loser back into "not fetched". */
        onSourceCancelled: ((LyricsSource) -> Unit)? = null,
        /** Reports a provider that timed out or errored rather than returning a genuine miss. */
        onSourceError: ((LyricsSource) -> Unit)? = null,
    ): Result? = coroutineScope {
        val sequence = (order + LyricsSource.offered)
            .distinct()
            .filter { it in sources && !it.hidden }

        val searchTitle = title.forLyricsSearch()
        val searchArtist = artist.artistForLyricsSearch()

        val known = isrc?.takeIf { it.isNotBlank() } ?: isrcs[videoId]
        val documents = SharedDocuments(this)

        suspend fun attempt(source: LyricsSource): Result? {
            onSourceStarted?.invoke(source)
            return try {
                var timedOut = true
                val fetched = withTimeoutOrNull(35_000L) {
                    val hit = if (known == null && source == LyricsSource.BINI_LYRICS) {
                        identify(videoId, searchTitle, searchArtist, durationMs, album, sequence)
                    } else null
                    val lines = fetch(source, videoId, title, artist, durationMs, album, known, hit, documents::get)
                    timedOut = false
                    lines
                }
                if (timedOut) {
                    if (onSourceError != null) onSourceError.invoke(source) else onSourceResult?.invoke(source, null)
                    return null
                }
                val answer = fetched?.takeIf { lines -> lines.any { !it.isGap } }?.let { result(source, it) }
                onSourceResult?.invoke(source, answer)
                answer
            } catch (cancelled: CancellationException) {
                onSourceCancelled?.invoke(source)
                throw cancelled
            } catch (_: Exception) {
                if (onSourceError != null) onSourceError.invoke(source) else onSourceResult?.invoke(source, null)
                null
            }
        }

        try {
            if (manualSelection) {
                return@coroutineScope sequence.singleOrNull()?.let { withContext(Dispatchers.IO) { attempt(it) } }
            }
            val candidates = sequence.filter { it in LyricsSource.standard && it != LyricsSource.YOUTUBE_AUTO_CAPTIONS }
                .map { source -> async(Dispatchers.IO) { attempt(source) } }
                .awaitAll().filterNotNull()
            chooseLyrics(title, artist, album, candidates)
                ?: LyricsSource.YOUTUBE_AUTO_CAPTIONS.takeIf { it in sequence && YOUTUBE_ID.matches(videoId) }
                    ?.let { withContext(Dispatchers.IO) { attempt(it) } }
                ?: sequence.filterNot { it in LyricsSource.standard }
                    .firstNotNullOfOrNull { withContext(Dispatchers.IO) { attempt(it) } }
        } finally {
            documents.cancel()
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        /** What [identify] already found, where it ran; saves a second search. */
        hit: BiniLyrics.Hit?,
        /** Fetches a document once per lookup, however many sources want it. */
        get: suspend (String) -> String?,
    ): List<LyricLine>? {
        val cleanTitle = title.forLyricsSearch()
        val cleanArtist = artist.artistForLyricsSearch()
        return when (source) {
            LyricsSource.LRC_RED -> LrcRed.lyrics(cleanTitle, cleanArtist, durationMs, isrc, get)
            LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
            LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
            LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc, videoId)
            LyricsSource.BINI_LYRICS ->
                (hit?.let { BiniLyrics.lyricsFor(it, get) }
                    ?: BiniLyrics.lyrics(cleanTitle, cleanArtist, durationMs, album, isrc, get))
                    ?.also { remember(videoId, it.isrc) }
                    ?.lines
            LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)
            LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
            LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
            LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
            LyricsSource.YOUTUBE_AUTO_CAPTIONS -> YouTubeTranscriptLyrics.autoCaptions(videoId)
            LyricsSource.NETEASE -> DirectCatalogLyrics.netEase(title, artist, durationMs)
            LyricsSource.QQ_MUSIC -> DirectCatalogLyrics.qqMusic(title, artist, durationMs)
            LyricsSource.DEEZER -> DirectCatalogLyrics.deezer(title, artist, durationMs)
            LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs, album)
            LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
            LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
            LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
            LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
            LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
            LyricsSource.GENIUS -> Genius.lyrics(cleanTitle, cleanArtist)
        }
    }

    /**
     * Whichever source won, its lines get the same last pass: the answering
     * vocal split off the lead so it can be drawn under it. Done here rather
     * than in each parser because most of them write it as a bracket and only
     * [TtmlLyrics] knows it structurally — [withBackgroundVocals] leaves that
     * one's own split alone.
     */
    private fun result(source: LyricsSource, lines: List<LyricLine>) =
        Result(source, lines.withBackgroundVocals())

    /**
     * Longest the lookup will wait to find out which recording this is.
     *
     * Short on purpose. Knowing the recording makes every match better, but not
     * knowing it only puts things back where they were a release ago, and a
     * lyrics panel sitting empty because one host is having a slow morning is a
     * worse failure than a fuzzy match.
     */
    private const val IDENTIFY_TIMEOUT_MS = 2_500L

    /**
     * The one request made before the race: which recording is this?
     *
     * Skipped entirely when the source that answers it is switched off, and
     * given up on rather than waited out — see [IDENTIFY_TIMEOUT_MS]. What it
     * finds is remembered, so a track pays for this once rather than once per
     * lookup, and the hit is handed back so [BiniLyrics] need not search twice.
     */
    private suspend fun identify(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sequence: List<LyricsSource>,
    ): BiniLyrics.Hit? {
        if (LyricsSource.BINI_LYRICS !in sequence) return null
        val hit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
            runCatching { BiniLyrics.identify(title, artist, durationMs, album) }.getOrNull()
        }
        if (hit == null) return null
        remember(videoId, hit.isrc)
        return hit
    }

    /** How many recordings to keep in hand; see [isrcs]. */
    private const val REMEMBERED = 100

    /**
     * The recording behind a video id, once something has worked it out.
     *
     * Bounded, least-recently-used, and in memory only. This is a shortcut,
     * not a store: losing it costs one fuzzy match, which is what every lookup
     * did before any of this, and keeping it on disk would mean keeping a
     * wrong answer on disk too.
     */
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED
        },
    )

    private fun remember(videoId: String, isrc: String?) {
        if (isrc.isNullOrBlank() || videoId.isEmpty()) return
        isrcs.put(videoId, isrc)
    }

    /**
     * Documents fetched during one lookup, by URL, so two sources after the
     * same file make one request between them. That is the ordinary case, not
     * an edge: [BiniLyrics]' search answers with an [LrcRed] URL, and [LrcRed],
     * handed the ISRC that search found, asks for exactly that URL too.
     *
     * Each download runs in the lookup's own scope rather than the asking
     * source's, so the source that started it losing the race doesn't take
     * the other one's copy down with it; [cancel] ends whatever is left.
     */
    private class SharedDocuments(private val scope: CoroutineScope) {
        private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

        suspend fun get(url: String): String? =
            inFlight.computeIfAbsent(url) { scope.async(Dispatchers.IO) { lyricsGet(url) } }.await()

        fun cancel() = inFlight.values.forEach { it.cancel() }
    }
}
