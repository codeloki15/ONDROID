package com.locallink.pro.ui.screens.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.service.llm.ModelState
import com.locallink.pro.ui.theme.*

@Composable
fun ModelGateScreen(
    onReady: () -> Unit,
    vm: ModelGateViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state is ModelState.Ready) onReady() }

    GlassBackground {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier.size(76.dp)
                    .glass(shape = RoundedCornerShape(24.dp), fill = OmniAccentContainer),
                contentAlignment = Alignment.Center,
            ) { Text("✦", color = OmniAccentBright, style = MaterialTheme.typography.headlineMedium) }

            Text("Omni", style = MaterialTheme.typography.headlineMedium, color = OmniText)

            when (val s = state) {
                is ModelState.Checking -> {
                    CircularProgressIndicator(color = OmniAccent)
                    Text("Preparing…", color = OmniTextDim, style = MaterialTheme.typography.bodyMedium)
                }
                is ModelState.Ready -> Text("Ready", color = OmniSuccess)
                is ModelState.Missing -> {
                    Text("On-device model not found", color = OmniText, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Push a model to:\n${s.expectedPath}\n\nOr add an OpenRouter key in Settings to use a cloud model. Then tap Retry.",
                        color = OmniTextFaint, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { vm.prepare() },
                        colors = ButtonDefaults.buttonColors(containerColor = OmniAccent, contentColor = OmniTextOnAccent),
                    ) { Text("Retry") }
                }
                is ModelState.Error -> {
                    Text("Error: ${s.message}", color = OmniError, textAlign = TextAlign.Center)
                    Button(
                        onClick = { vm.prepare() },
                        colors = ButtonDefaults.buttonColors(containerColor = OmniAccent, contentColor = OmniTextOnAccent),
                    ) { Text("Retry") }
                }
            }
        }
        }
    }
}
