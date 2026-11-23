package com.music.bitchord.ui.player

import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import com.music.bitchord.playback.PlaybackPosition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.painter.Painter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.model.PLAYER_ART_MEMORY_CACHE_KEY_EXTRA
import com.music.bitchord.ui.rememberIsForeground
import com.music.bitchord.ui.theme.ArtworkPlayerAnalysis
import com.music.bitchord.ui.theme.rememberArtworkPlayerAnalysis
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The looping clip some releases publish alongside their cover, for [song], or
 * null — a miss is the normal answer.
 */
@Composable
internal fun rememberCanvasArtwork(song: Song): CanvasArtwork? {
    val canvasEnabled by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by PlayerSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val disableSpotifyCanvas by PlayerSettings.disableSpotifyCanvas.collectAsStateWithLifecycle()
    val prioritizeSpotifyCanvas by PlayerSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    val meteredConnection by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    // The switch turns the feature off outright; this is the narrower "not
    // over cellular" case — see [PlayerSettings.canvasOverCellular] for why a
    // clip's own loop makes that worth guarding separately from a still image.
    val canvasAllowedNow = canvasEnabled && (meteredConnection != true || canvasOverCellular)
    // Keyed on the song's identity rather than its videoId: a version switch
    // swaps the videoId for another cut of the *same* song, and a canvas is
    // searched for by title and artist — resetting here threw the clip out
    // mid-loop, blanked the player down to the still sleeve and restarted it,
    // which is the flicker. A different song keys differently and starts
    // clean; the same-titled-impostor case is guarded where the clip is
    // adopted, by [CanvasArtwork.matches].
    var canvas by remember(song.title, song.artist) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(song.videoId, song.albumName, canvasAllowedNow, prioritizeSpotifyCanvas, disableSpotifyCanvas) {
        if (!canvasAllowedNow) {
            canvas = null
            return@LaunchedEffect
        }
        // Anything already settled for this track paints immediately: a
        // reopened player, or a track coming round again in the queue.
        //
        // Across a version switch the clip found for the last cut is carried
        // over rather than dropped — same title and artist, so the same
        // search, so the same clip — and only while it still *answers* to
        // what is playing: [CanvasArtwork.matches] is what stops a preserved
        // clip from being a different song's that happens to share a title.
        canvas = PlayerPlatform.host.cachedCanvas(song)
            ?: canvas?.takeIf {
                (!disableSpotifyCanvas || it.source != CanvasSource.SPOTIFY) &&
                    it.matches(song.title, song.artist, song.albumName)
            }

        // The album name is looked up separately and lands a moment after the
        // player opens, and it is the field that makes the catalogue searches
        // match. Give it that moment: if it arrives, this effect restarts and
        // all that was spent waiting is the wait. If it never does — a track
        // with no album, or a lookup that failed — the search still goes out,
        // just a beat later, which is imperceptible for decoration.
        if (canvas == null && song.albumName == null) delay(ALBUM_SETTLE_MS)
        // Keep what an earlier pass found if this one comes back empty, rather
        // than pulling a playing clip out from under itself.
        canvas = PlayerPlatform.host.canvasFor(song) ?: canvas
    }

    return canvas?.takeIf { canvasAllowedNow && (!disableSpotifyCanvas || it.source != CanvasSource.SPOTIFY) }
}

/**
 * The full-size cover request, shared by the player and the AutoMix prepared cover so
 * a prepared decode is the entry the player then reads.
 */
internal fun playerCoverRequest(
    context: PlatformContext,
    artworkUrl: String?,
    artPx: Int = ART_PX,
): ImageRequest.Builder =
    ImageRequest.Builder(context).data(artworkUrl).size(artPx)
        .memoryCacheKeyExtra(PLAYER_ART_MEMORY_CACHE_KEY_EXTRA, artPx.toString())

/**
 * The player's cover: the one request every surface draws it from, whether it
 * has loaded, and the bounded retries after a failure.
 *
 * Everything here is keyed on the cover's [url] — see [rememberPlayerArtwork].
 */
