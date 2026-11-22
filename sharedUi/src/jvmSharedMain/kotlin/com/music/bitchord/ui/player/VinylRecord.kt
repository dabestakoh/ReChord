package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.VinylAppearance
import com.music.bitchord.data.settings.VinylColorMode
import com.music.bitchord.data.settings.VinylFinish
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Keep the latest phases on the UI thread when the player leaves composition for Appearance.
private val lastRecordPhases = object : LinkedHashMap<String, Float>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > 8
}

private const val RECORD_WIDTH_FRACTION = 0.703f
private const val RECORD_HEIGHT_FRACTION = 0.446f

internal class RecordGeometry(
    size: Size,
    mountTopInset: Int = 0,
    maxDiscBottom: Float? = null,
    landscape: Boolean = false,
) {
    val radius = if (landscape) {
        minOf(size.height * 0.48f, size.width * 0.405f)
    } else {
        val base = minOf(size.width * RECORD_WIDTH_FRACTION, size.height * RECORD_HEIGHT_FRACTION)
        if (maxDiscBottom != null && maxDiscBottom > 0f) minOf(base, maxDiscBottom / 2f) else base
    }
    val unit = radius / RECORD_WIDTH_FRACTION
    val reach = (radius * if (landscape) 1.24f else 1.39f).coerceAtLeast(1f)
    val outer = radius * 0.952f
    val inner = radius * 0.565f
    val center: Offset
    val pivot: Offset

    init {
        if (landscape) {
            center = Offset(radius + size.width * 0.025f, size.height * 0.50f)
            pivot = Offset(
                (center.x + radius * 1.11f).coerceAtMost(size.width - unit * 0.09f),
                (center.y - radius * 0.62f).coerceAtLeast(unit * 0.13f),
            )
        } else {
            pivot = Offset(
                size.width * 0.895f,
                maxOf(size.width * 0.125f, mountTopInset + unit * 0.088f).coerceAtMost(radius * 1.6f),
            )
            val vertical = radius - pivot.y
            val maxDistance = (reach + inner - radius * 0.02f).coerceAtLeast(0f)
            val maxHorizontal = sqrt((maxDistance * maxDistance - vertical * vertical).coerceAtLeast(0f))
            center = Offset(maxOf(size.width * 0.163f, pivot.x - maxHorizontal), radius)
        }
    }

    private val distance = (center - pivot).getDistance()
    private val towardCenter = atan2(center.y - pivot.y, center.x - pivot.x)

    fun angle(progress: Float): Float {
        val groove = outer + (inner - outer) * progress.coerceIn(0f, 1f)
        val cosine = (reach * reach + distance * distance - groove * groove) /
            (2f * reach * distance).coerceAtLeast(1f)
        return towardCenter - acos(cosine.coerceIn(-1f, 1f))
    }

    fun tip(progress: Float): Offset = polar(pivot, reach, angle(progress))

    fun fraction(angle: Float): Float {
        val point = polar(pivot, reach, angle.coerceIn(angle(0f), angle(1f)))
        return ((outer - (point - center).getDistance()) / (outer - inner).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}

private fun polar(center: Offset, radius: Float, radians: Float): Offset =
    center + Offset(cos(radians) * radius, sin(radians) * radius)

private fun degrees(radians: Float) = radians * (180f / PI.toFloat())

@Composable
internal fun VinylArtwork(
    artwork: Any?,
    isPlaying: Boolean,
    shown: () -> Float,
    modifier: Modifier,
    onScrub: ((Float) -> Unit)? = null,
    onFinish: () -> Unit = {},
    onCancel: () -> Unit = {},
    trackKey: String = "",
    palette: Color = LocalPlayerAccent.current,
    appearance: VinylAppearance? = null,
    moving: () -> Float = { 0f },
    showArm: Boolean = true,
) {
    val saved by PlayerSettings.vinylAppearance.collectAsStateWithLifecycle()
    val style = appearance ?: saved
    val reduced by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val blurReduced by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    var seeking by remember { mutableStateOf(false) }
    Box(modifier) {
        VinylDisc(artwork, trackKey, style, palette, isPlaying && !reduced, shown, { seeking }, Modifier.fillMaxSize())
        if (showArm) {
            VinylTonearm(
                shown, onScrub, onFinish, onCancel, { seeking = it }, reduced, blurReduced, moving,
                Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun VinylDisc(
    artwork: Any?,
    trackKey: String,
    style: VinylAppearance,
    palette: Color,
    spin: Boolean,
    shown: () -> Float,
    seeking: () -> Boolean,
    modifier: Modifier,
    albumKey: String = trackKey,
    labelPlacement: ((LayoutCoordinates, () -> Float) -> Unit)? = null,
    coastOnPause: Boolean = false,
    coverAlpha: () -> Float = { 1f },
    mountTopInset: Int = 0,
    maxDiscBottom: Float? = null,
    landscape: Boolean = false,
) {
    var rotation by remember(trackKey) { mutableFloatStateOf(lastRecordPhases[trackKey] ?: 0f) }
    val foreground = com.music.bitchord.ui.rememberIsForeground()
    val coverAngle: () -> Float = remember(trackKey) { { rotation } }
    val spinRate = remember(trackKey) { Animatable(if (spin) 55f else 0f) }
    DisposableEffect(trackKey) {
        onDispose { lastRecordPhases[trackKey] = rotation }
    }
    LaunchedEffect(spin, coastOnPause, foreground, trackKey) {
        when {
            spin -> spinRate.snapTo(55f)
            foreground && coastOnPause -> spinRate.animateTo(0f, tween(230))
            else -> spinRate.snapTo(0f)
        }
    }
    LaunchedEffect(spin, coastOnPause, foreground, trackKey) {
        if (!foreground || (!spin && !coastOnPause)) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (spin || spinRate.value > 0f) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            rotation = (rotation + spinRate.value * seconds) % 360f
        }
    }
    val picture = style.finish == VinylFinish.PICTURE_DISC
    val colors = resolveVinylColors(style, palette)
    val base = colors.base
    val tint = colors.tint
    val darkTint = colors.darkTint
    val accentColor = colors.accent
    val waxLayer = rememberGraphicsLayer()
    val grooveLayer = rememberGraphicsLayer()

    BoxWithConstraints(modifier) {
        val geometry = with(androidx.compose.ui.platform.LocalDensity.current) {
            RecordGeometry(Size(maxWidth.toPx(), maxHeight.toPx()), mountTopInset, maxDiscBottom, landscape)
        }
        val radius = with(androidx.compose.ui.platform.LocalDensity.current) { geometry.radius.toDp() }
        val left = with(androidx.compose.ui.platform.LocalDensity.current) { (geometry.center.x - geometry.radius).toDp() }
        val top = with(androidx.compose.ui.platform.LocalDensity.current) { (geometry.center.y - geometry.radius).toDp() }
        val at = Modifier.offset(x = left, y = top)
            .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(radius * 2)
        val label = radius * 0.516f
        Box(Modifier.fillMaxSize().drawWithCache {
            val g = RecordGeometry(size, mountTopInset, maxDiscBottom, landscape)
            val shadowAt = g.center + Offset(g.radius * 0.024f, g.radius * 0.057f)
            val outer = (g.radius * 1.16f).coerceAtLeast(1f)
            val shadow = Brush.radialGradient(
                0.84f to Color.Black.copy(alpha = 0.70f),
                0.93f to Color.Black.copy(alpha = 0.19f), 1f to Color.Transparent,
                center = shadowAt, radius = outer,
            )
            onDrawBehind { if (g.radius > 0f) drawCircle(shadow, outer, shadowAt) }
        })
        val pictureCover = picture && artwork != null
        if (pictureCover) {
            Box(at.then(if (labelPlacement == null) Modifier else Modifier.onGloballyPositioned {
                labelPlacement(it, coverAngle)
            })) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = rotation
                    alpha = coverAlpha()
                }) {
                    LayoutArtwork(artwork, Modifier.fillMaxSize().clip(CircleShape))
                }
            }
        }
        Box(at.graphicsLayer { rotationZ = rotation }) {
            Box(Modifier.fillMaxSize().drawWithCache {
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val cache = buildVinylWaxCache(style.finish, albumKey, r, center, base, tint, darkTint, accentColor)
                val finish = style.finish
                val hasArt = artwork != null
                val layerSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                val discClip = Path().apply {
                    if (r > 0f) addOval(Rect(center - Offset(r, r), Size(r * 2f, r * 2f)))
                }
                var baked = false
                onDrawBehind {
                    if (r <= 0f) return@onDrawBehind
                    if (!baked) {
                        waxLayer.record(layerSize) {
                            clipPath(discClip) {
                                drawVinylWax(finish, r, center, base, tint, darkTint, accentColor, hasArt, cache)
                            }
                        }
                        baked = true
                    }
                    drawLayer(waxLayer)
                }
            })
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val g = RecordGeometry(size, mountTopInset, maxDiscBottom, landscape)
            val r = g.radius.coerceAtLeast(1f)
            val c = g.center
            val grooveCache = buildGrooveOverlayCache(c, r, 1.2.dp.toPx(), 2.2.dp.toPx())
            val layerSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
            val isPicture = pictureCover
            var groovesBaked = false
            val idleLight = Brush.sweepGradient(
                0f to Color.Transparent, 0.034f to Color.White.copy(alpha = 0.16f),
                0.073f to Color.Transparent, 1f to Color.Transparent, center = c,
            )
            val seekingLight = Brush.sweepGradient(
                0f to Color.Transparent, 0.034f to Color.White.copy(alpha = 0.43f),
                0.073f to Color.Transparent, 1f to Color.Transparent, center = c,
            )
            val idleStroke = Stroke(1.dp.toPx())
            val seekingStroke = Stroke(2.dp.toPx())
            onDrawBehind {
                if (g.radius <= 0f) return@onDrawBehind
                if (!groovesBaked) {
                    grooveLayer.record(layerSize) {
                        drawGrooveOverlay(c, r, isPicture, grooveCache)
                    }
                    groovesBaked = true
                }
                drawLayer(grooveLayer)
                val p = shown().coerceIn(0f, 1f)
                val tip = g.tip(p)
                val activeRadius = (tip - c).getDistance()
                val tipAngle = degrees(atan2(tip.y - c.y, tip.x - c.x))
                val isSeeking = seeking()
                val light = if (isSeeking) seekingLight else idleLight
                rotate(tipAngle - 12f, c) {
                    drawArc(light, 0f, 26f, false, c - Offset(activeRadius, activeRadius),
                        Size(activeRadius * 2, activeRadius * 2), style = if (isSeeking) seekingStroke else idleStroke)
                }
            }
        })
        val labelModifier = Modifier.offset(x = left + radius - label, y = top + radius - label)
            .size(label * 2)
            .then(if (labelPlacement == null) Modifier else Modifier.onGloballyPositioned {
                labelPlacement(it, coverAngle)
            })
        if (!pictureCover) {
            Box(labelModifier) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = rotation
                    alpha = coverAlpha()
                }) {
                    LayoutArtwork(artwork, Modifier.fillMaxSize().clip(CircleShape))
                }
                Canvas(Modifier.fillMaxSize()) {
                    val lr = size.minDimension / 2f
                    val lc = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(Color.Black.copy(alpha = 0.28f), lr - 0.8f, lc, style = Stroke(1.6f))
                    drawCircle(Color.White.copy(alpha = 0.14f), lr * 0.94f, lc, style = Stroke(1f))
                }
            }
        }
    }
}

