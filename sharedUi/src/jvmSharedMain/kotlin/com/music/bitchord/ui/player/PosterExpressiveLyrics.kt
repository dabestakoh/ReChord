package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import java.text.Bidi
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tight poster display typography with vertical glyph padding trimmed so stacked
 * rows sit close together like a concert poster.
 */
internal fun posterExpressiveType(
    size: TextUnit,
    fontFamily: FontFamily?,
    weight: FontWeight = FontWeight.Bold,
): TextStyle = TextStyle(
    fontFamily = fontFamily,
    fontSize = size,
    lineHeight = size * 0.96f,
    fontWeight = weight,
    letterSpacing = (-0.035).em,
    platformStyle = posterPlatformTextStyle(),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    textMotion = TextMotion.Animated,
)

/** First-strong LTR directionality check matching Mochy's poster RTL detection. */
internal fun posterIsRtlText(text: CharSequence): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = Character.codePointAt(text, i)
        when (Character.getDirectionality(cp)) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        }
        i += Character.charCount(cp)
    }
    return false
}

/**
 * Picks one short word (>= 3 chars, first shortest) to be emphasized at 1.55x
 * while stepping down very long words so they fit comfortably in the poster box.
 */
internal fun posterWordScales(words: List<String>): FloatArray {
    val emphasised = words.withIndex()
        .filter { it.value.length >= 3 }
        .minByOrNull { it.value.length }?.index ?: -1
    return FloatArray(words.size) { i ->
        val length = words[i].length
        when {
            i == emphasised && words.size > 1 -> 1.55f
            length >= 11 -> 0.74f
            length >= 9 -> 0.86f
            else -> 1f
        }
    }
}

/**
 * Scripts whose characters are separate shapes and can move letter-by-letter.
 * Joined/shaped scripts (Arabic, Devanagari, Thai, Han, Kana, etc.) stay whole
 * so ligatures and strokes are never torn apart.
 */
internal fun posterSplitsIntoLetters(text: String, start: Int = 0, end: Int = text.length): Boolean {
    var i = start
    while (i < end) {
        val cp = text.codePointAt(i)
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.LATIN,
            Character.UnicodeScript.GREEK,
            Character.UnicodeScript.CYRILLIC,
            Character.UnicodeScript.ARMENIAN,
            Character.UnicodeScript.GEORGIAN,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED -> Unit
            else -> return false
        }
        i += Character.charCount(cp)
    }
    return true
}

private const val SYLLABLE_GLYPH_WEIGHT = 3

/** Letters' worth of singing: a CJK/Hangul syllable counts as 3 Latin letters. */
internal fun posterSungLength(text: String, start: Int = 0, end: Int = text.length): Int {
    var n = 0
    var i = start
    while (i < end) {
        val cp = text.codePointAt(i)
        val isSyllable = when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
        n += if (isSyllable) SYLLABLE_GLYPH_WEIGHT else 1
        i += Character.charCount(cp)
    }
    return n.coerceAtLeast(1)
}

// ---- Expressive Title ----------------------------------------------------------------

private const val TITLE_SPACING = 0.24f

private data class PosterTitleFitKey(
    val text: String,
    val baseSize: TextUnit,
    val maxWidth: Int,
    val maxHeight: Int,
    val density: Float,
    val fontScale: Float,
)

private object PosterTitleFitCache {
    private val map = object : LinkedHashMap<PosterTitleFitKey, TextUnit>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PosterTitleFitKey, TextUnit>): Boolean =
            size > 48
    }

    fun get(key: PosterTitleFitKey): TextUnit? = synchronized(map) { map[key] }

    fun put(key: PosterTitleFitKey, value: TextUnit) {
        synchronized(map) { map[key] = value }
    }
}

private fun titleWordStyle(size: TextUnit, scale: Float, fontFamily: FontFamily?): TextStyle =
    posterExpressiveType(size * scale, fontFamily, if (scale > 1f) FontWeight.Black else FontWeight.Bold)

private fun fitPosterTitle(
    measurer: TextMeasurer,
    words: List<String>,
    scales: FloatArray,
    length: Int,
    baseSize: TextUnit,
    maxWidth: Int,
    maxHeight: Int,
    density: Density,
    fontFamily: FontFamily?,
): TextUnit {
    val fitWidth = (maxWidth * 0.94f).roundToInt().coerceAtLeast(1)
    var size = baseSize * sqrt((14f / length.coerceAtLeast(1)).coerceIn(0.52f, 1f))
    repeat(14) {
        if (posterTitleFits(measurer, words, scales, size, fitWidth, maxHeight, density, fontFamily)) return size
        val next = size * 0.9f
        if (next.value < 11f) return if (size.value < 11f) 11.sp else size
        size = next
    }
    return size
}

private fun posterTitleFits(
    measurer: TextMeasurer,
    words: List<String>,
    scales: FloatArray,
    size: TextUnit,
    maxWidth: Int,
    maxHeight: Int,
    density: Density,
    fontFamily: FontFamily?,
): Boolean {
    val spacing = with(density) { (size.value * TITLE_SPACING).dp.toPx() }
    var rowWidth = 0f
    var rowHeight = 0f
    var total = 0f
    words.forEachIndexed { i, word ->
        val layout = measurer.measure(
            word,
            titleWordStyle(size, scales[i], fontFamily),
            maxLines = 1,
            softWrap = false,
            constraints = Constraints(),
        )
        val w = layout.size.width.toFloat()
        if (w > maxWidth) return false
        if (rowWidth > 0f && rowWidth + spacing + w > maxWidth) {
            total += rowHeight
            rowWidth = 0f
            rowHeight = 0f
        }
        rowWidth += (if (rowWidth > 0f) spacing else 0f) + w
        rowHeight = max(rowHeight, layout.size.height.toFloat())
    }
    return total + rowHeight <= maxHeight
}

