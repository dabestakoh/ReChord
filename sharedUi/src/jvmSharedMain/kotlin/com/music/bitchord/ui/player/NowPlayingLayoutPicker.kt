package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.playback.PlaybackPosition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.theme.rememberArtworkPlayerAnalysis
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.LyricsStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.music.bitchord.data.settings.PlayerTheme
import com.music.bitchord.data.settings.NowPlayingLayout
import com.music.bitchord.sharedui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun nowPlayingLayoutLabel(layout: NowPlayingLayout): String = stringResource(
    when (layout) {
        NowPlayingLayout.DEFAULT -> Res.string.layout_default
        NowPlayingLayout.VINYL -> Res.string.layout_vinyl
        NowPlayingLayout.POSTER -> Res.string.layout_poster
    },
)

@Composable
fun lyricsStyleLabel(style: LyricsStyle): String = stringResource(
    when (style) {
        LyricsStyle.DEFAULT -> Res.string.lyrics_style_default
        LyricsStyle.MOTION -> Res.string.lyrics_style_motion
        LyricsStyle.WAVE -> Res.string.lyrics_style_wave
        LyricsStyle.LIFT -> Res.string.lyrics_style_lift
        LyricsStyle.PLAIN -> Res.string.lyrics_style_plain
    },
)