private data class VinylColors(val base: Color, val tint: Color, val darkTint: Color, val accent: Color)

private fun resolveVinylColors(style: VinylAppearance, palette: Color): VinylColors {
    if (style.finish == VinylFinish.GOLD_RECORD) {
        val goldBase = Color(0xFFC8A042)
        val goldTint = Color(0xFFF7E29C)
        val goldDark = Color(0xFF6B4E12)
        val goldAccent = Color(0xFFE6C367)
        return VinylColors(goldBase, goldTint, goldDark, goldAccent)
    }
    val picture = style.finish == VinylFinish.PICTURE_DISC
    val colorMode = if (picture) VinylColorMode.BLACK else style.colorMode
    val raw = when (colorMode) {
        VinylColorMode.BLACK -> Color(0xFF17181A)
        VinylColorMode.ALBUM -> palette
        VinylColorMode.CUSTOM -> Color(style.customColor)
    }
    val neutral = colorMode == VinylColorMode.BLACK || raw == Color.Unspecified || raw == Color.White
    val base = if (neutral) Color(0xFF17181A) else {
        lerp(raw.copy(alpha = 1f), Color(0xFF141416), if (raw.luminance() > 0.55f) 0.34f else 0.16f)
    }
    val customTint = style.secondaryColor.takeIf { colorMode == VinylColorMode.CUSTOM }
    val tint = customTint?.let { Color(it) } ?: lerp(base, Color.White, if (neutral) 0.44f else 0.60f)
    val darkTint = lerp(base, Color.Black, 0.72f)
    val accent = customTint?.let { Color(it) } ?: lerp(tint, Color(0xFFE7D3A3), if (neutral) 0.25f else 0.15f)
    return VinylColors(base, tint, darkTint, accent)
}