/**
 * Song title styled as a poster with mixed word scales and a staggered word rise
 * on each new track (shown when lyrics are unavailable or still loading).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PosterExpressiveTitle(
    text: String,
    baseSize: TextUnit,
    color: Color,
    fontFamily: FontFamily?,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val words = remember(text) { text.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
    if (words.isEmpty()) return
    val scales = remember(words) { posterWordScales(words) }
    val rtl = remember(text) { posterIsRtlText(text) }
    val enter = remember(text, reduceMotion) { Animatable(if (reduceMotion) 1f else 0f) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val fontFamilyResolver = LocalFontFamilyResolver.current
        val layoutDirection = LocalLayoutDirection.current
        val maxWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight
        if (maxWidth <= 0 || maxHeight <= 0) return@BoxWithConstraints

        val fitKey = PosterTitleFitKey(text, baseSize, maxWidth, maxHeight, density.density, density.fontScale)
        val fit = remember(text) { mutableStateOf(PosterTitleFitCache.get(fitKey)) }
        LaunchedEffect(fitKey) {
            fit.value = PosterTitleFitCache.get(fitKey) ?: withContext(Dispatchers.Default) {
                fitPosterTitle(
                    TextMeasurer(fontFamilyResolver, density, layoutDirection, 16),
                    words,
                    scales,
                    text.length,
                    baseSize,
                    maxWidth,
                    maxHeight,
                    density,
                    fontFamily,
                )
            }.also { PosterTitleFitCache.put(fitKey, it) }
        }
        val fitted = fit.value ?: return@BoxWithConstraints
        LaunchedEffect(text, reduceMotion) {
            if (reduceMotion) {
                enter.snapTo(1f)
            } else {
                enter.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 180f))
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(fitted.value.dp * TITLE_SPACING),
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                words.forEachIndexed { index, word ->
                    Text(
                        text = word,
                        color = color,
                        style = titleWordStyle(fitted, scales[index], fontFamily),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.graphicsLayer {
                            if (!reduceMotion) {
                                val delay = index * 0.09f
                                val p = ((enter.value - delay) / (1f - delay).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                                translationY = (1f - p) * 30.dp.toPx()
                                alpha = p
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---- Expressive Lyrics ---------------------------------------------------------------

internal const val POSTER_ENTER_LEAD_MS = 420L
private const val POSTER_ENTER_MS = 560f
private const val POSTER_ENTER_STAGGER_MS = 65f
internal const val POSTER_EXIT_MS = 460f
private const val POSTER_EXIT_STAGGER_MS = 40f
private const val POSTER_RISE_SHARE = 0.55f
private const val POSTER_RISE_LEAD_CHARS = 0.8f
private const val POSTER_RISE_SPAN_CHARS = 3.2f
internal const val POSTER_GAP_EXIT_DELAY_MS = 450L
internal const val POSTER_MIN_INTERLUDE_GAP_MS = 4_000L
private const val POSTER_ACTIVE_FADE_MS = 450f
private const val POSTER_GLOW_ALPHA = 0.6f
private const val POSTER_GLOW_SOFTNESS = 0.13f
private const val POSTER_GLOW_TRAIL_CHARS = 2.5f
private const val POSTER_GLOW_REST = 0.3f
private const val POSTER_GLOW_RES = 0.5f
private const val POSTER_GLOW_CUTOFF = 0.04f
private const val POSTER_BLUR_SHOWN_PX = 0.5f

internal enum class PosterLineSide { LEFT, CENTER, RIGHT }

internal data class PosterLineEntry(
    val line: LyricLine,
    val subText: String? = null,
    val subAlpha: Float = 0.56f,
    val isBackground: Boolean = false,
)

internal class PosterTimedWord(val text: String, val startMs: Long, val endMs: Long) {
    val sungLength: Int = posterSungLength(text)
}

/** Flattens lead and background vocals, pairing optional translation/romanization sub-lines. */
internal fun posterPrepareLines(
    lines: List<LyricLine>,
    subLines: List<LyricLine>?,
    subAlpha: Float,
): List<PosterLineEntry> {
    val out = ArrayList<PosterLineEntry>(lines.size)
    lines.forEachIndexed { index, line ->
        val sub = subLines?.getOrNull(index)
        if (!line.isGap && line.text.isNotBlank()) {
            val subText = sub?.text?.takeIf { it.isNotBlank() && it != line.text }
            out += PosterLineEntry(line.copy(background = null), subText, subAlpha, isBackground = false)
        }
        val bg = line.background
        if (bg != null && !bg.isGap && bg.text.isNotBlank()) {
            val bgSub = sub?.background?.text?.takeIf { it.isNotBlank() && it != bg.text }
            out += PosterLineEntry(bg.copy(alignment = LyricAlignment.End), bgSub, subAlpha, isBackground = true)
        }
    }
    return out.sortedBy { it.line.timeMs }
}

internal fun posterReadsRightToLeft(entries: List<PosterLineEntry>): Boolean =
    entries.count { posterIsRtlText(it.line.text) } * 2 > entries.size

internal fun posterSideOf(entry: PosterLineEntry, duet: Boolean, rtl: Boolean): PosterLineSide {
    val answering = duet && (entry.line.alignment == LyricAlignment.End || entry.isBackground)
    return if (answering != rtl) PosterLineSide.RIGHT else PosterLineSide.LEFT
}

/**
 * Word timings for [line]: uses real word-level timestamps when present, or distributes
 * 86% of the line's available duration across words weighted by character length.
 */
internal fun posterTimedWords(line: LyricLine, nextStartMs: Long?): List<PosterTimedWord> {
    val synced = line.words.filter { it.text.isNotBlank() }
    if (synced.isNotEmpty()) {
        return synced.flatMap { w ->
            val start = w.startMs
            val end = w.endMs.coerceAtLeast(start + 1L)
            val trimmed = w.text.trim()
            if (trimmed.any { it == '\n' || it == '\r' }) {
                trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }.map { part ->
                    PosterTimedWord(part, start, end)
                }
            } else {
                listOf(PosterTimedWord(trimmed, start, end))
            }
        }
    }
    val rawTokens = line.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val words = if (rawTokens.size == 1 && rawTokens[0].length > 8 && !posterSplitsIntoLetters(rawTokens[0])) {
        val breaker = BreakIterator.getWordInstance()
        breaker.setText(line.text)
        buildList {
            var s = breaker.first()
            var e = breaker.next()
            while (e != BreakIterator.DONE) {
                val tok = line.text.substring(s, e).trim()
                if (tok.isNotEmpty()) add(tok)
                s = e
                e = breaker.next()
            }
        }.ifEmpty { rawTokens }
    } else {
        rawTokens
    }
    if (words.isEmpty()) return emptyList()
    val lineEnd = (line.sungUntilMs ?: line.timingSource?.endMs ?: nextStartMs ?: (line.timeMs + 4_000L))
        .coerceAtLeast(line.timeMs + 600L)
    val singing = ((lineEnd - line.timeMs) * 0.86f).coerceAtMost(words.size * 900f)
    val weights = words.map { it.length + 1.5f }
    val total = weights.sum().coerceAtLeast(1f)
    var cursor = line.timeMs.toFloat()
    return words.mapIndexed { i, word ->
        val length = (singing * weights[i] / total).coerceAtLeast(1f)
        val start = cursor.toLong()
        val end = (cursor + length).toLong().coerceAtLeast(start + 1L)
        cursor += length
        PosterTimedWord(word, start, end)
    }
}

internal fun posterEnterTimes(entries: List<PosterLineEntry>): LongArray = LongArray(entries.size) { i ->
    val start = entries[i].line.timeMs
    if (i == 0) {
        if (start < POSTER_MIN_INTERLUDE_GAP_MS) 0L else start - POSTER_ENTER_LEAD_MS
    } else {
        (start - POSTER_ENTER_LEAD_MS).coerceAtLeast(entries[i - 1].line.timeMs)
    }
}

