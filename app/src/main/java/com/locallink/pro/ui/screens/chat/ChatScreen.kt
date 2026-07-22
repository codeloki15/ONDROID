package com.locallink.pro.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.North
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.components.InputPillSurface
import com.locallink.pro.ui.components.ParticleSphere
import com.locallink.pro.ui.theme.*

@Composable
fun ChatScreen(
    sessionId: String?,
    startVoice: Boolean = false,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var voiceMode by rememberSaveable { mutableStateOf(false) }

    // Auto-open OAuth links (agent connecting an app mid-chat) in a Custom Tab.
    LaunchedEffect(Unit) {
        vm.authUrlToOpen.collect { url ->
            runCatching {
                androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                    .launchUrl(context, Uri.parse(url))
            }
        }
    }

    LaunchedEffect(sessionId) { vm.openSession(sessionId) }
    LaunchedEffect(Unit) {
        if (startVoice && !state.isListening) {
            voiceMode = true
            vm.toggleVoiceInput()
        }
    }
    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + if (state.streamingText.isNotBlank() || state.isAiResponding) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    // Voice sent → assistant is replying: bring the user back to the conversation.
    LaunchedEffect(state.isAiResponding) {
        if (state.isAiResponding && voiceMode) voiceMode = false
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> vm.attachImage(uri) }

    AuroraBackground(glow = 0.16f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                ChatTopBar(
                    thinking = state.isAiResponding,
                    autoTts = state.autoTts,
                    onBack = onBack,
                    onToggleTts = { vm.toggleAutoTts() },
                    onSettings = onNavigateToSettings,
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.messages.isEmpty() && state.streamingText.isBlank() && !state.isAiResponding) {
                    EmptyChat(Modifier.weight(1f), onPrompt = { vm.updateInput(it) })
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val lastAiId = state.messages.lastOrNull { it.sender == MessageSender.AI }?.id
                        items(state.messages.size, key = { state.messages[it].id }) { i ->
                            val msg = state.messages[i]
                            MessageRow(
                                msg = msg,
                                isLastAi = msg.id == lastAiId && !state.isAiResponding,
                                onSpeak = { vm.speak(it) },
                                onRegenerate = { vm.regenerate() },
                                onEdit = { vm.editAndResend(it) },
                            )
                        }
                        if (state.streamingText.isNotBlank()) {
                            item {
                                AiBubble(
                                    text = state.streamingText, streaming = true,
                                    isLastAi = false, onSpeak = {}, onRegenerate = {},
                                )
                            }
                        } else if (state.isAiResponding) {
                            item { TypingBubble() }
                        }
                    }
                }

                state.pendingImageUri?.let { uri ->
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = uri, contentDescription = "Attached",
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                        )
                        IconButton(onClick = { vm.attachImage(null) }) {
                            Icon(Icons.Outlined.Close, "Remove image", tint = OmniTextDim)
                        }
                    }
                }

                InputBar(
                    input = state.inputText,
                    onInputChange = vm::updateInput,
                    onSend = { vm.sendMessage() },
                    onMic = {
                        voiceMode = true
                        if (!state.isListening) vm.toggleVoiceInput()
                    },
                    onPickImage = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
        }

        // ── Full-screen voice mode ────────────────────────────────────────
        AnimatedVisibility(
            visible = voiceMode,
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.zIndex(10f),
        ) {
            VoiceMode(
                listening = state.isListening,
                partial = state.partialVoiceResult,
                onRestart = {
                    if (state.isListening) vm.toggleVoiceInput()
                    vm.toggleVoiceInput()
                },
                onMic = { vm.toggleVoiceInput() },
                onClose = {
                    if (state.isListening) vm.toggleVoiceInput()
                    voiceMode = false
                },
            )
        }
    }
}

// ─── Top bar ─────────────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    thinking: Boolean,
    autoTts: Boolean,
    onBack: () -> Unit,
    onToggleTts: () -> Unit,
    onSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
        }
        GradientOrb(size = 40.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("Omni", style = MaterialTheme.typography.titleLarge, color = OmniText)
            Text(
                if (thinking) "thinking…" else "online",
                style = MaterialTheme.typography.bodySmall,
                color = if (thinking) AuroraVioletHi else OmniTextFaint,
            )
        }
        IconButton(onClick = onToggleTts) {
            Icon(
                if (autoTts) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                "Auto speech",
                tint = if (autoTts) AuroraVioletHi else OmniTextFaint,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, "More", tint = OmniText)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(18.dp), tint = OmniTextDim) },
                    onClick = { menu = false; onSettings() },
                )
            }
        }
    }
}

