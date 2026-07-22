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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Charcoal surface treatment for the "Aurora Ink" theme. Solid #221f20 panels
 * with generous radii; borders are opt-in (pass a visible [border]) since the
 * reference design keeps filled cards edge-free and uses outlines only for
 * ghost/outlined elements.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    fill: Color = GlassFill,
    border: Color = Color.Transparent,
    highlight: Boolean = false,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .then(
        if (border != Color.Transparent) Modifier.border(BorderStroke(1.dp, border), shape)
        else Modifier,
    )

/** A charcoal panel with content padding. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    fill: Color = GlassFill,
    border: Color = Color.Transparent,
    highlight: Boolean = false,
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .glass(shape = shape, fill = fill, border = border)
            .padding(contentPadding),
    ) { content() }
}
