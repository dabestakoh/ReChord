package com.music.bitchord.data

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import com.music.bitchord.data.model.TopPickKind
import com.music.bitchord.data.model.TopPickMood
import com.music.bitchord.data.model.WatchTarget
import com.music.bitchord.playback.QueueBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.music.bitchord.data.model.normalizedArtistName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.Locale
import kotlin.random.Random

/**
 * Builds Home's "Top Picks for You" row out of what the account already tells
 * YouTube Music, and starts the queue behind a card when it is tapped.
 *
 * Every card comes from YouTube's own data:
 *  - the listener's top artists are ranked from their YouTube listening
 *    history (the feed Recents was just built from, so normally no request),
 *    with this app's own play counts as a second opinion;
 *  - the three top artists' landing pages (one request each, in parallel)
 *    supply the latest release, the artist photos, related artists, and the
 *    header's Mix and Shuffle queues;
 *  - My Supermix, Discover Mix and New Release Mix come off Home's Mixed for
 *    you shelf when it is already loaded, or the Mixed for you page if not;
 *  - a mood is a fixed category id, so it costs nothing until tapped.
 *
 * A card with nothing behind it is left out rather than filled with a
 * placeholder. Queues are fetched only on tap.
 */
object TopPicks {

    private const val TAG = "BitChord"

    /** How many artists get a page fetched — and so a card. */
    private const val ARTISTS = 3

    /**
     * How far down the listener's chart those artists are drawn from. Every
     * build draws afresh, weighted toward the top, so the row changes each
     * time it is built without leaving the listener's own taste.
     */
    private const val ARTIST_POOL = 10
    private const val FEATURED_SEED_LIMIT = 12
    private const val PICKS_PER_SEED = 3

    /**
     * What this app knows about the listener's taste without asking YouTube:
     * its own Replay chart. Second to the account's history when signed in,
     * and all there is when signed out.
     */
    data class LocalTaste(
        val artists: List<ArtistRef> = emptyList(),
        val songs: List<Song> = emptyList(),
        val genres: List<String> = emptyList(),
        val recentGenres: List<String> = emptyList(),
        val genresForArtist: (String) -> List<String> = { emptyList() },
        val liked: List<Song> = emptyList(),
        val recent: List<Song> = emptyList(),
        val albumIds: Set<String> = emptySet(),
        val playlistSongs: List<Song> = emptyList(),
        val dislikedIds: Set<String> = emptySet(),
        val completedIds: Set<String> = emptySet(),
        val dismissedSeedKeys: Set<String> = emptySet(),
        val cooledArtists: Set<String> = emptySet(),
        val timeOfDayVideoIds: Set<String> = emptySet(),
        val timeOfDayArtists: Set<String> = emptySet(),
        val artistAffinities: Map<String, Double> = emptyMap(),
    )

    data class Build(val picks: List<TopPick>, val complete: Boolean)

    suspend fun build(
        homeShelves: List<HomeShelf>,
        local: LocalTaste,
        now: LocalDateTime = LocalDateTime.now(),
        onAvailable: suspend (List<TopPick>) -> Unit = {},
    ): Build = coroutineScope {
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val mixesAsync = async { findMixes(homeShelves) }
        val history = retryFeaturedRequest(interrupted) { YtMusicRepository.recentHistory() }.orEmpty()
        val stateLock = Mutex()
        val songsBySeed = sortedMapOf<Int, TopPick>()
        val artistsBySeed = sortedMapOf<Int, TopPick>()
        var lead: TopPick? = null
        var availablePhotos = emptyMap<String, String>()

        suspend fun update(change: () -> Unit) = stateLock.withLock {
            change()
            onAvailable(assembleFeatured(lead, songsBySeed.values, artistsBySeed.values, history, local))
        }

        val songPicks = async {
            relatedSongPicks(history, local, interrupted) { index, pick ->
                update { songsBySeed[index] = pick }
            }
        }
        val ranked = weightedSample(rankArtists(history, local).take(ARTIST_POOL), ARTISTS)
        val pages = ranked.map { artist ->
            async {
                val id = checkNotNull(artist.browseId)
                retryFeaturedRequest(interrupted) { YtMusicRepository.artistLanding(id) }?.let {
                    ArtistCard(it.copy(name = it.name ?: artist.name), id)
                }
            }
        }
        val radios = pages.mapIndexed { index, page ->
            async {
                val card = page.await() ?: return@async
                val radio = card.page.radio ?: return@async
                val batch = retryFeaturedRequest(interrupted) { YtMusicRepository.watchRadioPage(radio) }
                    ?: return@async
                val tracks = artistRadioTracks(batch.songs, card.name, card.browseId)
                update {
                    andSimilar(card, availablePhotos)?.let { pick ->
                        artistsBySeed[index] = pick.copy(
                            recommendations = tracks.take(24),
                            radioContinuation = batch.continuation,
                        )
                    }
                }
            }
        }
        // Each landing page supplies its release independently of radio and photos.
        val artists = pages.awaitAll().filterNotNull().distinctBy { normalizedArtistName(it.name) }
        update { lead = latestRelease(artists) }

        val artistPhotos = async {
            val photos = withTimeoutOrNull(3000) { applePhotos(artists) }.orEmpty()
            if (photos.isNotEmpty()) update {
                availablePhotos = photos
                val byId = artists.associateBy { it.browseId }
                artistsBySeed.replaceAll { _, pick ->
                    byId[pick.artistBrowseId]?.let { andSimilar(it, photos) }
                        ?.let { pick.copy(images = it.images) } ?: pick
                }
            }
            photos
        }
        songPicks.await()
        radios.awaitAll()
        val photos = artistPhotos.await()
        val mixes = mixesAsync.await()
        val rest = buildList {
            personalStation(mixes, history, local)?.let(::add)
            add(moodPick(now, local))
            artists.getOrNull(1)?.let { featured(it, photos) }?.let(::add)
            mixes[Mix.NEW_RELEASE]?.let { mix ->
                add(
                    TopPick(
                        kind = TopPickKind.NEW_RELEASE_MIX,
                        title = mix.title,
                        subtitle = mix.subtitle,
                        images = listOfNotNull(mix.thumbnailUrl),
                        watch = mix.watchTarget(),
                    ),
                )
            }
            discoveryStation(mixes, artists, history)?.let(::add)
        }.shuffled()
        val featured = stateLock.withLock {
            assembleFeatured(lead, songsBySeed.values, artistsBySeed.values, history, local)
        }
        Build(featured + rest, !interrupted.get())
    }