internal data class PosterInterlude(
    val startMs: Long,
    val endMs: Long,
    val afterIndex: Int,
    val side: PosterLineSide,
)

internal fun posterInterludesOf(
    entries: List<PosterLineEntry>,
    ends: LongArray,
    enterAts: LongArray,
    duet: Boolean,
    rtl: Boolean,
): List<PosterInterlude> {
    if (entries.isEmpty()) return emptyList()
    val gaps = ArrayList<PosterInterlude>()
    if (entries[0].line.timeMs >= POSTER_MIN_INTERLUDE_GAP_MS) {
        gaps += PosterInterlude(300L, enterAts[0], -1, posterSideOf(entries[0], duet, rtl))
    }
    for (i in 0 until entries.lastIndex) {
        val next = i + 1
        if (entries[next].line.timeMs - ends[i] >= POSTER_MIN_INTERLUDE_GAP_MS) {
            val start = ends[i] + POSTER_GAP_EXIT_DELAY_MS + 250L
            if (enterAts[next] - start > 1_500L) {
                gaps += PosterInterlude(start, enterAts[next], i, posterSideOf(entries[next], duet, rtl))
            }
        }
    }
    return gaps
}

internal fun posterOverlapsOf(
    entries: List<PosterLineEntry>,
    ends: LongArray,
    enterAts: LongArray,
): BooleanArray = BooleanArray(entries.size) { i ->
    i < entries.lastIndex && ends[i] - 100L > enterAts[i + 1]
}

internal fun posterExitTimes(
    entries: List<PosterLineEntry>,
    ends: LongArray,
    enterAts: LongArray,
    interludes: List<PosterInterlude>,
    overlaps: BooleanArray,
): Array<Long?> = Array(entries.size) { index ->
    val next = index + 1
    when {
        next !in entries.indices -> null
        interludes.any { it.afterIndex == index } -> (ends[index] + POSTER_GAP_EXIT_DELAY_MS).coerceAtMost(enterAts[next])
        overlaps[index] -> ends[index] + 150L
        else -> (ends[index] - 100L).coerceIn(enterAts[next], max(enterAts[next], entries[next].line.timeMs))
    }
}

private fun lastAtOrBefore(times: LongArray, t: Long): Int {
    var lo = 0
    var hi = times.lastIndex
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (times[mid] <= t) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

private enum class PosterWordStage { Waiting, Live, Done }

private class PosterWordCell(
    val word: PosterTimedWord,
    val letters: Array<TextLayoutResult>,
    val letterX: FloatArray,
    val width: Float,
    val height: Float,
    val baseline: Float,
    val rest: Float,
    val sizePx: Float,
    val row: Int,
    val charStart: Int,
    val bleed: Float,
    val rtl: Boolean,
) {
    val lift = FloatArray(letters.size)
    val zoom = FloatArray(letters.size)
    val glow = FloatArray(letters.size)
}

private class PosterSubCell(val layout: TextLayoutResult, val alpha: Float, val bleed: Float)

private fun letterBounds(word: String, graphemes: BreakIterator): IntArray {
    if (word.isEmpty() || !posterSplitsIntoLetters(word, 0, word.length)) {
        return intArrayOf(0, word.length)
    }
    graphemes.setText(word)
    val bounds = ArrayList<Int>(word.length + 1)
    var at = graphemes.first()
    while (at != BreakIterator.DONE) {
        bounds += at
        at = graphemes.next()
    }
    return bounds.toIntArray()
}

private fun visualOrder(words: List<PosterTimedWord>, rows: IntArray): IntArray {
    val order = IntArray(words.size) { it }
    val joined = words.joinToString(" ") { it.text }
    if (!Bidi.requiresBidi(joined.toCharArray(), 0, joined.length)) return order
    val bidi = Bidi(joined, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    val levels = ByteArray(words.size)
    var offset = 0
    words.forEachIndexed { i, w ->
        levels[i] = bidi.getLevelAt(offset).toByte()
        offset += w.text.length + 1
    }
    val boxed = Array<Any>(words.size) { it }
    var start = 0
    while (start < words.size) {
        var end = start
        while (end < words.size && rows[end] == rows[start]) end++
        Bidi.reorderVisually(levels, start, boxed, start, end - start)
        start = end
    }
    return IntArray(words.size) { boxed[it] as Int }
}

private class PosterLineCells(
    val words: List<PosterWordCell>,
    val spacePx: Float,
    val maxWidth: Float,
    val side: PosterLineSide,
    val subs: List<PosterSubCell>,
    val subGapPx: Float,
    val visual: IntArray = IntArray(words.size) { it },
) {
    val chars = words.sumOf { it.letters.size }
    val x = FloatArray(words.size)
    val y = FloatArray(words.size)
    val scale = FloatArray(words.size)
    val subY = FloatArray(subs.size)
    var totalHeight = 0f
        private set
    private var frameT = Long.MIN_VALUE

    private val liveFrom = LongArray(words.size)
    private val liveUntil = LongArray(words.size)

    init {
        var settled = Long.MIN_VALUE
        words.forEachIndexed { i, cell ->
            val w = cell.word
            settled = max(settled, max(w.startMs, w.endMs))
            liveFrom[i] = w.startMs
            liveUntil[i] = maxOf(settled, w.endMs + POSTER_ACTIVE_FADE_MS.toLong(), w.startMs + growSpanOf(w).toLong()) + 1L
        }
    }

    fun stageAt(index: Int, t: Long): PosterWordStage = when {
        t < liveFrom[index] -> PosterWordStage.Waiting
        t >= liveUntil[index] -> PosterWordStage.Done
        else -> PosterWordStage.Live
    }

    fun drawTimeOf(index: Int, stage: PosterWordStage, t: Long): Long = when (stage) {
        PosterWordStage.Waiting -> liveFrom[index] - 1L
        PosterWordStage.Done -> liveUntil[index]
        PosterWordStage.Live -> t
    }

    fun frameAt(t: Long) {
        if (t == frameT) return
        frameT = t
        var i = 0
        var rowTop = 0f
        var bottom = 0f
        while (i < words.size) {
            val row = words[i].row
            var j = i
            var baseline = 0f
            var height = 0f
            var rowWidth = 0f
            while (j < words.size && words[j].row == row) {
                val w = words[j]
                val s = if (w.rest >= 1f) 1f else w.rest + (1f - w.rest) * grow(w.word, t)
                scale[j] = s
                baseline = max(baseline, w.baseline * s)
                height = max(height, w.height * s)
                rowWidth += w.width * s + (if (j > i) spacePx else 0f)
                j++
            }
            var cx = when (side) {
                PosterLineSide.LEFT -> 0f
                PosterLineSide.CENTER -> (maxWidth - rowWidth) / 2f
                PosterLineSide.RIGHT -> maxWidth - rowWidth
            }
            for (p in i until j) {
                val k = visual[p]
                val w = words[k]
                x[k] = cx
                y[k] = rowTop + baseline - w.baseline * scale[k]
                cx += w.width * scale[k] + spacePx
            }
            bottom = rowTop + height
            rowTop += height * 0.98f
            i = j
        }
        var subTop = bottom
        subs.forEachIndexed { k, sub ->
            subTop += subGapPx
            subY[k] = subTop
            subTop += sub.layout.size.height
        }
        totalHeight = subTop
    }

    fun headAt(t: Long): Float {
        for (w in words) {
            val n = w.letters.size
            if (t < w.word.startMs) return w.charStart.toFloat()
            if (t < w.word.endMs) {
                val span = (w.word.endMs - w.word.startMs).coerceAtLeast(1L)
                return w.charStart + n * ((t - w.word.startMs).toFloat() / span)
            }
        }
        return chars.toFloat()
    }
}

private fun grow(word: PosterTimedWord, t: Long): Float =
    smootherstep((t - word.startMs) / growSpanOf(word))

private fun growSpanOf(word: PosterTimedWord): Float =
    max(520f, (word.endMs - word.startMs) * 0.75f)

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun smootherstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

private fun easeOutCubic(x: Float): Float {
    val u = 1f - x.coerceIn(0f, 1f)
    return 1f - u * u * u
}

private object PosterBlurCache {
    private val map = object : LinkedHashMap<Int, BlurEffect>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BlurEffect>): Boolean =
            size > 64
    }

    fun get(radiusPx: Float): BlurEffect {
        val bucket = (radiusPx * 2f).roundToInt().coerceAtLeast(1)
        return synchronized(map) {
            map.getOrPut(bucket) {
                val r = bucket / 2f
                BlurEffect(r, r, TileMode.Decal)
            }
        }
    }
}

