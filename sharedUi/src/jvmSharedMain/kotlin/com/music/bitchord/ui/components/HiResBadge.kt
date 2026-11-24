package com.music.bitchord.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pure-vector, zero-bitmap "Hi-Res" pill badge for lossless audio exceeding
 * CD quality (> 16-bit or > 44.1 kHz, or DSD).
 *
 * Uses a single top-level [ImageVector] and monochrome theme tokens so scrolling
 * 5,000+ song rows allocates zero bitmaps/shaders and adapts cleanly to Light,
 * Dark, and pure-black AMOLED themes.
 */
@Composable
fun HiResBadge(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .border(1.dp, tint.copy(alpha = 0.72f), CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = HiResWaveIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = "Hi-Res",
            color = tint,
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val HiResWaveIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "HiResWave",
        defaultWidth = 14.dp,
        defaultHeight = 14.dp,
        viewportWidth = 14f,
        viewportHeight = 14f,
    ).apply {
        val stroke = SolidColor(Color.White)
        // 5 symmetric vertical waveform bars
        val bars = arrayOf(
            floatArrayOf(2f, 5.5f, 8.5f),
            floatArrayOf(4.5f, 3.5f, 10.5f),
            floatArrayOf(7f, 1.5f, 12.5f),
            floatArrayOf(9.5f, 3.5f, 10.5f),
            floatArrayOf(12f, 5.5f, 8.5f),
        )
        for (b in bars) {
            path(
                stroke = stroke,
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(b[0], b[1])
                lineTo(b[0], b[2])
            }
        }
    }.build()
}