private class VinylWaxCache(
    val swirlPaths: List<Path>,
    val spots: List<Pair<Offset, Float>>,
    val haze: List<Brush>,
)

private fun buildVinylWaxCache(
    finish: VinylFinish,
    seedKey: String,
    r: Float,
    center: Offset,
    base: Color,
    tint: Color,
    darkTint: Color,
    accent: Color,
): VinylWaxCache {
    val random = Random(seedKey.hashCode() xor finish.ordinal * 73)
    val swirlPaths = if (finish == VinylFinish.MARBLED || finish == VinylFinish.SWIRL) {
        List(if (finish == VinylFinish.SWIRL) 14 else 22) { i ->
            val marble = finish == VinylFinish.MARBLED
            val phase = random.nextFloat() * 6.283f
            val offset = (i - 10) * r * (0.082f + random.nextFloat() * 0.036f)
            val amplitude = r * (0.06f + random.nextFloat() * 0.16f)
            val start = phase * 0.36f + offset / r.coerceAtLeast(1f)
            val points = List(65) { step ->
                val t = step / 64f
                if (marble) {
                    val x = (t * 2.55f - 1.275f) * r
                    val y = offset + sin(t * 7.5f + phase) * amplitude +
                        sin(t * 18f + phase * 1.6f) * amplitude * 0.28f
                    val angle = 0.52f + (i % 3) * 0.08f
                    center + Offset(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
                } else {
                    val distance = r * (0.24f + t * 0.90f) +
                        sin(t * 8.5f + phase) * amplitude * 0.24f
                    polar(center, distance, start + t * (3.5f + sin(phase) * 1.0f))
                }
            }
            Path().apply {
                moveTo(points.first().x, points.first().y)
                for (step in 1 until points.lastIndex) {
                    val point = points[step]
                    val middle = (point + points[step + 1]) * 0.5f
                    quadraticTo(point.x, point.y, middle.x, middle.y)
                }
                lineTo(points.last().x, points.last().y)
            }
        }
    } else emptyList()
    val spotCount = when (finish) {
        VinylFinish.GLITTER, VinylFinish.GALAXY -> 150
        VinylFinish.SPLATTER -> 110
        VinylFinish.SHIMMER -> 85
        else -> 0
    }
    val spots = List(spotCount) {
        val radial = r * (0.48f + 0.54f * random.nextFloat())
        polar(center, radial, random.nextFloat() * 6.283f) to (0.14f + random.nextFloat())
    }
    val hazeCount = if (finish == VinylFinish.SMOKED || finish == VinylFinish.GALAXY) 11 else 0
    val haze = List(hazeCount) { i ->
        val pt = polar(center, r * (0.58f + random.nextFloat() * 0.34f), random.nextFloat() * 6.283f)
        val ink = when {
            finish == VinylFinish.SMOKED -> if (i % 3 == 0) lerp(darkTint, Color.Black, 0.35f) else darkTint
            i % 3 == 0 -> tint
            i % 3 == 1 -> lerp(tint, Color(0xFF8D57BD), 0.58f)
            else -> lerp(accent, Color(0xFF4792B8), 0.48f)
        }
        Brush.radialGradient(
            listOf(ink.copy(alpha = if (finish == VinylFinish.SMOKED) 0.72f else 0.78f), ink.copy(alpha = 0f)),
            pt,
            (r * (0.36f + (i % 3) * 0.08f)).coerceAtLeast(1f),
        )
    }
    return VinylWaxCache(swirlPaths, spots, haze)
}

private fun DrawScope.drawVinylWax(
    finish: VinylFinish,
    r: Float,
    center: Offset,
    base: Color,
    tint: Color,
    darkTint: Color,
    accent: Color,
    hasArtwork: Boolean,
    cache: VinylWaxCache,
) {
    val baseFill = when (finish) {
        VinylFinish.TRANSLUCENT -> base.copy(alpha = 0.42f)
        VinylFinish.SMOKED -> base.copy(alpha = 0.62f)
        VinylFinish.GLITTER -> base.copy(alpha = 0.78f)
        VinylFinish.PICTURE_DISC -> if (hasArtwork) Color.Transparent else base
        else -> base
    }
    drawCircle(baseFill, r, center)
    when (finish) {
        VinylFinish.TRANSLUCENT, VinylFinish.SMOKED -> {
            drawCircle(
                Brush.radialGradient(
                    0.44f to base.copy(alpha = 0.82f),
                    0.56f to tint.copy(alpha = 0.44f),
                    0.82f to Color.Transparent,
                    0.96f to tint.copy(alpha = 0.46f),
                    1f to base.copy(alpha = 0.78f),
                    center = center,
                    radius = r,
                ),
                r,
                center,
            )
            if (finish == VinylFinish.SMOKED) {
                for (cloud in cache.haze) drawRect(cloud)
            }
        }
        VinylFinish.MARBLED, VinylFinish.SWIRL -> {
            for ((i, path) in cache.swirlPaths.withIndex()) {
                val ink = when (i % 3) {
                    0 -> darkTint
                    1 -> tint
                    else -> lerp(tint, accent, 0.5f)
                }
                val width = if (finish == VinylFinish.MARBLED) 0.008f else 0.034f
                val band = r * width * (1 + i % 4)
                drawPath(path, ink.copy(alpha = 0.14f), style = Stroke(band * 2.5f, cap = StrokeCap.Round))
                drawPath(path, ink.copy(alpha = 0.34f + (i % 3) * 0.11f), style = Stroke(band, cap = StrokeCap.Round))
            }
        }
        VinylFinish.GALAXY -> {
            drawCircle(darkTint, r, center)
            for (cloud in cache.haze) drawRect(cloud)
            for ((point, amount) in cache.spots) {
                val starColor = if (amount > 0.85f) lerp(tint, Color.White, 0.75f) else Color.White
                drawCircle(starColor.copy(alpha = (0.58f * amount).coerceAtMost(0.9f)), r * 0.0036f * amount, point)
            }
        }
        VinylFinish.SPLATTER, VinylFinish.GLITTER -> {
            val glitter = finish == VinylFinish.GLITTER
            for ((point, amount) in cache.spots) {
                if (glitter) {
                    val sparkle = lerp(tint, Color.White, 0.72f).copy(alpha = (amount * 0.85f).coerceAtMost(0.95f))
                    val fleck = r * 0.0065f * amount
                    drawCircle(sparkle, fleck, point)
                    if (amount > 0.82f) {
                        drawCircle(Color.White.copy(alpha = 0.75f), fleck * 0.45f, point)
                    }
                } else {
                    val ink = when {
                        amount > 0.75f -> tint
                        amount > 0.45f -> accent
                        else -> darkTint
                    }
                    val angle = atan2(point.y - center.y, point.x - center.x)
                    val end = polar(point, r * 0.105f * amount * amount, angle + (amount - 0.6f) * 0.55f)
                    drawLine(ink.copy(alpha = 0.18f), point, end, r * 0.034f * amount, StrokeCap.Round)
                    drawLine(ink.copy(alpha = 0.82f), point, end, r * 0.014f * amount, StrokeCap.Round)
                    drawCircle(ink.copy(alpha = 0.72f), r * 0.015f * amount, point)
                }
            }
        }
        VinylFinish.SPLIT -> {
            drawArc(lerp(base, tint, 0.90f), 21f, 180f, true, topLeft = center - Offset(r, r), size = Size(r * 2f, r * 2f))
            val seamRad = 21f * (PI / 180f).toFloat()
            val seamA = polar(center, r, seamRad)
            val seamB = polar(center, r, seamRad + PI.toFloat())
            drawLine(lerp(base, tint, 0.5f).copy(alpha = 0.45f), seamA, seamB, r * 0.025f)
        }
        VinylFinish.COLOR_IN_COLOR -> {
            drawCircle(
                Brush.radialGradient(
                    0.56f to tint.copy(alpha = 0.92f),
                    0.67f to tint.copy(alpha = 0.78f),
                    0.74f to lerp(tint, base, 0.5f).copy(alpha = 0.35f),
                    0.80f to Color.Transparent,
                    center = center,
                    radius = r,
                ),
                r,
                center,
            )
        }
        VinylFinish.SHIMMER, VinylFinish.METALLIC -> {
            val strong = finish == VinylFinish.METALLIC
            drawCircle(
                if (strong) Brush.sweepGradient(
                    0f to darkTint, 0.12f to tint, 0.22f to lerp(tint, Color.White, 0.35f), 0.30f to base,
                    0.42f to darkTint, 0.60f to tint, 0.72f to lerp(tint, Color.White, 0.3f), 0.82f to base,
                    1f to darkTint, center = center,
                ) else Brush.linearGradient(
                    listOf(
                        darkTint,
                        tint.copy(alpha = 0.22f),
                        lerp(tint, Color.White, 0.28f).copy(alpha = 0.76f),
                        base.copy(alpha = 0.52f),
                        accent.copy(alpha = 0.46f),
                    ),
                    start = center + Offset(-r, r),
                    end = center + Offset(r, -r),
                ),
                r,
                center,
            )
            if (!strong) for ((point, amount) in cache.spots) {
                drawCircle(Color.White.copy(alpha = 0.24f * amount), r * 0.005f * amount, point)
            }
        }
        VinylFinish.PINWHEEL -> {
            val wedges = 12
            val sweep = 360f / wedges
            val topLeft = center - Offset(r, r)
            val box = Size(r * 2f, r * 2f)
            for (i in 0 until wedges) {
                val wedgeColor = if (i % 2 == 0) base else lerp(base, tint, 0.88f)
                drawArc(wedgeColor, i * sweep, sweep + 0.6f, true, topLeft = topLeft, size = box)
            }
        }
        VinylFinish.TRI_COLOR -> {
            val topLeft = center - Offset(r, r)
            val box = Size(r * 2f, r * 2f)
            val c1 = base
            val c2 = lerp(base, tint, 0.86f)
            val c3 = lerp(darkTint, accent, 0.58f)
            drawArc(c1, -30f, 120.8f, true, topLeft = topLeft, size = box)
            drawArc(c2, 90f, 120.8f, true, topLeft = topLeft, size = box)
            drawArc(c3, 210f, 120.8f, true, topLeft = topLeft, size = box)
        }
        VinylFinish.GOLD_RECORD -> {
            drawCircle(
                Brush.sweepGradient(
                    0.00f to darkTint,
                    0.11f to base,
                    0.22f to tint,
                    0.31f to Color(0xFFFFF4C8),
                    0.41f to base,
                    0.52f to darkTint,
                    0.64f to base,
                    0.75f to tint,
                    0.83f to Color(0xFFFFF0B8),
                    0.92f to base,
                    1.00f to darkTint,
                    center = center,
                ),
                r,
                center,
            )
        }
        VinylFinish.PICTURE_DISC -> {
            drawCircle(Color.White.copy(alpha = 0.12f), r * 0.985f, center, style = Stroke(r * 0.03f))
        }
        VinylFinish.PLAIN -> Unit
    }
}

private class GrooveOverlayCache(
    val sheen: Brush,
    val bands: Brush,
    val fine: Brush,
    val rimSheen: Brush,
)

private fun buildGrooveOverlayCache(center: Offset, radius: Float, finePeriodPx: Float, bandWidthPx: Float): GrooveOverlayCache {
    val sheen = Brush.sweepGradient(
        0.00f to Color.Transparent,
        0.10f to Color.Black.copy(alpha = 0.24f),
        0.22f to Color.Transparent,
        0.34f to Color.White.copy(alpha = 0.20f),
        0.44f to Color.Transparent,
        0.64f to Color.Black.copy(alpha = 0.18f),
        0.82f to Color.White.copy(alpha = 0.16f),
        0.92f to Color.Transparent,
        1.00f to Color.Transparent,
        center = center,
    )
    val rimSheen = Brush.sweepGradient(
        0.00f to Color.White.copy(alpha = 0.04f),
        0.32f to Color.White.copy(alpha = 0.28f),
        0.50f to Color.White.copy(alpha = 0.04f),
        0.82f to Color.White.copy(alpha = 0.22f),
        1.00f to Color.White.copy(alpha = 0.04f),
        center = center,
    )
    val from = 0.56f
    val to = 0.968f
    val count = ((to - from) * radius / bandWidthPx.coerceAtLeast(1f)).toInt().coerceIn(10, 96)
    val stops = ArrayList<Pair<Float, Color>>(count + 3)
    stops += 0f to Color.Transparent
    for (i in 0..count) {
        val slow = 0.5f + 0.5f * sin(i * 0.29f + 1.5f * sin(i * 0.07f))
        val dark = (0.04f + 0.16f * slow).coerceAtMost(0.28f)
        stops += (from + (to - from) * i / count) to Color.Black.copy(alpha = dark)
    }
    stops += 1f to Color.Transparent
    val bands = Brush.radialGradient(*stops.toTypedArray(), center = center, radius = radius)
    val fine = Brush.radialGradient(
        0f to Color.Transparent,
        0.52f to Color.Black.copy(alpha = 0.16f),
        1f to Color.Transparent,
        center = center,
        radius = finePeriodPx.coerceAtLeast(1.8f),
        tileMode = TileMode.Repeated,
    )
    return GrooveOverlayCache(sheen, bands, fine, rimSheen)
}

private fun DrawScope.drawGrooveOverlay(
    c: Offset,
    r: Float,
    pictureCover: Boolean,
    cache: GrooveOverlayCache,
) {
    val grooveAlpha = if (pictureCover) 0.58f else 1f
    val band = Stroke(r * 0.415f)
    drawCircle(cache.sheen, r * 0.755f, c, alpha = grooveAlpha, style = band)
    drawCircle(cache.bands, r * 0.755f, c, alpha = grooveAlpha, style = band)
    drawCircle(cache.fine, r * 0.755f, c, alpha = grooveAlpha, style = band)
    for (i in 0..5) {
        val gap = 0.585f + i * 0.064f
        drawCircle(Color.Black.copy(alpha = 0.30f * grooveAlpha), r * gap, c, style = Stroke(1.dp.toPx()))
        drawCircle(cache.sheen, r * (gap + 0.004f), c, alpha = grooveAlpha, style = Stroke(1.3.dp.toPx()))
    }
    drawCircle(Color.Black.copy(alpha = 0.42f * grooveAlpha), r * 0.534f, c, style = Stroke(r * 0.042f))
    drawCircle(Color.Black.copy(alpha = 0.72f), r * 0.992f, c, style = Stroke(r * 0.015f))
    drawCircle(cache.rimSheen, r * 0.985f, c, style = Stroke(r * 0.014f))
}

/** Mini circular vinyl preview used in the revamped Vinyl Appearance Studio. */
@Composable
internal fun VinylFinishSwatch(
    appearance: VinylAppearance,
    palette: Color,
    artwork: Any?,
    modifier: Modifier = Modifier,
) {
    val colors = resolveVinylColors(appearance, palette)
    val picture = appearance.finish == VinylFinish.PICTURE_DISC && artwork != null
    Box(modifier.graphicsLayer { shape = CircleShape; clip = true }, contentAlignment = Alignment.Center) {
        if (picture) {
            LayoutArtwork(artwork, Modifier.fillMaxSize())
        }
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            if (r <= 0f) return@Canvas
            val wax = buildVinylWaxCache(
                appearance.finish,
                "swatch",
                r,
                c,
                colors.base,
                colors.tint,
                colors.darkTint,
                colors.accent,
            )
            drawVinylWax(appearance.finish, r, c, colors.base, colors.tint, colors.darkTint, colors.accent, picture, wax)
            val grooves = buildGrooveOverlayCache(c, r, 1.8f, 3.5f)
            drawGrooveOverlay(c, r, picture, grooves)
            if (!picture) {
                val labelR = r * 0.42f
                drawCircle(lerp(palette, Color(0xFF2B2D33), 0.35f), labelR, c)
                drawCircle(Color.Black.copy(alpha = 0.3f), labelR, c, style = Stroke(1f))
            }
        }
    }
}

