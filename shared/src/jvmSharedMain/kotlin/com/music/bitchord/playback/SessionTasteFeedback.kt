package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import java.util.Locale

/**
 * Thread-safe taste & feedback tracker shared by `PlaybackService`,
 * `AutoplayRadio`, `TopPicks`, `MainViewModel`, and `PersistentTasteStore`.
 *
 * - Positive signals (likes, completions, manual Play Next / Add to Queue)
 *   build artist affinity and clear prior cooldowns without hijacking the
 *   currently playing radio's vibe on single queue additions.
 * - Negative signals (early skips < 15s, swipe-removing Autoplay items,
 *   Dislikes, and "Not interested" dismissals) demote tracks/artists and
 *   cool down repeatedly skipped artists.
 * - Durable across restarts per account/profile via [PersistentTasteStore].
 */
object SessionTasteFeedback {
    const val EARLY_SKIP_THRESHOLD_MS = 15_000L
    const val ARTIST_COOLDOWN_SKIP_COUNT = 2
    const val ARTIST_COOLDOWN_SCORE = -2.0

    data class Snapshot(
        val skippedVideoIds: List<String>,
        val cooledArtists: List<String>,
        val dismissedSeedKeys: List<String>,
        val artistAffinities: Map<String, Double>,
    )

    private val lock = Any()
    private val skippedIds = LinkedHashSet<String>()
    private val artistSkipCounts = HashMap<String, Int>()
    private val cooledArtistKeys = LinkedHashSet<String>()
    private val artistAffinityMap = HashMap<String, Double>()
    private val dismissedKeys = LinkedHashSet<String>()
    private val completedIds = LinkedHashSet<String>()
    private val knownTracks = LinkedHashSet<String>()
    private val knownArtists = LinkedHashSet<String>()
    private var anchorSong: Song? = null

    @Volatile
    private var onChanged: (() -> Unit)? = null

