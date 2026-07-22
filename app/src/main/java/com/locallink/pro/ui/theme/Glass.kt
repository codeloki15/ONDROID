package com.locallink.pro.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass surface treatment for the "Amber Glass" theme.
 *
 * The ambient [GlassBackground] is a soft gradient with no hard edges, so a
 * translucent fill layered on top reads as frosted glass without needing a real
 * (expensive) backdrop blur. We add a top-edge light catch (a faint white→clear
 * vertical gradient) and a hairline border to sell the glass material.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    fill: Color = GlassFill,
    border: Color = GlassBorder,
    highlight: Boolean = true,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .then(
        if (highlight) Modifier.background(
            Brush.verticalGradient(
                0f to GlassHighlight,
                0.35f to Color.Transparent,
            ),
            shape,
        ) else Modifier,
    )
    .border(BorderStroke(1.dp, border), shape)

/** A frosted glass panel with content padding. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    fill: Color = GlassFill,
    border: Color = GlassBorder,
    highlight: Boolean = true,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .glass(shape = shape, fill = fill, border = border, highlight = highlight)
            .padding(contentPadding),
    ) { content() }
}
