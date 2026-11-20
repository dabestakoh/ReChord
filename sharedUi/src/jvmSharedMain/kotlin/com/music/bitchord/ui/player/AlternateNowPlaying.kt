package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.music.bitchord.ui.theme.rememberArtworkPlayerAnalysis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.min

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
    previousSong: Song?,
    nextSong: Song?,
    durationMs: Long,
    artwork: Any?,
    artworkUrl: String? = artwork as? String,
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
    onSwipePrevious: () -> Unit = onPrevious,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onMenu: () -> Unit,
    onOpenPicker: (() -> Unit)?,
    reduceAnimation: Boolean,
    artworkBackdrop: Color,
    recordPalette: Color = artworkBackdrop,
    statusScrimAlpha: Float,
    pane: PlayerPane,
    lyricsPane: @Composable (Boolean) -> Unit,
    queuePane: @Composable (Boolean) -> Unit,
    lyrics: @Composable () -> Unit,
    scrubber: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val vinyl = layout == NowPlayingLayout.VINYL
    val haptics = rememberHaptics()
    val topHold = Modifier.openLayoutOnHold(onOpenPicker)
    val artHold = Modifier.openLayoutOnHold(onOpenPicker)
    val accent = LocalPlayerAccent.current
    val base = Color(0xFF141417)
    val swipe = rememberAlternateTrackSwipe(song, previousSong, nextSong, pane == PlayerPane.Main && !reduceAnimation)
    val prevArt = rememberRemoteArtworkUrl(previousSong)
    val nextArt = rememberRemoteArtworkUrl(nextSong)
    val prevColor = rememberArtworkPlayerAnalysis(prevArt).accent ?: Color.Unspecified
    val nextColor = rememberArtworkPlayerAnalysis(nextArt).accent ?: Color.Unspecified
    val vinylStyle by PlayerSettings.vinylAppearance.collectAsStateWithLifecycle()
    val lessBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    var seekingArm by remember { mutableStateOf(false) }
    val artworkStride = if (vinyl) 1.64f else 1.08f
    val gesture = if (preview) Modifier else Modifier.alternateTrackGesture(
        swipe, previousSong, nextSong, artworkStride, reduceAnimation, onSwipePrevious, onNext,
    )
    val tracks = remember(song, previousSong, nextSong) {
        listOfNotNull(previousSong?.let { -1 to it }, 0 to song, nextSong?.let { 1 to it })
    }
    Box(modifier.fillMaxSize().clipToBounds().drawWithCache {
        fun colors(tone: Color): Brush {
            val tint = tone.takeUnless { it == Color.Unspecified || it == Color.White } ?: Color(0xFF3E4147)
            return if (vinyl) {
                Brush.verticalGradient(listOf(lerp(base, tint, 0.30f), base))
            } else {
                Brush.linearGradient(
                    0f to lerp(base, tint, 0.26f),
                    0.37f to lerp(base, tint, 0.49f),
                    1f to lerp(base, tint, 0.12f),
                )
            }
        }
        val current = colors(artworkBackdrop)
        val previous = colors(prevColor)
        val next = colors(nextColor)
        onDrawBehind {
            drawRect(current)
            val offset = swipe.offset
            val toward = if (offset > 0f) previous else next
            val strength = kotlin.math.abs(offset)
            if (strength > 0.001f) drawRect(toward, alpha = strength.coerceAtMost(1f))
        }
    }) {
        if (!vinyl && artworkUrl != null) {
            val mesh = rememberArtworkMesh(artworkUrl)
            ArtworkMeshBackdrop(
                mesh = mesh,
                modifier = Modifier.graphicsLayer {
                    alpha = if (mesh == null) 0f else 0.36f * (1f - kotlin.math.abs(swipe.offset).coerceIn(0f, 1f))
                },
            )
        }
        Column(Modifier.fillMaxSize().then(if (preview) Modifier else Modifier.navigationBarsPadding())) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val compact = maxHeight < 364.dp
                val controlsHeight = if (compact) 100.dp else 192.dp
                Column(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = pane,
                        modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                        transitionSpec = {
                            if (reduceAnimation) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                val forward = targetState != PlayerPane.Main
                                val enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 285f)) {
                                    if (forward) min(it / 4, 132) else -it / 12
                                } + fadeIn(spring(stiffness = 285f))
                                val exit = slideOutVertically(spring(dampingRatio = 0.90f, stiffness = 420f)) {
                                    if (forward) -it / 12 else min(it / 4, 132)
                                } + fadeOut(spring(stiffness = 420f))
                                enter togetherWith exit
                            }
                        },
                        label = "alternatePlayerPane",
                    ) { shownPane ->
                        val active = shownPane == pane
                        val paneModifier = if (active) Modifier else {
                            Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize().then(paneModifier)) {
                            when (shownPane) {
                                PlayerPane.Main -> {
                                    val drag = onScrub.takeIf { canSeek && active }
                                    val artModifier = Modifier.fillMaxSize().then(gesture).then(artHold)
                                    Box(artModifier) {
                                        for ((slot, track) in tracks) key(track.swipeIdentity) {
                                            val current = slot == 0
                                            val request = when (slot) {
                                                -1 -> prevArt
                                                1 -> nextArt
                                                else -> artwork
                                            }
                                            val offset = Modifier.fillMaxSize().graphicsLayer {
                                                translationX = (slot + swipe.offset) * size.width * artworkStride
                                            }.then(if (current) Modifier else Modifier.clearAndSetSemantics {})
                                            if (vinyl) {
                                                val color = when (slot) {
                                                    -1 -> prevColor
                                                    1 -> nextColor
                                                    else -> recordPalette
                                                }
                                                VinylDisc(
                                                    request, track.swipeIdentity, vinylStyle, color,
                                                    active && current && isPlaying && !reduceAnimation,
                                                    if (current) shown else ({ 0f }),
                                                    { current && seekingArm }, offset,
                                                    albumKey = track.albumId ?: track.albumName ?: track.thumbnailUrl ?: track.videoId,
                                                )
                                            } else {
                                                BoxWithConstraints(offset) {
                                                    val spotlightHeight = (maxHeight * 0.28f).coerceIn(
                                                        minOf(maxHeight * 0.36f, 78.dp), 160.dp,
                                                    )
                                                    Column(Modifier.fillMaxSize()) {
                                                        PosterArtwork(
                                                            request, if (current) shown else ({ 0f }),
                                                            Modifier.fillMaxWidth().weight(1f), drag.takeIf { current },
                                                            onScrubFinished, onScrubCanceled, durationMs.takeIf { current } ?: 0L,
                                                            reduceAnimation,
                                                        )
                                                        Box(
                                                            Modifier.fillMaxWidth().height(spotlightHeight)
                                                                .padding(horizontal = if (compact) 20.dp else 24.dp),
                                                        ) { if (active && current) lyrics() }
                                                    }
                                                }
                                            }
                                        }
                                        if (vinyl) {
                                            VinylTonearm(
                                                shown, drag, onScrubFinished, onScrubCanceled, { seekingArm = it },
                                                reduceAnimation, lessBlur, { swipe.offset }, Modifier.fillMaxSize(),
                                                durationMs = durationMs,
                                            )
                                        }
                                    }
                                    if (statusScrimAlpha > 0f) {
                                        Box(
                                            Modifier.fillMaxWidth().height(80.dp).background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Black.copy(alpha = statusScrimAlpha), Color.Transparent),
                                                ),
                                            ),
                                        )
                                    }
                                }
                                PlayerPane.Lyrics, PlayerPane.Queue -> Box(
                                    Modifier.fillMaxSize()
                                        .windowInsetsPadding(stableStatusBarInsets())
                                        .padding(start = 18.dp, top = 48.dp, end = 18.dp),
                                ) {
                                    if (shownPane == PlayerPane.Lyrics) lyricsPane(active) else queuePane(active)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(controlsHeight).padding(start = 20.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            Modifier.weight(1f).then(
                                if (vinyl) Modifier else Modifier.align(Alignment.Bottom).padding(bottom = 10.dp),
                            ),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.fillMaxWidth().clipToBounds()) {
                                if (pane != PlayerPane.Main) {
                                    PlayerLayoutCredits(song.title, song.artist)
                                } else {
                                    for ((slot, track) in tracks) key(track.swipeIdentity) {
                                        Box(Modifier.fillMaxWidth().graphicsLayer {
                                            translationX = (slot + swipe.offset) * size.width * 1.08f
                                        }.then(if (slot == 0) Modifier else Modifier.clearAndSetSemantics {})) {
                                            PlayerLayoutCredits(track.title, track.artist)
                                        }
                                    }
                                }
                            }
                            if (vinyl) {
                                // Reserve the same placement for the credits across all three panes.
                                Box(Modifier.fillMaxWidth().height(32.dp).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                                    if (pane == PlayerPane.Main) lyrics()
                                }
                            }
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
                scrubber()
                actions()
                Spacer(Modifier.height(6.dp))
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .then(if (preview) Modifier else Modifier.windowInsetsPadding(stableStatusBarInsets()))
                .height(44.dp).then(topHold),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(42.dp, 5.dp).clip(CircleShape).background(accent.copy(alpha = 0.75f)))
            val menuInset = if (vinyl) maxWidth * 0.185f else 8.dp
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterEnd).padding(end = menuInset).menuAnchor()) {
                Icon(Icons.Rounded.MoreHoriz, stringResource(Res.string.more), tint = accent)
            }
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

