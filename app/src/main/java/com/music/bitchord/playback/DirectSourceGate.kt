package com.music.bitchord.playback

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.MusicSource
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct Source Stream selects an addon before playback, with YouTube as
 * the bounded fallback. Restored current tracks prepare search metadata
 * while paused; the next track can resolve and buffer while music plays.
 * Preparation is shared by playback and read-ahead, and scoped to the
 * current/next selection and source settings.
 *
 * Automix's analysis-only Opus reads bypass this selection.
 */
object DirectSourceGate {

    private const val TAG = "BitChord"

    /** Playback's maximum wait for a stream, measured from the stream lookup's start. */
    const val LOOKUP_TIMEOUT_MS = 15_000L

    /**
     * Whether Direct Source Stream is on *and* has an addon to stream from.
     *
     * Never during a Listen Together party: a party seeks every member to the
     * host's position and expects the track to be there, and a fetch held
     * until the window opens would leave this phone behind the others.
     */
    fun isActive(): Boolean =
        AppSettings.directSourceStream.value && !ListenTogether.state.value.inParty &&
            SourceResolver.directAddons().isNotEmpty()

    private data class Open(val playing: Set<String>, val next: String?)

    private val open = MutableStateFlow(Open(emptySet(), null))

    /**
     * What is audible right now: the session's track and, mid-crossfade, the
     * other player's. Main thread. A change closes the window on whatever was
     * next, so a skip never inherits the previous track's read-ahead.
     *
     * @return whether anything changed.
     */
    fun onPlaying(mediaIds: Set<String>): Boolean {
        val now = open.value
        if (now.playing == mediaIds) return false
        open.value = Open(mediaIds, now.next?.takeIf { it in mediaIds })
        return true
    }

    /** Opens the window on [mediaId]. Returns false when it was already open. */
    fun openNext(mediaId: String): Boolean {
        val now = open.value
        if (now.next == mediaId || mediaId in now.playing) return false
        open.value = now.copy(next = mediaId)
        return true
    }

    private fun Open.admits(mediaId: String) = mediaId in playing || mediaId == next

    /**
     * Blocks a resolve for [mediaId] until it is current or next in the queue. Returns at once when the gate is off, the track is already
     * admitted, or the calling thread is inside [ungated].
     *
     * Runs on a loader thread under `runBlocking`. A released media period
     * interrupts that thread, which cancels the wait, so a skipped track never
     * leaves a loader parked here.
     */
    suspend fun awaitOpen(mediaId: String) {
        if (bypass.get() == true || !isActive() || open.value.admits(mediaId)) return
        TrackLog.d(TAG, "holding $mediaId until it is current or next", about = mediaId)
        combine(open, AppSettings.directSourceStream, ListenTogether.state, SourceRegistry.configs) { state, on, party, _ ->
            !on || party.inParty || SourceResolver.directAddons().isEmpty() || state.admits(mediaId)
        }
            .first { it }
        TrackLog.d(TAG, "released $mediaId", about = mediaId)
    }

    /** How much of the next track has to be on disk before it counts as ready to blend into. */
    private const val READY_BYTES = 512L * 1024

    /** How often [isStreamReady] may look at the cache for a track that isn't ready yet. */
    private const val READY_RECHECK_MS = 1_000L

    /** The last track asked about, whether it was ready, and when the cache was last asked. */
    private var readyCheck: Triple<String, Boolean, Long>? = null

    /**
     * Whether [item]'s audio is on disk far enough to start playing it, for
     * the Automix marker on the seek bar. Main thread.
     *
     * A file on the device is always ready. A streamed track needs its opening
     * [READY_BYTES] cached — written by [AudioCache.prefetchDirect] or by the
     * player's own preparation, or left over from an earlier play.
     *
     * Asked every crossfade tick, so the cache is read at most once a
     * [READY_RECHECK_MS] per track, and a yes is kept: bytes on disk do not
     * stop being there mid-song.
     */
    fun isStreamReady(item: androidx.media3.common.MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        if (uri.scheme != "bitchord") return true
        val now = android.os.SystemClock.elapsedRealtime()
        readyCheck?.let { (id, ready, at) ->
            if (id == item.mediaId && (ready || now - at < READY_RECHECK_MS)) return ready
        }
        // Or the whole file, for a track shorter than the threshold.
        val ready = AudioCache.cachedPrefixBytes(uri) >= READY_BYTES || AudioCache.isFullyCached(uri)
        readyCheck = Triple(item.mediaId, ready, now)
        return ready
    }

    private val bypass = ThreadLocal<Boolean>()

    /**
     * Runs [block] with the gate open, for a resolve made on behalf of
     * something other than this device's player. A Cast receiver is handed
     * URLs for items the phone is not playing.
     */
    fun <T> ungated(block: () -> T): T {
        val was = bypass.get()
        bypass.set(true)
        try {
            return block()
        } finally {
            bypass.set(was)
        }
    }

    private data class PreparationKey(
        val target: TrackMatcher.Target,
        val sources: List<SourceConfig>,
        val request: StreamRequest,
        val lengthSlack: Int,
    )

    private class Preparation(
        val key: PreparationKey,
        val scope: CoroutineScope,
        val sources: List<MusicSource>,
        val length: Deferred<Int?>?,
    ) {
        @Volatile var work: Deferred<SourceStream?>? = null
        var startedAtMs = 0L
    }