// ─── Message rows ────────────────────────────────────────────────────────

@Composable
private fun MessageRow(
    msg: Message,
    isLastAi: Boolean,
    onSpeak: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
) {
    when (msg.sender) {
        MessageSender.USER -> UserBubble(msg, onEdit = onEdit)
        MessageSender.AI -> AiBubble(
            text = msg.text, streaming = false, isLastAi = isLastAi,
            onSpeak = onSpeak, onRegenerate = onRegenerate,
        )
        MessageSender.SYSTEM -> SystemRow(msg.text)
    }
}

@Composable
private fun AiBubble(
    text: String,
    streaming: Boolean,
    isLastAi: Boolean,
    onSpeak: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        GradientOrb(size = 28.dp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(8.dp, 24.dp, 24.dp, 24.dp))
                .background(OmniAiBubble)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            MarkdownText(text = text, trailingCursor = streaming)
            if (!streaming) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BubbleAction(Icons.Outlined.ContentCopy, "Copy") {
                        clipboard.setText(AnnotatedString(text))
                    }
                    BubbleAction(Icons.AutoMirrored.Outlined.VolumeUp, "Speak") { onSpeak(text) }
                    Spacer(Modifier.weight(1f))
                    if (isLastAi) BubbleAction(Icons.Outlined.Refresh, "Regenerate", onClick = onRegenerate)
                }
            }
        }
    }
}

@Composable
private fun BubbleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Icon(icon, label, tint = OmniTextDim, modifier = Modifier.size(17.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: Message, onEdit: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(24.dp, 24.dp, 8.dp, 24.dp))
                    .background(BubbleBrush)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                msg.imageUri?.let {
                    AsyncImage(it, null, Modifier.size(190.dp).clip(RoundedCornerShape(16.dp)))
                    if (msg.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (msg.text.isNotBlank()) {
                    Text(msg.text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp), tint = OmniTextDim) },
                    onClick = { clipboard.setText(AnnotatedString(msg.text)); menu = false },
                )
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp), tint = OmniTextDim) },
                    onClick = { onEdit(msg.text); menu = false },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(OmniSurface2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, null, tint = OmniTextDim, modifier = Modifier.size(15.dp))
        }
    }
}

// ─── System / agent rows ─────────────────────────────────────────────────

@Composable
private fun SystemRow(text: String) {
    when {
        text.startsWith("🗒 Plan:") -> PlanCard(text.removePrefix("🗒 Plan:").trim())
        text.startsWith("✓") -> DoneRow(text.removePrefix("✓").trim())
        text.startsWith("⌨") -> InputRequestChip(text.removePrefix("⌨").trim())
        text.startsWith("🔧") -> ToolChip(ToolRow.parseCall(text.removePrefix("🔧").trim()))
        text.startsWith("↳") -> ToolChip(ToolRow.parseResult(text.removePrefix("↳").trim()))
        else -> SystemNote(text)
    }
}

/** The agent's plan, rendered as a charcoal card with numbered steps + channel tags. */
@Composable
private fun PlanCard(body: String) {
    val steps = remember(body) {
        body.lines().mapNotNull { line ->
            val m = Regex("^(\\d+)\\.\\s+(.*)$").find(line.trim()) ?: return@mapNotNull null
            var rest = m.groupValues[2]
            val needsInput = rest.contains("(needs input)")
            rest = rest.replace("(needs input)", "").trim()
            val tag = Regex("\\[(\\w+)]$").find(rest)?.groupValues?.get(1)
            if (tag != null) rest = rest.removeSuffix("[$tag]").trim()
            Triple(rest, tag ?: "", needsInput)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 38.dp)) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(GlassFillFaint)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientOrb(size = 18.dp, sparkle = true)
                Spacer(Modifier.width(8.dp))
                Text("Plan", style = MaterialTheme.typography.titleSmall, color = OmniText)
            }
            Spacer(Modifier.height(10.dp))
            steps.forEachIndexed { i, (step, tag, needsInput) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AuroraVioletHi,
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(OmniAccentContainer)
                            .wrapContentSize(Alignment.Center),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step, style = MaterialTheme.typography.bodyMedium, color = OmniText)
                        if (tag.isNotBlank() || needsInput) {
                            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (tag.isNotBlank()) TagChip(if (tag == "pilot") "phone" else tag, AuroraVioletHi)
                                if (needsInput) TagChip("asks you", AuroraRose)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** A completed agent step — quiet confirmation line. */
@Composable
private fun DoneRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Check, null, tint = OmniSuccess, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text, style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The agent paused for user input. */
@Composable
private fun InputRequestChip(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 38.dp)) {
        Row(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(OmniAccentContainer.copy(alpha = 0.5f))
                .border(1.dp, AuroraViolet.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Keyboard, null, tint = AuroraVioletHi, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Text(
                text.removePrefix("Input requested:").trim(),
                style = MaterialTheme.typography.bodySmall, color = OmniText,
            )
        }
    }
}

