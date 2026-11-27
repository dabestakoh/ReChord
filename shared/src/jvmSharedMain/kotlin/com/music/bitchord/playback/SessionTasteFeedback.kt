package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.TopPick
import java.util.Locale

/**
 * Thread-safe session taste & negative-feedback tracker shared by
 * `PlaybackService`, `AutoplayRadio`, `TopPicks`, and `MainViewModel`.
 *
 * - Early skips (`< 15s`) softly demote the skipped song and cool down an
 *   artist once skipped twice in the same session.
 * - Completed (`>= 30s` / `>= 50%`) or liked tracks update [lastGoodAnchorSong]
 *   so Autoplay stays anchored to the listener's real vibe rather than an
 *   accidental tap or quick skip.
 * - "Not interested" dismissals from Home Featured immediately remove that
 *   card's seed and cool down its artist across both Featured and Autoplay.
 */
object SessionTasteFeedback {
    const val EARLY_SKIP_THRESHOLD_MS = 15_000L
    const val ARTIST_COOLDOWN_SKIP_COUNT = 2

    private val lock = Any()
    private val skippedIds = LinkedHashSet<String>()
    private val artistSkipCounts = HashMap<String, Int>()
    private val cooledArtistKeys = LinkedHashSet<String>()
    private val dismissedKeys = LinkedHashSet<String>()
    private val completedIds = LinkedHashSet<String>()
    private var anchorSong: Song? = null

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

    /**
     * Records an early skip on [song]. Returns `true` if the song's artist
     * reached the cooldown threshold (so callers can prune remaining queued
     * Autoplay items from that artist immediately).
     */
    fun recordEarlySkip(song: Song): Boolean = synchronized(lock) {
        if (song.videoId.isNotBlank()) skippedIds.add(song.videoId)
        val artistKey = primaryArtistKey(song)
        if (artistKey.isBlank()) return@synchronized false
        val nextCount = (artistSkipCounts[artistKey] ?: 0) + 1
        artistSkipCounts[artistKey] = nextCount
        if (nextCount >= ARTIST_COOLDOWN_SKIP_COUNT) {
            cooledArtistKeys.add(artistKey)
        } else {
            false
        }
    }

    /**
     * Records a track the listener actually finished (`>= 30s` or `>= 50%`)
     * or explicitly liked. Updates [lastGoodAnchorSong] and clears any prior
     * cooldown on that song/artist.
     */
    fun recordCompletedOrLiked(song: Song) = synchronized(lock) {
        if (song.videoId.isNotBlank()) {
            completedIds.add(song.videoId)
            skippedIds.remove(song.videoId)
        }
        val artistKey = primaryArtistKey(song)
        if (artistKey.isNotBlank()) {
            artistSkipCounts.remove(artistKey)
            cooledArtistKeys.remove(artistKey)
        }
        anchorSong = song
    }

    /** Records a "Not interested" dismissal on a Home Featured card. */
    fun dismissFeaturedPick(pick: TopPick) = synchronized(lock) {
        dismissedKeys.add(pick.carouselKey)
        pick.seedTitle?.takeIf(String::isNotBlank)?.let { dismissedKeys.add(it.lowercase(Locale.ROOT)) }
        val artistKey = normalizeArtist(pick.artistName ?: pick.subtitle)
        if (artistKey.isNotBlank()) cooledArtistKeys.add(artistKey)
    }

    fun skippedVideoIds(): Set<String> = synchronized(lock) { skippedIds.toSet() }
    fun cooledArtists(): Set<String> = synchronized(lock) { cooledArtistKeys.toSet() }
    fun dismissedSeedKeys(): Set<String> = synchronized(lock) { dismissedKeys.toSet() }
    fun completedVideoIds(): Set<String> = synchronized(lock) { completedIds.toSet() }
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
        dismissedKeys.clear()
        completedIds.clear()
        anchorSong = null
    }
}