    fun setOnChangedListener(listener: (() -> Unit)?) {
        onChanged = listener
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    fun normalizeArtist(name: String?): String =
        name.orEmpty()
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun primaryArtistKey(song: Song): String {
        val primary = song.artists.firstOrNull()?.name?.takeIf(String::isNotBlank)
            ?: song.artist.split(',', '&', '•').firstOrNull().orEmpty()
        return normalizeArtist(primary)
    }

    private fun adjustAffinityLocked(artistKey: String, delta: Double) {
        if (artistKey.isBlank()) return
        val current = artistAffinityMap[artistKey] ?: 0.0
        val next = (current + delta).coerceIn(-6.0, 10.0)
        artistAffinityMap[artistKey] = next
        if (next <= ARTIST_COOLDOWN_SCORE) {
            cooledArtistKeys.add(artistKey)
        } else if (delta > 0.0 && next > ARTIST_COOLDOWN_SCORE) {
            cooledArtistKeys.remove(artistKey)
        }
    }

    /**
     * Records an early skip (`< 15s`) or removed `AUTOPLAY` suggestion.
     * Returns `true` if the song's artist reached the cooldown threshold.
     */
    fun recordEarlySkip(song: Song): Boolean {
        val cooledNow = synchronized(lock) {
            if (song.videoId.isNotBlank()) skippedIds.add(song.videoId)
            val artistKey = primaryArtistKey(song)
            if (artistKey.isBlank()) return@synchronized false
            val nextCount = (artistSkipCounts[artistKey] ?: 0) + 1
            artistSkipCounts[artistKey] = nextCount
            adjustAffinityLocked(artistKey, -1.0)
            if (nextCount >= ARTIST_COOLDOWN_SKIP_COUNT) {
                cooledArtistKeys.add(artistKey)
                true
            } else {
                artistKey in cooledArtistKeys
            }
        }
        notifyChanged()
        return cooledNow
    }

    /** Reverses [recordEarlySkip] when the user taps Undo on a Queue removal. */
    fun undoEarlySkip(song: Song) {
        synchronized(lock) {
            if (song.videoId.isNotBlank()) skippedIds.remove(song.videoId)
            val artistKey = primaryArtistKey(song)
            if (artistKey.isNotBlank()) {
                val prev = artistSkipCounts[artistKey] ?: 0
                if (prev <= 1) artistSkipCounts.remove(artistKey) else artistSkipCounts[artistKey] = prev - 1
                adjustAffinityLocked(artistKey, +1.0)
                if ((artistSkipCounts[artistKey] ?: 0) < ARTIST_COOLDOWN_SKIP_COUNT &&
                    (artistAffinityMap[artistKey] ?: 0.0) > ARTIST_COOLDOWN_SCORE
                ) {
                    cooledArtistKeys.remove(artistKey)
                }
            }
        }
        notifyChanged()
    }

    /** Records an explicit Dislike — immediately cools down the song and its artist. */
    fun recordDislike(song: Song): String {
        val key = synchronized(lock) {
            if (song.videoId.isNotBlank()) skippedIds.add(song.videoId)
            val artistKey = primaryArtistKey(song)
            if (artistKey.isNotBlank()) {
                artistSkipCounts[artistKey] = ARTIST_COOLDOWN_SKIP_COUNT
                cooledArtistKeys.add(artistKey)
                adjustAffinityLocked(artistKey, -3.0)
            }
            artistKey
        }
        notifyChanged()
        return key
    }

    /**
     * Records a track the listener finished (`>= 30s` or `>= 50%`) or liked.
     * Updates [lastGoodAnchorSong] and boosts artist affinity.
     */
    fun recordCompletedOrLiked(song: Song, isLike: Boolean = false) {
        synchronized(lock) {
            if (song.videoId.isNotBlank()) {
                completedIds.add(song.videoId)
                knownTracks.add(song.videoId)
                skippedIds.remove(song.videoId)
            }
            val artistKey = primaryArtistKey(song)
            if (artistKey.isNotBlank()) {
                knownArtists.add(artistKey)
                artistSkipCounts.remove(artistKey)
                cooledArtistKeys.remove(artistKey)
                adjustAffinityLocked(artistKey, if (isLike) 2.0 else 1.0)
            }
            anchorSong = song
        }
        notifyChanged()
    }

    /**
     * Records deliberate Play Next / Add to Queue intent for an online song:
     * clears prior cooldowns and boosts artist affinity without overwriting [anchorSong].
     */
    fun recordQueuedIntent(song: Song) {
        synchronized(lock) {
            if (song.videoId.isNotBlank()) {
                knownTracks.add(song.videoId)
                skippedIds.remove(song.videoId)
            }
            val artistKey = primaryArtistKey(song)
            if (artistKey.isNotBlank()) {
                knownArtists.add(artistKey)
                artistSkipCounts.remove(artistKey)
                cooledArtistKeys.remove(artistKey)
                val current = artistAffinityMap[artistKey] ?: 0.0
                artistAffinityMap[artistKey] = kotlin.math.max(current, 0.0) + 1.0
            }
        }
        notifyChanged()
    }

    /** Seeds known liked/library/history tracks and artists for Familiar vs Discovery Autoplay. */
    fun setKnownTaste(trackIds: Set<String>, artistNames: Set<String>) = synchronized(lock) {
        knownTracks.clear()
        knownTracks.addAll(trackIds.filter(String::isNotBlank))
        knownArtists.clear()
        artistNames.map(::normalizeArtist).filterTo(knownArtists, String::isNotBlank)
    }

    /** Records a "Not interested" dismissal on a Home Featured card. */
    fun dismissFeaturedPick(pick: TopPick) {
        synchronized(lock) {
            dismissedKeys.add(pick.carouselKey)
            pick.seedTitle?.takeIf(String::isNotBlank)?.let { dismissedKeys.add(it.lowercase(Locale.ROOT)) }
            val artistKey = normalizeArtist(pick.artistName ?: pick.subtitle)
            if (artistKey.isNotBlank()) {
                cooledArtistKeys.add(artistKey)
                adjustAffinityLocked(artistKey, -3.0)
            }
        }
        notifyChanged()
    }

    /** Hydrates persisted per-account taste memory (called on startup and account switch). */
    fun hydrate(snapshot: Snapshot) = synchronized(lock) {
        skippedIds.clear()
        skippedIds.addAll(snapshot.skippedVideoIds)
        cooledArtistKeys.clear()
        cooledArtistKeys.addAll(snapshot.cooledArtists)
        dismissedKeys.clear()
        dismissedKeys.addAll(snapshot.dismissedSeedKeys)
        artistAffinityMap.clear()
        artistAffinityMap.putAll(snapshot.artistAffinities)
        artistSkipCounts.clear()
        for (artist in snapshot.cooledArtists) {
            artistSkipCounts[artist] = ARTIST_COOLDOWN_SKIP_COUNT
        }
        anchorSong = null
    }

    fun snapshotForPersistence(): Snapshot = synchronized(lock) {
        Snapshot(
            skippedVideoIds = skippedIds.toList(),
            cooledArtists = cooledArtistKeys.toList(),
            dismissedSeedKeys = dismissedKeys.toList(),
            artistAffinities = artistAffinityMap.toMap(),
        )
    }

    fun skippedVideoIds(): Set<String> = synchronized(lock) { skippedIds.toSet() }
    fun cooledArtists(): Set<String> = synchronized(lock) { cooledArtistKeys.toSet() }
    fun dismissedSeedKeys(): Set<String> = synchronized(lock) { dismissedKeys.toSet() }
    fun completedVideoIds(): Set<String> = synchronized(lock) { completedIds.toSet() }
    fun knownTrackIds(): Set<String> = synchronized(lock) { knownTracks.toSet() }
    fun knownArtistKeys(): Set<String> = synchronized(lock) { knownArtists.toSet() }
    fun artistAffinities(): Map<String, Double> = synchronized(lock) { artistAffinityMap.toMap() }
    fun artistAffinity(artistName: String?): Double {
        val key = normalizeArtist(artistName)
        if (key.isBlank()) return 0.0
        return synchronized(lock) { artistAffinityMap[key] ?: 0.0 }
    }
    fun lastGoodAnchorSong(): Song? = synchronized(lock) { anchorSong }

    fun isArtistCooled(artistName: String?): Boolean {
        val key = normalizeArtist(artistName)
        if (key.isBlank()) return false
        return synchronized(lock) { key in cooledArtistKeys }
    }

    fun isSongCooled(song: Song): Boolean = synchronized(lock) {
        song.videoId in skippedIds || primaryArtistKey(song) in cooledArtistKeys
    }

    fun clearAnchor() = synchronized(lock) {
        anchorSong = null
    }

    fun reset() = synchronized(lock) {
        skippedIds.clear()
        artistSkipCounts.clear()
        cooledArtistKeys.clear()
        artistAffinityMap.clear()
        dismissedKeys.clear()
        completedIds.clear()
        knownTracks.clear()
        knownArtists.clear()
        anchorSong = null
    }
}