private fun measurePosterLine(
    measurer: TextMeasurer,
    entry: PosterLineEntry,
    nextStartMs: Long?,
    maxWidth: Float,
    maxHeight: Float,
    basePx: Float,
    density: Density,
    color: Color,
    fontFamily: FontFamily?,
    side: PosterLineSide,
): PosterLineCells {
    val words = posterTimedWords(entry.line, nextStartMs)
    val subGap = with(density) { 6.dp.toPx() }
    if (words.isEmpty()) return PosterLineCells(emptyList(), 0f, maxWidth, side, emptyList(), subGap)

    val subFloorPx = minOf(with(density) { 13.sp.toPx() }, basePx * 0.65f)
    val subPx = (basePx * 0.3f).coerceIn(subFloorPx, with(density) { 19.sp.toPx() })
    val align = when (side) {
        PosterLineSide.LEFT -> TextAlign.Left
        PosterLineSide.CENTER -> TextAlign.Center
        PosterLineSide.RIGHT -> TextAlign.Right
    }
    val fitWidth = (maxWidth * 0.91f).coerceAtLeast(1f)
    val subWidth = fitWidth.roundToInt().coerceAtLeast(1)
    val subs = listOfNotNull(entry.subText?.let { it to entry.subAlpha }).map { (text, alpha) ->
        val style = TextStyle(
            fontFamily = fontFamily,
            fontSize = with(density) { subPx.toSp() },
            lineHeight = with(density) { (subPx * 1.22f).toSp() },
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.02).em,
            color = color,
            textAlign = align,
            textMotion = TextMotion.Animated,
        )
        val layout = measurer.measure(
            text = text,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = subWidth),
        )
        PosterSubCell(layout, alpha, with(density) { 14.dp.toPx() })
    }
    val subsHeight = subs.sumOf { it.layout.size.height } + subs.size * subGap
    val lineHeightBudget = (maxHeight - subsHeight).coerceAtLeast(maxHeight * 0.60f)

    val scales = posterWordScales(words.map { it.text })
    fun style(i: Int, base: Float) =
        posterExpressiveType(with(density) { (base * scales[i]).toSp() }, fontFamily, FontWeight.Bold).copy(color = color)

    val minBasePx = minOf(basePx, with(density) { 12.sp.toPx() })
    var base = basePx
    var attempt = 0
    while (true) {
        val wholes = words.mapIndexed { i, w ->
            measurer.measure(w.text, style(i, base), maxLines = 1, softWrap = false, constraints = Constraints())
        }
        val space = base * 0.26f
        val rows = IntArray(words.size)
        var x = 0f
        var row = 0
        var rowHeight = 0f
        var total = 0f
        var widest = 0f
        wholes.forEachIndexed { i, layout ->
            val ww = layout.size.width.toFloat()
            if (x > 0f && x + ww > fitWidth) {
                total += rowHeight * 0.98f
                rowHeight = 0f
                x = 0f
                row++
            }
            rows[i] = row
            x += ww + space
            rowHeight = max(rowHeight, layout.size.height.toFloat())
            widest = max(widest, ww)
        }
        total += rowHeight
        val atFloor = base <= minBasePx + 0.5f
        if ((widest <= fitWidth && total <= lineHeightBudget) || (widest <= fitWidth && atFloor) || attempt >= 14) {
            var charStart = 0
            val graphemes = BreakIterator.getCharacterInstance()
            val cells = words.mapIndexed { i, w ->
                val whole = wholes[i]
                val st = style(i, base)
                val bounds = letterBounds(w.text, graphemes)
                val pieces = bounds.size - 1
                val letters = if (pieces == 1) arrayOf(whole) else Array(pieces) { k ->
                    measurer.measure(
                        w.text.substring(bounds[k], bounds[k + 1]),
                        st,
                        maxLines = 1,
                        softWrap = false,
                        constraints = Constraints(),
                    )
                }
                val letterX = if (pieces == 1) FloatArray(1) else FloatArray(pieces) { k ->
                    whole.getHorizontalPosition(bounds[k], usePrimaryDirection = true)
                }
                val sizePx = base * scales[i]
                PosterWordCell(
                    word = w,
                    letters = letters,
                    letterX = letterX,
                    width = whole.size.width.toFloat(),
                    height = whole.size.height.toFloat(),
                    baseline = whole.firstBaseline,
                    rest = if (scales[i] > 1f) 1f / scales[i] else 1f,
                    sizePx = sizePx,
                    row = rows[i],
                    charStart = charStart,
                    bleed = sizePx * 0.45f + with(density) { 14.dp.toPx() },
                    rtl = pieces == 1 && posterIsRtlText(w.text),
                ).also { charStart += pieces }
            }
            return PosterLineCells(cells, space, maxWidth, side, subs, subGap, visualOrder(words, rows))
        }
        base = (base * 0.88f).coerceAtLeast(minBasePx)
        attempt++
    }
}

