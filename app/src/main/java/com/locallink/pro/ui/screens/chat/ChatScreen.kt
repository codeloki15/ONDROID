package com.locallink.pro.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // Auto-open Composio OAuth links (agent connecting an app mid-chat) in a Custom Tab.
    LaunchedEffect(Unit) {
        vm.authUrlToOpen.collect { url ->
            runCatching {
                androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                    .launchUrl(context, android.net.Uri.parse(url))
            }
        }
    }

    LaunchedEffect(sessionId) { vm.openSession(sessionId) }
    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + if (state.streamingText.isNotBlank() || state.isAiResponding) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> vm.attachImage(uri) }

    GlassBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            MessageRow(
                                msg = msg,
                                onCopy = { /* handled inside */ },
                                onSpeak = { vm.speak(it) },
                                onRegenerate = { vm.regenerate() },
                                onEdit = { vm.editAndResend(it) },
                            )
                        }
                        if (state.streamingText.isNotBlank()) {
                            item {
                                AiBubble(
                                    text = state.streamingText, streaming = true,
                                    onSpeak = {}, onRegenerate = {},
                                )
                            }
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
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    thinking: Boolean,
    autoTts: Boolean,
    onBack: () -> Unit,
    onToggleTts: () -> Unit,
    onSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniAvatar(size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Omni", style = MaterialTheme.typography.titleLarge, color = OmniText)
                    val statusColor by animateColorAsState(
                        if (thinking) OmniAccentBright else OmniSuccess, label = "status",
                    )
                    Text(
                        if (thinking) "thinking…" else "online",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OmniText)
            }
        },
        actions = {
            IconButton(onClick = onToggleTts) {
                Icon(
                    if (autoTts) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    "Auto speech", tint = if (autoTts) OmniAccentBright else OmniTextFaint,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(OmniIcons.settings(), "Settings", tint = androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

/** Small amber-glass avatar dot used for Omni's identity. */
@Composable
private fun OmniAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(OmniAccentContainer)
            .border(1.dp, GlassBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.AutoAwesome, null,
            tint = OmniAccentBright, modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun MessageRow(
    msg: Message,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: (String) -> Unit,
) {
    when (msg.sender) {
        MessageSender.USER -> UserBubble(msg, onEdit = onEdit)
        MessageSender.AI -> AiBubble(text = msg.text, streaming = false, onSpeak = onSpeak, onRegenerate = onRegenerate)
        MessageSender.SYSTEM -> ToolOrSystemChip(msg.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubble(
    text: String,
    streaming: Boolean,
    onSpeak: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            OmniAvatar(size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Box {
                GlassCard(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .combinedClickable(onClick = {}, onLongClick = { if (!streaming) menu = true }),
                    shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
                    fill = OmniAiBubble,
                    contentPadding = 14.dp,
                ) {
                    MarkdownText(text = text, trailingCursor = streaming)
                }
                MessageMenu(
                    expanded = menu, onDismiss = { menu = false },
                    actions = listOf(
                        MenuAction("Copy", Icons.Default.ContentCopy) { clipboard.setText(AnnotatedString(text)) },
                        MenuAction("Speak", Icons.Default.VolumeUp) { onSpeak(text) },
                        MenuAction("Regenerate", Icons.Default.Refresh) { onRegenerate() },
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(msg: Message, onEdit: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            GlassCard(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menu = true }),
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                fill = OmniUserBubble,
                border = OmniAccentContainer,
            ) {
                Column {
                    msg.imageUri?.let {
                        AsyncImage(it, null, Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)))
                        if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    }
                    if (msg.text.isNotBlank())
                        Text(msg.text, color = OmniText, style = MaterialTheme.typography.bodyLarge)
                }
            }
            MessageMenu(
                expanded = menu, onDismiss = { menu = false },
                actions = listOf(
                    MenuAction("Copy", Icons.Default.ContentCopy) { clipboard.setText(AnnotatedString(msg.text)) },
                    MenuAction("Edit", Icons.Default.Edit) { onEdit(msg.text) },
                ),
            )
        }
    }
}

private data class MenuAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

@Composable
private fun MessageMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<MenuAction>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { a ->
            DropdownMenuItem(
                text = { Text(a.label) },
                leadingIcon = { Icon(a.icon, null, modifier = Modifier.size(18.dp), tint = OmniAccentBright) },
                onClick = { a.onClick(); onDismiss() },
            )
        }
    }
}

@Composable
private fun ToolOrSystemChip(text: String) {
    when {
        text.startsWith("🔧") -> ToolCallChip(ToolRow.parseCall(text.removePrefix("🔧").trim()))
        text.startsWith("↳") -> ToolCallChip(ToolRow.parseResult(text.removePrefix("↳").trim()))
        else -> SystemNote(text) // errors / "↳ Cloud model …" notes
    }
}

/** A parsed tool-call or tool-result row, ready to render as a pill. */
private data class ToolRow(
    val appLabel: String,      // "Gmail"
    val actionLabel: String,   // "Fetch emails"
    val logoUrl: String,       // Composio logo CDN url, derived from the app slug
    val detail: String,        // raw args (for a call) or raw result (for a result)
    val isResult: Boolean,
    val isError: Boolean,
) {
    companion object {
        /** "GMAIL_FETCH_EMAILS(...)" or "set_timer({...})" → ToolRow */
        fun parseCall(s: String): ToolRow {
            val name = s.substringBefore("(").trim()
            val args = s.substringAfter("(", "").removeSuffix(")").trim()
            return build(name, args, isResult = false, isError = false)
        }

        /** "GMAIL_FETCH_EMAILS → {result}" → ToolRow */
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
            // Composio toolkit logos: https://logos.composio.dev/api/{slug}
            val logo = "https://logos.composio.dev/api/$slug"
            return ToolRow(app, action, logo, detail, isResult, isError)
        }
    }
}

@Composable
private fun ToolCallChip(row: ToolRow) {
    var expanded by remember { mutableStateOf(false) }
    val accent = if (row.isError) OmniError else OmniAccentBright
    // "In progress" = a call row that has no result yet → ripple while the action runs.
    val active = !row.isResult && !row.isError
    Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.Start) {
        GlassCard(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clickable(enabled = row.detail.isNotBlank()) { expanded = !expanded },
            shape = RoundedCornerShape(14.dp),
            fill = GlassFillFaint,
            contentPadding = 10.dp,
            highlight = false,
        ) {
            Column {
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
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, tint = OmniTextFaint, modifier = Modifier.size(16.dp),
                        )
                    }
                }
                AnimatedVisibility(expanded) {
                    Text(
                        prettyDetail(row.detail),
                        color = OmniTextDim,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 34.dp),
                    )
                }
            }
        }
    }
}

