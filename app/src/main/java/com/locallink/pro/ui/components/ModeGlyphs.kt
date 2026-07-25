package com.locallink.pro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin

/** Which home-screen mode a glyph represents. */
enum class ModeGlyph { Chat, Voice, Automate }

/**
 * The animated mark on a home mode card.
 *
 * Drawn rather than shipped as an image: these tint with the theme, stay sharp at any density,
 * and cost a few hundred bytes instead of a decoder dependency plus per-density assets. Each
 * mode moves the way its mode behaves — a reply arriving, a voice level, a task running.
 *
 * One shared clock per glyph drives everything, and drawing happens in the draw phase only, so
 * an always-animating home screen doesn't recompose anything.
 */
@Composable
fun AnimatedModeGlyph(
    glyph: ModeGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "glyph")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = glyph.periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier) {
        when (glyph) {
            ModeGlyph.Chat -> drawTypingDots(t, tint)
            ModeGlyph.Voice -> drawLevels(t, tint)
            ModeGlyph.Automate -> drawSparkle(t, tint)
        }
    }
}

private val ModeGlyph.periodMs: Int
    get() = when (this) {
        ModeGlyph.Chat -> 1400
        ModeGlyph.Voice -> 1100
        ModeGlyph.Automate -> 4200
    }

/** Three dots rising in sequence — a reply being composed. */
private fun DrawScope.drawTypingDots(t: Float, tint: Color) {
    val r = size.minDimension * 0.11f
    val gap = size.minDimension * 0.30f
    val cy = size.height / 2f
    val startX = size.width / 2f - gap
    repeat(3) { i ->
        // Each dot trails the one before it by a third of the cycle.
        val phase = (t + i / 3f) % 1f
        val lift = sin(phase * 2f * PI.toFloat()).coerceAtLeast(0f)
        drawCircle(
            color = tint.copy(alpha = 0.45f + 0.55f * lift),
            radius = r * (0.82f + 0.30f * lift),
            center = Offset(startX + gap * i, cy - lift * size.minDimension * 0.10f),
        )
    }
}

/** Five bars breathing at different rates — a voice level meter. */
private fun DrawScope.drawLevels(t: Float, tint: Color) {
    val bars = 5
    val barW = size.width * 0.10f
    val gap = (size.width - bars * barW) / (bars - 1).toFloat()
    val maxH = size.height * 0.78f
    val cy = size.height / 2f
    // Coprime-ish multipliers keep the bars from ever marching in lockstep.
    val rates = floatArrayOf(1f, 1.7f, 1.3f, 2.1f, 1.5f)
    repeat(bars) { i ->
        val wave = sin((t * rates[i] + i * 0.2f) * 2f * PI.toFloat())
        val h = maxH * (0.28f + 0.72f * ((wave + 1f) / 2f))
        val x = i * (barW + gap)
        drawRoundRect(
            color = tint.copy(alpha = 0.65f + 0.35f * ((wave + 1f) / 2f)),
            topLeft = Offset(x, cy - h / 2f),
            size = Size(barW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
        )
    }
}

/** A four-point sparkle turning and pulsing — the agent working on its own. */
private fun DrawScope.drawSparkle(t: Float, tint: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val pulse = (sin(t * 2f * PI.toFloat()) + 1f) / 2f
    val outer = size.minDimension * (0.40f + 0.06f * pulse)
    val waist = outer * 0.30f

    rotate(degrees = t * 360f, pivot = Offset(cx, cy)) {
        val star = Path().apply {
            moveTo(cx, cy - outer)
            quadraticTo(cx + waist, cy - waist, cx + outer, cy)
            quadraticTo(cx + waist, cy + waist, cx, cy + outer)
            quadraticTo(cx - waist, cy + waist, cx - outer, cy)
            quadraticTo(cx - waist, cy - waist, cx, cy - outer)
            close()
        }
        drawPath(star, color = tint.copy(alpha = 0.55f + 0.45f * pulse))
    }
    // Small companion spark, offset and counter-phased so the mark never reads as static.
    val small = size.minDimension * 0.13f * (0.6f + 0.4f * (1f - pulse))
    drawCircle(
        color = tint.copy(alpha = 0.35f + 0.35f * (1f - pulse)),
        radius = small,
        center = Offset(cx + size.minDimension * 0.30f, cy - size.minDimension * 0.28f),
    )
}