private data class PosterLineCacheKey(val index: Int, val subText: String?)

private class PosterLineCache {
    private val measured = LinkedHashMap<PosterLineCacheKey, PosterLineCells>()
    private val landed = mutableIntStateOf(0)

    fun has(key: PosterLineCacheKey): Boolean = key in measured

    fun cellsFor(key: PosterLineCacheKey): PosterLineCells? {
        landed.intValue
        return measured[key] ?: measured.entries.lastOrNull { it.key.index == key.index }?.value
    }

    fun put(key: PosterLineCacheKey, cells: PosterLineCells, shows: Boolean) {
        measured[key] = cells
        if (shows) landed.intValue++
    }

    fun keepOnly(indices: IntRange) {
        measured.keys.retainAll { it.index in indices }
    }
}

/**
 * Full Mochy-style expressive poster lyrics renderer:
 * - Mixed-scale poster typography with dynamic emphasized-word growth & reflow
 * - Per-word staggered enter/exit layers with scale-around-pivot and GPU blur
 * - Per-letter rise wave, traveling scale wave, held-note swell, horizontal gradient sweep,
 *   and half-res GPU-blurred vocal glow
 * - Smooth overlapping-line glide-up, 3-dot interlude countdown, and optional sub-lines
 */
@Composable
internal fun PosterExpressiveLyrics(
    entries: List<PosterLineEntry>,
    clock: MutableLongState,
    color: Color,
    fontFamily: FontFamily?,
    baseSize: TextUnit,
    reduceMotion: Boolean,
    reduceBlur: Boolean,
    modifier: Modifier = Modifier,
    dimAlpha: Float = 0.32f,
) {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val layoutDirection = LocalLayoutDirection.current

    val duet = remember(entries) { entries.any { it.line.alignment == LyricAlignment.End || it.isBackground } }
    val rtl = remember(entries) { posterReadsRightToLeft(entries) }
    val enterAts = remember(entries) { posterEnterTimes(entries) }
    val ends = remember(entries) {
        LongArray(entries.size) { i ->
            posterTimedWords(entries[i].line, entries.getOrNull(i + 1)?.line?.timeMs)
                .lastOrNull()?.endMs ?: entries[i].line.timeMs
        }
    }
    val interludes = remember(entries, ends, enterAts, duet, rtl) {
        posterInterludesOf(entries, ends, enterAts, duet, rtl)
    }
    val overlaps = remember(entries, ends, enterAts) {
        posterOverlapsOf(entries, ends, enterAts)
    }
    val exitAts = remember(entries, ends, enterAts, interludes, overlaps) {
        posterExitTimes(entries, ends, enterAts, interludes, overlaps)
    }

    val shown by remember(entries, enterAts, clock) {
        derivedStateOf { lastAtOrBefore(enterAts, clock.longValue) }
    }
    val first by remember(entries, exitAts, clock, reduceMotion) {
        derivedStateOf {
            val t = clock.longValue
            val cur = lastAtOrBefore(enterAts, t)
            if (cur < 0) return@derivedStateOf -1
            if (reduceMotion) return@derivedStateOf cur
            var f = cur
            while (f > 0) {
                val exit = exitAts[f - 1] ?: break
                val wordCount = entries[f - 1].line.words.size.takeIf { it > 0 }
                    ?: entries[f - 1].line.text.count { it == ' ' } + 1
                val goneAt = exit + (wordCount + 1) * POSTER_EXIT_STAGGER_MS.toLong() + POSTER_EXIT_MS.toLong()
                if (t < goneAt) f-- else break
            }
            f
        }
    }
    val activeGap by remember(interludes, clock) {
        derivedStateOf {
            val t = clock.longValue
            interludes.firstOrNull { t >= it.startMs - 100L && t < it.endMs + 450L }
        }
    }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints

        val basePx = with(density) { baseSize.toPx() }
        val stackGapPx = with(density) { 14.dp.toPx() }
        val cache = remember(entries, widthPx, heightPx, basePx, color, fontFamily, density.density, density.fontScale) {
            PosterLineCache()
        }

        suspend fun measure(cacheKey: PosterLineCacheKey): PosterLineCells = withContext(Dispatchers.Default) {
            val index = cacheKey.index
            val concurrent = (1 +
                (if (index > 0 && overlaps[index - 1]) 1 else 0) +
                (if (index > 1 && overlaps[index - 2] && overlaps[index - 1]) 1 else 0) +
                (if (overlaps[index]) 1 else 0)).coerceIn(1, 3)
            val budget = if (concurrent > 1) {
                (heightPx * 0.94f - stackGapPx * (concurrent - 1)) / concurrent.toFloat()
            } else {
                heightPx * 0.94f
            }
            measurePosterLine(
                TextMeasurer(fontFamilyResolver, density, layoutDirection, 128),
                entries[index],
                entries.getOrNull(index + 1)?.line?.timeMs,
                widthPx,
                budget,
                basePx,
                density,
                color,
                fontFamily,
                posterSideOf(entries[index], duet, rtl),
            )
        }

        // Pre-measure the next line off-thread one frame after the current line lands.
        LaunchedEffect(shown, cache) {
            withFrameMillis { }
            cache.keepOnly((first - 1)..(shown + 1))
            val next = shown + 1
            if (next in entries.indices) {
                val nextKey = PosterLineCacheKey(next, entries[next].subText)
                if (!cache.has(nextKey)) {
                    cache.put(nextKey, measure(nextKey), shows = false)
                }
            }
        }

        activeGap?.let { interlude ->
            key(interlude.startMs) {
                PosterInterludeRow(
                    gap = interlude,
                    clock = clock,
                    color = color,
                    baseSize = baseSize,
                    reduceMotion = reduceMotion,
                    allowGlow = !reduceBlur && renderEffectBlurSupported,
                )
            }
        }

        if (shown in entries.indices) {
            val above = ArrayList<PosterLineCells>()
            val aboveExits = ArrayList<Long>()
            for (index in first.coerceAtLeast(0)..shown) {
                key(index) {
                    val entry = entries[index]
                    val cacheKey = PosterLineCacheKey(index, entry.subText)
                    if (!cache.has(cacheKey)) {
                        LaunchedEffect(cacheKey, cache) {
                            cache.put(cacheKey, measure(cacheKey), shows = true)
                        }
                    }
                    val cells = cache.cellsFor(cacheKey)
                    if (cells != null) {
                        val pushers = above.toList()
                        val pusherExits = aboveExits.toList()
                        PosterAnimatedLine(
                            cells = cells,
                            enterAt = enterAts[index],
                            exitAt = exitAts[index],
                            clock = clock,
                            color = color,
                            dimAlpha = dimAlpha,
                            reduceMotion = reduceMotion,
                            reduceBlur = reduceBlur,
                            offsetY = { t ->
                                var y = 0f
                                for (k in pushers.indices) {
                                    val holder = pushers[k]
                                    holder.frameAt(t)
                                    val hold = if (reduceMotion) {
                                        if (t < pusherExits[k]) 1f else 0f
                                    } else {
                                        1f - smootherstep((t - pusherExits[k]) / POSTER_EXIT_MS)
                                    }
                                    y += (holder.totalHeight + stackGapPx) * hold
                                }
                                y
                            },
                        )
                        val exit = exitAts[index]
                        if (overlaps[index] && exit != null) {
                            above += cells
                            aboveExits += exit
                        }
                    }
                }
            }
        }
    }
}

