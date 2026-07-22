package com.locallink.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The OmniPro identity mark: a violet→pink gradient sphere with a white
 * four-point sparkle — used as the assistant avatar, list icons and app logo.
 */
enum class OrbPalette(val highlight: Color, val mid: Color, val deep: Color) {
    Violet(Color(0xFFF2A3D2), Color(0xFFCC3FC2), Color(0xFF8A16AE)),
    Ember(Color(0xFFF7C38B), Color(0xFFE8734A), Color(0xFFB3372E)),
    Rose(Color(0xFFF2BCB4), Color(0xFFC26E73), Color(0xFF8E4A52)),
}

@Composable
fun GradientOrb(
    size: Dp,
    modifier: Modifier = Modifier,
    palette: OrbPalette = OrbPalette.Violet,
    sparkle: Boolean = true,
    glow: Boolean = false,
) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)

        if (glow) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.mid.copy(alpha = 0.45f), Color.Transparent),
                    center = c, radius = r * 1.65f,
                ),
                radius = r * 1.65f, center = c,
            )
        }

        // Sphere lit from the upper-left.
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to palette.highlight,
                0.38f to palette.mid,
                0.80f to palette.deep,
                1.00f to palette.deep,
                center = Offset(c.x - r * 0.35f, c.y - r * 0.40f),
                radius = r * 2.0f,
            ),
            radius = r, center = c,
        )

        if (sparkle) {
            val s = r * 0.62f
            val pinch = 0.16f
            val star = Path().apply {
                moveTo(c.x, c.y - s)
                quadraticBezierTo(c.x + s * pinch, c.y - s * pinch, c.x + s, c.y)
                quadraticBezierTo(c.x + s * pinch, c.y + s * pinch, c.x, c.y + s)
                quadraticBezierTo(c.x - s * pinch, c.y + s * pinch, c.x - s, c.y)
                quadraticBezierTo(c.x - s * pinch, c.y - s * pinch, c.x, c.y - s)
                close()
            }
            drawPath(star, color = Color.White)
        }
    }
}

/** Orb inside a fixed-size box so it can sit in icon slots. */
@Composable
fun OrbIcon(size: Dp, palette: OrbPalette = OrbPalette.Violet, glow: Boolean = false) {
    Box(Modifier.size(size)) { GradientOrb(size = size, palette = palette, glow = glow) }
}
