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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.VinylAppearance
import com.music.bitchord.data.settings.VinylColorMode
import com.music.bitchord.data.settings.VinylFinish
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Keep the latest phases on the UI thread when the player leaves composition for Appearance.
private val lastRecordPhases = object : LinkedHashMap<String, Float>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > 8
}

private const val RECORD_WIDTH_FRACTION = 0.703f
private const val RECORD_HEIGHT_FRACTION = 0.446f

internal class RecordGeometry(size: Size, mountTopInset: Int = 0) {
    val radius = minOf(size.width * RECORD_WIDTH_FRACTION, size.height * RECORD_HEIGHT_FRACTION)
    val unit = radius / RECORD_WIDTH_FRACTION
    val reach = (radius * 1.39f).coerceAtLeast(1f)
    val outer = radius * 0.952f
    val inner = radius * 0.565f
    val pivot = Offset(
        size.width * 0.895f,
        maxOf(size.width * 0.125f, mountTopInset + unit * 0.088f).coerceAtMost(radius * 1.6f),
    )
    private val vertical = radius - pivot.y
    private val maxDistance = (reach + inner - radius * 0.02f).coerceAtLeast(0f)
    private val maxHorizontal = sqrt((maxDistance * maxDistance - vertical * vertical).coerceAtLeast(0f))
    val center = Offset(maxOf(size.width * 0.163f, pivot.x - maxHorizontal), radius)
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
            val seconds = (frame - previousFrame) / 1_000_000_000f
            previousFrame = frame
            rotation = (rotation + spinRate.value * seconds) % 360f
        }
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
        lerp(raw.copy(alpha = 1f), Color(0xFF141416), if (raw.luminance() > 0.55f) 0.39f else 0.19f)
    }
    val customTint = style.secondaryColor.takeIf { colorMode == VinylColorMode.CUSTOM }
    val tint = customTint?.let { Color(it) } ?: lerp(base, Color.White, if (neutral) 0.41f else 0.60f)
    val darkTint = lerp(base, Color.Black, 0.74f)

    BoxWithConstraints(modifier) {
        val geometry = with(androidx.compose.ui.platform.LocalDensity.current) {
            RecordGeometry(Size(maxWidth.toPx(), maxHeight.toPx()), mountTopInset)
        }
        val radius = with(androidx.compose.ui.platform.LocalDensity.current) { geometry.radius.toDp() }
        val left = with(androidx.compose.ui.platform.LocalDensity.current) { (geometry.center.x - geometry.radius).toDp() }
        val at = Modifier.offset(x = left)
            .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(radius * 2)
        val label = radius * 0.516f
        Box(Modifier.fillMaxSize().drawWithCache {
            val g = RecordGeometry(size, mountTopInset)
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
                LayoutArtwork(artwork, Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = rotation
                    shape = CircleShape
                    clip = true
                    alpha = coverAlpha()
                })
            }
        }
        Box(at.graphicsLayer { rotationZ = rotation; shape = CircleShape; clip = true }) {
            Box(Modifier.fillMaxSize().drawWithCache {
                val random = Random(albumKey.hashCode() xor style.finish.ordinal * 73)
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseFill = when (style.finish) {
                    VinylFinish.TRANSLUCENT -> base.copy(alpha = 0.45f)
                    VinylFinish.SMOKED -> base.copy(alpha = 0.67f)
                    VinylFinish.PICTURE_DISC -> if (artwork != null) Color.Transparent else base
                    else -> base
                }
                val swirlPaths = if (style.finish == VinylFinish.MARBLED || style.finish == VinylFinish.SWIRL) {
                    List(if (style.finish == VinylFinish.SWIRL) 11 else 19) { i ->
                        val marble = style.finish == VinylFinish.MARBLED
                        val phase = random.nextFloat() * 6.283f
                        val offset = (i - 8) * r * (0.092f + random.nextFloat() * 0.038f)
                        val amplitude = r * (0.07f + random.nextFloat() * 0.17f)
                        val start = phase * 0.36f + offset / r.coerceAtLeast(1f)
                        val points = List(65) { step ->
                            val t = step / 64f
                            if (marble) {
                                val x = (t * 2.55f - 1.275f) * r
                                val y = offset + sin(t * 8f + phase) * amplitude +
                                    sin(t * 19f + phase * 1.6f) * amplitude * 0.27f
                                val angle = 0.55f
                                center + Offset(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
                            } else {
                                val distance = r * (0.24f + t * 0.90f) +
                                    sin(t * 9f + phase) * amplitude * 0.28f
                                polar(center, distance, start + t * (3.2f + sin(phase) * 1.1f))
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
                val spots = List(120) {
                    val radial = r * (0.50f + 0.53f * random.nextFloat())
                    polar(center, radial, random.nextFloat() * 6.283f) to (0.12f + random.nextFloat())
                }
                val haze = List(9) { i ->
                    val pt = polar(center, r * (0.62f + random.nextFloat() * 0.30f), random.nextFloat() * 6.283f)
                    val ink = when {
                        style.finish == VinylFinish.SMOKED -> darkTint
                        i % 2 == 0 -> tint
                        else -> lerp(tint, Color(0xFF8D57BD), 0.62f)
                    }
                    Brush.radialGradient(listOf(ink.copy(alpha = 0.83f), ink.copy(alpha = 0f)), pt, (r * 0.41f).coerceAtLeast(1f))
                }
                onDrawBehind {
                    if (r <= 0f) return@onDrawBehind
                    drawCircle(baseFill, r)
                    when (style.finish) {
                        VinylFinish.TRANSLUCENT, VinylFinish.SMOKED -> {
                            drawCircle(
                                Brush.radialGradient(
                                    0.46f to base, 0.58f to tint.copy(alpha = 0.50f),
                                    0.86f to Color.Transparent, 1f to tint.copy(alpha = 0.44f),
                                    center = center, radius = r,
                                ),
                                r,
                            )
                            if (style.finish == VinylFinish.SMOKED) {
                                for (cloud in haze) drawRect(cloud)
                            }
                        }
                        VinylFinish.MARBLED, VinylFinish.SWIRL -> {
                            for ((i, path) in swirlPaths.withIndex()) {
                                val ink = if (i % 3 == 0) darkTint else tint
                                val width = if (style.finish == VinylFinish.MARBLED) 0.009f else 0.037f
                                val band = r * width * (1 + i % 4)
                                drawPath(path, ink.copy(alpha = 0.13f), style = Stroke(band * 2.3f, cap = StrokeCap.Round))
                                drawPath(path, ink.copy(alpha = 0.30f + (i % 3) * 0.10f),
                                    style = Stroke(band, cap = StrokeCap.Round))
                            }
                        }
                        VinylFinish.GALAXY -> {
                            drawCircle(darkTint, r)
                            for (cloud in haze) drawRect(cloud)
                            for ((point, amount) in spots) {
                                drawCircle(Color.White.copy(alpha = 0.52f * amount), r * 0.003f * amount, point)
                            }
                        }
                        VinylFinish.SPLATTER, VinylFinish.GLITTER -> {
                            val glitter = style.finish == VinylFinish.GLITTER
                            for ((point, amount) in spots) {
                                if (glitter) {
                                    val sparkle = lerp(tint, Color.White, 0.68f).copy(alpha = amount * 0.77f)
                                    val fleck = r * 0.006f * amount
                                    drawCircle(sparkle, fleck, point)
                                } else {
                                    val ink = if (amount > 0.69f) tint else darkTint
                                    val angle = atan2(point.y - center.y, point.x - center.x)
                                    val end = polar(point, r * 0.082f * amount * amount, angle + (amount - 0.7f) * 0.85f)
                                    drawLine(ink.copy(alpha = 0.17f), point, end, r * 0.032f * amount, StrokeCap.Round)
                                    drawLine(ink.copy(alpha = 0.77f), point, end, r * 0.014f * amount, StrokeCap.Round)
                                    drawCircle(ink.copy(alpha = 0.63f), r * 0.013f * amount, point)
                                }
                            }
                        }
                        VinylFinish.SPLIT -> drawArc(lerp(base, tint, 0.92f), 21f, 180f, true,
                            size = Size(r * 2f, r * 2f))
                        VinylFinish.COLOR_IN_COLOR -> drawCircle(
                            Brush.radialGradient(
                                0.62f to tint.copy(alpha = 0.88f), 0.68f to tint.copy(alpha = 0.75f),
                                0.75f to Color.Transparent, center = center, radius = r,
                            ), r,
                        )
                        VinylFinish.SHIMMER, VinylFinish.METALLIC -> {
                            val strong = style.finish == VinylFinish.METALLIC
                            drawCircle(
                                if (strong) Brush.sweepGradient(
                                    0f to darkTint, 0.14f to tint, 0.24f to base, 0.39f to darkTint,
                                    0.61f to tint, 0.71f to base, 1f to darkTint, center = center,
                                ) else Brush.linearGradient(
                                    listOf(darkTint, tint.copy(alpha = 0.14f), tint.copy(alpha = 0.73f),
                                        base.copy(alpha = 0.5f), tint.copy(alpha = 0.44f)),
                                    start = Offset(0f, r * 2f), end = Offset(r * 2f, 0f),
                                ), r,
                            )
                            if (!strong) for ((point, amount) in spots) {
                                drawCircle(tint.copy(alpha = 0.28f), r * 0.005f * amount, point)
                            }
                        }
                        VinylFinish.PLAIN, VinylFinish.PICTURE_DISC -> Unit
                    }
                }
            })
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val g = RecordGeometry(size, mountTopInset)
            val r = g.radius.coerceAtLeast(1f)
            val c = g.center
            val sheen = Brush.sweepGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f),
                    Color.Transparent, Color.White.copy(alpha = 0.18f),
                    Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent), c,
            )
            val fine = Brush.radialGradient(
                0f to Color.Transparent, 0.53f to Color.Black.copy(alpha = 0.18f), 1f to Color.Transparent,
                center = c, radius = 2.35.dp.toPx(), tileMode = TileMode.Repeated,
            )
            val band = Stroke(r * 0.415f)
            onDrawBehind {
                if (g.radius <= 0f) return@onDrawBehind
                drawCircle(sheen, r * 0.75f, c, style = band)
                drawCircle(fine, r * 0.75f, c, style = band)
                for (i in 0..5) {
                    val gap = 0.571f + i * 0.066f
                    drawCircle(Color.Black.copy(alpha = 0.28f), r * gap, c, style = Stroke(1.dp.toPx()))
                    drawCircle(sheen, r * (gap + 0.004f), c, style = Stroke(1.4.dp.toPx()))
                }
                drawCircle(Color.Black.copy(alpha = 0.40f), r * 0.527f, c, style = Stroke(r * 0.045f))
                drawCircle(Color.Black.copy(alpha = 0.73f), r * 0.992f, c, style = Stroke(r * 0.014f))
                drawCircle(sheen, r * 0.978f, c, style = Stroke(r * 0.020f))
                val p = shown().coerceIn(0f, 1f)
                val tip = g.tip(p)
                val activeRadius = (tip - c).getDistance()
                val tipAngle = degrees(atan2(tip.y - c.y, tip.x - c.x))
                val strength = if (seeking()) 0.43f else 0.16f
                val light = Brush.sweepGradient(
                    0f to Color.Transparent, 0.034f to Color.White.copy(alpha = strength),
                    0.073f to Color.Transparent, 1f to Color.Transparent, center = c,
                )
                rotate(tipAngle - 12f, c) {
                    drawArc(light, 0f, 26f, false, c - Offset(activeRadius, activeRadius),
                        Size(activeRadius * 2, activeRadius * 2), style = Stroke(if (seeking()) 2.dp.toPx() else 1.dp.toPx()))
                }
            }
        })
        val labelModifier = Modifier.offset(x = left + radius - label, y = radius - label)
            .size(label * 2)
            .then(if (labelPlacement == null) Modifier else Modifier.onGloballyPositioned {
                labelPlacement(it, coverAngle)
            })
        if (!pictureCover) {
            Box(labelModifier) {
                LayoutArtwork(artwork, Modifier.fillMaxSize().graphicsLayer {
                    rotationZ = rotation
                    shape = CircleShape
                    clip = true
                    alpha = coverAlpha()
                })
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
) {
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    var seeking by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        if (seeking) 1f else 0f, animationSpec = if (reduced) snap() else spring(), label = "tonearmLift",
    )
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
            val g = RecordGeometry(bounds, mountTopInset)
            val arm = g.tip(fraction) - g.pivot
            val delta = down - g.pivot
            val projection = ((arm.x * delta.x + arm.y * delta.y) / (arm.x * arm.x + arm.y * arm.y)).coerceIn(0f, 1f)
            (delta - arm * projection).getDistance() <= tolerance
        },
        along = { _, _, _ -> true },
        fraction = { bounds, down, current, initial ->
            val g = RecordGeometry(bounds, mountTopInset)
            val before = down - g.pivot
            val after = current - g.pivot
            val change = atan2(after.y, after.x) - atan2(before.y, before.x)
            g.fraction(g.angle(initial) + atan2(sin(change), cos(change)))
        },
        onDragging = { seeking = it; onSeeking(it) },
    )
    Canvas(modifier.then(gesture).artworkSeekControls(shown, durationMs, onScrub, onFinish)) {
        val g = RecordGeometry(size, mountTopInset)
        if (g.radius <= 1f) return@Canvas
        val u = g.unit
        val mountSize = Size(u * 0.143f, u * 0.245f)
        val mount = g.pivot - Offset(mountSize.width * 0.5f, mountSize.height * 0.34f)
        val round = CornerRadius(mountSize.width * 0.45f)
        for (i in 1..5) {
            val spread = i * u * 0.003f
            drawRoundRect(Color.Black.copy(alpha = 0.085f), mount + Offset(u * 0.011f - spread, u * 0.017f - spread),
                Size(mountSize.width + spread * 2f, mountSize.height + spread * 2f), round)
        }
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF27292D), Color(0xFF0D0E11)), mount.y, mount.y + mountSize.height),
            mount, mountSize, round,
        )
        drawCircle(Color(0xFF101217), u * 0.050f, g.pivot)
        drawCircle(Color(0xFF454953), u * 0.043f, g.pivot, style = Stroke(u * 0.002f))
        val phase = trip.value
        val travelLift = when {
            seeking || reduced -> 0f
            traveling && phase < 0.2f -> start.lift + (1f - start.lift) * (phase / 0.2f)
            traveling -> ((1f - phase) / 0.2f).coerceIn(0f, 1f)
            landedTrack != trackKey -> lastDrawn.lift
            else -> 0f
        }
        val raised = if (reduced) 0f else maxOf(lift, travelLift, abs(moving()).coerceIn(0f, 1f) * 0.7f)
        val arm = armPosition()
        lastDrawn.progress = arm
        lastDrawn.lift = raised
        val swing = degrees(g.angle(arm))
        val dim = if (blurReduced) 0.34f else 0.52f
        val shift = u * (0.010f + 0.014f * raised)
        for (i in 0..if (blurReduced) 0 else 4) {
            val p = g.pivot + Offset(shift + u * i * 0.002f, shift * 1.5f)
            rotate(swing, p) {
                drawArm(p, g.reach, u, Color.Black.copy(alpha = (dim - raised * 0.1f) / if (blurReduced) 1f else 5f))
            }
        }
        rotate(swing, g.pivot) {
            val liftScale = 1f + raised * 0.018f
            scale(liftScale, liftScale, g.pivot) { drawArm(g.pivot, g.reach, u, null) }
        }
        if (seeking) {
            val center = g.pivot
            val reach = g.reach * 1.045f
            val from = degrees(g.angle(0f))
            val sweep = degrees(g.angle(1f)) - from
            val top = center - Offset(reach, reach)
            val ink = Color.White.copy(alpha = 0.65f)
            drawArc(ink.copy(alpha = 0.33f), from, sweep, false, top, Size(reach * 2, reach * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(ink, from, swing - from, false, top, Size(reach * 2, reach * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            if (durationMs > 0L) {
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
    val headLength = unit * 0.094f
    val socket = polar(tip, -unit * 0.128f, radians)
    val tangent = polar(socket, -unit * 0.047f, radians)
    val shaftWidth = unit * 0.020f
    val shaftAt = pivot.y - unit * 0.045f
    val tube = Path().apply {
        moveTo(pivot.x, shaftAt)
        lineTo(tangent.x - unit * 0.02f, shaftAt)
        quadraticTo(tangent.x, shaftAt, socket.x, socket.y)
    }
    val tubeStroke = Stroke(shaftWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (shadow != null) {
        drawPath(tube, shadow, style = tubeStroke)
    } else {
        drawPath(tube, Color(0xFF343941), style = tubeStroke)
        drawPath(tube, Color(0xFF848C95), style = Stroke(shaftWidth * 0.79f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        translate(top = -shaftWidth * 0.18f) {
            drawPath(tube, Color(0xFFE5EBF0), style = Stroke(shaftWidth * 0.26f, cap = StrokeCap.Round))
        }
    }
    val bearing = Size(unit * 0.105f, unit * 0.086f)
    val bearingAt = pivot - Offset(bearing.width * 0.5f, bearing.height * 0.65f)
    drawRoundRect(
        shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
            listOf(Color(0xFF676D78), Color(0xFF30343C), Color(0xFF171A20)),
            bearingAt.y, bearingAt.y + bearing.height,
        ), bearingAt, bearing, CornerRadius(unit * 0.017f),
    )
    if (shadow == null) {
        drawCircle(Color(0xFF80848D), unit * 0.010f, pivot - Offset(unit * 0.017f, unit * 0.009f))
        drawCircle(Color(0xFF242830), unit * 0.005f, pivot - Offset(unit * 0.017f, unit * 0.009f))
    }
    rotate(bend, tip) {
        val collarAt = tip - Offset(unit * 0.135f, unit * 0.014f)
        drawRoundRect(
            shadow ?: Color(0xFF292F37), collarAt, Size(unit * 0.038f, unit * 0.028f),
            CornerRadius(unit * 0.004f),
        )
        if (shadow == null) {
            val ringAt = collarAt + Offset(unit * 0.012f, 0f)
            drawRect(Color(0xFF9399A2), ringAt, Size(unit * 0.007f, unit * 0.028f))
        }
        val left = tip - Offset(unit * 0.109f, unit * 0.024f)
        val height = unit * 0.048f
        val nose = left + Offset(headLength, height * 0.14f)
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
        drawLine(
            shadow ?: Color(0xFFBFC7CD), tip - Offset(unit * 0.024f, -unit * 0.005f), tip,
            unit * 0.0055f, StrokeCap.Round,
        )
        drawRoundRect(
            shadow ?: Color(0xFF171B21), tip - Offset(unit * 0.075f, unit * 0.014f),
            Size(unit * 0.064f, unit * 0.033f), CornerRadius(unit * 0.005f),
        )
        if (shadow != null) drawPath(shell, shadow) else {
            drawPath(shell, Brush.verticalGradient(
                listOf(Color(0xFF676E79), Color(0xFF3C424B), Color(0xFF232831)), left.y, left.y + height,
            ))
            drawPath(shell, Color(0xFF868B95).copy(alpha = 0.65f), style = Stroke(unit * 0.002f))
            val fastener = left + Offset(headLength * 0.42f, height * 0.39f)
            drawCircle(Color(0xFFACB2BC), unit * 0.0038f, fastener)
            drawLine(Color(0xFF2E333A), fastener - Offset(unit * 0.0026f, 0f),
                fastener + Offset(unit * 0.0026f, 0f), unit * 0.0013f)
        }
    }
}
