package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.utils.containSheetGestures
import com.music.bitchord.ui.components.MENU_FROST_ALPHA
import com.music.bitchord.ui.components.MenuFrostDark
import com.music.bitchord.ui.components.liveMenuFrost
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import java.util.Locale
import kotlin.math.roundToInt

private val DrawerShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
private val ControlShape = RoundedCornerShape(16.dp)
private val ScrimColor = Color.Black.copy(alpha = 0.5f)
private val DrawerMaxWidth = 640.dp
private const val StepMs = 100
private const val DismissDragFraction = 0.25f

/** A player drawer for shifting synced lyrics for the current song, with one-tap vocal auto-sync. */
@Composable
internal fun LyricsOffsetSheet(
    songId: String,
    lyrics: List<com.music.bitchord.data.lyrics.LyricLine>,
    isPlaying: Boolean,
    currentPositionMs: () -> Long,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val perSongOffsets by PlayerSettings.perSongLyricsOffsets.collectAsStateWithLifecycle()
    val offsetMs = perSongOffsets[songId] ?: 0
    val autoSyncState by PlayerPlatform.host.lyricsAutoSync.collectAsStateWithLifecycle()
    val hasTimedLyrics = remember(lyrics) { lyrics.any { !it.isGap && (it.timeMs > 0L || it.sungUntilMs != null) } }
    val haptics = rememberHaptics()
    var drag by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val drawerOffset by animateFloatAsState(
        targetValue = drag,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "lyricsOffsetDrawerOffset",
    )
    val scrimAlpha = if (height > 0) {
        (1f - drawerOffset / height).coerceIn(0f, 1f)
    } else {
        1f
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val frost = LocalPlayerFrost.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .containSheetGestures()
            .background(ScrimColor.copy(alpha = ScrimColor.alpha * scrimAlpha))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it },
            exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = DrawerMaxWidth)
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .offset { IntOffset(0, drawerOffset.roundToInt()) }
                    .clip(DrawerShape)
                    // The ⋯ menu's frost, as every drawer over the player — see
                    // [PlayerDrawer].
                    .then(
                        when {
                            reduceDynamicBlur -> Modifier.background(MenuFrostDark)
                            frost != null -> with(frost) {
                                Modifier.frostPanel(DrawerShape, MenuFrostDark.copy(alpha = MENU_FROST_ALPHA))
                            }
                            else -> Modifier.liveMenuFrost(hazeState, MenuFrostDark)
                        },
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .pointerInput(height) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (height > 0 && drag > height * DismissDragFraction) {
                                    onDismiss()
                                } else {
                                    drag = 0f
                                }
                            },
                            onDragCancel = { drag = 0f },
                        ) { _, delta -> drag = (drag + delta).coerceAtLeast(0f) }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                )
                Text(
                    text = stringResource(Res.string.lyrics_offset_per_song_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 3.dp, bottom = 14.dp),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ControlShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatOffset(offsetMs),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OffsetButton(
                            label = "−",
                            contentDescription = stringResource(Res.string.decrease_lyrics_offset),
                            enabled = offsetMs > MIN_LYRICS_OFFSET_MS,
                        ) {
                            haptics.play(Haptic.Select)
                            PlayerSettings.setLyricsOffsetFor(songId, offsetMs - StepMs)
                        }
                        ThinSlider(
                            value = offsetToFraction(offsetMs),
                            onValueChange = { PlayerSettings.setLyricsOffsetFor(songId, fractionToOffset(it)) },
                            idleHeight = 6.dp,
                            activeHeight = 10.dp,
                            modifier = Modifier.weight(1f),
                        )
                        OffsetButton(
                            label = "+",
                            contentDescription = stringResource(Res.string.increase_lyrics_offset),
                            enabled = offsetMs < MAX_LYRICS_OFFSET_MS,
                        ) {
                            haptics.play(Haptic.Select)
                            PlayerSettings.setLyricsOffsetFor(songId, offsetMs + StepMs)
                        }
                    }
                }

                val activeSync = when (val s = autoSyncState) {
                    is LyricsAutoSyncState.Listening -> if (s.songId == songId) s else null
                    is LyricsAutoSyncState.Synced -> if (s.songId == songId) s else null
                    is LyricsAutoSyncState.Failed -> if (s.songId == songId) s else null
                    LyricsAutoSyncState.Idle -> null
                }
                if (activeSync is LyricsAutoSyncState.Listening) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "${stringResource(Res.string.lyrics_auto_sync_listening)} ${(activeSync.progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { activeSync.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = Color.White.copy(alpha = 0.85f),
                            trackColor = Color.White.copy(alpha = 0.14f),
                        )
                    }
                } else if (activeSync is LyricsAutoSyncState.Synced) {
                    Row(
                        Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.lyrics_auto_sync_synced, formatOffset(activeSync.offsetMs)),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                        Text(
                            text = stringResource(Res.string.lyrics_auto_sync_undo),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f))
                                .clickable {
                                    haptics.play(Haptic.Tap)
                                    PlayerSettings.setLyricsOffsetFor(songId, activeSync.previousOffsetMs)
                                    PlayerPlatform.host.cancelLyricsAutoSync()
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                } else if (activeSync is LyricsAutoSyncState.Failed) {
                    Text(
                        text = stringResource(
                            if (activeSync.needsPlayback) Res.string.lyrics_auto_sync_play_first
                            else Res.string.lyrics_auto_sync_failed,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val listening = activeSync is LyricsAutoSyncState.Listening
                    Text(
                        text = if (listening) stringResource(Res.string.cancel) else stringResource(Res.string.lyrics_auto_sync),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White.copy(alpha = if (hasTimedLyrics) 0.92f else 0.35f),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (hasTimedLyrics) 0.14f else 0.05f))
                            .clickable(enabled = hasTimedLyrics) {
                                haptics.play(Haptic.Tap)
                                if (listening) {
                                    PlayerPlatform.host.cancelLyricsAutoSync()
                                } else {
                                    PlayerPlatform.host.startLyricsAutoSync(
                                        songId = songId,
                                        lines = lyrics,
                                        isPlaying = { isPlaying },
                                        currentPositionMs = currentPositionMs,
                                        previousOffsetMs = offsetMs,
                                    )
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                    Text(
                        text = stringResource(Res.string.lyrics_offset_reset),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = if (offsetMs == 0) 0.35f else 0.8f),
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = offsetMs != 0) {
                                haptics.play(Haptic.Tap)
                                PlayerSettings.setLyricsOffsetFor(songId, 0)
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OffsetButton(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White.copy(alpha = if (enabled) 0.85f else 0.25f),
        )
    }
}

private fun offsetToFraction(offsetMs: Int): Float =
    (offsetMs - MIN_LYRICS_OFFSET_MS).toFloat() /
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)

private fun fractionToOffset(fraction: Float): Int {
    val raw = MIN_LYRICS_OFFSET_MS +
        fraction.coerceIn(0f, 1f) *
        (MAX_LYRICS_OFFSET_MS - MIN_LYRICS_OFFSET_MS)
    return (raw / StepMs).roundToInt() * StepMs
}

private fun formatOffset(offsetMs: Int): String = String.format(
    Locale.getDefault(),
    "%+.1f s",
    offsetMs / 1_000f,
)