    private suspend fun <T> retryFeaturedRequest(
        interrupted: java.util.concurrent.atomic.AtomicBoolean,
        request: suspend () -> Result<T>,
    ): T? {
        repeat(2) { attempt ->
            val response = withTimeoutOrNull(3000) { request() }
            if (response?.isSuccess == true) return response.getOrNull()
            if (attempt == 0) delay(350)
        }
        interrupted.set(true)
        return null
    }

    private fun assembleFeatured(
        lead: TopPick?,
        songPicks: Collection<TopPick>,
        artistPicks: Collection<TopPick>,
        history: List<Song>,
        local: LocalTaste,
    ): List<TopPick> {
        val known = (history + local.songs + local.recent + local.liked).mapTo(HashSet()) { it.videoId }
        val used = HashSet<String>()
        val alternatives = songPicks.map { pick ->
            pick to pick.recommendations.sortedBy { it.videoId in known }
        }
        val featured = buildList {
            val artistCounts = mutableMapOf<String, Int>()
            // Give each seed one turn before drawing more from the same Related page.
            repeat(PICKS_PER_SEED) {
                for ((pick, tracks) in alternatives) {
                    val track = tracks.firstOrNull { candidate ->
                        candidate.videoId !in used &&
                            (artistCounts[primaryArtist(candidate)] ?: 0) < 2 &&
                            (lead == null || !sameFeaturedItem(lead, candidate)) &&
                            none { sameFeaturedItem(it, candidate) }
                    } ?: continue
                    used += track.videoId
                    val artist = primaryArtist(track)
                    artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
                    add(pick.copy(
                        title = track.title,
                        subtitle = track.artist,
                        images = listOfNotNull(track.thumbnailUrl),
                        browseId = track.albumId,
                        recommendations = listOf(track),
                    ))
                }
            }
            var artistCards = 0
            for (pick in artistPicks.distinctBy { normalizedArtistName(it.title) }) {
                val tracks = pick.recommendations.filterNot { it.videoId in used }
                    .distinctBy { it.videoId }.sortedBy { it.videoId in known }.take(12)
                if (tracks.size < 3) continue
                tracks.mapTo(used) { it.videoId }
                add(pick.copy(recommendations = tracks))
                if (++artistCards == 2) break
            }
            rediscoverLikedPick(history, local, used)?.let(::add)
        }
        return fillFeaturedFromArtistPicks(listOfNotNull(lead) + featured)
    }

    /**
     * Builds a zero-network "Rediscover from Liked" Featured card from older
     * Liked Songs (`drop(20)`) that the listener hasn't heard recently.
     */
    internal fun rediscoverLikedPick(
        history: List<Song>,
        local: LocalTaste,
        excludeIds: Set<String> = emptySet(),
    ): TopPick? {
        if ("rediscover-liked" in local.dismissedSeedKeys) return null
        val recentIds = (local.recent + history).mapTo(HashSet()) { it.videoId }
        val candidates = local.liked.drop(20).filter { song ->
            !song.isVideo &&
                youtubeVideoId.matches(song.videoId) &&
                song.videoId !in recentIds &&
                song.videoId !in excludeIds &&
                song.videoId !in local.dislikedIds &&
                normalizedArtistName(primaryArtist(song)) !in local.cooledArtists
        }
            .distinctBy { it.videoId }
            .sortedByDescending { it.videoId in local.completedIds }
            .take(16)
        if (candidates.size < 3) return null
        val lead = candidates.first()
        val artists = candidates.mapNotNull { primaryArtist(it).takeIf(String::isNotBlank) }
            .distinct()
            .take(3)
            .joinToString(", ")
        return TopPick(
            kind = TopPickKind.REDISCOVER_LIKED,
            title = lead.title,
            subtitle = artists.ifBlank { lead.artist },
            artistName = primaryArtist(lead).takeIf(String::isNotBlank),
            artistBrowseId = lead.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                ?: lead.artistId?.takeIf(String::isNotBlank),
            images = candidates.mapNotNull { it.thumbnailUrl }.distinct().take(4),
            recommendations = candidates,
            likedSeed = true,
            seedTitle = "rediscover-liked",
        )
    }

    /**
     * [count] of [ranked] at random, the higher an artist sits the likelier —
     * the top of the chart turns up most builds, the rest often enough to
     * keep the row moving.
     */
    private fun <T> weightedSample(ranked: List<T>, count: Int): List<T> {
        val pool = ranked.withIndex().toMutableList()
        val picked = mutableListOf<T>()
        while (picked.size < count && pool.isNotEmpty()) {
            val weights = pool.map { (ranked.size - it.index).toDouble() }
            var roll = Random.nextDouble() * weights.sum()
            val index = weights.indexOfFirst { roll -= it; roll <= 0 }.coerceAtLeast(0)
            picked += pool.removeAt(index).value
        }
        return picked
    }

