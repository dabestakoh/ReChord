package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class AlternateTrackSwipe(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    var isDragging by mutableStateOf(false)
        private set
    var incomingDrop by mutableFloatStateOf(0f)
        private set
    var outgoingLift by mutableFloatStateOf(0f)
        private set
    var isButtonSwapping by mutableStateOf(false)
        private set
    var recordLanded by mutableStateOf(true)
        private set

    private var job: Job? = null
    private var awaitingId: String? = null
    private var requestedTrack: String? = null
    private var requestedDirection = 0f

    // The promoted cover may already be composed before the swipe's SideEffect settles the offset.
    fun artworkOffset(currentTrack: Song): Float = when {
        currentTrack.swipeIdentity == awaitingId -> 0f
        currentTrack.swipeIdentity == requestedTrack && requestedDirection != 0f -> -requestedDirection
        else -> offset
    }

    // Synchronous Frame-1 elevation for the current (slot == 0) vinyl disc so it NEVER snaps to 0f
    // for a single frame when a swiped or button-swapped track promotes to current.
    fun currentDropElevation(currentTrack: Song): Float = when {
        currentTrack.swipeIdentity == awaitingId -> 1f
        currentTrack.swipeIdentity == requestedTrack && requestedDirection != 0f -> 1f
        isButtonSwapping -> 1f
        else -> maxOf(incomingDrop, (kotlin.math.abs(offset) * 1.6f).coerceIn(0f, 1f))
    }

    // Synchronous elevation for neighbor (slot != 0) vinyl discs:
    // - During button swap Beat 1, the outgoing disc starts flat on the platter (0f) and lifts smoothly to 1f.
    // - During swipe, the incoming neighbor hovers cleanly at lifted height (1f).
    fun neighborDropElevation(currentTrack: Song): Float = when {
        currentTrack.swipeIdentity == requestedTrack && requestedDirection != 0f -> 0f
        isButtonSwapping -> outgoingLift
        else -> 1f
    }

    // True only when the current vinyl is centered on the spindle, fully dropped, and not being dragged.
    fun isRecordLanded(currentTrack: Song): Boolean = when {
        currentTrack.swipeIdentity == awaitingId -> false
        currentTrack.swipeIdentity == requestedTrack && requestedDirection != 0f -> false
        isDragging || isButtonSwapping -> false
        else -> recordLanded && incomingDrop <= 0.001f && kotlin.math.abs(offset) <= 0.001f
    }

    fun start() {
        job?.cancel()
        awaitingId = null
        requestedTrack = null
        isButtonSwapping = false
        outgoingLift = 0f
        isDragging = true
        recordLanded = false
    }

    fun expectTrack(target: Song?, direction: Float) {
        requestedTrack = target?.swipeIdentity
        requestedDirection = direction
    }

    fun drag(delta: Float, canPrevious: Boolean, canNext: Boolean) {
        val target = offset + delta
        val hasNeighbor = if (target < 0f) canNext else canPrevious
        offset = if (hasNeighbor) target.coerceIn(-1f, 1f) else {
            (offset + delta * 0.21f).coerceIn(-0.17f, 0.17f)
        }
    }

    fun end(target: Float, speed: Float, next: Song?, reduced: Boolean, skip: () -> Unit) {
        job?.cancel()
        awaitingId = next?.swipeIdentity
        job = scope.launch {
            if (reduced) offset = 0f else settle(target, speed)
            if (next != null) {
                skip()
                // Previous may restart the current song, or the playback queue can
                // change during the gesture. Recover if the intended item does not arrive.
                delay(1000)
                awaitingId = null
                isDragging = false
                settle(0f, 0f)
                incomingDrop = 0f
                recordLanded = true
            } else {
                isDragging = false
                incomingDrop = 0f
                recordLanded = true
            }
        }
    }

    fun update(song: Song, direction: Float, animate: Boolean, record: Boolean) {
        job?.cancel()
        val committed = awaitingId == song.swipeIdentity
        val motionDirection = if (requestedTrack == song.swipeIdentity) requestedDirection else direction
        awaitingId = null
        requestedTrack = null
        isDragging = false
        if (!animate) {
            offset = 0f
            incomingDrop = 0f
            outgoingLift = 0f
            isButtonSwapping = false
            recordLanded = true
            return
        }
        if (!record) {
            incomingDrop = 0f
            outgoingLift = 0f
            isButtonSwapping = false
            recordLanded = true
            if (motionDirection == 0f || committed) {
                offset = 0f
                return
            }
            offset = -motionDirection
            job = scope.launch { settle(0f, 0f) }
            return
        }
        if (committed) {
            // Swipe already cleared the tonearm and slid the incoming disc to dead center at elev = 1f.
            // Drop straight down onto the platter with zero 1-frame snap and zero mid-air pause.
            offset = 0f
            isButtonSwapping = false
            outgoingLift = 0f
            incomingDrop = 1f
            recordLanded = false
            job = scope.launch {
                Animatable(1f).animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 210f)) {
                    incomingDrop = value
                }
                incomingDrop = 0f
                recordLanded = true
            }
            return
        }
        if (motionDirection == 0f) {
            // Track changed from Queue/Search/external without a directional neighbor:
            // Beat 1: let tonearm clear to edge, Beat 3: drop new record down.
            offset = 0f
            isButtonSwapping = false
            outgoingLift = 0f
            incomingDrop = 1f
            recordLanded = false
            job = scope.launch {
                delay(110L)
                Animatable(1f).animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 210f)) {
                    incomingDrop = value
                }
                incomingDrop = 0f
                recordLanded = true
            }
            return
        }
        // Button Next/Previous (or auto-advance to next/prev):
        // Hold offset at -motionDirection so the outgoing disc stays visually centered on the platter
        // while Beat 1 lifts the tonearm & outgoing record smoothly off the platter.
        offset = -motionDirection
        isButtonSwapping = true
        outgoingLift = 0f
        incomingDrop = 1f
        recordLanded = false
        job = scope.launch {
            // Beat 1: tonearm clears to edge (110ms) while outgoing disc lifts smoothly off platter.
            Animatable(0f).animateTo(1f, tween(120, easing = FastOutSlowInEasing)) {
                outgoingLift = value
            }
            outgoingLift = 1f
            // Beat 2: horizontal swap at lifted height (both discs stay at elev = 1f).
            Animatable(offset).animateTo(0f, tween(340, easing = FastOutSlowInEasing)) {
                offset = value
            }
            offset = 0f
            isButtonSwapping = false
            outgoingLift = 0f
            // Beat 3: now centered over the spindle, drop straight down onto the platter.
            Animatable(1f).animateTo(0f, spring(dampingRatio = 0.88f, stiffness = 210f)) {
                incomingDrop = value
            }
            incomingDrop = 0f
            // Beat 4: signal VinylTonearm to glide from resting edge to groove & lower needle.
            recordLanded = true
        }
    }

    private suspend fun settle(target: Float, velocity: Float) {
        Animatable(offset).animateTo(
            target,
            spring(dampingRatio = 0.94f, stiffness = 330f, visibilityThreshold = 0.001f),
            initialVelocity = velocity.coerceIn(-4f, 4f),
        ) { offset = value }
        offset = target
    }
}

