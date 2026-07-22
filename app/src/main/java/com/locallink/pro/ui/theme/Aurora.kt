package com.locallink.pro.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Ambient backdrop for the "Aurora Ink" theme: near-black ink lit from the top
 * by a violet→rose bloom, fading out by ~40% of the screen height. Static
 * (no per-frame animation) so it is cheap to draw.
 *
 * [glow] scales the bloom's intensity: 1f = editorial home-screen header,
 * 0.25f = whisper for chat/settings, 0f = pure ink.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    glow: Float = 0.3f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(OmniBg)
            .drawBehind {
                if (glow <= 0f) return@drawBehind
                val w = size.width
                val h = size.height

                // Deep violet wash across the whole header zone.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF8A16AE).copy(alpha = 0.42f * glow), Color.Transparent),
                        center = Offset(w * 0.42f, -h * 0.05f),
                        radius = maxOf(w, h) * 0.75f,
                    ),
                )
                // Bright violet core, top-left.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraViolet.copy(alpha = 0.50f * glow), Color.Transparent),
                        center = Offset(w * 0.18f, h * 0.02f),
                        radius = w * 0.78f,
                    ),
                )
                // Warm rose-peach bloom, top-right.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraRose.copy(alpha = 0.46f * glow), Color.Transparent),
                        center = Offset(w * 0.85f, h * 0.03f),
                        radius = w * 0.70f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraPeach.copy(alpha = 0.22f * glow), Color.Transparent),
                        center = Offset(w * 0.65f, h * 0.01f),
                        radius = w * 0.45f,
                    ),
                )
                // Settle everything back into ink below the header.
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.13f to OmniBg.copy(alpha = 0.30f),
                        0.34f to OmniBg,
                        startY = 0f, endY = h,
                    ),
                )
            },
    ) {
        content()
    }
}

/** Back-compat alias — older call sites wrap content in [GlassBackground]. */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = AuroraBackground(modifier = modifier, glow = 0.25f, content = content)
