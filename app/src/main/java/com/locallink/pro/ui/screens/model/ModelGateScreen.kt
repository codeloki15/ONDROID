package com.locallink.pro.ui.screens.model

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.service.llm.ModelState
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.theme.*

@Composable
fun ModelGateScreen(
    onReady: () -> Unit,
    vm: ModelGateViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state is ModelState.Ready) onReady() }

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

                when (val s = state) {
                    is ModelState.Checking -> {
                        CircularProgressIndicator(color = AuroraVioletHi, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                        Text("Preparing…", color = OmniTextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                    is ModelState.Ready -> Text("Ready", color = OmniSuccess, style = MaterialTheme.typography.bodyMedium)
                    is ModelState.Missing -> {
                        Text("No model configured", color = OmniText, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add an OpenRouter key in Settings to use a cloud model, " +
                                "or push an on-device model to:\n${s.expectedPath}",
                            color = OmniTextFaint, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        GradientPill("Retry", onClick = { vm.prepare() })
                    }
                    is ModelState.Error -> {
                        Text(
                            "Error: ${s.message}", color = OmniError,
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                        )
                        GradientPill("Retry", onClick = { vm.prepare() })
                    }
                }
            }
        }
    }
}