internal val Song.swipeIdentity: String get() = queueEntryId ?: videoId

@Composable
internal fun rememberAlternateTrackSwipe(
    song: Song,
    previous: Song?,
    next: Song?,
    animate: Boolean,
    record: Boolean,
): AlternateTrackSwipe {
    val scope = rememberCoroutineScope()
    val swipe = remember { AlternateTrackSwipe(scope) }
    var last by remember { mutableStateOf(song) }
    var oldNext by remember { mutableStateOf(next) }
    var oldPrevious by remember { mutableStateOf(previous) }
    SideEffect {
        if (last.swipeIdentity != song.swipeIdentity) {
            val direction = when (song.swipeIdentity) {
                oldNext?.swipeIdentity -> -1f
                oldPrevious?.swipeIdentity -> 1f
                else -> when (song.videoId) {
                    oldNext?.videoId -> -1f
                    oldPrevious?.videoId -> 1f
                    else -> 0f
                }
            }
            swipe.update(song, direction, animate, record)
        }
        last = song
        oldNext = next
        oldPrevious = previous
    }
    return swipe
}

@Composable
internal fun Modifier.alternateTrackGesture(
    swipe: AlternateTrackSwipe,
    previous: Song?,
    next: Song?,
    artworkStride: Float,
    reduced: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
): Modifier {
    val haptics = rememberHaptics()
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestPreviousAction by rememberUpdatedState(onPrevious)
    val latestNextAction by rememberUpdatedState(onNext)
    val locked by rememberUpdatedState(controlsLocked)
    val blocked by rememberUpdatedState(onBlockedControl)
    return pointerInput(swipe, reduced, artworkStride) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val width = size.width.coerceAtLeast(1).toFloat()
            val stride = width * artworkStride
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var travelled = 0f
            fun move(change: PointerInputChange, delta: Float) {
                velocity.addPosition(change.uptimeMillis, change.position)
                travelled += delta / stride
                if (!reduced) swipe.drag(delta / stride, latestPrevious != null, latestNext != null)
                change.consume()
            }
            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, delta ->
                swipe.start()
                move(change, delta)
            } ?: return@awaitEachGesture
            val released = horizontalDrag(slop.id) { change -> move(change, change.positionChange().x) }
            val speed = velocity.calculateVelocity().x / stride
            val direction = when {
                !released -> 0f
                (travelled < -0.40f || speed < -1.0f) && latestNext != null -> -1f
                (travelled > 0.40f || speed > 1.0f) && latestPrevious != null -> 1f
                else -> 0f
            }
            if (direction != 0f && locked) {
                blocked()
                swipe.end(0f, speed, null, reduced) {}
                return@awaitEachGesture
            }
            val target = when (direction) {
                -1f -> latestNext
                1f -> latestPrevious
                else -> null
            }
            swipe.end(direction, speed, target, reduced) {
                if (direction < 0f) {
                    haptics.play(Haptic.SkipNext)
                    latestNextAction()
                } else {
                    haptics.play(Haptic.SkipPrevious)
                    latestPreviousAction()
                }
            }
        }
    }
}

