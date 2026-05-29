package com.locallink.pro.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) { vm.openSession(sessionId) }
    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + if (state.streamingText.isNotBlank()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> vm.attachImage(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Omni Pro") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.toggleAutoTts() }) {
                        Icon(if (state.autoTts) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Auto TTS")
                    }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
            ) {
                items(state.messages, key = { it.id }) { MessageBubble(it) }
                if (state.streamingText.isNotBlank()) {
                    item { MessageBubble(Message(text = state.streamingText + "▌", sender = MessageSender.AI)) }
                } else if (state.isAiResponding) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(8.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (state.partialVoiceResult.isNotBlank()) {
                Text(
                    state.partialVoiceResult,
                    Modifier.fillMaxWidth().padding(8.dp),
                    textAlign = TextAlign.Center,
                )
            }
            state.pendingImageUri?.let { uri ->
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    IconButton(onClick = { vm.attachImage(null) }) { Icon(Icons.Default.Close, "Remove image") }
                }
            }

            InputBar(
                input = state.inputText,
                isListening = state.isListening,
                onInputChange = vm::updateInput,
                onSend = { vm.sendMessage() },
                onMic = { vm.toggleVoiceInput() },
                onPickImage = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val isUser = msg.sender == MessageSender.USER
    val align = if (isUser) Alignment.End else Alignment.Start
    val color = when (msg.sender) {
        MessageSender.USER -> MaterialTheme.colorScheme.primaryContainer
        MessageSender.AI -> MaterialTheme.colorScheme.surfaceVariant
        MessageSender.SYSTEM -> MaterialTheme.colorScheme.errorContainer
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        Surface(color = color, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp)) {
                msg.imageUri?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (msg.text.isNotBlank()) Text(msg.text)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    input: String,
    isListening: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onPickImage: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPickImage) { Icon(Icons.Default.Image, "Attach image") }
            IconButton(onClick = onMic) {
                Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic, "Voice")
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 4,
            )
            IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