@Composable
fun NowPlayingLayoutPicker(
    selected: NowPlayingLayout,
    onApply: (NowPlayingLayout) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: Any? = null,
    showTitle: Boolean = true,
    initialPage: NowPlayingLayout = selected,
    hideVolumeFor: ((NowPlayingLayout) -> Boolean)? = null,
    options: @Composable (NowPlayingLayout) -> Unit = {},
    defaultArtworkFullBleed: Boolean? = null,
    defaultPlayerTheme: PlayerTheme? = null,
) {
    val layouts = NowPlayingLayout.entries
    val pages = rememberPagerState(initialPage = initialPage.ordinal) { layouts.size }
    val scope = rememberCoroutineScope()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showTitle) {
            Text(
                stringResource(Res.string.now_playing_layout),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardHeight = (maxWidth * 0.87f).coerceIn(240.dp, 355.dp)
            val cardWidth = cardHeight * 0.56f
            HorizontalPager(
                state = pages,
                pageSize = PageSize.Fixed(cardWidth),
                contentPadding = PaddingValues(horizontal = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                LayoutPreview(
                    layout = layouts[page],
                    artwork = artwork,
                    hideVolume = hideVolumeFor?.invoke(layouts[page]),
                    fullBleed = defaultArtworkFullBleed,
                    playerTheme = defaultPlayerTheme,
                    modifier = Modifier.fillMaxWidth().height(cardHeight),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            layouts.forEach { layout ->
                val focused = pages.currentPage == layout.ordinal
                TextButton(
                    onClick = { scope.launch { pages.animateScrollToPage(layout.ordinal) } },
                    modifier = Modifier.weight(1f).semantics { this.selected = focused },
                ) {
                    Text(
                        nowPlayingLayoutLabel(layout),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        color = if (focused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        options(layouts[pages.currentPage])
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            Button(
                onClick = { onApply(layouts[pages.settledPage]) },
                enabled = !pages.isScrollInProgress,
            ) { Text(stringResource(Res.string.apply_layout)) }
        }
    }
}

@Composable
private fun LayoutPreview(
    layout: NowPlayingLayout, artwork: Any?, hideVolume: Boolean?,
    fullBleed: Boolean?, playerTheme: PlayerTheme?, modifier: Modifier,
) {
    val savedHideVolume by PlayerSettings.hideVolumeBarFor(layout).collectAsStateWithLifecycle()
    val volumeHidden = hideVolume ?: savedHideVolume
    val shape = RoundedCornerShape(22.dp)
    val tint = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val track = Song("layout-preview", nowPlayingLayoutLabel(layout), "", artwork as? String)
    val palette = rememberArtworkPlayerAnalysis(track.thumbnailUrl).accent ?: tint
    val line = stringResource(Res.string.lyrics_style_sample)
    Layout(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, ink.copy(alpha = 0.16f), shape).clearAndSetSemantics {},
        content = {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (layout == NowPlayingLayout.DEFAULT) {
                    val position = remember { PlaybackPosition().apply { report(85_500L) } }
                    NowPlayingScreen(
                        song = track, isPlaying = false, isLoading = false, position = position,
                        durationMs = 225_000L, audioVersionSwitching = false, qualityUpgraded = false,
                        queue = listOf(track), queueIndex = 0, hasPrevious = true, hasNext = true,
                        repeatMode = 0, shuffleEnabled = false, autoplayEnabled = false,
                        signedIn = false, accountName = null, likeStatus = LikeStatus.INDIFFERENT,
                        onToggleLike = {}, onPlayPause = {}, onNext = {}, onPrevious = {},
                        onBlockedControl = {}, onSeek = {}, onSeekFraction = {},
                        onToggleShuffle = {}, onCycleRepeat = {}, onToggleAutoplay = {},
                        onJumpTo = {}, onRemoveFromQueue = {}, onMoveInQueue = { _, _ -> },
                        onClearQueue = {}, onOpenMenu = {}, onOpenAlbum = {}, onOpenArtist = { _, _ -> },
                        onOpenPlaybackSource = {}, onListenTogether = {},
                        lyrics = remember(line) { listOf(LyricLine(0L, line)) },
                        lyricsSource = null, lyricsProviderStates = emptyMap(), onSelectLyricsProvider = {},
                        lyricsUnavailable = false, lyricsOffsetOpen = false, onDismissLyricsOffset = {},
                        windowWidth = maxWidth, windowHeight = maxHeight, layoutPreview = true,
                        previewHideVolume = volumeHidden,
                        previewFullBleedArtwork = fullBleed,
                        previewPlayerTheme = playerTheme,
                    )
                } else androidx.compose.runtime.CompositionLocalProvider(LocalPlayerAccent provides palette) {
                    AlternateNowPlaying(
                        layout = layout, song = track, previousSong = null, nextSong = null,
                        artwork = artwork, durationMs = 225_000L, isPlaying = false, isLoading = false,
                        liked = false, previousEnabled = true, nextEnabled = true, canSeek = false,
                        shown = { 0.38f }, onScrub = {}, onScrubFinished = {}, onScrubCanceled = {},
                        onFavorite = {}, onPrevious = {}, onPlayPause = {}, onNext = {}, onMenu = {},
                        onOpenPicker = null, reduceAnimation = true, artworkBackdrop = palette,
                        statusScrimAlpha = 0f, pane = PlayerPane.Main, lyricsPane = {}, queuePane = {},
                        lyrics = {
                            Text(
                                line, color = palette, maxLines = if (layout == NowPlayingLayout.VINYL) 1 else 3,
                                style = if (layout == NowPlayingLayout.VINYL) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.headlineLarge,
                            )
                        },
                        volume = if (volumeHidden) null else {
                            { VolumeRow({ 0.5f }, {}, {}) }
                        },
                        quality = {},
                        actions = { Box(Modifier.height(44.dp)) },
                        preview = true,
                    )
                }
                // Keep the preview inert while leaving horizontal paging to its parent.
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                })
            }
        },
    ) { children, bounds ->
        val height = 776.dp.roundToPx()
        val width = minOf(408.dp.roundToPx(), (height * bounds.maxWidth.toFloat() / bounds.maxHeight).toInt())
            .coerceAtLeast(1)
        val stage = children.single().measure(Constraints.fixed(width, height))
        val factor = minOf(bounds.maxWidth.toFloat() / width, bounds.maxHeight.toFloat() / height)
        layout(bounds.maxWidth, bounds.maxHeight) {
            stage.placeRelativeWithLayer(((bounds.maxWidth - width * factor) / 2f).toInt(), 0) {
                scaleX = factor
                scaleY = factor
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
