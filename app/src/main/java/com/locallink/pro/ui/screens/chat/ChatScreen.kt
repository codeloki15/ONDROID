package com.locallink.pro.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.ui.theme.*

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
        val count = state.messages.size + if (state.streamingText.isNotBlank() || state.isAiResponding) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> vm.attachImage(uri) }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Omni", style = MaterialTheme.typography.titleLarge, color = OmniText)
                        Text(
                            if (state.isAiResponding) "thinking…" else "online",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isAiResponding) OmniAccentBright else OmniSuccess,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OmniText)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleAutoTts() }) {
                        Icon(
                            if (state.autoTts) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            "Auto TTS", tint = if (state.autoTts) OmniAccentBright else OmniTextFaint,
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = OmniTextDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniBg),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.messages.isEmpty() && state.streamingText.isBlank() && !state.isAiResponding) {
                EmptyChat(Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { MessageBubble(it) }
                    if (state.streamingText.isNotBlank()) {
                        item { MessageBubble(Message(text = state.streamingText, sender = MessageSender.AI), streaming = true) }
                    } else if (state.isAiResponding) {
                        item { TypingBubble() }
                    }
                }
            }

            if (state.partialVoiceResult.isNotBlank()) {
                Text(
                    state.partialVoiceResult,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    textAlign = TextAlign.Center, color = OmniAccentBright,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.pendingImageUri?.let { uri ->
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = uri, contentDescription = "Attached",
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    IconButton(onClick = { vm.attachImage(null) }) {
                        Icon(Icons.Default.Close, "Remove image", tint = OmniTextDim)
                    }
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
private fun EmptyChat(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(20.dp))
                    .background(OmniAccentContainer),
                contentAlignment = Alignment.Center,
            ) { Text("✦", color = OmniAccentBright, style = MaterialTheme.typography.headlineMedium) }
            Spacer(Modifier.height(16.dp))
            Text("How can I help?", color = OmniText, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask anything, or try “set a timer for 5 minutes”.",
                color = OmniTextFaint, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: Message, streaming: Boolean = false) {
    when (msg.sender) {
        MessageSender.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = OmniUserBubble,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    msg.imageUri?.let {
                        AsyncImage(it, null, Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)))
                        if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    }
                    if (msg.text.isNotBlank())
                        Text(msg.text, color = OmniText, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        MessageSender.AI -> Column(Modifier.fillMaxWidth()) {
            Text(
                buildString { append(msg.text); if (streaming) append(" ▌") },
                color = OmniText, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        MessageSender.SYSTEM -> {
            val tool = msg.text.startsWith("🔧") || msg.text.startsWith("↳")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(
                    color = if (tool) OmniSurface2 else OmniErrorDim,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (tool) OmniBorder else OmniError.copy(alpha = 0.4f)),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        msg.text,
                        color = if (tool) OmniTextDim else OmniError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.3f, 1f,
                infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier.padding(end = 5.dp).size(7.dp).clip(CircleShape)
                    .graphicsLayer { alpha = a }.background(OmniTextDim),
            )
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
    Surface(color = OmniBg) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Input pill (image + mic inside)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(OmniSurface2)
                    .border(1.dp, OmniBorder, RoundedCornerShape(24.dp))
                    .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPickImage, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Image, "Attach image", tint = OmniTextFaint, modifier = Modifier.size(21.dp))
                }
                BasicTextFieldWrap(input, onInputChange, Modifier.weight(1f))
                IconButton(onClick = onMic, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Default.Mic, "Voice",
                        tint = if (isListening) OmniVoice else OmniTextFaint, modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Send button (accent circle, disabled when empty)
            val enabled = input.isNotBlank()
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(if (enabled) OmniAccent else OmniSurface3)
                    .then(if (enabled) Modifier else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onSend, enabled = enabled, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (enabled) OmniTextOnAccent else OmniTextFaint, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BasicTextFieldWrap(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 10.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = OmniText),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(OmniAccent),
        maxLines = 5,
        keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text("Message", color = OmniTextFaint, style = MaterialTheme.typography.bodyLarge)
            }
            inner()
        },
    )
}