    fun diversifyQuickPicks(shelf: HomeShelf): HomeShelf {
        val title = shelf.title.trim().lowercase(Locale.ROOT)
        if (title !in quickPicksTitles) return shelf
        val artistCounts = mutableMapOf<String, Int>()
        val items = shelf.items.map { item ->
            val artist = normalizedArtistName(item.subtitle)
            val position = artistCounts.getOrDefault(artist, 0)
            if (artist.isNotEmpty()) artistCounts[artist] = position + 1
            item to position
        }.sortedBy { it.second }.map { it.first }
        return shelf.copy(items = items)
    }

    private val quickPicksTitles = setOf(
        "quick picks", "lựa chọn nhanh", "chọn nhanh đài phát", "クイック ミックス",
    )

    /** Surface tracks already vetted by the artist radio when song Related runs short. */
    fun fillFeaturedFromArtistPicks(picks: List<TopPick>): List<TopPick> {
        val wanted = 6
        if (picks.count { it.kind == TopPickKind.RELATED_SONG } >= wanted) return picks
        val stations = picks.filter { it.kind == TopPickKind.ARTIST_AND_SIMILAR }
        var filled = picks
        for (index in 0 until (stations.maxOfOrNull { it.recommendations.size } ?: 0)) {
            for (station in stations) {
                val song = station.recommendations.getOrNull(index) ?: continue
                if (song.isVideo) continue
                val card = TopPick(
                    kind = TopPickKind.RELATED_SONG,
                    title = song.title,
                    subtitle = song.artist,
                    images = listOfNotNull(song.thumbnailUrl),
                    browseId = song.albumId,
                    seedTitle = station.artistName ?: station.title,
                    recommendations = listOf(song),
                )
                filled = appendFeaturedCards(filled, listOf(card))
                if (filled.count { it.kind == TopPickKind.RELATED_SONG } >= wanted) return filled
            }
        }
        return filled
    }

    /** Keep cards in place as slower sources deliver results or better artwork. */
    fun appendFeaturedCards(shown: List<TopPick>, ready: List<TopPick>): List<TopPick> = buildList {
        val updates = ready.associateBy { it.carouselKey }
        shown.forEach { add(updates[it.carouselKey] ?: it) }
        ready.forEach { card ->
            val limit = when (card.kind) {
                TopPickKind.RELATED_SONG -> FEATURED_SEED_LIMIT * PICKS_PER_SEED
                TopPickKind.ARTIST_AND_SIMILAR -> 2
                else -> 1
            }
            if (count { it.kind == card.kind } >= limit) return@forEach
            val selected = card.recommendations.firstOrNull().takeIf { card.kind == TopPickKind.RELATED_SONG }
            if (selected != null && count {
                    it.kind == TopPickKind.RELATED_SONG &&
                        it.recommendations.firstOrNull()?.let(::primaryArtist) == primaryArtist(selected)
                } >= 2
            ) return@forEach
            val clash = any { earlier ->
                if (earlier.carouselKey == card.carouselKey) return@any true
                val priorSong = earlier.recommendations.firstOrNull().takeIf { earlier.kind == TopPickKind.RELATED_SONG }
                val newSong = card.recommendations.firstOrNull().takeIf { card.kind == TopPickKind.RELATED_SONG }
                when (card.kind) {
                    TopPickKind.RELATED_SONG -> newSong != null &&
                        earlier.kind in setOf(TopPickKind.LATEST_RELEASE, TopPickKind.RELATED_SONG) &&
                        sameFeaturedItem(earlier, newSong)
                    TopPickKind.LATEST_RELEASE -> priorSong != null && sameFeaturedItem(card, priorSong)
                    TopPickKind.ARTIST_AND_SIMILAR -> earlier.kind == card.kind &&
                        normalizedArtistName(earlier.title) == normalizedArtistName(card.title)
                    else -> false
                }
            }
            if (!clash) add(card)
        }
    }

    internal fun sameFeaturedItem(pick: TopPick, track: Song): Boolean {
        val cover = track.thumbnailUrl?.substringBefore('=')?.substringBefore('?')
        return (track.albumId != null && track.albumId == pick.browseId) ||
            (cover != null && pick.images.any { it.substringBefore('=').substringBefore('?') == cover }) ||
            (normalizedArtistName(pick.title) in listOfNotNull(track.title, track.albumName).map(::normalizedArtistName) &&
                normalizedArtistName(pick.artistName ?: pick.subtitle) == primaryArtist(track))
    }