@Stable
internal class PlayerArtwork(
    val url: String?,
    private val context: PlatformContext,
    /** The last track's cover, drawn in this one's place until it loads — see [lastCover]. */
    private val holdOver: MemoryCache.Key? = null,
    private val artPx: Int = ART_PX,
) {
    /**
     * Whether the cover is on screen. Keyed on the artwork rather than on the
     * track, because that is what it actually describes and because only Coil
     * can set it back to true.
     */
    val loaded: Boolean get() = lastPainter != null

    var lastPainter by mutableStateOf<Painter?>(null)
        private set

    var attempt by mutableIntStateOf(0)
        private set

    /** Latched on an error, and only upwards — cleared by the retry itself. */
    internal var failed by mutableStateOf(false)

    /** This cover's memory-cache entry, once it has been drawn. */
    private var shown: MemoryCache.Key? = null
    private var decodedCover: DecodedAutoMixCover? = null
    private var decodedDuringMix: Long? = null

    fun decodedCoverBefore(generation: Long): DecodedAutoMixCover? =
        decodedCover?.takeIf { decodedDuringMix != generation }

    fun noteDisplayedCover(cover: DecodedAutoMixCover) {
        shown = cover.cacheKey
    }

    val lastCover: MemoryCache.Key? get() = shown ?: holdOver

    val request: ImageRequest by derivedStateOf {
        val attempt = attempt
        playerCoverRequest(context, url, artPx)
            .placeholderMemoryCacheKey(holdOver)
            .apply { if (attempt > 0) memoryCacheKeyExtra("attempt", attempt.toString()) }
            .build()
    }

    fun keepLoadedPainter(state: AsyncImagePainter.State, fallback: Painter? = null): AsyncImagePainter.State {
        val painter = lastPainter ?: fallback ?: return state
        return when (state) {
            is AsyncImagePainter.State.Loading -> state.copy(painter = painter)
            is AsyncImagePainter.State.Error -> state.copy(painter = painter)
            else -> state
        }
    }

    /** Fed from each painter drawing [request]. */
    fun onState(state: AsyncImagePainter.State) {
        if (state is AsyncImagePainter.State.Success) {
            lastPainter = state.painter
            failed = false
            shown = state.result.memoryCacheKey
            decodedCover = state.result.decodedAutoMixCover()
            decodedDuringMix = PlayerPlatform.host.autoMixVisualState?.value?.takeIf { it.mixActive }?.generation
        }
        if (state is AsyncImagePainter.State.Error) failed = true
    }

    internal fun retry() {
        failed = false
        attempt++
    }
}

@Composable
internal fun rememberPlayerArtwork(remoteArt: String?, artPx: Int = ART_PX): PlayerArtwork {
    val context = LocalPlatformContext.current
    val artUrl = remoteArt?.artworkAt(artPx)
    val last = remember(artPx) { arrayOfNulls<PlayerArtwork>(1) }
    val art = remember(artUrl, artPx) {
        PlayerArtwork(artUrl, context, holdOver = last[0]?.lastCover, artPx = artPx).also { last[0] = it }
    }
    LaunchedEffect(artUrl, art.failed) {
        // A track with no artwork at all fails immediately and would fail
        // identically three more times: there is no request to make, so there is
        // nothing a second go could do differently.
        if (artUrl == null || !art.failed || art.attempt >= ART_RETRIES) return@LaunchedEffect
        delay(ART_RETRY_DELAY_MS)
        art.retry()
    }
    return art
}

internal data class PlayerArtworkPresentation(
    val trackKey: String,
    val url: String?,
    val art: PlayerArtwork,
    val analysis: ArtworkPlayerAnalysis,
)

@Composable
internal fun rememberPlayerArtworkPresentation(
    trackKey: String,
    url: String?,
    artPx: Int,
    preview: Boolean,
): PlayerArtworkPresentation {
    val candidate = PlayerArtworkPresentation(
        trackKey, url, rememberPlayerArtwork(url, artPx), rememberArtworkPlayerAnalysis(url, artPx),
    )
    if (preview) return candidate

    val context = LocalPlatformContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val foreground = rememberIsForeground()
    val currentAnalysis by rememberUpdatedState(candidate.analysis)
    var lastVisible by remember { mutableStateOf(candidate) }
    var awaitingResume by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) awaitingResume = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val changed = candidate.trackKey != lastVisible.trackKey || candidate.url != lastVisible.url
    val hold = awaitingResume && changed
    // Ordinary skips keep the player's existing carousel. On resume, publish the cover and palette together.
    SideEffect { if (!awaitingResume) lastVisible = candidate }
    LaunchedEffect(foreground, awaitingResume, candidate.trackKey, candidate.art) {
        if (!foreground || !awaitingResume) return@LaunchedEffect
        withFrameNanos {}
        if (hold) {
            withTimeoutOrNull(1_500L) {
                if (url != null) SingletonImageLoader.get(context).execute(candidate.art.request)
                snapshotFlow { currentAnalysis }.first { it.accent != null || it.unavailable }
            }
        }
        awaitingResume = false
    }
    return if (hold) lastVisible else candidate
}

