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
 * Ambient backdrop for the "Porcelain" theme: warm off-white lit from the top by
 * soft pastel blooms (lavender, peach, a hint of mint) that settle back into the
 * base within the header zone. Static — cheap to draw.
 *
 * [glow] scales the wash: 1f = editorial home header, ~0.3f = whisper for inner
 * screens, 0f = plain porcelain.
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

                // Lavender bloom, top-left.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(GlowAmber.copy(alpha = 0.55f * glow), Color.Transparent),
                        center = Offset(w * 0.20f, h * 0.02f),
                        radius = w * 0.80f,
                    ),
                )
                // Peach bloom, top-right.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(AuroraPeach.copy(alpha = 0.45f * glow), Color.Transparent),
                        center = Offset(w * 0.85f, h * 0.03f),
                        radius = w * 0.70f,
                    ),
                )
                // Mint hint, upper middle.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(PastelMint.copy(alpha = 0.40f * glow), Color.Transparent),
                        center = Offset(w * 0.55f, h * 0.08f),
                        radius = w * 0.55f,
                    ),
                )
                // Settle back into porcelain below the header.
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.14f to OmniBg.copy(alpha = 0.35f),
                        0.36f to OmniBg,
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
