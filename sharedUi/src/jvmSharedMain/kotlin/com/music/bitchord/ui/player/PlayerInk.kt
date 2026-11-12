package com.music.bitchord.ui.player

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Artwork-derived now-playing tint. Defaults to white outside the player
 * (dialogs, queue sheets, and any other hosts).
 */
internal val LocalPlayerAccent = compositionLocalOf { Color.White }