// ---- Interlude Dots ------------------------------------------------------------------

private const val DOT_COUNT = 3
private val DOT_ROW_HEIGHT = 56.dp
private val DOT_SIZE = 12.dp
private val DOT_GAP = 7.dp
private val DOT_GLOW_ROOM = 8.dp
private const val DOT_REST_ALPHA = 0.3f
private const val DOT_GLOW_FADE_MS = 900f
private const val DOT_BOB_PERIOD_MS = 2_600f
private val DOT_BOB_HEIGHT = 1.8.dp
private const val DOT_BOB_PHASE_STEP = 0.9f
private const val DOT_POP_STAGGER_MS = 70L
private const val DOT_FADE_FRACTION = 0.55f
private const val DOT_FINALE_FRACTION = 0.88f

@Composable
private fun PosterInterludeRow(
    gap: PosterInterlude,
    clock: MutableLongState,
    color: Color,
    baseSize: TextUnit,
    reduceMotion: Boolean,
    allowGlow: Boolean,
) {
    val visible by remember(gap, clock) {
        derivedStateOf {
            val t = clock.longValue
            t >= gap.startMs && t < gap.endMs
        }
    }
    val span = (gap.endMs - gap.startMs).coerceAtLeast(1L)
    val fills = remember(gap.startMs) { Array(DOT_COUNT) { mutableFloatStateOf(0f) } }
    val glows = remember(gap.startMs) { Array(DOT_COUNT) { mutableFloatStateOf(0f) } }
    val finale = remember(gap.startMs) { mutableFloatStateOf(0f) }
    val frameMsState = remember(gap.startMs) { mutableLongStateOf(0L) }

    val heightAnim = remember(gap.startMs) { Animatable(if (visible) 1f else 0f) }
    val pops = remember(gap.startMs) { Array(DOT_COUNT) { Animatable(if (visible) 1f else 0f) } }

    LaunchedEffect(visible, reduceMotion) {
        if (reduceMotion) {
            val target = if (visible) 1f else 0f
            heightAnim.snapTo(target)
            pops.forEach { it.snapTo(target) }
            return@LaunchedEffect
        }
        if (visible) {
            launch {
                heightAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow))
            }
            pops.forEachIndexed { index, pop ->
                launch {
                    delay(index * DOT_POP_STAGGER_MS)
                    pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
                }
            }
        } else {
            launch {
                heightAnim.animateTo(0f, tween(340, easing = LYRIC_EASING))
            }
            pops.forEach { pop ->
                launch { pop.animateTo(0f, tween(170, easing = LYRIC_EASING)) }
            }
        }
    }

    val foreground = com.music.bitchord.ui.rememberIsForeground()
    LaunchedEffect(gap.startMs, gap.endMs, visible, foreground) {
        if (!visible || !foreground) return@LaunchedEffect
        var lastDotMs = 0L
        while (true) {
            withFrameMillis { frameMs ->
                if (lastDotMs != 0L && frameMs - lastDotMs < 30L) return@withFrameMillis
                lastDotMs = frameMs
                frameMsState.longValue = frameMs
                val progress = ((clock.longValue - gap.startMs).toFloat() / span).coerceIn(0f, 1f)
                finale.floatValue = smoothstep((progress - DOT_FINALE_FRACTION) / (1f - DOT_FINALE_FRACTION))
                for (index in 0 until DOT_COUNT) {
                    val sliceStart = index.toFloat() / DOT_COUNT
                    val fill = smoothstep((progress - sliceStart) * DOT_COUNT / DOT_FADE_FRACTION)
                    fills[index].floatValue = fill
                    val litAt = sliceStart + DOT_FADE_FRACTION / DOT_COUNT
                    glows[index].floatValue = if (progress < litAt) fill else exp(-((progress - litAt) * span) / DOT_GLOW_FADE_MS)
                }
            }
        }
    }

    val scale = (baseSize.value / 30f).coerceIn(1f, 1.8f)
    val origin = when (gap.side) {
        PosterLineSide.LEFT -> 0f
        PosterLineSide.CENTER -> 0.5f
        PosterLineSide.RIGHT -> 1f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(origin, 0f)
                alpha = heightAnim.value.coerceIn(0f, 1f)
            }
            .layout { measurable, constraints ->
                val full = DOT_ROW_HEIGHT.roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = full, maxHeight = full))
                val shown = (full * heightAnim.value.coerceIn(0f, 1f)).roundToInt()
                layout(placeable.width, shown) {
                    placeable.place(0, (shown - full) / 2)
                }
            }
            .height(DOT_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = when (gap.side) {
            PosterLineSide.LEFT -> Arrangement.Start
            PosterLineSide.CENTER -> Arrangement.Center
            PosterLineSide.RIGHT -> Arrangement.End
        },
    ) {
        Row(
            modifier = Modifier.padding(vertical = DOT_GLOW_ROOM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(DOT_COUNT) { index ->
                val glowLayer = if (allowGlow) rememberGraphicsLayer() else null
                Spacer(
                    modifier = Modifier
                        .size(DOT_SIZE)
                        .graphicsLayer {
                            val pop = pops[index].value
                            scaleX = pop
                            scaleY = pop
                            alpha = (DOT_REST_ALPHA + (1f - DOT_REST_ALPHA) * fills[index].floatValue) * pop.coerceIn(0f, 1f)
                            if (!reduceMotion) {
                                val phase = frameMsState.longValue / DOT_BOB_PERIOD_MS * 2f * PI.toFloat() - index * DOT_BOB_PHASE_STEP
                                translationY = sin(phase) * DOT_BOB_HEIGHT.toPx()
                            }
                        }
                        .drawBehind {
                            val fill = fills[index].floatValue
                            val pulse = sin(PI.toFloat() * finale.floatValue) * 0.7f
                            val glowStrength = max(glows[index].floatValue, pulse).coerceIn(0f, 1f)
                            val radius = size.minDimension / 2f
                            if (glowLayer != null && glowStrength > 0.04f) {
                                val pad = DOT_GLOW_ROOM.toPx()
                                glowLayer.renderEffect = PosterBlurCache.get(radius * 0.9f)
                                val box = IntSize(
                                    (size.width + pad * 2f).roundToInt().coerceAtLeast(1),
                                    (size.height + pad * 2f).roundToInt().coerceAtLeast(1),
                                )
                                glowLayer.record(box) {
                                    drawCircle(
                                        color = color.copy(alpha = 0.55f * glowStrength),
                                        radius = radius,
                                        center = Offset(pad + radius, pad + radius),
                                    )
                                }
                                withTransform({ translate(-pad, -pad) }) {
                                    drawLayer(glowLayer)
                                }
                            }
                            drawCircle(color.copy(alpha = 0.75f + 0.25f * fill), radius)
                        },
                )
                if (index < DOT_COUNT - 1) Spacer(Modifier.size(DOT_GAP))
            }
        }
    }
}