private fun posterRimPoint(size: Size, fraction: Float) =
    Offset(size.width * fraction, size.height * (0.95f - 0.33f * fraction * fraction))

private class PosterArc(size: Size, inward: Float, horizontalInset: Float) {
    private val points = Array(49) { index ->
        val x = horizontalInset + (size.width - horizontalInset * 2f) * index / 48f
        posterRimPoint(size, x / size.width.coerceAtLeast(1f)) - Offset(0f, inward)
    }
    private val distance = FloatArray(points.size).apply {
        for (i in 1 until this.size) this[i] = this[i - 1] + (points[i] - points[i - 1]).getDistance()
    }
    val length get() = distance.last()
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (point in points.drop(1)) lineTo(point.x, point.y)
    }

    fun project(position: Offset): Pair<Float, Float> {
        var best = Float.POSITIVE_INFINITY
        var at = 0f
        for (i in 1 until points.size) {
            val delta = position - points[i - 1]
            val edge = points[i] - points[i - 1]
            val t = ((delta.x * edge.x + delta.y * edge.y) /
                (edge.x * edge.x + edge.y * edge.y).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val miss = (delta - edge * t).getDistanceSquared()
            if (miss < best) {
                best = miss
                at = distance[i - 1] + (distance[i] - distance[i - 1]) * t
            }
        }
        return at / length.coerceAtLeast(1f) to best
    }
}

