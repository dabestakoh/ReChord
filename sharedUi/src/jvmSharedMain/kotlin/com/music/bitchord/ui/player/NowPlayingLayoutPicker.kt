package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.LyricsStyle
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
) {
    val layouts = NowPlayingLayout.entries
    val pages = rememberPagerState(initialPage = selected.ordinal) { layouts.size }
    val scope = rememberCoroutineScope()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showTitle) {
            Text(
                stringResource(Res.string.now_playing_layout),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
            )
        }
        HorizontalPager(
            state = pages,
            contentPadding = PaddingValues(horizontal = 30.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            LayoutPreview(
                layout = layouts[page],
                artwork = artwork,
                modifier = Modifier.fillMaxWidth().height(252.dp),
            )
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
private fun LayoutPreview(layout: NowPlayingLayout, artwork: Any?, modifier: Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val tint = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier.clip(shape)
            .border(1.dp, ink.copy(alpha = 0.16f), shape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.32f), MaterialTheme.colorScheme.surface))),
    ) {
        Box(Modifier.fillMaxWidth().height(145.dp)) {
            when (layout) {
                NowPlayingLayout.DEFAULT -> LayoutArtwork(
                    artwork,
                    Modifier.align(Alignment.Center).padding(horizontal = 38.dp, vertical = 16.dp)
                        .fillMaxSize().clip(RoundedCornerShape(12.dp)),
                )
                NowPlayingLayout.VINYL -> VinylArtwork(
                    artwork = artwork, isPlaying = false, shown = { 0.35f }, modifier = Modifier.fillMaxSize(),
                )
                NowPlayingLayout.POSTER -> PosterArtwork(
                    artwork = artwork, shown = { 0.46f }, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            fun bar(x: Float, y: Float, w: Float, opacity: Float) = drawRoundRect(
                color = ink.copy(alpha = opacity),
                topLeft = Offset(size.width * x, size.height * y),
                size = Size(size.width * w, 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            bar(0.10f, 0.66f, if (layout == NowPlayingLayout.POSTER) 0.49f else 0.36f, 0.84f)
            bar(0.10f, 0.72f, 0.22f, 0.42f)
            if (layout == NowPlayingLayout.DEFAULT) {
                bar(0.10f, 0.79f, 0.80f, 0.25f)
                for (i in 0..2) drawCircle(ink.copy(alpha = 0.76f), 5.dp.toPx(), Offset(size.width * (0.32f + i * 0.18f), size.height * 0.88f))
            } else {
                for (i in 0..3) drawCircle(ink.copy(alpha = 0.68f), 5.dp.toPx(), Offset(size.width * 0.84f, size.height * (0.65f + i * 0.075f)))
                if (layout == NowPlayingLayout.VINYL) bar(0.10f, 0.86f, 0.51f, 0.48f)
            }
        }
    }
}
