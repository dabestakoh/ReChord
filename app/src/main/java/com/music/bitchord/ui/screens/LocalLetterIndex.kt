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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
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
    val letters = remember(positions) { positions.keys.sortedWith(compareBy { if (it == '#') '[' else it }) }
    var preview by remember(positions) { mutableStateOf<Char?>(null) }
    fun jump(index: Int) {
        val row = letters.getOrNull(index)?.let(positions::get) ?: return
        scope.launch { if (row < list.layoutInfo.totalItemsCount) list.scrollToItem(row) }
    }
    Box(modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().pointerInput(positions, list) {
            fun select(y: Float) {
                if (size.height <= 0 || letters.isEmpty()) return
                val index = (y / size.height * letters.size).toInt().coerceIn(letters.indices)
                if (preview != letters[index]) {
                    preview = letters[index]
                    jump(index)
                }
            }
            detectDragGestures(
                onDragStart = { select(it.y) },
                onDragEnd = { preview = null },
                onDragCancel = { preview = null },
            ) { change, _ ->
                change.consume()
                select(change.position.y)
            }
        }) {
            letters.forEachIndexed { index, letter ->
                Box(Modifier.weight(1f).fillMaxWidth().clickable { jump(index) }, contentAlignment = Alignment.Center) {
                    Text(letter.toString(), color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        preview?.let { letter ->
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-28).dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(letter.toString(), Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