/**
 * The seek bar's own state: a drag in progress, and where the handle was
 * dropped until the player's position catches up with it.
 */
@Stable
internal class PlayerScrub {
    var scrubbing by mutableStateOf(false)
        private set
    var value by mutableFloatStateOf(0f)
        private set

    /**
     * After releasing the scrubber the player needs to buffer before it
     * reports the new position. Kept showing where the user dropped it so the
     * handle doesn't snap back and then jump forward once loading finishes.
     */
    internal var pendingSeek by mutableStateOf<Float?>(null)

    /** Where the handle is drawn. */
    fun shown(positionMs: Long, durationMs: Long): Float {
        val fraction = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        return when {
            scrubbing -> value
            pendingSeek != null -> pendingSeek!!
            else -> fraction.coerceIn(0f, 1f)
        }
    }

    fun drag(to: Float) {
        scrubbing = true
        value = to
    }

    fun cancel() {
        scrubbing = false
    }

    fun release(seekTo: (Float) -> Unit) {
        pendingSeek = value
        seekTo(value)
        scrubbing = false
    }
}

@Composable
internal fun rememberPlayerScrub(trackId: String, position: PlaybackPosition, durationMs: Long): PlayerScrub {
    val scrub = remember { PlayerScrub() }
    var pendingSeek by scrub::pendingSeek

    // Released as soon as the player's own position agrees with where the handle
    // was dropped — and unconditionally a few seconds later whether it agrees or
    // not.
    //
    // The agreement test alone is not enough, because it is the only thing that
    // ever cleared the override: if the position never passes close to the
    // target — a clamped or rejected seek, a rendition swapped underneath, a
    // progress sample that steps straight over the window — nothing releases it
    // and the handle sits frozen at the drop point for the rest of the track.
    // Audio and lyrics follow the real position perfectly throughout, so the
    // failure looks like a stuck seek bar on a track that is playing fine.
    //
    // Tolerance is absolute rather than a share of the duration: two percent is
    // a quarter-second on a jingle and twelve seconds on a long mix, and it is
    // the wall-clock gap that decides whether the handle appears to jump.
    //
    // Watched from inside the effect rather than keyed on the position, so the
    // screen that owns this does not have to read the playhead to drive it.
    LaunchedEffect(durationMs, pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        if (durationMs <= 0) return@LaunchedEffect
        snapshotFlow { position.positionMs }.first { positionMs ->
            abs(positionMs - (target * durationMs).toLong()) < SEEK_SETTLE_TOLERANCE_MS
        }
        pendingSeek = null
    }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == null) return@LaunchedEffect
        delay(SEEK_SETTLE_TIMEOUT_MS)
        pendingSeek = null
    }
    LaunchedEffect(trackId) { pendingSeek = null }

    return scrub
}

/**
 * The volume bar's state: the system output level, tweened to whatever the
 * system reports, and followed exactly under a finger.
 */
@Stable
internal class PlayerVolume(
    private val system: SystemVolume,
    private val scope: CoroutineScope,
) {
    /**
     * Animatable rather than plain state: a hardware volume step is a jump of
     * 1/15th of the bar, which reads as a stutter unless it's tweened.
     */
    val level = Animatable(system.level.value)
    var dragging by mutableStateOf(false)
        private set

    fun drag(to: Float) {
        dragging = true
        // Follow the finger exactly; only external changes tween.
        scope.launch { level.snapTo(to) }
        system.set(to)
    }

    fun release() {
        dragging = false
    }
}

@Composable
internal fun rememberPlayerVolume(): PlayerVolume {
    val system = PlayerPlatform.host.volume
    val scope = rememberCoroutineScope()
    val volume = remember(system) { PlayerVolume(system, scope) }
    // Hardware volume keys and the system panel change the level behind our
    // back; the host follows them, and so does the bar.
    val systemVolume by system.level.collectAsStateWithLifecycle()

    // Glide to the level the system reports, but never fight the finger — a
    // drag writes the level, which calls straight back through here.
    LaunchedEffect(systemVolume) {
        if (!volume.dragging) {
            volume.level.animateTo(systemVolume, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
    }

    return volume
}