    internal fun featuredSeeds(history: List<Song>, local: LocalTaste): List<Song> {
        val playlistIds = local.playlistSongs.mapTo(HashSet()) { it.videoId }
        val likedIds = local.liked.mapTo(HashSet()) { it.videoId }
        val recentIds = local.recent.mapTo(HashSet()) { it.videoId }
        val familiar = (local.genres + local.recentGenres).map { it.lowercase(Locale.ROOT) }.distinct()
        val frequencies = history.groupingBy { it.videoId }.eachCount()
        val remote = history.distinctBy { it.videoId }.sortedByDescending { frequencies[it.videoId] }
        val recentLikes = local.liked.take(15)
        // Sample older "deep-cut" liked tracks (favoring tracks the listener finished
        // or hasn't heard recently) so Featured stays fresh across the full Liked library.
        val deepCutLikes = local.liked.drop(15)
            .sortedWith(
                compareByDescending<Song> { it.videoId in local.completedIds }
                    .thenBy { it.videoId in recentIds },
            )
            .take(10)
        val sources = listOf(
            local.recent.take(20),
            remote.take(30),
            recentLikes,
            deepCutLikes,
            local.songs.take(30),
        )
        val candidates = (0 until sources.maxOf { it.size }).flatMap { index ->
            sources.mapNotNull { it.getOrNull(index) }
        } + local.playlistSongs.take(12)
        val ranked = candidates.filter { song ->
            !song.isVideo &&
                youtubeVideoId.matches(song.videoId) &&
                song.videoId !in local.dislikedIds &&
                song.title.lowercase(Locale.ROOT) !in local.dismissedSeedKeys &&
                normalizedArtistName(primaryArtist(song)) !in local.cooledArtists
        }
            .distinctBy { it.videoId }
            .withIndex()
            .sortedBy { (index, song) ->
                val doubleConfirmed = if (song.videoId in likedIds && song.videoId in local.completedIds) 3 else 0
                val timeOfDayMatch = if (
                    song.videoId in local.timeOfDayVideoIds ||
                    normalizedArtistName(primaryArtist(song)) in local.timeOfDayArtists
                ) 2 else 0
                index - doubleConfirmed - timeOfDayMatch -
                    (if (song.videoId in playlistIds) 2 else 0) -
                    (if (song.albumId in local.albumIds) 1 else 0)
            }
            .map { it.value }
            .distinctBy { primaryArtist(it).ifBlank { it.videoId } }
        val buckets = ranked.groupBy { song ->
            val artist = song.artists.firstOrNull()?.name ?: song.artist
            val genres = local.genresForArtist(artist).map { it.lowercase(Locale.ROOT) }
            familiar.firstOrNull { it in genres }
        }
        val ordered = familiar.mapNotNull { buckets[it] } + listOfNotNull(buckets[null])
        return buildList {
            for (index in 0 until (ordered.maxOfOrNull { it.size } ?: 0)) {
                for (bucket in ordered) {
                    bucket.getOrNull(index)?.let(::add)
                    if (size == FEATURED_SEED_LIMIT) return@buildList
                }
            }
        }
    }

    private suspend fun relatedSongPicks(
        history: List<Song>,
        local: LocalTaste,
        interrupted: java.util.concurrent.atomic.AtomicBoolean,
        onPick: suspend (Int, TopPick) -> Unit,
    ): Unit = coroutineScope {
        val seeds = featuredSeeds(history, local)
        val known = (local.recent + history + local.songs + local.liked).mapTo(HashSet()) { it.videoId }
        val liked = local.liked.mapTo(HashSet()) { it.videoId }
        val limiter = Semaphore(3)
        seeds.mapIndexed { index, seed ->
            async {
                limiter.withPermit {
                    val related = retryFeaturedRequest(interrupted) { YtMusicRepository.relatedSongs(seed.videoId) }.orEmpty()
                    val artistCounts = mutableMapOf<String, Int>()
                    // Stay within YouTube Music's top relevance/popularity window (top 16)
                    // and prefer official catalogued releases over uncatalogued/obscure uploads.
                    val tracks = related.filter { song ->
                        !QueueBuilder.isSameRecording(seed, song) &&
                            !song.isVideo &&
                            song.videoId !in local.dislikedIds &&
                            normalizedArtistName(primaryArtist(song)) !in local.cooledArtists
                    }
                        .distinctBy { it.videoId }
                        .take(24)
                        .withIndex()
                        .sortedWith(
                            compareBy<IndexedValue<Song>> { it.value.videoId in known }
                                .thenBy { it.value.albumId.isNullOrBlank() && it.value.albumName.isNullOrBlank() }
                                .thenBy { it.index },
                        )
                        .map { it.value }
                        .filter { song ->
                            val artist = primaryArtist(song).ifBlank { song.videoId }
                            val count = artistCounts.getOrDefault(artist, 0)
                            artistCounts[artist] = count + 1
                            count < 3
                        }.take(16)
                    if (tracks.isNotEmpty()) onPick(index, TopPick(
                        kind = TopPickKind.RELATED_SONG,
                        title = seed.title,
                        subtitle = seed.artist,
                        artistName = primaryArtist(seed).takeIf(String::isNotBlank),
                        artistBrowseId = seed.artists.firstOrNull { !it.browseId.isNullOrBlank() }?.browseId
                            ?: seed.artistId?.takeIf(String::isNotBlank),
                        images = listOfNotNull(seed.thumbnailUrl ?: tracks.firstOrNull()?.thumbnailUrl),
                        recommendations = tracks,
                        likedSeed = seed.videoId in liked,
                        seedTitle = seed.title,
                    ))
                }
            }
        }.awaitAll()
    }

