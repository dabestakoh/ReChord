package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.music.bitchord.data.LocalAlphabet
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

@Composable
internal fun LocalLetterIndex(
    positions: Map<String, Int>,
    list: LazyListState,
    firstRowIndex: Int,
    topInset: Dp,
    bottomInset: Dp,
    onPreview: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val updatePreview by rememberUpdatedState(onPreview)
    DisposableEffect(positions, list) { onDispose { updatePreview(null) } }
    val scope = rememberCoroutineScope()
    val letters = remember(positions) { positions.keys.toList() }
    var preview by remember(positions) { mutableStateOf<String?>(null) }
    fun jump(index: Int) {
        val row = letters.getOrNull(index)?.let(positions::get) ?: return
        scope.launch { if (row < list.layoutInfo.totalItemsCount) list.scrollToItem(row) }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topInsetPx = with(density) { (topInset + 4.dp).roundToPx() }
    val bottomInsetPx = with(density) { (bottomInset + 4.dp).roundToPx() }
    // Derived state: once scrolled past the header (firstVisibleItemIndex > firstRowIndex),
    // rowTop stays fixed at topInsetPx and triggers ZERO layout passes during list scrolling.
    val rowTopPx by remember(list, firstRowIndex, topInsetPx) {
        androidx.compose.runtime.derivedStateOf {
            if (list.firstVisibleItemIndex > firstRowIndex) {
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
    val compactFont = letters.size > 24
    Box(modifier.fillMaxHeight().layout { measurable, constraints ->
        val bottom = (constraints.maxHeight - bottomInsetPx).coerceAtLeast(0)
        val rawTop = rowTopPx
        val start = if (rawTop == Int.MAX_VALUE) bottom else rawTop.coerceIn(topInsetPx.coerceAtMost(bottom), bottom)
        // Keep touch coordinates fixed when a jump scrolls the header away mid-drag.
        if (preview == null) {
            val available = (bottom - start).coerceAtLeast(0)
            val preferred = (18.dp * letters.size).roundToPx()
            val minNeeded = (8.dp * letters.size).roundToPx()
            heldBounds.height = if (available >= minNeeded) {
                minOf(available, preferred)
            } else 0
            heldBounds.top = start + (available - heldBounds.height) / 2
        }
        val railHeight = heldBounds.height.coerceIn(0, bottom)
        val railTop = heldBounds.top.coerceIn(0, (bottom - railHeight).coerceAtLeast(0))
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
                        if (preview != letters[index]) {
                            preview = letters[index]
                            updatePreview(preview)
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
            val fontSize = if (compactFont) 9.sp else 10.sp
            letters.forEachIndexed { index, letter ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable { jump(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = letter,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        fontSize = fontSize,
                        lineHeight = fontSize,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

private class RailBounds(var top: Int = 0, var height: Int = 0)