/** A parsed tool-call or tool-result row, ready to render as a chip. */
private data class ToolRow(
    val appLabel: String,
    val actionLabel: String,
    val logoUrl: String,
    val detail: String,
    val isResult: Boolean,
    val isError: Boolean,
) {
    companion object {
        fun parseCall(s: String): ToolRow {
            val name = s.substringBefore("(").trim()
            val args = s.substringAfter("(", "").removeSuffix(")").trim()
            return build(name, args, isResult = false, isError = false)
        }

        fun parseResult(s: String): ToolRow {
            val name = s.substringBefore(" → ").trim()
            val result = s.substringAfter(" → ", "").trim()
            return build(name, result, isResult = true, isError = result.contains("\"error\""))
        }

        private fun build(name: String, detail: String, isResult: Boolean, isError: Boolean): ToolRow {
            val parts = name.split('_').filter { it.isNotBlank() }
            val slug = parts.firstOrNull()?.lowercase() ?: name.lowercase()
            val app = slug.replaceFirstChar { it.uppercase() }
            val action = parts.drop(1).joinToString(" ") { it.lowercase() }
                .replaceFirstChar { it.uppercase() }.ifBlank { "Run" }
            val logo = "https://logos.composio.dev/api/$slug"
            return ToolRow(app, action, logo, detail, isResult, isError)
        }
    }
}