// ---- Per-Word & Per-Letter Drawing ---------------------------------------------------

@Composable
private fun PosterAnimatedLine(
    cells: PosterLineCells,
    enterAt: Long,
    exitAt: Long?,
    clock: MutableLongState,
    color: Color,
    dimAlpha: Float,
    reduceMotion: Boolean,
    reduceBlur: Boolean,
    offsetY: (Long) -> Float,
) {
    val density = LocalDensity.current
    val blurIn = with(density) { 10.dp.toPx() }
    val blurOut = with(density) { 12.dp.toPx() }
    val dim = color.copy(alpha = color.alpha * dimAlpha)
    val gpuBlur = renderEffectBlurSupported && !reduceBlur

    fun enterOf(order: Int, t: Long): Float =
        if (reduceMotion) 1f else easeOutCubic((t - enterAt - order * POSTER_ENTER_STAGGER_MS) / POSTER_ENTER_MS)

    fun exitOf(order: Int, t: Long): Float = when {
        exitAt == null -> 0f
        reduceMotion -> if (t >= exitAt) 1f else 0f
        else -> smoothstep((t - exitAt - order * POSTER_EXIT_STAGGER_MS) / POSTER_EXIT_MS)
    }

    fun blurOf(order: Int, t: Long): Float =
        if (!gpuBlur || reduceMotion) 0f
        else (1f - enterOf(order, t)) * blurIn + exitOf(order, t) * blurOut

    fun GraphicsLayerScope.motion(
        order: Int,
        t: Long,
        liftPx: Float,
        x: Float,
        y: Float,
        bleed: Float,
        mayGlow: Boolean,
    ) {
        val e = enterOf(order, t)
        val out = exitOf(order, t)
        translationX = x - bleed
        translationY = y + offsetY(t) - bleed + (1f - e) * liftPx * 0.55f - out * out * liftPx * 0.9f
        alpha = (e * (1f - out)).coerceIn(0f, 1f)
        val zoom = if (reduceMotion) 1f else (0.94f + 0.06f * e) * (1f + 0.08f * out)
        scaleX = zoom
        scaleY = zoom
        val pivotX = when (cells.side) {
            PosterLineSide.LEFT -> 0f
            PosterLineSide.CENTER -> cells.maxWidth / 2f
            PosterLineSide.RIGHT -> cells.maxWidth
        }
        val safeW = size.width.coerceAtLeast(1f)
        val safeH = size.height.coerceAtLeast(1f)
        transformOrigin = TransformOrigin((pivotX - (x - bleed)) / safeW, (cells.totalHeight / 2f - (y - bleed)) / safeH)
        val blurred = blurOf(order, t) > POSTER_BLUR_SHOWN_PX
        compositingStrategy = if (blurred || (!mayGlow && alpha > 0.95f)) {
            CompositingStrategy.ModulateAlpha
        } else {
            CompositingStrategy.Auto
        }
    }

    cells.words.forEachIndexed { w, cell ->
        key(w) {
            val stage = remember(cells, w, clock) { derivedStateOf { cells.stageAt(w, clock.longValue) } }
            val blurring = remember(cells, w, clock, gpuBlur, reduceMotion) {
                derivedStateOf { blurOf(w, clock.longValue) > POSTER_BLUR_SHOWN_PX }
            }
            val glowLayer = if (gpuBlur) rememberGraphicsLayer() else null
            val blurLayer = if (gpuBlur) rememberGraphicsLayer() else null
            Spacer(
                modifier = Modifier
                    .layout { measurable, _ ->
                        val p = measurable.measure(
                            Constraints.fixed(
                                (cell.width + cell.bleed * 2f).roundToInt().coerceAtLeast(1),
                                (cell.height + cell.bleed * 2f).roundToInt().coerceAtLeast(1),
                            ),
                        )
                        layout(0, 0) {
                            p.placeWithLayer(0, 0) {
                                val t = clock.longValue
                                cells.frameAt(t)
                                motion(w, t, cell.sizePx, cells.x[w], cells.y[w], cell.bleed, mayGlow = stage.value == PosterWordStage.Live)
                            }
                        }
                    }
                    .drawBehind {
                        val now = stage.value
                        val moving = blurring.value
                        val clockMs = if (now == PosterWordStage.Live || moving) clock.longValue else 0L
                        val t = cells.drawTimeOf(w, now, clockMs)
                        cells.frameAt(t)
                        if (moving && blurLayer != null) {
                            drawBlurred(blurLayer, blurOf(w, clockMs)) {
                                drawPosterWord(cell, cells.scale[w], cells.headAt(t), t, color, dim, glowLayer, reduceMotion)
                            }
                        } else {
                            drawPosterWord(cell, cells.scale[w], cells.headAt(t), t, color, dim, glowLayer, reduceMotion)
                        }
                    },
            )
        }
    }

    cells.subs.forEachIndexed { k, sub ->
        val order = cells.words.size + k
        val blurring = remember(cells, k, clock, gpuBlur, reduceMotion) {
            derivedStateOf { blurOf(order, clock.longValue) > POSTER_BLUR_SHOWN_PX }
        }
        val blurLayer = if (gpuBlur) rememberGraphicsLayer() else null
        Spacer(
            modifier = Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(
                        Constraints.fixed(
                            (sub.layout.size.width + sub.bleed * 2f).roundToInt().coerceAtLeast(1),
                            (sub.layout.size.height + sub.bleed * 2f).roundToInt().coerceAtLeast(1),
                        ),
                    )
                    layout(0, 0) {
                        p.placeWithLayer(0, 0) {
                            val t = clock.longValue
                            cells.frameAt(t)
                            motion(order, t, sub.layout.size.height * 1.6f, 0f, cells.subY[k], sub.bleed, mayGlow = false)
                        }
                    }
                }
                .drawBehind {
                    val drawSub: DrawScope.() -> Unit = {
                        drawText(
                            sub.layout,
                            color = color.copy(alpha = color.alpha * sub.alpha),
                            topLeft = Offset(sub.bleed, sub.bleed),
                            shadow = Shadow.None,
                        )
                    }
                    if (blurring.value && blurLayer != null) {
                        drawBlurred(blurLayer, blurOf(order, clock.longValue), drawSub)
                    } else {
                        drawSub()
                    }
                },
        )
    }
}