    private val preparations = ConcurrentHashMap<String, Preparation>()
    private const val STREAM_TTL_MS = 5 * 60_000L

    /** Metadata only: safe while the restored queue is paused. */
    @Synchronized
    fun prepare(videoId: String, target: TrackMatcher.Target) {
        if (!isActive() || target.title.isBlank() || target.isVideo) return
        val key = PreparationKey(
            target, SourceRegistry.configs.value.toList(),
            SourceResolver.requestForNow(), SourceResolver.upgradeSlackSec(),
        )
        val held = preparations[videoId]
        if (held?.key == key) return
        held?.scope?.cancel()
        val owner = CoroutineScope(Dispatchers.IO + SupervisorJob() + TrackLog.about(videoId))
        preparations[videoId] = Preparation(
            key, owner, SourceResolver.prepareDirectSources(owner, target),
            if (target.durationSec == null) owner.async { youTubeLength(videoId) } else null,
        )
    }

    /** Drop searches and URLs for tracks that are no longer current or next. */
    @Synchronized
    fun retain(mediaIds: Set<String>) {
        preparations.entries.removeIf { (id, preparation) ->
            if (id in mediaIds && isActive()) false
            else {
                preparation.scope.cancel()
                true
            }
        }
    }

    @Synchronized
    fun reset() {
        preparations.values.forEach { it.scope.cancel() }
        preparations.clear()
        open.value = Open(emptySet(), null)
        readyCheck = null
    }

    @Synchronized
    private fun startLookup(videoId: String, target: TrackMatcher.Target): Preparation? {
        prepare(videoId, target)
        val preparation = preparations[videoId] ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val work = preparation.work
        if (work == null || (work.isCompleted && now - preparation.startedAtMs >= STREAM_TTL_MS)) {
            preparation.startedAtMs = now
            preparation.work = preparation.scope.async(start = CoroutineStart.LAZY) {
                SourceResolver.directStream(
                    target, lengthOf = { preparation.length?.await() },
                    preparedSources = preparation.sources,
                )
            }.also { it.start() }
        }
        return preparation
    }

    /** Playback joins the metadata search already started for this track. */
    suspend fun lookup(videoId: String, target: TrackMatcher.Target): SourceStream? {
        val preparation = startLookup(videoId, target) ?: return null
        val work = preparation.work ?: return null
        val waited = android.os.SystemClock.elapsedRealtime() - preparation.startedAtMs
        val result = if (work.isCompleted) work.await()
            else withTimeoutOrNull(LOOKUP_TIMEOUT_MS - waited) { work.await() }
        return result?.takeIf {
            preparations[videoId] === preparation && isActive() &&
                preparation.key.request == SourceResolver.requestForNow() &&
                preparation.key.sources == SourceRegistry.configs.value
        }
    }

    /**
     * YouTube's runtimes for tracks queued without one, looked up for the
     * addon match and kept so playback's decoded-length check measures against
     * the same figure. Bounded: a long session queues a lot of tracks.
     */
    private val lengths = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > LENGTHS_KEPT
    }

    /** YouTube's runtime for [videoId], when [youTubeLength] has looked it up. */
    fun knownLength(videoId: String): Int? = synchronized(lengths) { lengths[videoId] }

    /**
     * YouTube's runtime for [videoId], from its watch-queue entry: metadata
     * only, one `next` call, no audio. Asked only for a track whose queue row
     * carried no duration — nearly every row in the app shows one, and that
     * figure rides in the playback URI already — and only alongside the addon
     * search, so it costs no time of its own. See [SourceResolver.directStream].
     */
    private suspend fun youTubeLength(videoId: String): Int? {
        knownLength(videoId)?.let { return it }
        val seconds = withTimeoutOrNull(LENGTH_TIMEOUT_MS) {
            YtMusicRepository.trackLinks(videoId).getOrNull()
                ?.durationText
                ?.let(TrackMatcher::secondsOf)
        }
        if (seconds == null) {
            TrackLog.d(TAG, "no YouTube runtime for $videoId; matching addons on title and artist", about = videoId)
            return null
        }
        synchronized(lengths) { lengths[videoId] = seconds }
        TrackLog.d(TAG, "YouTube runtime for $videoId is ${seconds}s; matching addons against it", about = videoId)
        return seconds
    }

    private const val LENGTHS_KEPT = 256

    /** Well inside an addon search's time, which is what it runs beside. */
    private const val LENGTH_TIMEOUT_MS = 5_000L

    /** The lookup for [videoId] if it is still going, past the point anyone waits for it. */
    fun stillLooking(videoId: String): Deferred<SourceStream?>? =
        preparations[videoId]?.work?.takeIf { !it.isCompleted }

    /**
     * [videoId] is about to play from YouTube because the addons did not
     * answer in time, so it gets the second look it would get with Direct
     * Source Stream off. A lookup still running is handed over and swaps in
     * when it lands; one that came back empty leaves the second look to search
     * for itself.
     *
     * Called only once the YouTube URL is in hand, because that walk is what
     * sets the bitrate a candidate has to beat.
     */
    fun startedOnYouTube(videoId: String, target: TrackMatcher.Target) {
        val pending = QualityUpgrade.settledForLess(
            mediaId = videoId,
            target = target,
            inFlight = stillLooking(videoId),
            playing = NerdStats.pickedBitrateKbps(videoId)?.let { StreamFormat(kbps = it) },
        )
        if (!pending) NerdStats.onLosslessRaceEnd(videoId)
    }
}
