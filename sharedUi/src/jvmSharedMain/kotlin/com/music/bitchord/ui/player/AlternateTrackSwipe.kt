package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
internal class AlternateTrackSwipe(private val scope: CoroutineScope) {
    var offset by mutableFloatStateOf(0f)
        private set
    private var job: Job? = null
    private var awaitingId: String? = null

    // The promoted cover may already be composed before the swipe's SideEffect settles the offset.
    fun artworkOffset(currentTrack: Song): Float =
        if (currentTrack.swipeIdentity == awaitingId) 0f else offset

    fun start() {
        job?.cancel()
        awaitingId = null
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
                settle(0f, 0f)
            }
        }
    }

    fun update(song: Song, direction: Float, animate: Boolean) {
        job?.cancel()
        val committed = awaitingId == song.swipeIdentity
        awaitingId = null
        if (!animate || direction == 0f || committed) {
            offset = 0f
            return
        }
        offset = -direction
        job = scope.launch { settle(0f, 0f) }
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
internal fun rememberAlternateTrackSwipe(song: Song, previous: Song?, next: Song?, animate: Boolean): AlternateTrackSwipe {
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
                else -> 0f
            }
            swipe.update(song, direction, animate)
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
): Modifier {
    val haptics = rememberHaptics()
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestPreviousAction by rememberUpdatedState(onPrevious)
    val latestNextAction by rememberUpdatedState(onNext)
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
