package com.music.bitchord.ui.screens

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val isDot: Boolean = false,
)

/**
 * Condenses [letters] into at most [maxSlots] non-overlapping visual slots.
 * Always keeps the first and last letter and alternates intermediate sampled
 * letters with a compact `•` dot when space allows so letters never collide
 * vertically in short Landscape viewports while touch/drag still resolves every
 * underlying letter in [letters].
 */
private fun buildVisibleLetterSlots(letters: List<String>, maxSlots: Int): List<VisibleLetterSlot> {
    val n = letters.size
    if (n == 0) return emptyList()
    if (n <= maxSlots) {
        return letters.mapIndexed { idx, l -> VisibleLetterSlot(l, idx) }
    }
    val slots = maxSlots.coerceAtLeast(3)
    // Prefer alternating `Letter • Letter • Letter` when at least 5 slots fit.
    if (slots >= 5) {
        val oddSlots = if (slots % 2 == 1) slots else slots - 1
        val letterCount = (oddSlots + 1) / 2
        return List(oddSlots) { slotIdx ->
            if (slotIdx % 2 == 0) {
                val k = slotIdx / 2
                val letterIdx = ((k.toFloat() / (letterCount - 1).coerceAtLeast(1)) * (n - 1))
                    .roundToInt()
                    .coerceIn(0, n - 1)
                VisibleLetterSlot(letters[letterIdx], letterIdx, isDot = false)
            } else {
                val letterIdx = ((slotIdx.toFloat() / (oddSlots - 1).coerceAtLeast(1)) * (n - 1))
                    .roundToInt()
                    .coerceIn(0, n - 1)
                VisibleLetterSlot("•", letterIdx, isDot = true)
            }
        }
    }
    return List(slots) { slotIdx ->
        val letterIdx = ((slotIdx.toFloat() / (slots - 1).coerceAtLeast(1)) * (n - 1))
            .roundToInt()
            .coerceIn(0, n - 1)
        VisibleLetterSlot(letters[letterIdx], letterIdx, isDot = false)
    }
}

@Composable
internal fun LocalLetterIndex(
    positions: Map<String, Int>,
    list: LazyListState,
    firstRowIndex: Int,
    topInset: Dp,
    bottomInset: Dp,
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
    fun jump(index: Int) {
        val row = letters.getOrNull(index)?.let(positions::get) ?: return
        scope.launch { if (row < list.layoutInfo.totalItemsCount) list.scrollToItem(row) }
    }
    val density = LocalDensity.current
    val topInsetPx = with(density) { (topInset + 4.dp).roundToPx() }
    val fallbackBottomInsetPx = with(density) { (bottomInset - 52.dp).coerceAtLeast(80.dp).roundToPx() }
    val bottomClearancePx = with(density) { 6.dp.roundToPx() }
    val minSlotPx = with(density) { 13.dp.roundToPx().coerceAtLeast(1) }
    val maxSlotPx = with(density) { 26.dp.roundToPx().coerceAtLeast(minSlotPx) }
    val minRailHeightPx = with(density) { 52.dp.roundToPx() }

    // Derived state: once scrolled past the header (firstVisibleItemIndex > 0) or
    // when rows are visible, keep the rail stable without per-frame layout churn.
    val rowTopPx by remember(list, firstRowIndex, topInsetPx) {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) {
                topInsetPx
            } else {
                val firstVisibleRow = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index >= firstRowIndex }
                firstVisibleRow?.let {
                    if (it.index == firstRowIndex) maxOf(it.offset, topInsetPx) else topInsetPx
                } ?: Int.MAX_VALUE
            }
        }
    }
    val heldBounds = remember(list, positions) { RailBounds() }
    var maxVisibleSlots by remember(letters.size) { mutableIntStateOf(letters.size.coerceAtLeast(1)) }
    val visibleSlots = remember(letters, maxVisibleSlots) {
        buildVisibleLetterSlots(letters, maxVisibleSlots)
    }

    Box(modifier.fillMaxHeight().layout { measurable, constraints ->
        val measuredBarsPx = floatingBarsHeightPx()
        val effectiveBottomInsetPx = if (measuredBarsPx > 0) {
            measuredBarsPx + bottomClearancePx
        } else {
            fallbackBottomInsetPx
        }
        val bottom = (constraints.maxHeight - effectiveBottomInsetPx).coerceAtLeast(0)
        val isLandscapeViewport = constraints.maxWidth > constraints.maxHeight
        val rawTop = rowTopPx
        val start = when {
            rawTop == Int.MAX_VALUE -> if (isLandscapeViewport) topInsetPx.coerceAtMost(bottom) else bottom
            // In Landscape (short height), always grant the rail the full vertical corridor
            // below the top/sticky bar so the header doesn't squash the rail off-screen.
            isLandscapeViewport -> topInsetPx.coerceAtMost(bottom)
            else -> rawTop.coerceIn(topInsetPx.coerceAtMost(bottom), bottom)
        }
        // Keep touch coordinates fixed when a jump scrolls the header away mid-drag.
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
    }) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .pointerInput(positions, list) {
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
                val isSelected = !slot.isDot && preview == slot.display
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable {
                            haptics.play(Haptic.Tap)
                            jump(slot.letterIndex)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = slot.display,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            slot.isDot -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.50f)
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                        fontSize = if (slot.isDot) 8.sp else fontSize,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        lineHeight = if (slot.isDot) 8.sp else fontSize,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private class RailBounds(var top: Int = 0, var height: Int = 0)
