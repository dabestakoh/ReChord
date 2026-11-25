package com.music.bitchord.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.LocalAlphabet
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal fun localLetterPositions(
    names: List<String>,
    start: Int,
    columns: Int,
    alphabet: LocalAlphabet = LocalAlphabet(Locale.ENGLISH, ignoreThe = false),
): Map<String, Int> = buildMap {
    names.forEachIndexed { index, name ->
        val label = alphabet.label(name)
        if (label !in this) put(label, start + index / columns)
    }
}

private data class VisibleLetterSlot(
    val display: String,
    val letterIndex: Int,
)

/**
 * Condenses [letters] into at most [maxSlots] non-overlapping visual slots,
 * using 100% real letters (no `•` dots) and always keeping the first and last
 * letters while touch/drag still resolves every underlying letter in [letters].
 */
private fun buildVisibleLetterSlots(letters: List<String>, maxSlots: Int): List<VisibleLetterSlot> {
    val n = letters.size
    if (n == 0) return emptyList()
    if (n <= maxSlots) {
        return letters.mapIndexed { idx, l -> VisibleLetterSlot(l, idx) }
    }
    val slots = maxSlots.coerceAtLeast(3)
    return List(slots) { slotIdx ->
        val letterIdx = ((slotIdx.toFloat() / (slots - 1).coerceAtLeast(1)) * (n - 1))
            .roundToInt()
            .coerceIn(0, n - 1)
        VisibleLetterSlot(letters[letterIdx], letterIdx)
    }
}

@Composable
internal fun LocalLetterIndex(
    positions: Map<String, Int>,
    list: LazyListState,
    firstRowIndex: Int,
    topInset: Dp,
    bottomInset: Dp,
    visible: Boolean = true,
    floatingBarsHeightPx: () -> Int = { 0 },
    onPreview: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val updatePreview by rememberUpdatedState(onPreview)
    DisposableEffect(positions, list) { onDispose { updatePreview(null) } }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val letters = remember(positions) { positions.keys.toList() }
    var preview by remember(positions) { mutableStateOf<String?>(null) }
    val isShown = visible || preview != null
    val railAlpha by animateFloatAsState(
        targetValue = if (isShown) 1f else 0f,
        animationSpec = tween(180),
        label = "letterRailAlpha",
    )
    fun jump(index: Int) {
        val row = letters.getOrNull(index)?.let(positions::get) ?: return
        scope.launch { if (row < list.layoutInfo.totalItemsCount) list.scrollToItem(row) }
    }
    val density = LocalDensity.current
    val topInsetPx = with(density) { (topInset + 4.dp).roundToPx() }
    val fallbackBottomInsetPx = with(density) { (bottomInset - 52.dp).coerceAtLeast(80.dp).roundToPx() }
    val bottomClearancePx = with(density) { 6.dp.roundToPx() }
    val minSlotPx = with(density) { 9.5.dp.roundToPx().coerceAtLeast(1) }
    val maxSlotPx = with(density) { 26.dp.roundToPx().coerceAtLeast(minSlotPx) }
    val minRailHeightPx = with(density) { 52.dp.roundToPx() }

    val heldBounds = remember(list, positions) { RailBounds() }
    var maxVisibleSlots by remember(letters.size) { mutableIntStateOf(letters.size.coerceAtLeast(1)) }
    val visibleSlots = remember(letters, maxVisibleSlots) {
        buildVisibleLetterSlots(letters, maxVisibleSlots)
    }

    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer { alpha = railAlpha }
            .layout { measurable, constraints ->
                val measuredBarsPx = floatingBarsHeightPx()
                val effectiveBottomInsetPx = if (measuredBarsPx < 0) {
                    // Bars are beside the rail, not under it: run to the bottom edge.
                    bottomClearancePx * 3
                } else if (measuredBarsPx > 0) {
                    measuredBarsPx + bottomClearancePx
                } else {
                    fallbackBottomInsetPx
                }
                val bottom = (constraints.maxHeight - effectiveBottomInsetPx).coerceAtLeast(0)
                val start = topInsetPx.coerceAtMost(bottom)
                // Keep touch coordinates fixed when a jump scrolls the list mid-drag.
                if (preview == null) {
                    val available = (bottom - start).coerceAtLeast(0)
                    // Dynamic vertical spread:
                    // - Many letters (e.g. 18-28 letters): 26.dp * letters.size >= available -> fills 100% of available
                    //   height all the way down to 6.dp above the Now Playing mini-player pill!
                    // - Few letters (e.g. 3-12 letters): caps each letter at 26.dp and vertically centers the group
                    //   so a short alphabet stays compact and never looks awkwardly stretched out.
                    val maxDesiredHeight = maxSlotPx * letters.size
                    heldBounds.height = if (available >= minRailHeightPx && letters.isNotEmpty()) {
                        minOf(available, maxDesiredHeight)
                    } else 0
                    heldBounds.top = start + (available - heldBounds.height) / 2
                }
                val railHeight = heldBounds.height.coerceIn(0, bottom)
                val railTop = heldBounds.top.coerceIn(0, (bottom - railHeight).coerceAtLeast(0))
                val computedMaxSlots = if (railHeight > 0) (railHeight / minSlotPx).coerceAtLeast(3) else letters.size
                if (computedMaxSlots != maxVisibleSlots) {
                    maxVisibleSlots = computedMaxSlots
                }
                val rail = measurable.measure(Constraints.fixed(constraints.maxWidth, railHeight))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    rail.placeRelative(0, railTop)
                }
            },
    ) {
        if (isShown || railAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .pointerInput(positions, list, isShown) {
                        if (!isShown) return@pointerInput
                        fun select(y: Float) {
                            if (size.height <= 0 || letters.isEmpty()) return
                            val index = (y / size.height * letters.size).toInt().coerceIn(letters.indices)
                            val target = letters[index]
                            if (preview != target) {
                                preview = target
                                updatePreview(target)
                                haptics.play(Haptic.Tap)
                                jump(index)
                            }
                        }
                        detectDragGestures(
                            onDragStart = { select(it.y) },
                            onDragEnd = {
                                preview = null
                                updatePreview(null)
                            },
                            onDragCancel = {
                                preview = null
                                updatePreview(null)
                            },
                        ) { change, _ ->
                            change.consume()
                            select(change.position.y)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val compactFont = visibleSlots.size > 22
                val fontSize = if (compactFont) 9.5.sp else 10.5.sp
                visibleSlots.forEach { slot ->
                    val isSelected = preview == slot.display
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(enabled = isShown) {
                                haptics.play(Haptic.Tap)
                                jump(slot.letterIndex)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = slot.display,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            },
                            fontSize = fontSize,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            lineHeight = fontSize,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

private class RailBounds(var top: Int = 0, var height: Int = 0)