@Composable
internal fun PosterArtwork(
    artwork: Any?,
    shown: () -> Float,
    modifier: Modifier,
    onScrub: ((Float) -> Unit)? = null,
    onFinish: () -> Unit = {},
    onCancel: () -> Unit = {},
    durationMs: Long = 0L,
    reduced: Boolean = true,
) {
    val accent = LocalPlayerAccent.current
    val ink = if (accent.luminance() < 0.33f) lerp(accent, Color.White, 0.48f) else lerp(accent, Color.White, 0.25f)
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    var seeking by remember { mutableStateOf(false) }
    val insetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 7.dp.toPx() }
    val expanded by animateFloatAsState(
        if (seeking) 1f else 0f, animationSpec = if (reduced) snap() else spring(), label = "posterArc",
    )
    val mask = remember {
        androidx.compose.foundation.shape.GenericShape { size, _ ->
            val start = posterRimPoint(size, 0f)
            val end = posterRimPoint(size, 1f)
            moveTo(start.x, start.y)
            cubicTo(size.width / 3f, start.y, size.width * 2f / 3f, size.height * 0.84f, end.x, end.y)
            lineTo(size.width, 0f)
            lineTo(0f, 0f)
            close()
        }
    }
    Box(modifier.clipToBounds()) {
        LayoutArtwork(artwork, Modifier.fillMaxSize().clip(mask))
        val rimGesture = Modifier.seekArtwork(
            shown, onScrub, onFinish, onCancel,
            accepts = { size, down, tolerance, _ ->
                PosterArc(size, insetPx, size.width * 0.034f).project(down).second <= tolerance * tolerance
            },
            along = { size, down, motion ->
                val slope = -0.66f * size.height * down.x / (size.width * size.width)
                abs(motion.y - slope * motion.x) < abs(motion.x) * 1.3f
            },
            fraction = { size, _, current, _ ->
                PosterArc(size, insetPx, size.width * 0.034f).project(current).first
            },
            onDragging = { seeking = it },
        )
        Box(Modifier.fillMaxSize().then(rimGesture).drawWithCache {
            val arc = PosterArc(size, 7.dp.toPx(), size.width * 0.034f)
            val measure = PathMeasure().apply { setPath(arc.path, false) }
            val played = Path()
            onDrawBehind {
                val amount = expanded.coerceIn(0f, 1f)
                val progress = shown().coerceIn(0f, 1f)
                val width = (2.1f + 2.6f * amount).dp.toPx()
                // The darker underlay separates the rim from both bright and similarly tinted covers.
                drawPath(arc.path, Color.Black.copy(alpha = 0.57f), style = Stroke(width + 2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(arc.path, ink.copy(alpha = 0.59f), style = Stroke(width, cap = StrokeCap.Round))
                played.reset()
                if (measure.getSegment(0f, measure.length * progress, played, true)) {
                    drawPath(played, ink, style = Stroke(width, cap = StrokeCap.Round))
                }
                if (amount > 0.04f && durationMs > 0L && measure.length > 0f) {
                    val start = measure.getPosition(0f)
                    val end = measure.getPosition(measure.length)
                    for ((point, ms) in arrayOf(start to (durationMs * progress).toLong(), end to durationMs)) {
                        val measureLabel = measurer.measure(formatTime(ms), timeStyle)
                        val x = if (point == start) point.x else point.x - measureLabel.size.width
                        val y = (point.y - measureLabel.size.height - 5.dp.toPx()).coerceAtLeast(0f)
                        val at = Offset(x.coerceAtLeast(0f), y)
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.72f * amount),
                            at - Offset(3.dp.toPx(), 1.dp.toPx()),
                            Size(measureLabel.size.width + 6.dp.toPx(), measureLabel.size.height + 2.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                        )
                        drawText(measureLabel, color = Color.White, topLeft = at, alpha = amount)
                    }
                }
            }
        })
    }
}

@Composable
internal fun Modifier.seekArtwork(
    shown: () -> Float,
    onScrub: ((Float) -> Unit)?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    accepts: (Size, Offset, Float, Float) -> Boolean,
    along: (Size, Offset, Offset) -> Boolean,
    fraction: (Size, Offset, Offset, Float) -> Float,
    claimDown: Boolean = false,
    onDragging: (Boolean) -> Unit = {},
): Modifier {
    if (onScrub == null) return this
    val currentShown by rememberUpdatedState(shown)
    val currentScrub by rememberUpdatedState(onScrub)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    val reportDragging by rememberUpdatedState(onDragging)
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
                        reportDragging(true)
                    }
                    change.consume()
                    currentScrub(fraction(bounds, down.position, change.position, initial).coerceIn(0f, 1f))
                }
            } finally {
                if (dragging) {
                    reportDragging(false)
                    if (!committed) cancel()
                }
            }
        }
    }
}
