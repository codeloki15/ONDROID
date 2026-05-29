package com.locallink.pro.ui.screens.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.service.llm.ModelState

@Composable
fun ModelGateScreen(
    onReady: () -> Unit,
    vm: ModelGateViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state is ModelState.Ready) onReady() }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Omni Pro", style = MaterialTheme.typography.headlineMedium)
            when (val s = state) {
                is ModelState.Checking -> {
                    CircularProgressIndicator()
                    Text("Checking model…")
                }
                is ModelState.Ready -> Text("Ready")
                is ModelState.Missing -> {
                    Text("Model not found on device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Push the Gemma model to:\n${s.expectedPath}\n\nThen tap Retry.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { vm.prepare() }) { Text("Retry") }
                }
                is ModelState.Error -> {
                    Text("Error: ${s.message}", textAlign = TextAlign.Center)
                    Button(onClick = { vm.prepare() }) { Text("Retry") }
                }
            }
        }
    }
}