private class TonearmPose(var progress: Float, var lift: Float = 0f)

@Composable
internal fun VinylTonearm(
    shown: () -> Float,
    onScrub: ((Float) -> Unit)?,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onSeeking: (Boolean) -> Unit,
    reduced: Boolean,
    blurReduced: Boolean,
    moving: () -> Float,
    modifier: Modifier,
    durationMs: Long = 0L,
    trackKey: String? = null,
    mountTopInset: Int = 0,
    maxDiscBottom: Float? = null,
    isPlaying: Boolean = true,
    landscape: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    var seeking by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        if (seeking) 1f else 0f, animationSpec = if (reduced) snap() else spring(), label = "tonearmLift",
    )
    val pauseLift by animateFloatAsState(
        if (!isPlaying && !seeking) 0.42f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = 240f),
        label = "tonearmPauseLift",
    )
    val seekArcAlpha by animateFloatAsState(
        if (seeking) 1f else 0f, animationSpec = if (reduced) snap() else tween(220), label = "tonearmSeekArc",
    )
    val peekAlpha = remember { Animatable(0f) }
    LaunchedEffect(trackKey, durationMs > 0L, reduced, onScrub != null) {
        if (reduced || durationMs <= 0L || onScrub == null || trackKey == null) {
            peekAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        peekAlpha.animateTo(1f, tween(260))
        delay(3_000L)
        peekAlpha.animateTo(0f, tween(520))
    }
    val useGpuShadow = renderEffectBlurSupported && !blurReduced
    val shadowLayer = if (useGpuShadow) rememberGraphicsLayer() else null
    val plinthLayer = rememberGraphicsLayer()
    val plinthCache = remember {
        object {
            var box: IntSize = IntSize.Zero
            var mountTopInset: Int = Int.MIN_VALUE
            var maxDiscBottom: Float? = null
            var landscape: Boolean = false
        }
    }
    val shadowCache = remember {
        object {
            var box: IntSize = IntSize.Zero
            var swing: Float = Float.NaN
            var raised: Float = Float.NaN
            var blurRadius: Float = Float.NaN
            var dim: Float = Float.NaN
        }
    }
    val lastDrawn = remember { TonearmPose(shown()) }
    val start = remember { TonearmPose(shown()) }
    val trip = remember { Animatable(1f) }
    val duration by rememberUpdatedState(durationMs)
    var landedTrack by remember { mutableStateOf(trackKey) }
    var traveling by remember { mutableStateOf(false) }
    LaunchedEffect(trackKey, seeking, reduced) {
        if (reduced || seeking) {
            landedTrack = trackKey
            traveling = false
        } else if (landedTrack != trackKey || traveling) {
            start.progress = lastDrawn.progress
            start.lift = lastDrawn.lift
            trip.snapTo(0f)
            traveling = true
            trip.animateTo(0.2f, tween(100, easing = LinearEasing))
            withTimeoutOrNull(600) { snapshotFlow { duration }.first { it > 0L } }
            trip.animateTo(0.8f, tween(285, easing = LinearEasing))
            trip.animateTo(1f, tween(135, easing = LinearEasing))
            landedTrack = trackKey
            traveling = false
        }
    }
    val armPosition: () -> Float = {
        when {
            seeking || reduced -> shown()
            traveling -> {
                val sweep = FastOutSlowInEasing.transform(((trip.value - 0.2f) / 0.6f).coerceIn(0f, 1f))
                start.progress + (shown() - start.progress) * sweep
            }
            landedTrack != trackKey -> lastDrawn.progress
            else -> shown()
        }
    }
    val gesture = Modifier.seekArtwork(
        armPosition, onScrub, onFinish, onCancel, claimDown = true,
        accepts = { bounds, down, tolerance, fraction ->
            val g = RecordGeometry(bounds, mountTopInset, maxDiscBottom, landscape)
            val arm = g.tip(fraction) - g.pivot
            val delta = down - g.pivot
            val projection = ((arm.x * delta.x + arm.y * delta.y) / (arm.x * arm.x + arm.y * arm.y)).coerceIn(0f, 1f)
            (delta - arm * projection).getDistance() <= tolerance
        },
        along = { _, _, _ -> true },
        fraction = { bounds, down, current, initial ->
            val g = RecordGeometry(bounds, mountTopInset, maxDiscBottom, landscape)
            val before = down - g.pivot
            val after = current - g.pivot
            val change = atan2(after.y, after.x) - atan2(before.y, before.x)
            g.fraction(g.angle(initial) + atan2(sin(change), cos(change)))
        },
        onDragging = { seeking = it; onSeeking(it) },
    )
    Canvas(modifier.then(gesture).artworkSeekControls(shown, durationMs, onScrub, onFinish)) {
        val g = RecordGeometry(size, mountTopInset, maxDiscBottom, landscape)
        if (g.radius <= 1f) return@Canvas
        val u = g.unit
        val fullBox = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
        if (plinthCache.box != fullBox ||
            plinthCache.mountTopInset != mountTopInset ||
            plinthCache.maxDiscBottom != maxDiscBottom ||
            plinthCache.landscape != landscape
        ) {
            val mountSize = Size(u * 0.148f, u * 0.248f)
            val mount = g.pivot - Offset(mountSize.width * 0.5f, mountSize.height * 0.34f)
            val round = CornerRadius(mountSize.width * 0.46f)
            val cueBase = g.pivot + Offset(u * 0.044f, u * 0.095f)
            plinthLayer.record(fullBox) {
                for (i in 1..5) {
                    val spread = i * u * 0.003f
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.068f),
                        mount + Offset(u * 0.011f - spread, u * 0.017f - spread),
                        Size(mountSize.width + spread * 2f, mountSize.height + spread * 2f),
                        round,
                    )
                }
                // Machined plinth mount housing with metallic rim bevel.
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFF2D3138), Color(0xFF15181E), Color(0xFF0A0B0E)), mount.y, mount.y + mountSize.height),
                    mount, mountSize, round,
                )
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        mount.y, mount.y + mountSize.height,
                    ),
                    mount, mountSize, round, style = Stroke(u * 0.0024f),
                )
                // Subtle cueing lever tucked beside the lower mount base.
                drawLine(Color(0xFF8C94A0), cueBase, cueBase + Offset(u * 0.022f, u * 0.014f), u * 0.006f, StrokeCap.Round)
                drawCircle(Color(0xFFCCD2DC), u * 0.007f, cueBase + Offset(u * 0.024f, u * 0.015f))
                // Layered concentric machined gimbal base rings.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF3E444F), Color(0xFF1A1D24), Color(0xFF0C0E12)),
                        center = g.pivot - Offset(u * 0.012f, u * 0.012f),
                        radius = u * 0.068f,
                    ),
                    u * 0.058f,
                    g.pivot,
                )
                drawCircle(Color(0xFF6A717E).copy(alpha = 0.65f), u * 0.056f, g.pivot, style = Stroke(u * 0.0022f))
                drawCircle(Color(0xFF101217), u * 0.045f, g.pivot)
                drawCircle(Color(0xFF535966), u * 0.041f, g.pivot, style = Stroke(u * 0.0018f))
            }
            plinthCache.box = fullBox
            plinthCache.mountTopInset = mountTopInset
            plinthCache.maxDiscBottom = maxDiscBottom
            plinthCache.landscape = landscape
        }
        drawLayer(plinthLayer)
        val phase = trip.value
        val travelLift = when {
            seeking || reduced -> 0f
            traveling && phase < 0.2f -> start.lift + (1f - start.lift) * (phase / 0.2f)
            traveling -> ((1f - phase) / 0.2f).coerceIn(0f, 1f)
            landedTrack != trackKey -> lastDrawn.lift
            else -> 0f
        }
        val raised = if (reduced) 0f else maxOf(lift, travelLift, pauseLift, abs(moving()).coerceIn(0f, 1f) * 0.7f)
        val arm = armPosition()
        lastDrawn.progress = arm
        lastDrawn.lift = raised
        val swing = degrees(g.angle(arm))
        val dim = if (blurReduced) 0.20f else 0.28f
        val shift = u * (0.014f + 0.018f * raised)
        if (shadowLayer != null && size.width > 1f && size.height > 1f) {
            val res = 0.5f
            val blurRadius = (u * (0.020f + 0.026f * raised) * res).coerceAtLeast(1.5f)
            val box = IntSize(
                (size.width * res).roundToInt().coerceAtLeast(1),
                (size.height * res).roundToInt().coerceAtLeast(1),
            )
            val blurChanged = shadowCache.blurRadius.isNaN() || abs(blurRadius - shadowCache.blurRadius) > 0.4f
            val needsRecord = shadowCache.box != box ||
                blurChanged ||
                shadowCache.swing.isNaN() || abs(swing - shadowCache.swing) > 0.25f ||
                shadowCache.raised.isNaN() || abs(raised - shadowCache.raised) > 0.015f ||
                shadowCache.dim != dim
            if (blurChanged) {
                shadowLayer.renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Decal)
                shadowCache.blurRadius = blurRadius
            }
            if (needsRecord) {
                val p = g.pivot + Offset(shift, shift * 1.45f)
                shadowLayer.record(box) {
                    scale(res, res, pivot = Offset.Zero) {
                        rotate(swing, p) {
                            drawArm(p, g.reach, u, Color.Black.copy(alpha = (dim - raised * 0.06f).coerceIn(0.10f, 0.32f)))
                        }
                    }
                }
                shadowCache.box = box
                shadowCache.swing = swing
                shadowCache.raised = raised
                shadowCache.dim = dim
            }
            scale(1f / res, 1f / res, pivot = Offset.Zero) { drawLayer(shadowLayer) }
        } else {
            for (i in 0..if (blurReduced) 0 else 4) {
                val p = g.pivot + Offset(shift + u * i * 0.0025f, shift * 1.45f)
                rotate(swing, p) {
                    drawArm(p, g.reach, u, Color.Black.copy(alpha = (dim - raised * 0.06f) / if (blurReduced) 1f else 5f))
                }
            }
        }
        rotate(swing, g.pivot) {
            val liftScale = 1f + raised * 0.022f
            scale(liftScale, liftScale, g.pivot) { drawArm(g.pivot, g.reach, u, null) }
        }
        val arcAlpha = maxOf(seekArcAlpha, peekAlpha.value).coerceIn(0f, 1f)
        if (arcAlpha > 0.01f) {
            val center = g.pivot
            val reach = g.reach * 1.045f
            val from = degrees(g.angle(0f))
            val sweep = degrees(g.angle(1f)) - from
            val top = center - Offset(reach, reach)
            val ink = Color.White.copy(alpha = 0.65f * arcAlpha)
            drawArc(ink.copy(alpha = 0.33f * arcAlpha), from, sweep, false, top, Size(reach * 2, reach * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(ink, from, swing - from, false, top, Size(reach * 2, reach * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            if (seeking && durationMs > 0L) {
                val tip = g.tip(shown())
                val label = measurer.measure(formatTime((durationMs * shown()).toLong()), timeStyle)
                val at = Offset(
                    (tip.x + 10.dp.toPx()).coerceIn(7.dp.toPx(), (size.width - label.size.width - 7.dp.toPx()).coerceAtLeast(7.dp.toPx())),
                    (tip.y + 14.dp.toPx()).coerceIn(4.dp.toPx(), (size.height - label.size.height - 4.dp.toPx()).coerceAtLeast(4.dp.toPx())),
                )
                drawRoundRect(Color.Black.copy(alpha = 0.77f), at - Offset(4.dp.toPx(), 2.dp.toPx()),
                    Size(label.size.width + 8.dp.toPx(), label.size.height + 4.dp.toPx()), CornerRadius(5.dp.toPx()))
                drawText(label, Color.White, topLeft = at)
            }
        }
    }
}

private fun DrawScope.drawArm(pivot: Offset, length: Float, unit: Float, shadow: Color?) {
    val tip = pivot + Offset(length, 0f)
    val bend = 13f
    val radians = bend * (PI / 180).toFloat()
    val headLength = unit * 0.096f
    val socket = polar(tip, -unit * 0.128f, radians)
    val tangent = polar(socket, -unit * 0.047f, radians)
    val shaftWidth = unit * 0.021f
    val shaftAt = pivot.y - unit * 0.045f

    // Rear counterweight stub & brushed cylindrical counterweight behind the pivot.
    val stubEnd = pivot.x - unit * 0.125f
    drawLine(
        shadow ?: Color(0xFF6B737E),
        Offset(stubEnd, shaftAt),
        Offset(pivot.x, shaftAt),
        shaftWidth * 0.85f,
        StrokeCap.Round,
    )
    if (shadow == null) {
        drawLine(
            Color(0xFFD5DCE5),
            Offset(stubEnd + unit * 0.004f, shaftAt - shaftWidth * 0.18f),
            Offset(pivot.x, shaftAt - shaftWidth * 0.18f),
            shaftWidth * 0.28f,
            StrokeCap.Round,
        )
    }
    val cwWidth = unit * 0.068f
    val cwHeight = unit * 0.052f
    val cwPos = Offset(pivot.x - unit * 0.118f, shaftAt - cwHeight * 0.5f)
    val cwCorner = CornerRadius(unit * 0.011f)
    drawRoundRect(
        shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
            listOf(Color(0xFFC5CCD6), Color(0xFF838B96), Color(0xFF474D57), Color(0xFF2B3038)),
            cwPos.y,
            cwPos.y + cwHeight,
        ),
        cwPos,
        Size(cwWidth, cwHeight),
        cwCorner,
    )
    if (shadow == null) {
        // Tracking-force calibration ring on front edge of counterweight.
        val dialX = cwPos.x + cwWidth * 0.72f
        drawRect(
            Color(0xFF1F232B),
            Offset(dialX, cwPos.y + cwHeight * 0.05f),
            Size(cwWidth * 0.18f, cwHeight * 0.90f),
        )
        drawLine(
            Color.White.copy(alpha = 0.65f),
            Offset(dialX + cwWidth * 0.09f, cwPos.y + cwHeight * 0.22f),
            Offset(dialX + cwWidth * 0.09f, cwPos.y + cwHeight * 0.44f),
            unit * 0.0022f,
            StrokeCap.Round,
        )
        drawRoundRect(
            Color.White.copy(alpha = 0.24f),
            cwPos,
            Size(cwWidth, cwHeight),
            cwCorner,
            style = Stroke(unit * 0.0018f),
        )
    }

    // 3D brushed-aluminum arm tube with clean silver edges (no harsh dark border on bright vinyls).
    val tube = Path().apply {
        moveTo(pivot.x, shaftAt)
        lineTo(tangent.x - unit * 0.02f, shaftAt)
        quadraticTo(tangent.x, shaftAt, socket.x, socket.y)
    }
    val tubeStroke = Stroke(shaftWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (shadow != null) {
        drawPath(tube, shadow, style = tubeStroke)
    } else {
        drawPath(tube, Color(0xFF68707B), style = tubeStroke)
        translate(top = -shaftWidth * 0.06f) {
            drawPath(tube, Color(0xFFA2AAB5), style = Stroke(shaftWidth * 0.80f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        translate(top = -shaftWidth * 0.14f) {
            drawPath(tube, Color(0xFFDCE3EC), style = Stroke(shaftWidth * 0.46f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        translate(top = -shaftWidth * 0.22f) {
            drawPath(tube, Color(0xFFF5F8FC), style = Stroke(shaftWidth * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    // Machined gimbal yoke & pivot cap.
    val bearing = Size(unit * 0.108f, unit * 0.088f)
    val bearingAt = pivot - Offset(bearing.width * 0.5f, bearing.height * 0.64f)
    drawRoundRect(
        shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
            listOf(Color(0xFF78808C), Color(0xFF3E434D), Color(0xFF1D2128), Color(0xFF121419)),
            bearingAt.y, bearingAt.y + bearing.height,
        ), bearingAt, bearing, CornerRadius(unit * 0.018f),
    )
    if (shadow == null) {
        drawRoundRect(
            Color.White.copy(alpha = 0.24f),
            bearingAt,
            bearing,
            CornerRadius(unit * 0.018f),
            style = Stroke(unit * 0.0018f),
        )
        drawCircle(Color(0xFF9EA5B0), unit * 0.012f, pivot - Offset(unit * 0.017f, unit * 0.009f))
        drawCircle(Color(0xFF23272F), unit * 0.006f, pivot - Offset(unit * 0.017f, unit * 0.009f))
        drawCircle(Color(0xFF7E8591), unit * 0.008f, pivot + Offset(unit * 0.022f, -unit * 0.009f))
    }

    rotate(bend, tip) {
        // Knurled metallic locking collar joining arm tube to headshell.
        val collarAt = tip - Offset(unit * 0.138f, unit * 0.015f)
        val collarSize = Size(unit * 0.040f, unit * 0.030f)
        drawRoundRect(
            shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
                listOf(Color(0xFF6B727E), Color(0xFF2B3038), Color(0xFF14171D)),
                collarAt.y, collarAt.y + collarSize.height,
            ),
            collarAt, collarSize, CornerRadius(unit * 0.004f),
        )
        if (shadow == null) {
            for (k in 0..2) {
                val rx = collarAt.x + unit * (0.008f + k * 0.009f)
                drawRect(Color(0xFFB2B9C3), Offset(rx, collarAt.y + unit * 0.002f), Size(unit * 0.0035f, collarSize.height - unit * 0.004f))
            }
        }

        val left = tip - Offset(unit * 0.109f, unit * 0.024f)
        val height = unit * 0.048f
        val nose = left + Offset(headLength, height * 0.14f)

        // Iconic curved chrome finger lift jutting out from the outer side of the headshell.
        val liftPath = Path().apply {
            moveTo(left.x + headLength * 0.56f, left.y + height * 0.85f)
            quadraticTo(
                left.x + headLength * 0.78f,
                left.y + height * 1.48f,
                left.x + headLength * 1.12f,
                left.y + height * 1.38f,
            )
        }
        val liftStroke = Stroke(unit * 0.0075f, cap = StrokeCap.Round)
        if (shadow != null) {
            drawPath(liftPath, shadow, style = liftStroke)
        } else {
            drawPath(liftPath, Color(0xFF474E58), style = liftStroke)
            drawPath(liftPath, Color(0xFFE2E8F0), style = Stroke(unit * 0.0042f, cap = StrokeCap.Round))
        }

        val shell = Path().apply {
            moveTo(left.x + unit * 0.01f, left.y)
            lineTo(nose.x - unit * 0.012f, left.y)
            quadraticTo(nose.x, left.y, nose.x, nose.y)
            lineTo(nose.x, left.y + height * 0.78f)
            quadraticTo(nose.x, left.y + height, nose.x - unit * 0.014f, left.y + height)
            lineTo(left.x + unit * 0.01f, left.y + height * 0.83f)
            quadraticTo(left.x, left.y + height * 0.8f, left.x, left.y + height * 0.65f)
            lineTo(left.x, left.y + height * 0.24f)
            quadraticTo(left.x, left.y, left.x + unit * 0.01f, left.y)
            close()
        }
        // Stylus cantilever + metallic tip glint at the groove contact point.
        drawLine(
            shadow ?: Color(0xFFD5DCE4), tip - Offset(unit * 0.024f, -unit * 0.005f), tip,
            unit * 0.0055f, StrokeCap.Round,
        )
        if (shadow == null) {
            drawCircle(Color(0xFFF8FAFC), unit * 0.0042f, tip)
        }
        // Cartridge body beneath headshell.
        drawRoundRect(
            shadow ?: Color(0xFF171B21), tip - Offset(unit * 0.075f, unit * 0.014f),
            Size(unit * 0.064f, unit * 0.033f), CornerRadius(unit * 0.005f),
        )
        if (shadow != null) drawPath(shell, shadow) else {
            drawPath(shell, Brush.verticalGradient(
                listOf(Color(0xFF7B8390), Color(0xFF434A55), Color(0xFF232831)), left.y, left.y + height,
            ))
            drawPath(shell, Color(0xFF9EA5B2).copy(alpha = 0.7f), style = Stroke(unit * 0.0022f))
            // Dual parallel slotted cartridge mounting screws.
            for (row in listOf(0.30f, 0.62f)) {
                val fastener = left + Offset(headLength * 0.44f, height * row)
                drawCircle(Color(0xFFC5CCD6), unit * 0.0042f, fastener)
                drawLine(
                    Color(0xFF272C34),
                    fastener - Offset(unit * 0.0028f, 0f),
                    fastener + Offset(unit * 0.0028f, 0f),
                    unit * 0.0014f,
                )
            }
        }
    }
}