    fun rankReleases(items: List<ShelfItem>, history: List<Song>, local: LocalTaste): List<ShelfItem> {
        val known = (rankArtists(history, local).map { it.name } + history.map { it.artist } + local.songs.map { it.artist })
            .map(::normalizedArtistName).filter { it.isNotBlank() }.distinct()
            .map { artist -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(artist)}(?![\\p{L}\\p{N}])") }
        return items.sortedBy { item ->
            val byline = normalizedArtistName(item.subtitle)
            known.indexOfFirst { it.containsMatchIn(byline) }.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
    }

    /** The queue a card starts. Never called for [TopPickKind.LATEST_RELEASE], which opens a page. */
    suspend fun queue(pick: TopPick, local: LocalTaste = LocalTaste()): Result<List<Song>> {
        if (pick.kind == TopPickKind.RELATED_SONG || pick.recommendations.isNotEmpty()) return Result.success(pick.recommendations)
        if (pick.kind == TopPickKind.MOOD) return moodQueue(pick)
        val target = pick.watch ?: return Result.failure(IllegalStateException("no queue behind ${pick.kind}"))
        if (pick.kind == TopPickKind.PERSONAL_STATION || pick.kind == TopPickKind.DISCOVERY_STATION) {
            return freshStation(pick, target, local)
        }
        val tracks = YtMusicRepository.watchQueue(target)
        if (pick.kind != TopPickKind.ARTIST_AND_SIMILAR) return tracks
        return tracks.map { artistRadioTracks(it, pick.artistName, pick.artistBrowseId) }
    }

    fun artistRadioTracks(songs: List<Song>, artistName: String?, artistId: String?): List<Song> {
        val artist = artistName?.let(::normalizedArtistName)
        return songs.distinctBy { it.videoId }
            .filter { song ->
                !song.isVideo && primaryArtist(song) != artist && song.artistId.let { artistId == null || it != artistId } &&
                    song.artists.none { (artistId != null && it.browseId == artistId) || normalizedArtistName(it.name) == artist }
            }
            .distinctBy { primaryArtist(it).ifBlank { it.videoId } }
    }

    /**
     * A Made for You station that sounds different on every tap.
     *
     * My Supermix and Discover Mix are rebuilt by YouTube about once a day
     * and come back in the same order every time they're asked for, so
     * played as they are, the station opened on the same songs every time.
     * Here the mix is the core — a random half of it — blended with the
     * radios of recent or liked tracks from different artists, and
     * the whole thing shuffled.
     *
     * A Discovery Station leaves out everything already in that history (and
     * the seed artist, when it's built off an artist's Mix rather than
     * Discover Mix), so what it adds stays new to the listener.
     */
    private suspend fun freshStation(
        pick: TopPick,
        target: WatchTarget,
        local: LocalTaste,
    ): Result<List<Song>> = coroutineScope {
        val mixAsync = async { YtMusicRepository.watchQueue(target) }
        val history = YtMusicRepository.recentHistory().getOrNull().orEmpty()
        val seeds = (local.liked.take(1) + history.take(SEED_POOL).shuffled() + local.songs + local.liked.take(10))
            .filter { !it.isVideo && youtubeVideoId.matches(it.videoId) }
            .distinctBy { it.videoId }
            .distinctBy { primaryArtist(it).ifBlank { it.videoId } }
            .take(SEED_RADIOS)
        val radios = seeds.map { seed ->
            async {
                YtMusicRepository.watchQueue(WatchTarget(playlistId = "RDAMVM${seed.videoId}", videoId = seed.videoId))
                    .getOrNull().orEmpty()
            }
        }
        val mix = mixAsync.await().getOrElse { failure ->
            if (seeds.isEmpty()) return@coroutineScope Result.failure(failure)
            emptyList()
        }
        val around = radios.awaitAll().flatten()

        val discovery = pick.kind == TopPickKind.DISCOVERY_STATION
        val heard = if (discovery) {
            (history + local.songs + local.liked).mapTo(HashSet()) { it.videoId }.apply { addAll(pick.exclude) }
        } else {
            emptySet()
        }
        val seedArtist = pick.artistName?.takeIf { discovery }?.let(::normalizedArtistName)
        val candidates = (mix.shuffled().take(STATION_FROM_MIX) + around.shuffled() + mix.shuffled())
            .distinctBy { it.videoId }
            .filter { song ->
                song.videoId !in heard &&
                    (seedArtist.isNullOrEmpty() || primaryArtist(song) != seedArtist)
            }
        val artistCounts = mutableMapOf<String, Int>()
        val diverse = candidates.filter { song ->
            val artist = primaryArtist(song).ifBlank { song.videoId }
            val count = artistCounts.getOrDefault(artist, 0)
            artistCounts[artist] = count + 1
            count < STATION_TRACKS_PER_ARTIST
        }
        val station = if (discovery) diverse else (diverse + candidates).distinctBy { it.videoId }

        when {
            station.isNotEmpty() -> Result.success(station.take(STATION_SIZE).shuffled())
            mix.isNotEmpty() -> Result.success(mix.distinctBy { it.videoId }.shuffled())
            else -> Result.failure(IllegalStateException("${pick.kind} came back empty"))
        }
    }

    private fun primaryArtist(song: Song): String =
        normalizedArtistName(song.artists.firstOrNull()?.name ?: song.artist)

    private val youtubeVideoId = Regex("[A-Za-z0-9_-]{11}")
    private const val STATION_TRACKS_PER_ARTIST = 3

    /** History tracks a station's extra radios are seeded from: the recent end of it. */
    private const val SEED_POOL = 40
    private const val SEED_RADIOS = 3
    private const val STATION_FROM_MIX = 30
    private const val STATION_SIZE = 60

    // ---- Cards --------------------------------------------------------------

    private class ArtistCard(val page: ArtistPage, val browseId: String) {
        val name: String get() = page.name.orEmpty()

        /**
         * The page's "Fans might also like", by name, without the artist
         * themselves — YouTube can list a second channel of the same person
         * there — and without repeats. A few more than a card shows, so an
         * artist Apple has no photo of can be passed over.
         */
        val relatedNames: List<String> get() = related.keys.toList()

        /** [relatedNames] → each one's channel, which their Apple photo is checked against. */
        val related: Map<String, String> by lazy {
            val own = normalizedArtistName(name)
            page.sections
                .firstOrNull { shelf -> shelf.items.isNotEmpty() && shelf.items.all { it.browseId?.startsWith("UC") == true } }
                ?.items.orEmpty()
                .filter { it.browseId != browseId }
                .filter { normalizedArtistName(it.title).let { key -> key.isNotEmpty() && key != own } }
                .distinctBy { normalizedArtistName(it.title) }
                .take(RELATED_CANDIDATES)
                .associate { it.title to it.browseId!! }
        }
    }

    private const val RELATED_CANDIDATES = 4
    private const val PHOTO_LOOKUPS_AT_ONCE = 4

    /**
     * Apple Music photos for [artists] and the people their fans also play, by
     * name; an artist Apple has none of (or none provably theirs) is left out.
     * A card's own artist is checked against the page already in hand; a
     * related one's page is fetched only if their photo isn't known yet.
     */
    private suspend fun applePhotos(artists: List<ArtistCard>): Map<String, String> = coroutineScope {
        val catalogues = LinkedHashMap<String, suspend () -> Collection<String>>()
        artists.forEach { artist -> catalogues.putIfAbsent(artist.name) { AppleCatalogue.titlesOf(artist.page) } }
        artists.forEach { artist ->
            artist.related.forEach { (name, channel) ->
                catalogues.putIfAbsent(name) {
                    YtMusicRepository.artistLanding(channel).getOrNull()?.let(AppleCatalogue::titlesOf).orEmpty()
                }
            }
        }
        val limiter = Semaphore(PHOTO_LOOKUPS_AT_ONCE)
        catalogues.map { (name, catalogue) ->
            async { limiter.withPermit { AppleArtistPhotos.photoFor(name, catalogue)?.let { name to it } } }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun latestRelease(artists: List<ArtistCard>): TopPick? {
        data class Candidate(val rank: Int, val artist: ArtistCard, val item: ShelfItem, val year: Int, val single: Boolean)
        val candidates = artists.flatMapIndexed { rank, artist ->
            // The release shelves are the ones of albums (MPRE… pages). Each is
            // newest first, so only its lead card is a candidate.
            artist.page.sections
                .mapNotNull { shelf -> shelf.items.firstOrNull()?.takeIf { it.browseId?.startsWith("MPRE") == true } }
                .map { item ->
                    Candidate(rank, artist, item, yearOf(item.subtitle), SINGLE.containsMatchIn(item.subtitle))
                }
        }
        // Newest year first. Within a year a single wins: a bare-year "album"
        // on a big artist's page is as often one of YouTube's auto-made
        // compilations as a real record, and a single never is.
        val best = candidates.filter { it.year >= LocalDateTime.now().year - 1 }.maxWithOrNull(
            compareBy<Candidate> { it.year }.thenBy { it.single }.thenByDescending { it.rank },
        ) ?: return null
        return TopPick(
            kind = TopPickKind.LATEST_RELEASE,
            title = best.item.title,
            subtitle = best.item.subtitle,
            artistName = best.artist.name,
            images = listOfNotNull(best.item.thumbnailUrl),
            browseId = best.item.browseId,
        )
    }

    private fun personalStation(mixes: Map<Mix, ShelfItem>, history: List<Song>, local: LocalTaste): TopPick? {
        val watch = mixes[Mix.SUPERMIX]?.watchTarget()
            // No Supermix (signed out, or a feed without one): a radio off the
            // track the listener plays most, which is the same idea built by hand.
            ?: (local.songs.firstOrNull() ?: history.firstOrNull())?.let { song ->
                WatchTarget(playlistId = "RDAMVM${song.videoId}", videoId = song.videoId)
            }
            ?: return null
        return TopPick(kind = TopPickKind.PERSONAL_STATION, watch = watch)
    }

    private fun moodPick(now: LocalDateTime, local: LocalTaste): TopPick =
        TopPick(
            kind = TopPickKind.MOOD,
            mood = moodFor(now),
            hints = (local.genres.map { it.lowercase(Locale.ROOT) } + regionHints()).distinct(),
        )

    private fun andSimilar(artist: ArtistCard, photos: Map<String, String>): TopPick? {
        val radio = artist.page.radio ?: return null
        // No Apple photo of the artist, no card: the big circle is the card.
        val face = photos[artist.name] ?: artist.page.thumbnailUrl ?: return null
        val related = artist.relatedNames
            .mapNotNull { photos[it] }
            // Two names can share one photo; one face per circle.
            .filter { it != face }
            .distinct()
            .take(2)
        return TopPick(
            kind = TopPickKind.ARTIST_AND_SIMILAR,
            title = artist.name,
            artistName = artist.name,
            artistBrowseId = artist.browseId,
            images = listOf(face) + related,
            watch = radio,
        )
    }

    private fun featured(artist: ArtistCard, photos: Map<String, String>): TopPick? {
        val shuffle = artist.page.shuffle ?: artist.page.radio ?: return null
        val face = photos[artist.name] ?: artist.page.thumbnailUrl ?: return null
        return TopPick(
            kind = TopPickKind.FEATURED_ARTIST,
            title = artist.name,
            subtitle = artist.page.monthlyListenerCount ?: artist.page.subscriberCountText.orEmpty(),
            artistName = artist.name,
            artistBrowseId = artist.browseId,
            images = listOf(face),
            watch = shuffle,
        )
    }

    private fun discoveryStation(
        mixes: Map<Mix, ShelfItem>,
        artists: List<ArtistCard>,
        history: List<Song>,
    ): TopPick? {
        mixes[Mix.DISCOVER]?.watchTarget()?.let { watch ->
            return TopPick(kind = TopPickKind.DISCOVERY_STATION, watch = watch)
        }
        // No Discover Mix: the top artist's Mix with everything already heard
        // filtered out at play time — see [queue].
        val seed = artists.firstOrNull() ?: return null
        val radio = seed.page.radio ?: return null
        return TopPick(
            kind = TopPickKind.DISCOVERY_STATION,
            artistName = seed.name,
            watch = radio,
            exclude = history.take(200).map { it.videoId },
        )
    }

    // ---- Artists ------------------------------------------------------------

    /**
     * The listener's artists, best first. Each history row counts once, with
     * a little extra the more recent it is; the app's own chart adds a smaller
     * share by position. Only artists with a channel id take part — a card
     * needs the page, and a bare name would need a search to find one.
     */
    private fun rankArtists(history: List<Song>, local: LocalTaste): List<ArtistRef> {
        val scores = LinkedHashMap<String, Pair<String, Double>>()
        fun credit(id: String?, name: String, weight: Double) {
            if (id == null || !id.startsWith("UC") || name.isBlank()) return
            if (name.equals("Various Artists", ignoreCase = true)) return
            if (normalizedArtistName(name) in local.cooledArtists) return
            val (_, score) = scores[id] ?: (name to 0.0)
            scores[id] = name to score + weight
        }
        history.forEachIndexed { index, song ->
            if (song.videoId in local.dislikedIds) return@forEachIndexed
            val recency = 1.0 - index.toDouble() / history.size.coerceAtLeast(1)
            val lead = song.artists.firstOrNull { it.browseId != null }
            if (lead != null) credit(lead.browseId, lead.name, 0.25 + recency * recency * 1.25)
            else credit(song.artistId, song.artist, 0.25 + recency * recency * 1.25)
        }
        val likedWindow = local.liked.distinctBy { it.videoId }.take(60)
        val likedSize = likedWindow.size.coerceAtLeast(1)
        likedWindow.forEachIndexed { index, song ->
            if (song.videoId in local.dislikedIds) return@forEachIndexed
            val recency = 1.0 - index.toDouble() / likedSize
            val baseWeight = 1.0 + recency * 1.2
            val completedBonus = if (song.videoId in local.completedIds) 0.6 else 0.0
            val lead = song.artists.firstOrNull { it.browseId != null }
            credit(lead?.browseId ?: song.artistId, lead?.name ?: song.artist, baseWeight + completedBonus)
        }
        val playedIds = (history + local.songs + local.recent).mapTo(HashSet()) { it.videoId }
        local.playlistSongs.distinctBy { it.videoId }.forEach { song ->
            if (song.videoId in local.dislikedIds) return@forEach
            val lead = song.artists.firstOrNull { it.browseId != null }
            credit(lead?.browseId ?: song.artistId, lead?.name ?: song.artist, if (song.videoId in playedIds) 0.5 else 0.15)
        }
        local.artists.forEachIndexed { index, artist ->
            val key = normalizedArtistName(artist.name)
            val todBonus = if (key in local.timeOfDayArtists) 1.0 else 0.0
            val affinityBonus = (local.artistAffinities[key] ?: 0.0).coerceIn(-3.0, 4.0)
            credit(artist.browseId, artist.name, (local.artists.size - index).toDouble() * 0.5 + todBonus + affinityBonus)
        }
        return scores.entries
            .sortedByDescending { it.value.second }
            .map { ArtistRef(name = it.value.first, browseId = it.key) }
            .distinctBy { normalizedArtistName(it.name) }
    }

    // ---- Mixes --------------------------------------------------------------

    private enum class Mix { SUPERMIX, DISCOVER, NEW_RELEASE }

    /**
     * The account's mixes. All of them are `RDTMAK…` playlists, so the id says
     * "a mix" but not which; the title does, in English. In another interface
     * language only the Supermix is placed, as the lead mix, which is where
     * YouTube puts it.
     */
    private suspend fun findMixes(homeShelves: List<HomeShelf>): Map<Mix, ShelfItem> {
        val onHome = homeShelves.flatMap { it.items }.filter { it.isMix() }
        val mixes = onHome.takeIf { it.isNotEmpty() }
            ?: YtMusicRepository.mixedForYou().getOrNull().orEmpty().filter { it.isMix() }
        if (mixes.isEmpty()) return emptyMap()
        val found = mutableMapOf<Mix, ShelfItem>()
        mixes.forEach { item ->
            val title = item.title.lowercase(Locale.ROOT)
            val kind = when {
                "supermix" in title -> Mix.SUPERMIX
                "discover" in title -> Mix.DISCOVER
                "new release" in title -> Mix.NEW_RELEASE
                else -> null
            }
            if (kind != null) found.putIfAbsent(kind, item)
        }
        if (found.isEmpty()) found[Mix.SUPERMIX] = mixes.first()
        return found
    }

    private fun ShelfItem.isMix(): Boolean =
        browseId?.removePrefix("VL")?.startsWith("RDTMAK") == true

    private fun ShelfItem.watchTarget(): WatchTarget? =
        browseId?.removePrefix("VL")?.let { WatchTarget(playlistId = it) }

    // ---- Moods --------------------------------------------------------------

    /**
     * A mood at random, leaning on the hour: most of the time one that suits
     * it (Focus mid-morning, Love late in the evening), sometimes any of
     * them. Only Apple's seven Find Your Mood moods, as on Apple Music's own
     * Top Picks — the activities live in Find Your Mood's Show all.
     */
    fun moodFor(now: LocalDateTime): TopPickMood {
        val suited = when (now.hour) {
            in 5..8 -> listOf(TopPickMood.ENERGY, TopPickMood.FEEL_GOOD, TopPickMood.FOCUS)
            in 9..11 -> listOf(TopPickMood.FOCUS, TopPickMood.FEEL_GOOD, TopPickMood.ENERGY)
            in 12..16 -> listOf(TopPickMood.FEEL_GOOD, TopPickMood.FOCUS, TopPickMood.ENERGY, TopPickMood.RELAX)
            in 17..19 -> listOf(TopPickMood.ENERGY, TopPickMood.FEEL_GOOD, TopPickMood.RELAX)
            in 20..22 -> listOf(TopPickMood.LOVE, TopPickMood.RELAX, TopPickMood.HEARTBREAK, TopPickMood.FEEL_GOOD)
            else -> listOf(TopPickMood.RELAX, TopPickMood.FEELING_BLUE, TopPickMood.HEARTBREAK, TopPickMood.LOVE)
        }
        return if (Random.nextFloat() < 0.7f) suited.random()
        else TopPickMood.entries.filter { it.isCoreMood }.random()
    }

    /**
     * A mood's queue: the mood's own playlists, played as they are and
     * shuffled together. Not a playlist *radio* — a radio wanders off to
     * whatever sounds like the seed, and a Love station drifted into plain
     * feel-good pop within a few tracks.
     *
     * The category is shelves of playlists by scene ("Romantic Bollywood &
     * Indian", "Pop romance", …). A shelf naming the listener's own scene
     * wins; two of its playlists are drawn at random and mixed.
     */
    private suspend fun moodQueue(pick: TopPick): Result<List<Song>> = coroutineScope {
        val mood = pick.mood ?: return@coroutineScope Result.failure(IllegalStateException("mood card without a mood"))
        val shelves = YtMusicRepository.moodGenreShelves(TopPickMood.CATEGORY_BROWSE_ID, mood.params)
            .getOrElse { return@coroutineScope Result.failure(it) }
        val playlists = { shelf: HomeShelf -> shelf.items.filter { it.browseId?.startsWith("VL") == true } }
        val candidates = shelves.filter { playlists(it).isNotEmpty() }
        val score = { shelf: HomeShelf ->
            val title = shelf.title.lowercase(Locale.ROOT)
            2 * mood.shelfWords.count { it in title } + pick.hints.count { it.isNotBlank() && it in title }
        }
        val best = candidates.maxOfOrNull(score)
            ?: return@coroutineScope Result.failure(IllegalStateException("no playlists for $mood"))
        // With no scene in common, any shelf of the mood will do.
        val shelf = candidates.filter { score(it) == best }.random()
        val chosen = playlists(shelf).take(6).shuffled().take(MOOD_PLAYLISTS)
        val songs = chosen.map { item ->
            async {
                val playlistId = item.browseId!!.removePrefix("VL")
                // As a watch queue, so YouTube hands over each official video's
                // song version directly; the playlist page only lists videos.
                YtMusicRepository.playlistAsAudio(playlistId).getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: YtMusicRepository.browseSongs(item.browseId!!).getOrNull()?.songs.orEmpty()
            }
        }.awaitAll().flatten()
            // A row YouTube couldn't pair stays the video, and plays as one;
            // the player menu's version switch turns it into the song.
            .distinctBy { it.videoId }
            .shuffled()
            .take(MOOD_QUEUE)
        if (songs.isEmpty()) Result.failure(IllegalStateException("$mood playlists were empty"))
        else Result.success(songs)
    }

    private const val MOOD_PLAYLISTS = 3
    private const val MOOD_QUEUE = 60

    /**
     * A mood station for a Find Your Mood tile — the same queue the Mood for
     * You card plays, steered toward the listener's scene by [hints].
     */
    fun moodStation(mood: TopPickMood, hints: List<String> = regionHints()): TopPick =
        TopPick(kind = TopPickKind.MOOD, mood = mood, hints = hints)

    fun regionHints(): List<String> = when (Locale.getDefault().country) {
        "IN" -> listOf("bollywood", "indian", "hindi", "punjabi", "desi")
        "PK" -> listOf("pakistani", "urdu", "punjabi")
        "KR" -> listOf("k-pop", "korean")
        "JP" -> listOf("j-pop", "japanese")
        "BR" -> listOf("brazil", "sertanejo", "funk")
        "MX", "ES", "AR", "CO", "CL", "PE" -> listOf("latin", "latino", "reggaeton")
        else -> emptyList()
    }

    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val SINGLE = Regex("""\b(Single|EP)\b""")

    private fun yearOf(subtitle: String): Int =
        YEAR.findAll(subtitle).lastOrNull()?.value?.toIntOrNull() ?: 0

    // ---- Storage ------------------------------------------------------------

    /** A day's row as stored on disk, so Home can paint it before any request. */
    @Serializable
    data class Stored(
        val listener: String?,
        /** The hour it was built in, e.g. `2026-10-21T15`. */
        val day: String,
        val picks: List<TopPick>,
        /** [AppleArtistPhotos]' answers, misses included, so the next build doesn't ask again. */
        val photos: Map<String, String> = emptyMap(),
        val revision: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(stored: Stored): String = json.encodeToString(Stored.serializer(), stored)

    fun decode(text: String): Stored? = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull()
}
