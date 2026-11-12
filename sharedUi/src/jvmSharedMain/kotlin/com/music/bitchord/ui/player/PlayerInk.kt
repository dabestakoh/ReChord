package com.music.bitchord.ui.player

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Artwork-derived now-playing tint. Defaults to white outside the player
 * (dialogs, queue sheets, and any other hosts).
 */
internal val LocalPlayerAccent = compositionLocalOf { Color.White }

/**
 * Last resolved album ink survives expanded-player disposal during collapse.
 * It is only replaced by actual artwork color, never a loading placeholder.
 */
internal object PlayerAccentMemory {
    var lastResolved by mutableStateOf(Color.White)
}