@Stable
internal class DefaultCoverSwipeState(
    val swipe: AlternateTrackSwipe,
) {
    var previousSong: Song? by mutableStateOf(null)
    var nextSong: Song? by mutableStateOf(null)
    var previousArt: String? by mutableStateOf(null)
    var nextArt: String? by mutableStateOf(null)
    var artworkWidthPx: Float by mutableFloatStateOf(0f)
}

@Composable
internal fun rememberDefaultCoverSwipeState(
    song: Song,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    animate: Boolean,
): DefaultCoverSwipeState {
    val prev = queue.getOrNull(queueIndex - 1).takeIf { queueIndex > 0 && hasPrevious }
    val next = queue.getOrNull(queueIndex + 1).takeIf { queueIndex >= 0 && hasNext }
    val prevCoverUrl = rememberRemoteArtworkUrl(prev)
    val nextCoverUrl = rememberRemoteArtworkUrl(next)
    val swipe = rememberAlternateTrackSwipe(
        song = song,
        previous = prev,
        next = next,
        animate = animate,
        record = false,
    )
    return remember(swipe) { DefaultCoverSwipeState(swipe) }.apply {
        previousSong = prev
        nextSong = next
        previousArt = prevCoverUrl
        nextArt = nextCoverUrl
    }
}

@Composable
internal fun DefaultSwipeNeighborCovers(
    state: DefaultCoverSwipeState,
    offsetFraction: Float,
) {
    if (abs(offsetFraction) <= 0.001f) return
    state.previousSong?.let { prev ->
        key(prev.swipeIdentity) {
            LayoutArtwork(
                state.previousArt,
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = (-1f + offsetFraction) * size.width
                },
            )
        }
    }
    state.nextSong?.let { next ->
        key(next.swipeIdentity) {
            LayoutArtwork(
                state.nextArt,
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = (1f + offsetFraction) * size.width
                },
            )
        }
    }
}
