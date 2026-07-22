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
 * Warm ambient backdrop for the "Amber Glass" theme: a near-black base lit by
 * two soft radial glows (amber top-right, bronze bottom-left) plus a gentle
 * vignette. Static (no per-frame animation) so it's cheap to draw — the frosted
 * glass surfaces layered on top are what create the depth.
 *
 * Wrap a screen's content in this; it fills the available space and draws the
 * glow behind whatever you pass as [content].
 */
@Composable
fun GlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(OmniBg)
            .drawBehind {
                val w = size.width
                val h = size.height

                // Whisper of indigo lift in the upper area — barely-there depth,
                // keeps the screen from being a flat dead black without going muddy.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(GlowAmber.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.04f),
                        radius = maxOf(w, h) * 0.9f,
                    ),
                )
                // Subtle cool vignette to settle the corners.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, GlowEdge.copy(alpha = 0.4f)),
                        center = Offset(w * 0.5f, h * 0.45f),
                        radius = maxOf(w, h) * 1.0f,
                    ),
                )
            },
    ) {
        content()
    }
}
