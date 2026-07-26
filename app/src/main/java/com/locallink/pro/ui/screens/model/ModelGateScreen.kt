package com.locallink.pro.ui.screens.model

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.theme.*

/**
 * Launch screen shown while the app decides where to send you (onboarding or home).
 *
 * It used to gate on an on-device model being present and downloadable, which is why it could
 * report Missing/Error and offer a retry. The brain is cloud-only now — a missing OpenRouter key
 * is surfaced by the setup banner on the home screen, where it can actually be fixed — so there
 * is nothing left to check and this is purely the moment of brand before the first frame.
 */
@Composable
fun ModelGateScreen(onReady: () -> Unit) {
    LaunchedEffect(Unit) { onReady() }

    val t = rememberInfiniteTransition(label = "gate")
    val breath by t.animateFloat(
        0.96f, 1.04f,
        infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "breath",
    )

    AuroraBackground(glow = 0.7f) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                GradientOrb(
                    size = 96.dp, glow = true,
                    modifier = Modifier.graphicsLayer { scaleX = breath; scaleY = breath },
                )
                Text("OmniPro", style = MaterialTheme.typography.displayMedium, color = OmniText)
            }
        }
    }
}