@Composable
private fun ToolChip(row: ToolRow) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (row.isError) OmniError else AuroraVioletHi
    val active = !row.isResult && !row.isError
    Row(Modifier.fillMaxWidth().padding(start = 38.dp)) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassFillFaint)
                .clickable(enabled = row.detail.isNotBlank()) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolIcon(row = row, accent = accent, rippling = active)
                Spacer(Modifier.width(10.dp))
                Text(
                    buildString {
                        append(row.appLabel)
                        if (row.actionLabel.isNotBlank()) append(" · ").append(row.actionLabel)
                    },
                    color = OmniText, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (row.detail.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, tint = OmniTextFaint, modifier = Modifier.size(16.dp),
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Text(
                    prettyDetail(row.detail),
                    color = OmniTextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 8.dp, start = 34.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolIcon(row: ToolRow, accent: Color, rippling: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
        if (rippling) PulseRing(color = accent)
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(OmniSurface3),
            contentAlignment = Alignment.Center,
        ) {
            Text(row.appLabel.take(1).uppercase(), color = OmniTextDim, style = MaterialTheme.typography.labelMedium)
            AsyncImage(
                model = row.logoUrl, contentDescription = row.appLabel,
                modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        if (row.isResult || row.isError) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                    .background(accent).border(1.5.dp, OmniBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (row.isError) Icons.Outlined.Close else Icons.Outlined.Check,
                    null, tint = OmniBg, modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}

/** Expanding ring while an action runs. */
@Composable
private fun PulseRing(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    repeat(2) { i ->
        val progress by t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1400, delayMillis = i * 700, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "ring$i",
        )
        androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
            drawCircle(
                color = color.copy(alpha = (1f - progress) * 0.5f),
                radius = size.minDimension / 2f * progress,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    val isError = text.startsWith("Error")
    Row(Modifier.fillMaxWidth().padding(start = 38.dp)) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isError) OmniErrorDim else GlassFillFaint)
                .then(
                    if (isError) Modifier.border(1.dp, OmniError.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text.removePrefix("Error:").trim().let { if (isError) it else text },
                color = if (isError) OmniError else OmniTextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun prettyDetail(s: String): String {
    val t = s.trim()
    return if (t.length > 1200) t.take(1200) + "…" else t
}

// ─── Empty state ─────────────────────────────────────────────────────────

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onPrompt: (String) -> Unit) {
    val examples = listOf(
        "Set a timer for 5 minutes",
        "Open Settings and turn on dark mode",
        "Play lo-fi beats on YouTube",
    )
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(shown, enter = fadeIn(tween(500)) + slideInVertically { it / 4 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GradientOrb(size = 84.dp, glow = true)
                    Spacer(Modifier.height(22.dp))
                    Text("How can I help?", color = OmniText, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Ask anything — or let me drive your phone.",
                        color = OmniTextFaint, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            examples.forEachIndexed { i, ex ->
                AnimatedVisibility(
                    shown,
                    enter = fadeIn(tween(400, delayMillis = 140 + i * 90)) +
                        slideInVertically(tween(400, delayMillis = 140 + i * 90)) { it / 3 },
                ) {
                    Box(
                        Modifier
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                            .clickable { onPrompt(ex) }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Text(ex, color = OmniTextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ─── Typing indicator ────────────────────────────────────────────────────

@Composable
private fun TypingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientOrb(size = 28.dp)
        Spacer(Modifier.width(12.dp))
        ThinkingDots()
    }
}

@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val v by t.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, delayMillis = i * 160, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(7.dp)
                    .graphicsLayer {
                        val s = 0.7f + 0.5f * v
                        scaleX = s; scaleY = s; alpha = 0.35f + 0.65f * v
                    }
                    .clip(CircleShape)
                    .background(AuroraVioletHi),
            )
        }
    }
}

// ─── Composer ────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onPickImage: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        InputPillSurface(Modifier.weight(1f)) {
            Row(
                Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPickImage, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Outlined.Add, "Attach image", tint = OmniTextDim, modifier = Modifier.size(23.dp))
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 11.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = OmniText),
                    cursorBrush = SolidColor(AuroraVioletHi),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text("Send message…", color = OmniTextFaint, style = MaterialTheme.typography.bodyLarge)
                        }
                        inner()
                    },
                )
                IconButton(onClick = onMic, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Mic, "Voice", tint = OmniTextDim, modifier = Modifier.size(23.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = input.isNotBlank(),
            enter = scaleIn(tween(160)) + fadeIn(tween(160)),
            exit = scaleOut(tween(120)) + fadeOut(tween(120)),
        ) {
            Row {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(AuroraBrush)
                        .clickable(onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.North, "Send", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// ─── Voice mode ──────────────────────────────────────────────────────────

@Composable
private fun VoiceMode(
    listening: Boolean,
    partial: String,
    onRestart: () -> Unit,
    onMic: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(OmniBg)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GradientOrb(size = 40.dp)
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("Omni", style = MaterialTheme.typography.titleLarge, color = OmniText)
                    Text(
                        if (listening) "listening…" else "paused",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (listening) AuroraPink else OmniTextFaint,
                    )
                }
            }

            ParticleSphere(
                listening = listening,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            Text(
                text = partial.ifBlank { if (listening) "Say something…" else "Tap the mic to talk" },
                style = MaterialTheme.typography.headlineSmall,
                color = if (partial.isBlank()) OmniTextFaint else OmniText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 34.dp)
                    .heightIn(min = 84.dp),
            )

            Row(
                Modifier.padding(top = 10.dp, bottom = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(34.dp),
            ) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable(onClick = onRestart),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Replay, "Restart", tint = OmniText, modifier = Modifier.size(22.dp))
                }
                MicButton(listening = listening, onClick = onMic)
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .border(1.dp, GlassBorder, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, "Close", tint = OmniText, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "mic")
    val halo by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "halo",
    )
    Box(contentAlignment = Alignment.Center) {
        if (listening) {
            androidx.compose.foundation.Canvas(Modifier.size(118.dp)) {
                drawCircle(
                    color = AuroraPink.copy(alpha = (1f - halo) * 0.35f),
                    radius = size.minDimension / 2f * (0.62f + 0.38f * halo),
                )
            }
        }
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(AuroraBrush)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, if (listening) "Pause" else "Talk", tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}
