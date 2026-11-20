package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.NowPlayingLayout
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.menuAnchor
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.theme.stableStatusBarInsets
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun Modifier.openLayoutOnHold(action: (() -> Unit)?): Modifier {
    if (action == null) return this
    val current by rememberUpdatedState(action)
    val label = stringResource(Res.string.now_playing_layout)
    return semantics {
        onLongClick(label) { current(); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val held = awaitLongPressOrCancellation(down.id)
            if (held != null) {
                held.consume()
                current()
            }
        }
    }
}

@Composable
internal fun AlternateNowPlaying(
    layout: NowPlayingLayout,
    song: Song,
    artwork: Any?,
    isPlaying: Boolean,
    isLoading: Boolean,
    liked: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    canSeek: Boolean,
    shown: () -> Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onScrubCanceled: () -> Unit,
    onFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
    onOpenPicker: (() -> Unit)?,
    reduceAnimation: Boolean,
    artworkBackdrop: Color,
    lyrics: @Composable () -> Unit,
    scrubber: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vinyl = layout == NowPlayingLayout.VINYL
    val haptics = rememberHaptics()
    val topHold = Modifier.openLayoutOnHold(onOpenPicker)
    val artHold = Modifier.openLayoutOnHold(onOpenPicker)
    Column(
        modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(lerp(Color(0xFF16161B), artworkBackdrop, 0.32f), Color(0xFF111113))))
            .windowInsetsPadding(stableStatusBarInsets())
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(40.dp).then(topHold), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(42.dp, 5.dp).clip(CircleShape)
                    .background(LocalPlayerAccent.current.copy(alpha = 0.55f)),
            )
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).menuAnchor()) {
                Icon(Icons.Rounded.MoreHoriz, stringResource(Res.string.more), tint = LocalPlayerAccent.current)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val compact = maxHeight < 330.dp
            val controlsHeight = if (compact) 100.dp else 204.dp
            val artHeight = (maxHeight - controlsHeight).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                val artModifier = Modifier.fillMaxWidth().height(artHeight).then(artHold)
                val drag = if (canSeek) onScrub else null
                if (vinyl) {
                    VinylArtwork(
                        artwork, isPlaying && !reduceAnimation, shown, artModifier,
                        drag, onScrubFinished, onScrubCanceled, song.videoId,
                    )
                } else {
                    PosterArtwork(artwork, shown, artModifier, drag, onScrubFinished, onScrubCanceled)
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(start = 20.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (vinyl) PlayerLayoutCredits(song.title, song.artist) else lyrics()
                    }
                    val favorite: @Composable () -> Unit = {
                        PlaybackPill(
                            icon = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            description = stringResource(if (liked) Res.string.remove_from_liked else Res.string.like),
                            onClick = { haptics.play(Haptic.Tap); onFavorite() },
                        )
                    }
                    val previous: @Composable () -> Unit = {
                        PlaybackPill(
                            Res.drawable.ic_player_previous, stringResource(Res.string.widget_previous), previousEnabled,
                        ) { haptics.play(Haptic.SkipPrevious); onPrevious() }
                    }
                    val play: @Composable () -> Unit = {
                        if (isLoading) {
                            Box(Modifier.size(52.dp, 48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(21.dp), color = LocalPlayerAccent.current, strokeWidth = 2.dp)
                            }
                        } else {
                            PlaybackPill(
                                if (isPlaying) Res.drawable.ic_player_pause else Res.drawable.ic_player_play,
                                stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                                true,
                            ) { haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume); onPlayPause() }
                        }
                    }
                    val next: @Composable () -> Unit = {
                        PlaybackPill(
                            Res.drawable.ic_player_next, stringResource(Res.string.widget_next), nextEnabled,
                        ) { haptics.play(Haptic.SkipNext); onNext() }
                    }
                    if (compact) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Column { favorite(); previous() }
                            Column { play(); next() }
                        }
                    } else {
                        Column { favorite(); previous(); play(); next() }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (vinyl) Box(Modifier.fillMaxWidth().height(36.dp).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                lyrics()
            }
            scrubber()
            actions()
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
internal fun PlayerLayoutCredits(title: String?, artist: String) {
    Column {
        val titleStyle = MaterialTheme.typography.headlineSmall
        if (title != null) {
            Text(
                title,
                color = LocalPlayerAccent.current,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = titleStyle,
            )
        } else {
            val height = with(androidx.compose.ui.platform.LocalDensity.current) { titleStyle.lineHeight.toDp() }
            Spacer(Modifier.height(height))
        }
        Text(
            artist,
            color = LocalPlayerAccent.current.copy(alpha = 0.74f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PlaybackPill(icon: ImageVector, description: String, onClick: () -> Unit) {
    PlaybackPillFrame(onClick, true) {
        Icon(icon, description, tint = LocalPlayerAccent.current, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun PlaybackPill(icon: DrawableResource, description: String, enabled: Boolean, onClick: () -> Unit) {
    PlaybackPillFrame(onClick, enabled) {
        Icon(
            painterResource(icon), description, modifier = Modifier.size(22.dp),
            tint = LocalPlayerAccent.current.copy(alpha = if (enabled) 1f else 0.32f),
        )
    }
}

@Composable
private fun PlaybackPillFrame(onClick: () -> Unit, enabled: Boolean, icon: @Composable () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(52.dp, 48.dp)) {
        Box(
            Modifier.size(46.dp, 39.dp).background(LocalPlayerAccent.current.copy(alpha = 0.13f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

@Composable
internal fun LayoutArtwork(artwork: Any?, modifier: Modifier) {
    val accent = LocalPlayerAccent.current
    Box(modifier.background(Brush.linearGradient(listOf(lerp(Color(0xFF242332), accent, 0.46f), Color(0xFF292023))))) {
        if (artwork != null) AsyncImage(artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

private const val ArmTravelRadians = 0.42f

private fun armPivot(size: Size) = Offset(size.width * 0.83f, size.height * 0.13f)
private fun vinylCenter(size: Size) = Offset(size.width * 0.23f, size.height * 0.51f)

private fun armTip(size: Size, progress: Float): Offset {
    val radius = min(size.width * 0.64f, size.height * 0.61f)
    val firstGroove = vinylCenter(size) + Offset(radius * 0.94f, radius * 0.30f)
    val fromPivot = firstGroove - armPivot(size)
    val angle = atan2(fromPivot.y, fromPivot.x) + progress * ArmTravelRadians
    return armPivot(size) + Offset(cos(angle), sin(angle)) * fromPivot.getDistance()
}

@Composable
internal fun VinylArtwork(
    artwork: Any?,
    isPlaying: Boolean,
    shown: () -> Float,
    modifier: Modifier,
    onScrub: ((Float) -> Unit)? = null,
    onFinish: () -> Unit = {},
    onCancel: () -> Unit = {},
    trackKey: String = "",
) {
    var rotation by remember(trackKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, trackKey) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            rotation = (rotation + (frame - last) / 1_000_000_000f * 32f) % 360f
            last = frame
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val radius = minOf(maxWidth * 0.64f, maxHeight * 0.61f)
        val labelRadius = radius * 0.50f
        Canvas(Modifier.fillMaxSize()) {
            val center = vinylCenter(size)
            val r = radius.toPx()
            if (r <= 0f) return@Canvas
            drawCircle(Brush.radialGradient(listOf(Color(0xFF34343B), Color(0xFF101015), Color(0xFF24242A)), center, r), r, center)
            for (groove in 0..28) {
                val spoke = r * (0.52f + groove * 0.016f)
                drawCircle(Color.Black.copy(alpha = if (groove % 7 == 0) 0.65f else 0.30f), spoke, center, style = Stroke(0.8.dp.toPx()))
            }
            drawCircle(
                Brush.sweepGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                    center,
                ),
                r,
                center,
            )
        }
        LayoutArtwork(
            artwork,
            Modifier.offset(x = maxWidth * 0.23f - labelRadius, y = maxHeight * 0.51f - labelRadius)
                .size(labelRadius * 2)
                .graphicsLayer { rotationZ = rotation; shape = CircleShape; clip = true },
        )
        val armGesture = Modifier.seekArtwork(
            shown, onScrub, onFinish, onCancel,
            claimDown = true,
            accepts = { bounds, down, tolerance, fraction ->
                val base = armPivot(bounds)
                val arm = armTip(bounds, fraction) - base
                val delta = down - base
                val projection = ((arm.x * delta.x + arm.y * delta.y) / (arm.x * arm.x + arm.y * arm.y)).coerceIn(0f, 1f)
                (delta - arm * projection).getDistance() <= tolerance
            },
            along = { _, _, _ -> true },
            fraction = { bounds, down, current, initial ->
                val base = armPivot(bounds)
                val before = down - base
                val after = current - base
                val delta = atan2(after.y, after.x) - atan2(before.y, before.x)
                initial + atan2(sin(delta), cos(delta)) / ArmTravelRadians
            },
        )
        Canvas(Modifier.fillMaxSize().then(armGesture)) {
            val pivot = armPivot(size)
            val tip = armTip(size, shown())
            drawCircle(Color(0xFF17171E), 19.dp.toPx(), pivot)
            drawCircle(Color(0xFF4A4851), 10.dp.toPx(), pivot)
            drawLine(Color.Black.copy(alpha = 0.62f), pivot + Offset(3f, 4f), tip + Offset(3f, 4f), 9.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFFC2BEC9), pivot, tip, 5.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF2C2B35), pivot + (tip - pivot) * 0.90f, tip, 12.dp.toPx(), StrokeCap.Round)
        }
    }
}

private fun posterRimY(size: Size, fraction: Float): Float = size.height * (0.95f - 0.33f * fraction * fraction)

private fun Path.posterRim(size: Size) {
    moveTo(0f, posterRimY(size, 0f))
    cubicTo(size.width / 3f, size.height * 0.95f, size.width * 2f / 3f, size.height * 0.84f, size.width, posterRimY(size, 1f))
}

@Composable
internal fun PosterArtwork(
    artwork: Any?,
    shown: () -> Float,
    modifier: Modifier,
    onScrub: ((Float) -> Unit)? = null,
    onFinish: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val accent = LocalPlayerAccent.current
    val mask = remember {
        androidx.compose.foundation.shape.GenericShape { size, _ ->
            posterRim(size)
            lineTo(size.width, 0f)
            lineTo(0f, 0f)
            close()
        }
    }
    Box(modifier.clipToBounds()) {
        LayoutArtwork(artwork, Modifier.fillMaxSize().clip(mask))
        val rimGesture = Modifier.seekArtwork(
            shown, onScrub, onFinish, onCancel,
            accepts = { size, down, tolerance, _ -> abs(down.y - (posterRimY(size, down.x / size.width))) <= tolerance },
            along = { size, down, motion ->
                val slope = -0.66f * size.height * down.x / (size.width * size.width)
                abs(motion.y - slope * motion.x) < abs(motion.x) * 1.3f
            },
            fraction = { size, _, current, _ -> current.x / size.width },
        )
        Canvas(Modifier.fillMaxSize().then(rimGesture)) {
            val inset = Size(size.width, (size.height - 7.dp.toPx()).coerceAtLeast(0f))
            val rim = Path().apply { posterRim(inset) }
            val measure = PathMeasure().apply { setPath(rim, false) }
            val segment = Path()
            val margin = 12.dp.toPx().coerceAtMost(measure.length * 0.2f)
            val end = measure.length - margin
            if (measure.getSegment(margin, end, segment, true)) {
                drawPath(segment, accent.copy(alpha = 0.38f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            segment.reset()
            if (measure.getSegment(margin, margin + (end - margin) * shown(), segment, true)) {
                drawPath(segment, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Modifier.seekArtwork(
    shown: () -> Float,
    onScrub: ((Float) -> Unit)?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    accepts: (Size, Offset, Float, Float) -> Boolean,
    along: (Size, Offset, Offset) -> Boolean,
    fraction: (Size, Offset, Offset, Float) -> Float,
    claimDown: Boolean = false,
): Modifier {
    if (onScrub == null) return this
    val currentShown by rememberUpdatedState(shown)
    val currentScrub by rememberUpdatedState(onScrub)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val bounds = Size(size.width.toFloat(), size.height.toFloat())
            if (bounds.isEmpty()) return@awaitEachGesture
            val initial = currentShown()
            if (!accepts(bounds, down.position, 23.dp.toPx(), initial)) return@awaitEachGesture
            if (claimDown) down.consume()

            var dragging = false
            var committed = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        if (dragging) {
                            finish()
                            committed = true
                        }
                        break
                    }
                    if (!dragging) {
                        val delta = change.position - down.position
                        if (delta.getDistance() < viewConfiguration.touchSlop) continue
                        if (!along(bounds, down.position, delta)) break
                        dragging = true
                    }
                    change.consume()
                    currentScrub(fraction(bounds, down.position, change.position, initial).coerceIn(0f, 1f))
                }
            } finally {
                if (dragging && !committed) cancel()
            }
        }
    }
}