/**
 * Tool icon = the real Composio app logo, with a small status badge (✓/✗) corner overlay.
 * When [rippling] (an action in progress), concentric "water droplet" rings expand outward.
 */
@Composable
private fun ToolIcon(row: ToolRow, accent: androidx.compose.ui.graphics.Color, rippling: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
        if (rippling) WaterRipple(color = accent)
        // logo tile
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(OmniSurface3),
            contentAlignment = Alignment.Center,
        ) {
            Text(row.appLabel.take(1).uppercase(), color = OmniTextDim, style = MaterialTheme.typography.labelMedium)
            AsyncImage(
                model = row.logoUrl, contentDescription = row.appLabel,
                modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        // status badge (only once we have a result)
        if (row.isResult || row.isError) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                    .background(accent).border(1.5.dp, OmniBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (row.isError) Icons.Default.Close else Icons.Default.Check,
                    null, tint = OmniBg, modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}

/** Concentric expanding rings — a water-droplet ripple, drawn behind the tool icon while active. */
@Composable
private fun WaterRipple(color: androidx.compose.ui.graphics.Color) {
    val t = rememberInfiniteTransition(label = "ripple")
    // Two staggered rings so it reads as continuous droplets.
    repeat(2) { i ->
        val progress by t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1400, delayMillis = i * 700, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "ring$i",
        )
        Canvas(Modifier.size(26.dp)) {
            val maxR = size.minDimension / 2f
            val r = maxR * progress
            drawCircle(
                color = color.copy(alpha = (1f - progress) * 0.5f),
                radius = r,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/** Plain system/error note (not a tool row). */
@Composable
private fun SystemNote(text: String) {
    val isError = text.startsWith("Error")
    Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = if (isError) OmniErrorDim else GlassFillFaint,
            shape = RoundedCornerShape(12.dp),
            border = if (isError) androidx.compose.foundation.BorderStroke(1.dp, OmniError.copy(alpha = 0.4f)) else null,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text.removePrefix("Error:").trim().let { if (isError) it else text },
                color = if (isError) OmniError else OmniTextDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** Tidy raw JSON-ish detail for the expanded view (trim, cap length). */
private fun prettyDetail(s: String): String {
    val t = s.trim()
    return if (t.length > 1200) t.take(1200) + "…" else t
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier, onPrompt: (String) -> Unit) {
    val examples = listOf(
        "Set a timer for 5 minutes",
        "What's the weather like today?",
        "Summarize my last email",
    )
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Box(modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(shown, enter = fadeIn(tween(500)) + slideInVertically { it / 4 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OmniAvatar(size = 72.dp)
                    Spacer(Modifier.height(18.dp))
                    Text("How can I help?", color = OmniText, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ask anything — or try one of these.",
                        color = OmniTextFaint, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            examples.forEachIndexed { i, ex ->
                AnimatedVisibility(
                    shown,
                    enter = fadeIn(tween(400, delayMillis = 120 + i * 90)) +
                        slideInVertically(tween(400, delayMillis = 120 + i * 90)) { it / 3 },
                ) {
                    Box(
                        Modifier
                            .padding(vertical = 5.dp)
                            .glass(shape = RoundedCornerShape(16.dp), fill = GlassFillFaint)
                            .clickable { onPrompt(ex) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(ex, color = OmniTextDim, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(verticalAlignment = Alignment.Top) {
        OmniAvatar(size = 26.dp)
        Spacer(Modifier.width(10.dp))
        GlassCard(
            shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
            fill = OmniAiBubble,
            contentPadding = 16.dp,
        ) {
            ThinkingDots()
        }
    }
}

/**
 * Clean, modern "thinking" animation: three dots with a smooth traveling wave — each rises,
 * brightens and scales up slightly in sequence, then settles. Reads as premium and never breaks.
 */
@Composable
private fun ThinkingDots() {
    val t = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            // A single phase per dot, staggered, eased in/out for a soft wave.
            val v by t.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, delayMillis = i * 160, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            val scale = 0.7f + 0.5f * v
            val alpha = 0.35f + 0.65f * v
            val lift = -3f * v // dp, rises as it brightens
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .offset(y = lift.dp)
                    .size(8.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clip(CircleShape)
                    .background(OmniAccentBright),
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Frosted input pill (image + mic inside)
        Row(
            Modifier.weight(1f)
                .glass(shape = RoundedCornerShape(26.dp), fill = GlassFillStrong)
                .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPickImage, modifier = Modifier.size(40.dp)) {
                Icon(OmniIcons.image(), "Attach image", tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(24.dp))
            }
            BasicTextFieldWrap(input, onInputChange, Modifier.weight(1f))
            IconButton(onClick = onMic, modifier = Modifier.size(40.dp)) {
                if (isListening) {
                    Icon(OmniIcons.mic(base = OmniVoice), "Voice", tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(24.dp))
                } else {
                    Icon(OmniIcons.mic(), "Voice", tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        // Amber circular send
        val enabled = input.isNotBlank()
        val bg by animateColorAsState(if (enabled) OmniAccent else OmniSurface3, label = "sendbg")
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(bg)
                .clickable(enabled = enabled, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                OmniIcons.send(base = if (enabled) OmniTextOnAccent else OmniTextFaint, over = OmniTextOnAccent.copy(alpha = 0.5f)),
                "Send", tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(22.dp),
            )
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
                Text("Message Omni…", color = OmniTextFaint, style = MaterialTheme.typography.bodyLarge)
            }
            inner()
        },
    )
}
