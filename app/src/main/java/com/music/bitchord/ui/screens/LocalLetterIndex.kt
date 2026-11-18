package com.music.bitchord.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import kotlinx.coroutines.launch

internal fun localLetterPositions(names: List<String>, start: Int, columns: Int): Map<Char, Int> =
    buildMap {
        names.forEachIndexed { index, name ->
            val letter = Normalizer.normalize(name.trimStart(), Normalizer.Form.NFD)
                .firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
            if (letter !in this) put(letter, start + index / columns)
        }
    }

@Composable
internal fun LocalLetterIndex(positions: Map<Char, Int>, list: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val letters = positions.keys.sortedWith(compareBy { if (it == '#') '[' else it })
    fun jump(index: Int) {
        val row = letters.getOrNull(index)?.let(positions::get) ?: return
        scope.launch { if (row < list.layoutInfo.totalItemsCount) list.scrollToItem(row) }
    }
    Column(modifier.fillMaxHeight().pointerInput(positions, list) {
        fun select(y: Float) {
            if (size.height > 0) jump((y / size.height * letters.size).toInt().coerceIn(letters.indices))
        }
        detectDragGestures(onDragStart = { select(it.y) }) { change, _ ->
            change.consume()
            select(change.position.y)
        }
    }) {
        letters.forEachIndexed { index, letter ->
            Box(Modifier.weight(1f).fillMaxWidth().clickable { jump(index) }, contentAlignment = Alignment.Center) {
                Text(letter.toString(), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}
