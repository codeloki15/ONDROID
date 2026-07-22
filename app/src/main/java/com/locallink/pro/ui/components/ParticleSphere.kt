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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.locallink.pro.ui.theme.AuroraPink
import com.locallink.pro.ui.theme.AuroraViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice-mode visual: a slowly tumbling cloud of dots on a deformed sphere,
 * violet fading to pink with depth — the reference design's particle blob.
 * While [listening], the sphere breathes; idle, it drifts.
 */
@Composable
fun ParticleSphere(
    listening: Boolean,
    modifier: Modifier = Modifier,
    particleCount: Int = 850,
) {
    // Unit sphere points via the Fibonacci lattice (stable across recompositions).
    val points = remember(particleCount) {
        val golden = PI * (3.0 - sqrt(5.0))
        List(particleCount) { i ->
            val y = 1.0 - (i / (particleCount - 1.0)) * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = golden * i
            Triple((cos(theta) * r).toFloat(), y.toFloat(), (sin(theta) * r).toFloat())
        }
    }

    val t = rememberInfiniteTransition(label = "sphere")
    val spin by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(26000, easing = LinearEasing)),
        label = "spin",
    )
    val wobble by t.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "wobble",
    )
    val breath by t.animateFloat(
        0.97f, 1.05f,
        infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "breath",
    )

    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val baseR = size.minDimension * 0.36f * (if (listening) breath else 1f)
        val tilt = 0.42f // fixed X-axis tilt so the pole never faces the camera

        // Soft ambient glow behind the cloud.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AuroraViolet.copy(alpha = if (listening) 0.30f else 0.20f), Color.Transparent),
                center = c, radius = baseR * 1.9f,
            ),
            radius = baseR * 1.9f, center = c,
        )

        val cosS = cos(spin); val sinS = sin(spin)
        val cosT = cos(tilt); val sinT = sin(tilt)
        points.forEachIndexed { i, (px, py, pz) ->
            // Organic surface ripple — each dot swells in and out slightly out of phase.
            val ripple = 1f + 0.08f * sin(wobble * 2f + i * 0.37f)
            // Rotate around Y (spin), then tilt around X.
            val x1 = px * cosS + pz * sinS
            val z1 = -px * sinS + pz * cosS
            val y2 = py * cosT - z1 * sinT
            val z2 = py * sinT + z1 * cosT

            val depth = (z2 + 1f) / 2f // 0 = far, 1 = near
            val sr = baseR * ripple
            val pos = Offset(c.x + x1 * sr, c.y + y2 * sr)
            drawCircle(
                color = lerp(AuroraViolet, AuroraPink, depth).copy(alpha = 0.16f + 0.74f * depth * depth),
                radius = (0.5f + 1.4f * depth).dp.toPx(),
                center = pos,
            )
        }
    }
}
