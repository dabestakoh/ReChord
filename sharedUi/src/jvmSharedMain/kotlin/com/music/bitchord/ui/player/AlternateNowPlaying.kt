package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.atan2
import kotlin.math.PI
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
import androidx.compose.ui.unit.Dp
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
    volume: (@Composable () -> Unit)?,
    quality: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    deckVisible: Boolean = true,
    deckReveal: Animatable<Float, AnimationVector1D>? = null,
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
    panelGestures: Modifier = Modifier,
    onArtworkBand: (Rect) -> Unit = {},
    queuePreview: (() -> Float)? = null,
    onDismissPane: () -> Unit = {},
    playbackOrigin: String? = null,
    onOpenPlaybackOrigin: () -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String?, String) -> Unit = { _, _ -> },
    spinRecord: Boolean = isPlaying,
    dock: PlayerDock? = null,
) {
    val vinyl = layout == NowPlayingLayout.VINYL
    val haptics = rememberHaptics()
    val topHold = Modifier.openLayoutOnHold(onOpenPicker)
    val artHold = Modifier.openLayoutOnHold(onOpenPicker)
    val accent = LocalPlayerAccent.current
    val base = Color(0xFF141417)
    val swipe = rememberAlternateTrackSwipe(
        song, previousSong, nextSong, pane == PlayerPane.Main && !reduceAnimation, record = vinyl,
    )
    val prevArt = rememberRemoteArtworkUrl(previousSong)
    val nextArt = rememberRemoteArtworkUrl(nextSong)
    val prevAnalysis = rememberArtworkPlayerAnalysis(prevArt)
    val nextAnalysis = rememberArtworkPlayerAnalysis(nextArt)
    val prevColor = prevAnalysis.accent ?: Color.Unspecified
    val nextColor = nextAnalysis.accent ?: Color.Unspecified
    val vinylStyle by PlayerSettings.vinylAppearance.collectAsStateWithLifecycle()
    val lessBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    var seekingArm by remember { mutableStateOf(false) }
    val artworkStride = if (vinyl) 1.64f else 1f
    val gesture = if (preview) Modifier else Modifier.alternateTrackGesture(
        swipe, previousSong, nextSong, artworkStride, reduceAnimation, onSwipePrevious, onNext,
        controlsLocked, onBlockedControl,
    )
    val textOpacity by animateFloatAsState(
        (1f - abs(swipe.artworkOffset(song)) * 3.5f).coerceIn(0f, 1f),
        animationSpec = if (reduceAnimation) snap() else tween(130),
        label = "alternateTrackText",
    )
    val tracks = remember(song, previousSong, nextSong) {
        listOfNotNull(previousSong?.let { -1 to it }, 0 to song, nextSong?.let { 1 to it })
    }
    var dockRoot by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var posterCurveHeight by remember { mutableStateOf<Dp?>(null) }
    var posterSeekFooter by remember { mutableStateOf(POSTER_SEEK_FOOTER) }
    var dockRestOrigin by remember { mutableStateOf<Offset?>(null) }
    var artworkSource by remember(pane) { mutableStateOf<LayoutCoordinates?>(null) }
    var artworkAngle by remember(pane) { mutableStateOf<(() -> Float)?>(null) }
    val reportArtwork: (LayoutCoordinates) -> Unit = {
        if (dock != null && it.isAttached && it.size.width > 0 && it.size.height > 0) {
            artworkSource = it
        }
    }
    if (dock != null && dockRestOrigin != null && artworkSource?.isAttached == true) {
        SideEffect { if (!dock.attached) dock.attached = true }
    }
    // Share the last few frames with the actual cover, which has the same crop and clip.
    fun liveCoverAlpha(): Float {
        if (dock == null || !dock.hasMiniArt() || artworkSource?.isAttached != true) return 1f
        return ((dock.openFraction() - 0.94f) / 0.06f).coerceIn(0f, 1f)
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        val offset = dock?.offset()?.toInt()?.toFloat() ?: return@onGloballyPositioned
        val origin = coordinates.localToScreen(Offset.Zero)
        if (origin.isSpecified) {
            dockRoot = coordinates
            dockRestOrigin = origin - Offset(0f, offset)
        }
    }) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val fraction = dock?.openFraction() ?: 1f
            alpha = ((fraction - 0.18f) / 0.82f).coerceIn(0f, 1f)
        }.drawWithCache {
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
            val previous = colors(if (prevColor == Color.Unspecified && !prevAnalysis.unavailable) artworkBackdrop else prevColor)
            val next = colors(if (nextColor == Color.Unspecified && !nextAnalysis.unavailable) artworkBackdrop else nextColor)
            onDrawBehind {
                drawRect(current)
                val offset = swipe.artworkOffset(song)
                val toward = if (offset > 0f) previous else next
                val strength = kotlin.math.abs(offset)
                if (strength > 0.001f) drawRect(toward, alpha = strength.coerceAtMost(1f))
            }
        }) {
            if (!vinyl) {
                for ((slot, track) in tracks) key(track.swipeIdentity) {
                    val mesh = rememberArtworkMesh(when (slot) {
                        -1 -> prevArt
                        1 -> nextArt
                        else -> artworkUrl
                    })
                    if (mesh != null) {
                        ArtworkMeshBackdrop(mesh, Modifier.graphicsLayer {
                            alpha = 0.36f * (1f - abs(slot + swipe.artworkOffset(song))).coerceIn(0f, 1f)
                        })
                    }
                }
            }
            Column(Modifier.fillMaxSize().then(if (preview) Modifier else Modifier.navigationBarsPadding())) {
                BoxWithConstraints(Modifier.fillMaxSize().then(panelGestures)) {
                    val compact = maxHeight < 465.dp
                    val transportHeight = if (compact) 144.dp else 240.dp
                    val posterTransport = !vinyl && !compact
                    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                    val controlsHeight = if (posterTransport) maxOf(86.dp, 75.dp * fontScale) else transportHeight
                    var footerHeight by remember { mutableStateOf(0) }
                    val layoutHeight = maxHeight
                    var fullDeckHeight by remember { mutableStateOf(0) }
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val artworkLimit = with(density) { (layoutHeight - fullDeckHeight.toDp()).coerceAtLeast(1.dp) }
                    val safeTop = stableStatusBarInsets()
                    val mountTopInset = if (preview) 0 else safeTop.getTop(density)
                    val reveal = deckReveal ?: remember { Animatable(1f) }
                    val showPosterRail = posterTransport && (deckVisible || reveal.value > 0f)
                    val paneEndInset = if (showPosterRail) 72.dp else 18.dp
                    val menu: @Composable () -> Unit = {
                        PlaybackPill(Icons.Rounded.MoreHoriz, stringResource(Res.string.more), Modifier.menuAnchor(), onMenu)
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
                        ) {
                            swipe.expectTrack(previousSong, 1f)
                            haptics.play(Haptic.SkipPrevious)
                            onPrevious()
                        }
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
                        ) {
                            swipe.expectTrack(nextSong, -1f)
                            haptics.play(Haptic.SkipNext)
                            onNext()
                        }
                    }
                    val vinylMain = vinyl && pane == PlayerPane.Main
                    val vinylAlpha by animateFloatAsState(
                        if (vinylMain) 1f else 0f,
                        animationSpec = when {
                            reduceAnimation -> snap()
                            vinylMain -> tween(durationMillis = 145, delayMillis = 105)
                            else -> tween(durationMillis = 100)
                        },
                        label = "vinylPaneAlpha",
                    )
                    val vinylSlide by animateFloatAsState(
                        if (vinylMain) 0f else 1f,
                        animationSpec = when {
                            reduceAnimation -> snap()
                            vinylMain -> spring(dampingRatio = 0.86f, stiffness = 285f)
                            else -> spring(dampingRatio = 0.90f, stiffness = 420f)
                        },
                        label = "vinylPaneSlide",
                    )
                    if (vinyl && (vinylMain || vinylAlpha > 0.001f)) {
                        val drag = onScrub.takeIf { canSeek && vinylMain }
                        val vinylStageModifier = Modifier
                            .fillMaxSize()
                            .then(if (vinylMain) gesture.then(artHold) else Modifier.clearAndSetSemantics {})
                            .graphicsLayer {
                                alpha = (vinylAlpha * (1f - (queuePreview?.invoke() ?: 0f))).coerceIn(0f, 1f)
                                translationY = -vinylSlide * (size.height / 12f)
                            }
                        Box(vinylStageModifier) {
                            for ((slot, track) in tracks) key(track.swipeIdentity) {
                                val current = slot == 0
                                val request = when (slot) {
                                    -1 -> prevArt
                                    1 -> nextArt
                                    else -> artworkUrl ?: artwork
                                }
                                val color = when (slot) {
                                    -1 -> prevColor
                                    1 -> nextColor
                                    else -> recordPalette
                                }
                                VinylDisc(
                                    request, track.swipeIdentity, vinylStyle, color,
                                    vinylMain && spinRecord && !reduceAnimation && (current || abs(swipe.artworkOffset(song)) > 0.16f),
                                    if (current) shown else ({ 0f }),
                                    { current && seekingArm },
                                    Modifier.fillMaxSize().graphicsLayer {
                                        translationX = (slot + swipe.artworkOffset(song)) * size.width * artworkStride
                                    },
                                    albumKey = track.albumId ?: track.albumName ?: track.thumbnailUrl ?: track.videoId,
                                    labelPlacement = if (vinylMain && current) ({ coordinates, angle ->
                                        artworkAngle = angle
                                        reportArtwork(coordinates)
                                    }) else null,
                                    coastOnPause = vinylMain && current && !reduceAnimation,
                                    coverAlpha = { if (vinylMain && current) liveCoverAlpha() else 1f },
                                    mountTopInset = mountTopInset,
                                )
                            }
                            VinylTonearm(
                                shown, drag, onScrubFinished, onScrubCanceled, { seekingArm = it },
                                reduceAnimation, lessBlur, { swipe.artworkOffset(song) }, Modifier.fillMaxSize(),
                                durationMs = durationMs,
                                trackKey = song.swipeIdentity,
                                mountTopInset = mountTopInset,
                            )
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
                    }
                    Column(Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = pane,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .then(if (vinyl) Modifier else Modifier.clipToBounds())
                                .onGloballyPositioned { if (pane == PlayerPane.Main) onArtworkBand(it.boundsInRoot()) },
                            transitionSpec = {
                                if (reduceAnimation) {
                                    fadeIn(snap()) togetherWith fadeOut(snap())
                                } else {
                                    val forward = targetState != PlayerPane.Main
                                    val enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 285f)) {
                                        if (forward) min(it / 4, 132) else -it / 12
                                    } + fadeIn(tween(durationMillis = 145, delayMillis = 105))
                                    val exit = slideOutVertically(spring(dampingRatio = 0.90f, stiffness = 420f)) {
                                        if (forward) -it / 12 else min(it / 4, 132)
                                    } + fadeOut(tween(durationMillis = 100))
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
                                        if (vinyl) {
                                            Spacer(Modifier.fillMaxSize())
                                        } else {
                                            val drag = onScrub.takeIf { canSeek && active }
                                            var posterSeekExpand by remember { mutableStateOf(0f) }
                                            val artModifier = Modifier.fillMaxSize().then(gesture).then(artHold)
                                                .graphicsLayer { alpha = 1f - (queuePreview?.invoke() ?: 0f) }
                                            Box(artModifier) {
                                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                                    val stageHeight = minOf(maxHeight, artworkLimit)
                                                    val previousHeight = stageHeight * if (compact) 0.59f else 0.57f
                                                    val curveHeight = (previousHeight - POSTER_SEEK_FOOTER).coerceAtLeast(1.dp)
                                                    val lyricsRoom = minOf(stageHeight * 0.50f, maxOf(112.dp * fontScale, stageHeight * 0.30f))
                                                    val coverHeight = (minOf(
                                                        stageHeight - lyricsRoom,
                                                        maxWidth * 1.22f + POSTER_SEEK_FOOTER,
                                                    ) - POSTER_SEEK_FOOTER).coerceAtLeast(1.dp)
                                                    // The rim is lowest at the left. Reserve its gap, the seek
                                                    // touch radius or timestamp height, and a little text clearance.
                                                    val seekFooter = (9.dp + maxOf(23.dp, 16.dp * fontScale) + 4.dp -
                                                        coverHeight * 0.05f).coerceAtLeast(8.dp)
                                                    if (active) SideEffect {
                                                        posterCurveHeight = curveHeight
                                                        posterSeekFooter = seekFooter
                                                    }
                                                    Column(Modifier.fillMaxWidth().height(stageHeight)) {
                                                        PosterArtwork(
                                                            null, shown, Modifier.fillMaxWidth().height(coverHeight + seekFooter)
                                                                .onGloballyPositioned { if (active) reportArtwork(it) }, drag,
                                                            onScrubFinished, onScrubCanceled, durationMs, reduceAnimation,
                                                            curveHeight = curveHeight,
                                                            seekFooter = seekFooter,
                                                            onSeekExpand = { posterSeekExpand = it },
                                                        ) {
                                                            for ((slot, track) in tracks) key(track.swipeIdentity) {
                                                                val art = when (slot) {
                                                                    -1 -> prevArt
                                                                    1 -> nextArt
                                                                    else -> artworkUrl ?: artwork
                                                                }
                                                                LayoutArtwork(art, Modifier.fillMaxSize().graphicsLayer {
                                                                    translationX = (slot + swipe.artworkOffset(song)) * size.width
                                                                    if (slot == 0 && active) alpha = liveCoverAlpha()
                                                                })
                                                            }
                                                        }
                                                        Box(
                                                            Modifier.fillMaxWidth().weight(1f)
                                                                .padding(start = 20.dp, end = if (compact) 20.dp else 72.dp)
                                                                .graphicsLayer {
                                                                    alpha = textOpacity
                                                                    translationY = posterSeekExpand * 14.dp.toPx()
                                                                },
                                                        ) { if (active) key(song.swipeIdentity) { lyrics() } }
                                                    }
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
                                    }
                                    PlayerPane.Lyrics, PlayerPane.Queue -> Box(
                                        Modifier.fillMaxSize().clipToBounds()
                                            .windowInsetsPadding(stableStatusBarInsets())
                                            .padding(start = 18.dp, top = 48.dp, end = paneEndInset),
                                    ) {
                                        if (shownPane == PlayerPane.Lyrics) lyricsPane(active) else queuePane(active)
                                    }
                                }
                                if (queuePreview != null && shownPane == PlayerPane.Main) {
                                    Box(Modifier.fillMaxSize().windowInsetsPadding(stableStatusBarInsets())
                                        .padding(start = 18.dp, end = paneEndInset, top = 48.dp)
                                        .graphicsLayer {
                                            alpha = ((queuePreview() - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                            translationY = (1f - queuePreview()) * 26.dp.toPx()
                                        }
                                        .clearAndSetSemantics {},
                                    ) { queuePane(false) }
                                }
                            }
                        }
                        SlidingPlayerDeck(visible = deckVisible, reveal = reveal, reduced = reduceAnimation,
                            modifier = Modifier.clipToBounds(),
                        ) {
                            Column(Modifier.fillMaxWidth().onSizeChanged { fullDeckHeight = it.height }) {
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
                                        Box(Modifier.fillMaxWidth().graphicsLayer {
                                            alpha = if (pane == PlayerPane.Main) textOpacity else 1f
                                        }) {
                                            PlayerLayoutCredits(song, onOpenAlbum, onOpenArtist)
                                        }
                                        if (playbackOrigin != null && pane == PlayerPane.Main) {
                                            PlaybackOriginCaption(
                                                playbackOrigin, onOpenPlaybackOrigin, TextAlign.Start, PaddingValues(0.dp),
                                            )
                                        }
                                        if (vinyl) {
                                            // Reserve the same placement for the credits across all three panes.
                                            Box(
                                                Modifier.fillMaxWidth().height(32.dp).clipToBounds().graphicsLayer {
                                                    alpha = textOpacity
                                                },
                                                contentAlignment = Alignment.CenterStart,
                                            ) {
                                                if (pane == PlayerPane.Main) lyrics()
                                            }
                                        }
                                    }
                                    if (posterTransport) {
                                        Spacer(Modifier.width(52.dp))
                                    } else if (compact) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Column { menu(); favorite(); previous() }
                                            Column(Modifier.align(Alignment.Bottom)) { play(); next() }
                                        }
                                    } else {
                                        Column { menu(); favorite(); previous(); play(); next() }
                                    }
                                }
                                Column(Modifier.fillMaxWidth().onSizeChanged { footerHeight = it.height }.padding(horizontal = 20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (!preview) SleeveNerdStats(song, Modifier.fillMaxWidth(), reserveSpace = true)
                                    volume?.invoke()
                                    quality()
                                    actions()
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                    if (showPosterRail) {
                        Column(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp)
                            .offset { IntOffset(0, -footerHeight) }
                            .graphicsLayer {
                                alpha = reveal.value
                                translationY = (1f - reveal.value) * footerHeight
                            }) {
                            menu(); favorite(); previous(); play(); next()
                        }
                    }
                }
            }
            val handlePanel by animateFloatAsState(
                if (pane == PlayerPane.Main) 0f else 1f,
                animationSpec = if (reduceAnimation) snap() else tween(280),
                label = "alternateHandlePosition",
            )
            Box(
                Modifier.fillMaxWidth()
                    .then(if (preview) Modifier else Modifier.windowInsetsPadding(stableStatusBarInsets()))
                    .height(44.dp).then(topHold),
            ) {
                val handleShape = RoundedCornerShape(3.dp)
                Box(Modifier.align(Alignment.TopCenter)
                    .offset { IntOffset(0, playerHandleTop(handlePanel).roundToPx()) }
                    .size(38.dp, 5.dp)
                    .shadow(2.dp, handleShape, clip = false)
                    .background(accent.copy(alpha = 0.75f), handleShape))
                if (pane == PlayerPane.Lyrics) {
                    LayoutArtwork(artworkUrl ?: artwork, Modifier.align(Alignment.CenterStart).padding(start = 18.dp)
                        .size(36.dp).onGloballyPositioned(reportArtwork).clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = onDismissPane).graphicsLayer { alpha = liveCoverAlpha() })
                }

            }
        }
    }
        if (dock != null && artworkSource != null && dockRoot != null && dockRestOrigin != null) {
            val circular = vinyl && pane == PlayerPane.Main
            val curved = !vinyl && pane == PlayerPane.Main
            val roll = remember(pane) { DockCoverRotation() }
            LayoutArtwork(artworkUrl ?: artwork, Modifier
                .clearAndSetSemantics {}
                .layout { measurable, constraints ->
                    val root = dockRoot
                    val source = artworkSource
                    val atRest = dockRestOrigin
                    val offset = dock.offset()?.toInt()?.toFloat()
                    val fraction = dock.openFraction()
                    val frame = if (root != null && source != null && root.isAttached && source.isAttached &&
                        atRest != null && offset != null && fraction < 1f
                    ) {
                        val sourceInRoot = root.localPositionOf(source, Offset.Zero)
                        val origin = atRest + sourceInRoot
                        val size = Size(source.size.width.toFloat(), source.size.height.toFloat())
                        dock.artworkInFlight(Rect(origin, size))
                    } else null
                    if (frame == null || atRest == null || offset == null) {
                        val invisible = measurable.measure(Constraints.fixed(0, 0))
                        layout(0, 0) { invisible.place(0, 0) }
                    } else {
                        // The layout's coordinates may still describe the preceding placement.
                        val at = frame.topLeft - atRest - Offset(0f, offset)
                        val work = measurable.measure(Constraints.fixed(
                            frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1),
                        ))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            work.place(at.x.roundToInt(), at.y.roundToInt())
                        }
                    }
                }
                .graphicsLayer {
                    val fraction = dock.openFraction()
                    if (circular) rotationZ = roll.angle(artworkAngle?.invoke() ?: 0f, fraction)
                    val round = when {
                        circular -> size.minDimension.toDp() / 2
                        curved -> 0.dp
                        else -> 7.dp
                    }
                    shape = RoundedCornerShape(androidx.compose.ui.unit.lerp(dock.miniArtCorner(), round, dock.openFraction()))
                    clip = true
                    alpha = 1f - liveCoverAlpha()
                }.then(if (!curved) Modifier else Modifier.drawWithCache {
                    val fraction = dock.openFraction()
                    val mask = Path().apply {
                        addPosterMask(size, posterSeekFooter.toPx(), fraction, posterCurveHeight?.toPx())
                    }
                    onDrawWithContent { clipPath(mask) { this@onDrawWithContent.drawContent() } }
                }), placeholder = dock.miniPainterFor(song.videoId))
        }
}

