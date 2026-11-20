package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.VinylAppearance
import com.music.bitchord.data.settings.VinylColorMode
import com.music.bitchord.data.settings.VinylFinish
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class RecordGeometry(size: Size) {
    val radius = minOf(size.width * 0.698f, size.height * 0.468f)
    val center = Offset(size.width * 0.163f, radius)
    val pivot = Offset(size.width * 0.887f, minOf(size.width * 0.148f, size.height * 0.19f))
    val unit = minOf(size.width, size.height)
    private val distance = (center - pivot).getDistance()
    private val towardCenter = atan2(center.y - pivot.y, center.x - pivot.x)
    val reach = (distance - radius * 0.23f).coerceAtLeast(1f)
    val outer = radius * 0.952f
    val inner = radius * 0.565f

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
) {
    var rotation by remember(trackKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(spin, trackKey) {
        if (!spin) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            rotation = (rotation + (frame - last).coerceAtMost(70_000_000L) * 0.0000002f) % 360f
            last = frame
        }
    }
    val raw = when (style.colorMode) {
        VinylColorMode.BLACK -> Color(0xFF17181A)
        VinylColorMode.ALBUM -> palette
        VinylColorMode.CUSTOM -> Color(style.customColor)
    }
    val neutral = style.colorMode == VinylColorMode.BLACK || raw == Color.Unspecified || raw == Color.White
    val base = if (neutral) Color(0xFF17181A) else {
        lerp(raw.copy(alpha = 1f), Color(0xFF141416), if (raw.luminance() > 0.55f) 0.39f else 0.19f)
    }
    val tint = if (neutral) Color(0xFF66666B) else when (style.colorMode) {
        VinylColorMode.CUSTOM -> style.secondaryColor?.let { Color(it) } ?: lerp(base, Color.White, 0.43f)
        else -> lerp(base, Color.White, 0.43f)
    }

    BoxWithConstraints(modifier) {
        val radius = minOf(maxWidth * 0.698f, maxHeight * 0.468f)
        val at = Modifier.offset(x = maxWidth * 0.163f - radius)
            .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(radius * 2)
        val label = radius * 0.516f
        Box(Modifier.fillMaxSize().drawWithCache {
            val g = RecordGeometry(size)
            val shadowAt = g.center + Offset(g.radius * 0.02f, g.radius * 0.046f)
            val outer = (g.radius * 1.12f).coerceAtLeast(1f)
            val shadow = Brush.radialGradient(
                0.82f to Color.Black.copy(alpha = 0.65f), 1f to Color.Transparent,
                center = shadowAt, radius = outer,
            )
            onDrawBehind { if (g.radius > 0f) drawCircle(shadow, outer, shadowAt) }
        })
        Box(at.graphicsLayer { rotationZ = rotation; shape = CircleShape; clip = true }) {
            if (style.finish == VinylFinish.PICTURE_DISC && artwork != null) {
                LayoutArtwork(artwork, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().drawWithCache {
                val random = Random(albumKey.hashCode() xor style.finish.ordinal * 73)
                val r = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseFill = when (style.finish) {
                    VinylFinish.TRANSLUCENT, VinylFinish.SMOKED -> base.copy(alpha = 0.76f)
                    VinylFinish.PICTURE_DISC -> if (artwork != null) base.copy(alpha = 0.48f) else base
                    else -> base
                }
                val swirlPaths = List(15) { i ->
                    val start = random.nextFloat() * 6.283f
                    val winding = if (style.finish == VinylFinish.SWIRL) 4.8f else 1.25f + random.nextFloat() * 2.2f
                    val inner = r * (0.43f + random.nextFloat() * 0.34f)
                    Path().apply {
                        for (step in 0..35) {
                            val t = step / 35f
                            val distance = inner + (r - inner) * t + sin((t * 12f) + i) * r * 0.035f
                            val pt = polar(center, distance, start + winding * t)
                            if (step == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                    }
                }
                val spots = List(120) {
                    val radial = r * (0.54f + 0.48f * random.nextFloat())
                    polar(center, radial, random.nextFloat() * 6.283f) to (0.18f + random.nextFloat())
                }
                val haze = List(8) {
                    val pt = polar(center, r * (0.62f + random.nextFloat() * 0.30f), random.nextFloat() * 6.283f)
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.29f), tint.copy(alpha = 0f)), pt, (r * 0.37f).coerceAtLeast(1f))
                }
                onDrawBehind {
                    if (r <= 0f) return@onDrawBehind
                    drawCircle(baseFill, r)
                    when (style.finish) {
                        VinylFinish.TRANSLUCENT, VinylFinish.SMOKED -> {
                            drawCircle(
                                Brush.radialGradient(listOf(base, tint.copy(alpha = 0.34f), Color.Transparent), center, r),
                                r,
                            )
                            if (style.finish == VinylFinish.SMOKED) {
                                for (cloud in haze) drawRect(cloud)
                            }
                        }
                        VinylFinish.MARBLED, VinylFinish.SWIRL -> {
                            for ((i, path) in swirlPaths.withIndex()) {
                                val strength = if (style.finish == VinylFinish.MARBLED) 0.16f else 0.24f
                                drawPath(path, tint.copy(alpha = strength + (i % 3) * 0.04f),
                                    style = Stroke(r * (0.012f + (i % 4) * 0.014f), cap = StrokeCap.Round))
                            }
                        }
                        VinylFinish.GALAXY -> for (cloud in haze) drawRect(cloud)
                        VinylFinish.SPLATTER, VinylFinish.GLITTER -> {
                            val glitter = style.finish == VinylFinish.GLITTER
                            for ((point, amount) in spots) {
                                if (glitter) {
                                    drawCircle(lerp(tint, Color.White, 0.38f).copy(alpha = amount * 0.44f), r * 0.009f * amount, point)
                                } else {
                                    drawOval(tint.copy(alpha = 0.36f), point,
                                        Size(r * 0.042f * amount, r * 0.015f * amount))
                                }
                            }
                        }
                        VinylFinish.SPLIT -> drawArc(tint.copy(alpha = 0.60f), 21f, 180f, true,
                            size = Size(r * 2f, r * 2f))
                        VinylFinish.COLOR_IN_COLOR -> drawCircle(
                            Brush.radialGradient(
                                0.58f to tint.copy(alpha = 0.52f), 0.69f to tint.copy(alpha = 0.33f),
                                0.80f to Color.Transparent, center = center, radius = r,
                            ), r,
                        )
                        VinylFinish.SHIMMER, VinylFinish.METALLIC -> {
                            val strong = style.finish == VinylFinish.METALLIC
                            drawCircle(
                                Brush.sweepGradient(
                                    listOf(Color.Transparent, tint.copy(alpha = if (strong) 0.52f else 0.26f),
                                        Color.Transparent, tint.copy(alpha = 0.22f), Color.Transparent),
                                    center,
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
            val g = RecordGeometry(size)
            val r = g.radius.coerceAtLeast(1f)
            val c = g.center
            val sheen = Brush.sweepGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.26f),
                    Color.Transparent, Color.White.copy(alpha = 0.18f),
                    Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent), c,
            )
            val fine = Brush.radialGradient(
                0f to Color.Transparent, 0.53f to Color.Black.copy(alpha = 0.23f), 1f to Color.Transparent,
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
        LayoutArtwork(
            artwork,
            Modifier.offset(x = maxWidth * 0.163f - label, y = radius - label)
                .size(label * 2).graphicsLayer { rotationZ = rotation; shape = CircleShape; clip = true },
        )
    }
}

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
) {
    val measurer = rememberTextMeasurer()
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    var seeking by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(
        if (seeking) 1f else 0f, animationSpec = if (reduced) snap() else spring(), label = "tonearmLift",
    )
    val gesture = Modifier.seekArtwork(
        shown, onScrub, onFinish, onCancel, claimDown = true,
        accepts = { bounds, down, tolerance, fraction ->
            val g = RecordGeometry(bounds)
            val arm = g.tip(fraction) - g.pivot
            val delta = down - g.pivot
            val projection = ((arm.x * delta.x + arm.y * delta.y) / (arm.x * arm.x + arm.y * arm.y)).coerceIn(0f, 1f)
            (delta - arm * projection).getDistance() <= tolerance
        },
        along = { _, _, _ -> true },
        fraction = { bounds, down, current, initial ->
            val g = RecordGeometry(bounds)
            val before = down - g.pivot
            val after = current - g.pivot
            val change = atan2(after.y, after.x) - atan2(before.y, before.x)
            g.fraction(g.angle(initial) + atan2(sin(change), cos(change)))
        },
        onDragging = { seeking = it; onSeeking(it) },
    )
    Canvas(modifier.then(gesture)) {
        val g = RecordGeometry(size)
        if (g.radius <= 1f) return@Canvas
        val u = g.unit
        val mountSize = Size(u * 0.154f, u * 0.265f)
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
        val raised = if (reduced) 0f else maxOf(lift, abs(moving()).coerceIn(0f, 1f) * 0.7f)
        val swing = degrees(g.angle(shown()))
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
    val shaftWidth = unit * 0.026f
    val shaftStart = minOf(length * 0.10f, unit * 0.080f)
    val shaft = pivot + Offset(shaftStart, -shaftWidth / 2f)
    val shaftSize = Size(length * 0.85f - shaftStart, shaftWidth)
    val shaftBrush = shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
        listOf(Color(0xFF42464C), Color(0xFFA7ADB4), Color(0xFFE9E9ED), Color(0xFF6E747B)),
        shaft.y, shaft.y + shaftWidth,
    )
    drawRoundRect(shaftBrush, shaft, shaftSize, CornerRadius(shaftWidth * 0.4f))
    val weight = Size(unit * 0.14f, unit * 0.095f)
    val top = pivot - Offset(weight.width * 0.32f, weight.height / 2f)
    drawRoundRect(
        shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
            listOf(Color(0xFF50525B), Color(0xFF1C1F25)),
            top.y, top.y + weight.height,
        ), top, weight, CornerRadius(unit * 0.014f),
    )
    val tip = pivot + Offset(length, 0f)
    val head = Size(length * 0.165f, unit * 0.060f)
    val bend = (16f * PI / 180f).toFloat()
    val collar = polar(tip, -(head.width + unit * 0.006f), bend)
    drawLine(
        shadow ?: Color(0xFF444850), pivot + Offset(length * 0.815f, 0f), collar,
        shaftWidth * 1.17f, StrokeCap.Round,
    )
    rotate(16f, tip) {
        val left = tip - Offset(head.width + unit * 0.012f, head.height / 2f)
        drawLine(shadow ?: Color(0xFFE1E5EA), tip - Offset(unit * 0.033f, 0f), tip, unit * 0.009f, StrokeCap.Round)
        drawRoundRect(
            shadow?.let { Brush.linearGradient(listOf(it, it)) } ?: Brush.verticalGradient(
                listOf(Color(0xFF545862), Color(0xFF272A30), Color(0xFF17191D)), left.y, left.y + head.height,
            ), left, head, CornerRadius(unit * 0.011f),
        )
        if (shadow == null) {
            val seam = left.x + head.width * 0.73f
            drawLine(Color.White.copy(alpha = 0.18f), Offset(seam, left.y + 2f),
                Offset(seam, left.y + head.height - 2f), unit * 0.002f)
        }
    }
}