private fun DrawScope.drawBlurred(layer: GraphicsLayer, radius: Float, content: DrawScope.() -> Unit) {
    val res = if (radius >= 12f) 0.5f else 1f
    layer.renderEffect = PosterBlurCache.get(radius * res)
    val box = IntSize(
        (size.width * res).roundToInt().coerceAtLeast(1),
        (size.height * res).roundToInt().coerceAtLeast(1),
    )
    layer.record(box) { scale(res, res, pivot = Offset.Zero) { content() } }
    scale(1f / res, 1f / res, pivot = Offset.Zero) { drawLayer(layer) }
}

private enum class PosterLetterPass { Plain, Shadowed, Glow }

private fun DrawScope.drawPosterWord(
    cell: PosterWordCell,
    scale: Float,
    head: Float,
    t: Long,
    color: Color,
    dim: Color,
    glowLayer: GraphicsLayer?,
    reduceMotion: Boolean,
) {
    val word = cell.word
    val n = cell.letters.size
    if (n == 0) return
    val k = (head - cell.charStart).coerceIn(0f, n.toFloat())
    val through = k / n
    val wordRise = smootherstep(through / 0.85f)
    val soft = (cell.width / n * 1.15f).coerceAtLeast(1f)
    val frontAtHead = cell.width * through
    val front = frontAtHead + soft * through
    val litFrom = front - soft
    val span = (word.endMs - word.startMs).coerceAtLeast(1L).toFloat()
    val hold = smoothstep((span / word.sungLength - 140f) / 200f)
    val within = ((t - word.startMs) / span).coerceIn(0f, 1f)
    val swellEnvelope = hold * smootherstep(within / 0.2f) * smootherstep((1f - within) / 0.12f)
    val active = smootherstep(through / 0.15f) * (1f - smootherstep((t - word.endMs) / POSTER_ACTIVE_FADE_MS))

    var glowing = false
    for (i in 0 until n) {
        val lx = cell.letterX[i]
        val lw = cell.letters[i].size.width.toFloat()
        val c = i.toFloat()
        if (reduceMotion) {
            cell.lift[i] = 0f
            cell.zoom[i] = 1f
            cell.glow[i] = 0f
        } else {
            val letterRise = if (k >= n) 1f else smootherstep((k - c + POSTER_RISE_LEAD_CHARS) / POSTER_RISE_SPAN_CHARS)
            val rise = wordRise + (letterRise - wordRise) * POSTER_RISE_SHARE
            val d = k - (c + 0.5f)
            val wave = smootherstep((d + 1.6f) / 1.6f) * (1f - smootherstep(d / 3.2f)) * active
            val swell = swellEnvelope * smootherstep((k - c + 0.35f) / 1.4f)
            cell.lift[i] = (rise * 0.07f + wave * 0.025f + swell * 0.02f) * cell.sizePx
            cell.zoom[i] = 1f + wave * 0.035f + swell * 0.07f
            val lit = ((front - (lx + lw / 2f)) / soft).coerceIn(0f, 1f)
            val since = k - (c + 0.5f)
            val trail = if (since <= 0f) 1f else 1f - smootherstep(since / POSTER_GLOW_TRAIL_CHARS)
            val glow = if (lx >= front) 0f else (active * lit * (POSTER_GLOW_REST + (1f - POSTER_GLOW_REST) * trail) + swell * 0.6f).coerceIn(0f, 1f)
            cell.glow[i] = if (glow > POSTER_GLOW_CUTOFF) glow else 0f
            if (glow > POSTER_GLOW_CUTOFF) glowing = true
        }
    }

    if (glowing && glowLayer != null) {
        glowLayer.renderEffect = PosterBlurCache.get(cell.sizePx * POSTER_GLOW_SOFTNESS * POSTER_GLOW_RES)
        val half = IntSize(
            (size.width * POSTER_GLOW_RES).roundToInt().coerceAtLeast(1),
            (size.height * POSTER_GLOW_RES).roundToInt().coerceAtLeast(1),
        )
        glowLayer.record(half) {
            scale(POSTER_GLOW_RES, POSTER_GLOW_RES, pivot = Offset.Zero) {
                drawPosterLetters(cell, scale, litFrom, front, color, dim, PosterLetterPass.Glow)
            }
        }
        scale(1f / POSTER_GLOW_RES, 1f / POSTER_GLOW_RES, pivot = Offset.Zero) {
            drawLayer(glowLayer)
        }
    }
    drawPosterLetters(
        cell,
        scale,
        litFrom,
        front,
        color,
        dim,
        if (glowLayer == null && !reduceMotion) PosterLetterPass.Shadowed else PosterLetterPass.Plain,
    )
}

private fun DrawScope.drawPosterLetters(
    cell: PosterWordCell,
    scale: Float,
    litFrom: Float,
    front: Float,
    color: Color,
    dim: Color,
    pass: PosterLetterPass,
) {
    withTransform({
        translate(cell.bleed, cell.bleed)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        for (i in cell.letters.indices) {
            val glow = cell.glow[i]
            if (pass == PosterLetterPass.Glow && glow == 0f) continue
            val layout = cell.letters[i]
            val lx = cell.letterX[i]
            val lw = layout.size.width.toFloat()
            val lh = layout.size.height.toFloat()
            val zoom = cell.zoom[i]
            val shadow = if (pass == PosterLetterPass.Shadowed && glow > 0f) {
                Shadow(color.copy(alpha = POSTER_GLOW_ALPHA * glow), Offset.Zero, cell.sizePx * POSTER_GLOW_SOFTNESS)
            } else {
                Shadow.None
            }
            withTransform({
                translate(lx, -cell.lift[i])
                scale(zoom, zoom, pivot = Offset(lw / 2f, lh * 0.85f))
            }) {
                when {
                    lx + lw <= litFrom -> drawText(
                        layout,
                        color = if (pass == PosterLetterPass.Glow) color.copy(alpha = POSTER_GLOW_ALPHA * glow) else color,
                        shadow = shadow,
                    )
                    lx >= front -> drawText(layout, color = dim, shadow = Shadow.None)
                    else -> {
                        val litX = if (cell.rtl) cell.width - litFrom - lx else litFrom - lx
                        val frontX = if (cell.rtl) cell.width - front - lx else front - lx
                        drawText(
                            layout,
                            brush = Brush.horizontalGradient(0f to color, 1f to dim, startX = litX, endX = frontX),
                            alpha = if (pass == PosterLetterPass.Glow) POSTER_GLOW_ALPHA * glow else Float.NaN,
                            shadow = shadow,
                        )
                    }
                }
            }
        }
    }
}