// Keep the turn continuous if the record crosses zero degrees while docking.
private class DockCoverRotation {
    private var previous = Float.NaN
    private var unwrapped = 0f

    fun angle(current: Float, fraction: Float): Float {
        fun shortest(delta: Float) = (delta + 180f).mod(360f) - 180f
        unwrapped = if (previous.isNaN()) shortest(current) else unwrapped + shortest(current - previous)
        previous = if (fraction in 0.001f..0.999f) current else Float.NaN
        return unwrapped * fraction
    }
}

@Composable
private fun PlayerLayoutCredits(
    song: Song,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String?, String) -> Unit,
) {
    Column {
        MarqueeText(
            text = song.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = LocalPlayerAccent.current,
            enabled = true,
            modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
        )
        ArtistCreditsMarquee(
            text = song.artist,
            credits = remember(song.artist, song.artists, song.artistId) {
                artistCredits(song.artist, song.artists, song.artistId)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LocalPlayerAccent.current.copy(alpha = 0.74f),
            enabled = true,
            onOpenArtist = onOpenArtist,
        )
    }
}

@Composable
private fun PlaybackPill(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PlaybackPillFrame(onClick, true, modifier) {
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
private fun PlaybackPillFrame(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(onClick, enabled = enabled, modifier = modifier.size(52.dp, 48.dp)) {
        Box(
            Modifier.size(46.dp, 39.dp).background(LocalPlayerAccent.current.copy(alpha = 0.13f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

@Composable
internal fun LayoutArtwork(artwork: Any?, modifier: Modifier, placeholder: Painter? = null) {
    val accent = LocalPlayerAccent.current
    Box(modifier.background(Brush.linearGradient(listOf(lerp(Color(0xFF242332), accent, 0.46f), Color(0xFF292023))))) {
        if (artwork != null) {
            AsyncImage(artwork, null, Modifier.fillMaxSize(), placeholder = placeholder, contentScale = ContentScale.Crop)
        }
    }
}

private val POSTER_SEEK_FOOTER = 38.dp

private fun posterRimPoint(size: Size, fraction: Float, curveHeight: Float) =
    Offset(size.width * fraction, size.height * 0.95f - curveHeight * 0.33f * fraction * fraction)

private fun Path.addPosterMask(bounds: Size, footer: Float, fraction: Float, referenceHeight: Float? = null) {
    val size = Size(bounds.width, (bounds.height - footer).coerceAtLeast(1f))
    val curveHeight = (referenceHeight ?: size.height).coerceAtMost(size.height)
    fun y(target: Float) = bounds.height + (target - bounds.height) * fraction
    val left = y(posterRimPoint(size, 0f, curveHeight).y)
    moveTo(0f, left)
    cubicTo(size.width / 3f, left, size.width * 2f / 3f, y(size.height * 0.95f - curveHeight * 0.11f),
        size.width, y(posterRimPoint(size, 1f, curveHeight).y))
    lineTo(size.width, 0f)
    lineTo(0f, 0f)
    close()
}

private class PosterArc(size: Size, inward: Float, horizontalInset: Float, curveHeight: Float) {
    private val points = Array(49) { index ->
        val x = horizontalInset + (size.width - horizontalInset * 2f) * index / 48f
        posterRimPoint(size, x / size.width.coerceAtLeast(1f), curveHeight) - Offset(0f, inward)
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
    curveHeight: Dp? = null,
    seekFooter: Dp = POSTER_SEEK_FOOTER,
    onSeekExpand: (Float) -> Unit = {},
    coverLayers: (@Composable () -> Unit)? = null,
) {
    val accent = LocalPlayerAccent.current
    val ink = if (accent.luminance() < 0.33f) lerp(accent, Color.White, 0.48f) else lerp(accent, Color.White, 0.25f)
    val timeColor = accent.copy(alpha = 0.65f)
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    val endpointLabel = if (durationMs > 0L) measurer.measure(formatTime(durationMs), timeStyle) else null
    var seeking by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 9.dp.toPx() }
    val footer = with(density) { seekFooter.toPx() }
    val referenceHeight = with(density) { curveHeight?.toPx() }
    fun coverSize(size: Size) = Size(size.width, (size.height - footer).coerceAtLeast(1f))
    fun seekArc(size: Size): PosterArc {
        val inset = if (endpointLabel == null) size.width * 0.047f else {
            (endpointLabel.size.width + gap * 1.7f).coerceAtMost(size.width * 0.25f)
        }
        val cover = coverSize(size)
        return PosterArc(cover, -gap, inset, minOf(referenceHeight ?: cover.height, cover.height))
    }
    val expanded by animateFloatAsState(
        if (seeking) 1f else 0f, animationSpec = if (reduced) snap() else spring(), label = "posterArc",
    )
    SideEffect { onSeekExpand(expanded) }
    val mask = remember(footer, referenceHeight) {
        androidx.compose.foundation.shape.GenericShape { bounds, _ -> addPosterMask(bounds, footer, 1f, referenceHeight) }
    }
    Box(modifier.clipToBounds()) {
        Box(Modifier.fillMaxSize().clip(mask)) {
            if (coverLayers == null) LayoutArtwork(artwork, Modifier.fillMaxSize()) else coverLayers()
        }
        val rimGesture = Modifier.seekArtwork(
            shown, onScrub, onFinish, onCancel,
            accepts = { size, down, tolerance, _ ->
                seekArc(size).project(down).second <= tolerance * tolerance
            },
            along = { size, down, motion ->
                val height = coverSize(size).height
                val slope = -0.66f * minOf(referenceHeight ?: height, height) * down.x / (size.width * size.width)
                abs(motion.y - slope * motion.x) < abs(motion.x) * 1.3f
            },
            fraction = { size, _, current, _ ->
                seekArc(size).project(current).first
            },
            onDragging = { seeking = it },
        )
        Box(Modifier.fillMaxSize().then(rimGesture)
            .artworkSeekControls(shown, durationMs, onScrub, onFinish).drawWithCache {
            val arc = seekArc(size)
            val measure = PathMeasure().apply { setPath(arc.path, false) }
            val played = Path()
            onDrawBehind {
                val amount = expanded.coerceIn(0f, 1f)
                val progress = shown().coerceIn(0f, 1f)
                val width = (2.1f + 2.6f * amount).dp.toPx()
                drawPath(arc.path, ink.copy(alpha = 0.59f), style = Stroke(width, cap = StrokeCap.Round))
                played.reset()
                if (measure.getSegment(0f, measure.length * progress, played, true)) {
                    drawPath(played, ink, style = Stroke(width, cap = StrokeCap.Round))
                }
                if (endpointLabel != null && measure.length > 0f) {
                    val elapsed = measurer.measure(formatTime((durationMs * progress).toLong()), timeStyle)
                    for ((at, label) in listOf(0f to elapsed, measure.length to endpointLabel)) {
                        val point = measure.getPosition(at)
                        val tangent = measure.getTangent(at)
                        val angle = (atan2(tangent.y, tangent.x) * 180f / PI).toFloat()
                        val x = if (at == 0f) point.x - label.size.width - gap else point.x + gap
                        val topLeft = Offset(x, point.y - label.size.height / 2f)
                        rotate(angle, pivot = point) {
                            drawText(label, color = timeColor, topLeft = topLeft)
                        }
                    }
                }
            }
        })
    }
}

internal fun Modifier.artworkSeekControls(
    shown: () -> Float,
    durationMs: Long,
    onScrub: ((Float) -> Unit)?,
    onFinish: () -> Unit,
): Modifier {
    if (onScrub == null || durationMs <= 0L) return this
    fun seek(value: Float): Boolean {
        onScrub(value.coerceIn(0f, 1f))
        onFinish()
        return true
    }
    return semantics {
        val fraction = shown().coerceIn(0f, 1f)
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        stateDescription = "\${formatTime((fraction * durationMs).toLong())} / \${formatTime(durationMs)}"
        setProgress(action = ::seek)
    }.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val step = 5_000f / durationMs
        when (event.key) {
            Key.DirectionLeft -> seek(shown() - step)
            Key.DirectionRight -> seek(shown() + step)
            else -> false
        }
    }.focusable()
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
    val currentAccepts by rememberUpdatedState(accepts)
    val currentAlong by rememberUpdatedState(along)
    val currentFraction by rememberUpdatedState(fraction)
    val reportDragging by rememberUpdatedState(onDragging)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val bounds = Size(size.width.toFloat(), size.height.toFloat())
            if (bounds.isEmpty()) return@awaitEachGesture
            val initial = currentShown()
            if (!currentAccepts(bounds, down.position, 23.dp.toPx(), initial)) return@awaitEachGesture
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
                        if (!currentAlong(bounds, down.position, delta)) break
                        dragging = true
                        reportDragging(true)
                    }
                    change.consume()
                    currentScrub(currentFraction(bounds, down.position, change.position, initial).coerceIn(0f, 1f))
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
