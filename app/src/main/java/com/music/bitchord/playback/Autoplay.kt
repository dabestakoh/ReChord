package com.music.bitchord.playback

import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.TopPicks
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Module tracks do not
 * carry YouTube ids, so they are matched on YouTube before the radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

internal fun shouldResumeAutoplay(ended: Boolean, playWhenReady: Boolean, hasNext: Boolean): Boolean =
    ended && playWhenReady && hasNext

/** Keeps YouTube's radio cursor with the queue that started it. */
internal class AutoplayRadio(
    private val page: suspend (String, String?, Boolean) -> YtMusicRepository.SongPage? =
        { id, token, video -> YtMusicRepository.autoplayRadioPage(id, token, video).getOrNull() },
    private val related: suspend (String) -> List<Song> =
        { YtMusicRepository.relatedSongs(it).getOrNull().orEmpty() },
    private val artistSongs: suspend (Song, String) -> List<Song> = { song, id ->
        val artist = song.artistId ?: song.artists.firstNotNullOfOrNull { it.browseId }
            ?: YtMusicRepository.trackLinks(id).getOrNull()?.artistId
        artist?.let { YtMusicRepository.artistPage(it).getOrNull()?.songs }.orEmpty()
    },
) {
    private val usedSeeds = mutableSetOf<String>()
    private var seedSong: Song? = null
    private var seedId: String? = null
    private var continuation: String? = null
    private var pending = emptyList<Song>()
    private var featuredArtist: Pair<String?, String?>? = null
    private var continuationFailures = 0

    fun continueArtistRadio(seed: Song, cursor: String, name: String?, artistId: String?) {
        seedSong = seed
        seedId = seed.videoId
        continuation = cursor
        featuredArtist = name to artistId
    }

    suspend fun load(existing: List<Song>, queue: List<Song>, limit: Int): List<Song> {
        fun takesVideos(seed: Song) = seed.isVideo && !MusicOnlyPlayback.isActive()
        val disliked = LikeState.dislikedIds()
        val skipped = SessionTasteFeedback.skippedVideoIds()
        val cooled = SessionTasteFeedback.cooledArtists()
        val knownTracks = SessionTasteFeedback.knownTrackIds()
        val knownArtists = SessionTasteFeedback.knownArtistKeys()
        val mode = com.music.bitchord.data.settings.AppSettings.autoplayMode.value

        fun pick(source: Song, candidates: List<Song>): List<Song> {
            val preferVideos = takesVideos(source)
            val eligible = candidates.filter { song ->
                song.videoId !in disliked &&
                    song.videoId !in skipped &&
                    SessionTasteFeedback.primaryArtistKey(song) !in cooled &&
                    existing.none { QueueBuilder.isSameRecording(it, song) }
            }
            val mediaFiltered = if (preferVideos) {
                eligible
            } else {
                eligible.filterNot { it.isVideo }.ifEmpty { eligible }
            }
            // Keep the top 24 actually playable, relevant candidates before mode ranking
            // so switching modes always has a full pool instead of 1-2 leftover tracks.
            val topRelevant = mediaFiltered.take(24)

            val ranked = topRelevant.withIndex().sortedWith(
                when (mode) {
                    com.music.bitchord.data.model.AutoplayMode.FAMILIAR -> compareBy<IndexedValue<Song>>(
                        {
                            val known = it.value.videoId in knownTracks ||
                                SessionTasteFeedback.primaryArtistKey(it.value) in knownArtists
                            !known
                        },
                        { it.value.albumId.isNullOrBlank() && it.value.albumName.isNullOrBlank() },
                        { it.index },
                    )
                    com.music.bitchord.data.model.AutoplayMode.DISCOVERY -> compareBy<IndexedValue<Song>>(
                        { it.value.videoId in knownTracks },
                        { SessionTasteFeedback.primaryArtistKey(it.value) in knownArtists },
                        { it.value.albumId.isNullOrBlank() && it.value.albumName.isNullOrBlank() },
                        { it.index },
                    )
                },
            ).map { it.value }

            val extended = QueueBuilder.extend(existing, ranked, limit * 2, preferVideos = preferVideos)
            // Prevent back-to-back same-artist tracks in the upcoming Autoplay tail.
            var prevArtist = existing.lastOrNull()?.let(SessionTasteFeedback::primaryArtistKey).orEmpty()
            val varied = ArrayList<Song>(limit)
            val deferred = ArrayList<Song>()
            for (song in extended) {
                val artist = SessionTasteFeedback.primaryArtistKey(song)
                if (artist.isNotBlank() && artist == prevArtist) {
                    deferred.add(song)
                } else {
                    varied.add(song)
                    prevArtist = artist
                    if (varied.size == limit) break
                }
            }
            if (varied.size < limit) {
                for (song in deferred) {
                    varied.add(song)
                    if (varied.size == limit) break
                }
            }
            return varied.map {
                it.copy(
                    radioName = source.radioName,
                    playbackSource = source.playbackSource,
                    playbackSourceType = source.playbackSourceType,
                    playbackSourceId = source.playbackSourceId,
                ).asQueueEntry(QueueTier.AUTOPLAY)
            }
        }

        val active = seedSong
        if (active != null && !SessionTasteFeedback.isSongCooled(active)) {
            val ready = pick(active, pending)
            if (ready.isNotEmpty()) return ready
            repeat(3) {
                val cursor = continuation ?: return@repeat
                val next = page(checkNotNull(seedId), cursor, takesVideos(active))
                coroutineContext.ensureActive()
                if (next == null && ++continuationFailures < 2) return emptyList()
                continuationFailures = 0
                continuation = next?.continuation?.takeIf { it != cursor }
                val tracks = next?.songs.orEmpty()
                pending = featuredArtist?.let { (name, id) -> TopPicks.artistRadioTracks(tracks, name, id) } ?: tracks
                val continued = pick(active, pending)
                if (continued.isNotEmpty()) return continued
            }
            if (continuation != null) return emptyList()
            featuredArtist = null
        }

        val newestFirst = queue.asReversed()
        val queueIds = queue.mapTo(HashSet()) { it.videoId }
        // Only fall back to an anchor that belongs to the current queue (never a stale
        // track from an earlier listening session). When the user selects a fresh song,
        // `newestFirst` picks that exact song first so its genre/vibe is preserved.
        val anchor = SessionTasteFeedback.lastGoodAnchorSong()?.takeIf { song ->
            song.videoId in queueIds &&
                song.videoId !in usedSeeds &&
                song.videoId !in disliked &&
                (VIDEO_ID.matches(song.videoId) || SourceRegistry.parseTrackKey(song.videoId) != null)
        }
        val online = newestFirst.firstOrNull {
            VIDEO_ID.matches(it.videoId) && it.videoId !in usedSeeds &&
                it.videoId !in disliked && !SessionTasteFeedback.isSongCooled(it)
        }
            ?: anchor?.takeIf { VIDEO_ID.matches(it.videoId) }
            ?: newestFirst.firstOrNull {
                VIDEO_ID.matches(it.videoId) && it.videoId !in usedSeeds && it.videoId !in disliked
            }
        val source = online
            ?: newestFirst.firstOrNull {
                SourceRegistry.parseTrackKey(it.videoId) != null && it.videoId !in usedSeeds &&
                    it.videoId !in disliked && !SessionTasteFeedback.isSongCooled(it)
            }
            ?: anchor
            ?: newestFirst.firstOrNull {
                SourceRegistry.parseTrackKey(it.videoId) != null && it.videoId !in usedSeeds && it.videoId !in disliked
            }
        if (source == null) {
            usedSeeds.clear()
            return emptyList()
        }
        val id = if (online != null) online.videoId else youtubeSeedFor(source)
        coroutineContext.ensureActive()
        usedSeeds += source.videoId
        if (id == null) return emptyList()
        val initial = page(id, null, takesVideos(source))
        coroutineContext.ensureActive()
        usedSeeds += id
        seedSong = source
        seedId = id
        continuation = initial?.continuation
        pending = initial?.songs.orEmpty()
        pick(source, pending).takeIf { it.isNotEmpty() }?.let { return it }

        val relatedTracks = related(id)
        coroutineContext.ensureActive()
        pick(source, relatedTracks).takeIf { it.isNotEmpty() }?.let { return it }

        val artistTracks = artistSongs(source, id)
        coroutineContext.ensureActive()
        return pick(source, artistTracks)
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
): Result<List<Song>> {
    val seed = youtubeSeedFor(seedSong) ?: return Result.success(emptyList())
    // A music video plays on with music videos, as YouTube Music does — unless
    // they would only be converted again, when the station's songs are the answer.
    val videos = seedSong.isVideo && seed == seedSong.videoId && !MusicOnlyPlayback.isActive()
    val related = YtMusicRepository.radio(seed, preferVideo = videos).getOrElse { return Result.failure(it) }
    val extra = QueueBuilder.extend(existing, related, limit, preferVideos = videos)
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}
