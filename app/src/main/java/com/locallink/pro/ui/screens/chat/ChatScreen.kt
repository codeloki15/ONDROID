package com.locallink.pro.ui.screens.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.domain.model.ConnectionInfo
import com.locallink.pro.domain.model.ConnectionState
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.domain.model.ToolCallInfo
import com.locallink.pro.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToFiles: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val omniColors = LocalOmniPinColors.current
    val pullToRefreshState = rememberPullToRefreshState()

    // Auto-scroll to bottom on new messages (only when not loading history)
    LaunchedEffect(uiState.messages.size, uiState.streamingText, uiState.activeToolCalls.size) {
        if (!uiState.isLoadingHistory && !uiState.isRefreshing) {
            val totalItems = uiState.messages.size +
                (if (uiState.activeToolCalls.isNotEmpty()) 1 else 0) +
                (if (uiState.streamingText.isNotBlank()) 1 else 0) +
                (if (uiState.hasMoreHistory) 1 else 0) // Account for load more item
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    // Detect when scrolled to top to load more history
    val shouldLoadMore by remember {
        derivedStateOf {
            val firstVisibleItem = listState.firstVisibleItemIndex
            firstVisibleItem <= 1 && uiState.hasMoreHistory && !uiState.isLoadingHistory
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMoreHistory()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar — stays pinned, not affected by IME
        OmniPinTopBar(
            isSpeaking = uiState.isSpeaking,
            onStopTts = viewModel::stopTts,
            onNewChat = viewModel::clearChat,
            onSettingsClick = onNavigateToSettings,
            onFilesClick = onNavigateToFiles,
            onRefresh = viewModel::refreshChatHistory,
        )

        // Content area — absorbs keyboard resize via imePadding + weight
        Column(
            modifier = Modifier
                .weight(1f)
                .imePadding()
        ) {
            // Messages list with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::refreshChatHistory,
                state = pullToRefreshState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // Load more indicator at top
                    if (uiState.hasMoreHistory) {
                        item(key = "load_more") {
                            LoadMoreIndicator(isLoading = uiState.isLoadingHistory)
                        }
                    }

                    items(uiState.messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }

                    // Active tool calls during AI response
                    if (uiState.activeToolCalls.isNotEmpty()) {
                        item(key = "tool_calls") {
                            ToolCallsSection(toolCalls = uiState.activeToolCalls)
                        }
                    }

                    // Streaming AI response
                    if (uiState.streamingText.isNotBlank()) {
                        item(key = "streaming") {
                            StreamingBubble(text = uiState.streamingText)
                        }
                    }

                    // AI typing indicator
                    if (uiState.isAiResponding && uiState.streamingText.isBlank() && uiState.activeToolCalls.isEmpty()) {
                        item(key = "typing") {
                            TypingIndicator()
                        }
                    }
                }
            }

            // Error message
            uiState.historyError?.let { error ->
                Text(
                    text = error,
                    color = OmniStatusError,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Input bar
            ChatInputBar(
                inputText = uiState.inputText,
                isListening = uiState.isListening,
                isAiResponding = uiState.isAiResponding,
                partialVoice = uiState.partialVoiceResult,
                onInputChange = viewModel::updateInput,
                onSend = { viewModel.sendMessage() },
                onVoiceToggle = viewModel::toggleVoiceInput
            )
        }

        // Status strip — stays at very bottom
        StatusStrip(connectionInfo = uiState.connectionInfo)
    }
}

// ─── Load More Indicator ─────────────────────────────────────────────

@Composable
private fun LoadMoreIndicator(isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = LocalOmniPinColors.current.accent
            )
        } else {
            Text(
                text = "Scroll up for more",
                style = MaterialTheme.typography.labelSmall,
                color = LocalOmniPinColors.current.textTertiary
            )
        }
    }
}

// ─── Top Bar ────────────────────────────────────────────────────────

@Composable
private fun OmniPinTopBar(
    isSpeaking: Boolean,
    onStopTts: () -> Unit,
    onNewChat: () -> Unit,
    onSettingsClick: () -> Unit,
    onFilesClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    val omniColors = LocalOmniPinColors.current

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // App branding
            Text(
                "*",
                color = omniColors.accent,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "OmniPro",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.weight(1f))

            // Stop TTS
            if (isSpeaking) {
                IconButton(
                    onClick = onStopTts,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeOff,
                        contentDescription = "Stop speaking",
                        modifier = Modifier.size(18.dp),
                        tint = VoiceActive
                    )
                }
            }

            // Refresh chat history
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh chat history",
                    modifier = Modifier.size(18.dp),
                    tint = omniColors.textTertiary
                )
            }

            // Files navigation
            IconButton(
                onClick = onFilesClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = "Files",
                    modifier = Modifier.size(18.dp),
                    tint = omniColors.textTertiary
                )
            }

            // New Chat
            IconButton(
                onClick = onNewChat,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New Chat",
                    modifier = Modifier.size(18.dp),
                    tint = omniColors.textTertiary
                )
            }

            // Settings
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(18.dp),
                    tint = omniColors.textTertiary
                )
            }
        }

        HorizontalDivider(
            color = LocalOmniPinColors.current.border,
            thickness = 0.5.dp
        )
    }
}

// ─── Message Bubbles ────────────────────────────────────────────────

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.sender == MessageSender.USER
    val isRemoteUser = message.sender == MessageSender.USER_REMOTE
    val isSystem = message.sender == MessageSender.SYSTEM

    when {
        isUser -> UserMessageBubble(message)
        isRemoteUser -> RemoteUserMessage(message)
        isSystem -> SystemMessage(message)
        else -> AiMessage(message)
    }
}

@Composable
private fun UserMessageBubble(message: Message) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = LocalOmniPinColors.current.userBubble,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (message.isVoice) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = OmniTextOnDarkSecondary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Voice",
                            style = MaterialTheme.typography.labelSmall,
                            color = OmniTextOnDarkSecondary
                        )
                    }
                }
                Text(
                    text = message.text,
                    color = OmniTextOnDark,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AiMessage(message: Message) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 48.dp),
    ) {
        Text(
            text = message.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun RemoteUserMessage(message: Message) {
    val omniColors = LocalOmniPinColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = omniColors.statusConnected
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Desktop",
                style = MaterialTheme.typography.labelSmall,
                color = omniColors.statusConnected
            )
        }
        Text(
            text = message.text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun SystemMessage(message: Message) {
    Text(
        text = message.text,
        color = OmniStatusError,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    )
}

// ─── Tool Calls ─────────────────────────────────────────────────────

@Composable
private fun ToolCallsSection(toolCalls: List<ToolCallInfo>) {
    val omniColors = LocalOmniPinColors.current
    var expanded by remember { mutableStateOf(false) }

    val allDone = toolCalls.all { it.result != null }
    val hasErrors = toolCalls.any { it.success == false }
    val pendingCount = toolCalls.count { it.result == null }

    val summary = if (allDone) {
        "${toolCalls.size} tool${if (toolCalls.size > 1) "s" else ""} used${if (hasErrors) " (with errors)" else ""}"
    } else {
        "Running tools... ($pendingCount pending)"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 48.dp)
    ) {
        // Collapsed summary bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            shape = RoundedCornerShape(10.dp),
            color = omniColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, omniColors.borderLight),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = omniColors.textTertiary
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = omniColors.textTertiary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = OmniTextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                // Status icon
                when {
                    allDone && !hasErrors -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Done",
                        modifier = Modifier.size(14.dp),
                        tint = OmniStatusConnected
                    )
                    hasErrors -> Icon(
                        Icons.Default.Cancel,
                        contentDescription = "Error",
                        modifier = Modifier.size(14.dp),
                        tint = OmniStatusError
                    )
                    else -> {
                        val infiniteTransition = rememberInfiniteTransition(label = "spin")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                tween(1000, easing = LinearEasing)
                            ),
                            label = "rotation"
                        )
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Running",
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = rotation },
                            tint = OmniStatusConnecting
                        )
                    }
                }
            }
        }

        // Expanded tool list
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, omniColors.borderLight),
            ) {
                Column {
                    toolCalls.forEachIndexed { index, tc ->
                        ToolCallItem(tc)
                        if (index < toolCalls.lastIndex) {
                            HorizontalDivider(
                                color = omniColors.borderLight,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallItem(toolCall: ToolCallInfo) {
    val omniColors = LocalOmniPinColors.current
    val isDone = toolCall.result != null
    val hasSubTools = toolCall.subTools.isNotEmpty()
    var showDetails by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isDone || hasSubTools) Modifier.clickable { showDetails = !showDetails }
                    else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon
            when {
                isDone && toolCall.success != false -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    modifier = Modifier.size(13.dp),
                    tint = OmniStatusConnected
                )
                isDone && toolCall.success == false -> Icon(
                    Icons.Default.Cancel,
                    contentDescription = "Failed",
                    modifier = Modifier.size(13.dp),
                    tint = OmniStatusError
                )
                else -> {
                    val infiniteTransition = rememberInfiniteTransition(label = "toolSpin")
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            tween(1000, easing = LinearEasing)
                        ),
                        label = "toolRotation"
                    )
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "Running",
                        modifier = Modifier
                            .size(13.dp)
                            .graphicsLayer { rotationZ = rotation },
                        tint = OmniStatusConnecting
                    )
                }
            }
            Spacer(Modifier.width(8.dp))

            // Tool name
            Text(
                toolCall.toolName,
                style = MaterialTheme.typography.labelMedium,
                color = OmniTextSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Sub-tools count
            if (hasSubTools) {
                val completedSubs = toolCall.subTools.count { it.success != null }
                Text(
                    "${completedSubs}/${toolCall.subTools.size} steps",
                    style = MaterialTheme.typography.labelSmall,
                    color = omniColors.textTertiary,
                )
                Spacer(Modifier.width(4.dp))
            }

            if (isDone || hasSubTools) {
                Icon(
                    if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = omniColors.textTertiary
                )
            }
        }

        // Sub-tools detail
        AnimatedVisibility(
            visible = showDetails && hasSubTools,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 21.dp, top = 4.dp)
            ) {
                toolCall.subTools.forEach { sub ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        when {
                            sub.success == true -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Done",
                                modifier = Modifier.size(11.dp),
                                tint = OmniStatusConnected
                            )
                            sub.success == false -> Icon(
                                Icons.Default.Cancel,
                                contentDescription = "Failed",
                                modifier = Modifier.size(11.dp),
                                tint = OmniStatusError
                            )
                            else -> {
                                val infiniteTransition = rememberInfiniteTransition(label = "subSpin")
                                val rotation by infiniteTransition.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        tween(1000, easing = LinearEasing)
                                    ),
                                    label = "subRotation"
                                )
                                Icon(
                                    Icons.Default.Sync,
                                    contentDescription = "Running",
                                    modifier = Modifier
                                        .size(11.dp)
                                        .graphicsLayer { rotationZ = rotation },
                                    tint = OmniStatusConnecting
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            sub.toolName,
                            style = MaterialTheme.typography.labelSmall,
                            color = omniColors.textTertiary,
                        )
                    }
                }
            }
        }

        // Result detail
        AnimatedVisibility(
            visible = showDetails && !hasSubTools && isDone && !toolCall.result.isNullOrBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val truncated = toolCall.result?.take(300) ?: ""
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(6.dp),
                color = omniColors.surfaceElevated,
            ) {
                Text(
                    truncated + if ((toolCall.result?.length ?: 0) > 300) "..." else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = omniColors.textTertiary,
                    modifier = Modifier.padding(8.dp),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─── Streaming & Typing ─────────────────────────────────────────────

@Composable
private fun StreamingBubble(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 48.dp),
    ) {
        Text(
            text = text + "\u258C",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val dot1 = infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(600), RepeatMode.Reverse,
            initialStartOffset = StartOffset(0)
        ), label = "dot1"
    )
    val dot2 = infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(600), RepeatMode.Reverse,
            initialStartOffset = StartOffset(200)
        ), label = "dot2"
    )
    val dot3 = infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(600), RepeatMode.Reverse,
            initialStartOffset = StartOffset(400)
        ), label = "dot3"
    )

    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(dot1, dot2, dot3).forEach { anim ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .scale(anim.value)
                    .clip(CircleShape)
                    .background(
                        OmniTextTertiary.copy(alpha = anim.value)
                    )
            )
        }
    }
}

// ─── Input Bar ──────────────────────────────────────────────────────

@Composable
private fun ChatInputBar(
    inputText: String,
    isListening: Boolean,
    isAiResponding: Boolean,
    partialVoice: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceToggle: () -> Unit
) {
    val omniColors = LocalOmniPinColors.current
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale = pulseAnim.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "pulseScale"
    )

    Column {
        HorizontalDivider(color = omniColors.border, thickness = 0.5.dp)

        // Partial voice result
        AnimatedVisibility(
            visible = isListening && partialVoice.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = partialVoice,
                style = MaterialTheme.typography.bodySmall,
                color = omniColors.textTertiary,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Voice button
            IconButton(
                onClick = onVoiceToggle,
                modifier = Modifier
                    .size(36.dp)
                    .then(
                        if (isListening) Modifier.scale(pulseScale.value) else Modifier
                    )
            ) {
                Icon(
                    if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Voice input",
                    modifier = Modifier.size(18.dp),
                    tint = if (isListening) VoiceActive else omniColors.textTertiary
                )
            }

            Spacer(Modifier.width(4.dp))

            // Text input with subtle border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = omniColors.borderLight,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = false,
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box {
                            if (inputText.isEmpty()) {
                                Text(
                                    if (isListening) "Listening..." else "Ask anything...",
                                    color = omniColors.textTertiary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(Modifier.width(8.dp))

            // Send button - flat icon
            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && !isAiResponding,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(18.dp),
                    tint = if (inputText.isNotBlank() && !isAiResponding)
                        MaterialTheme.colorScheme.primary
                    else
                        omniColors.textTertiary
                )
            }
        }
    }
}

// ─── Status Strip ───────────────────────────────────────────────────

@Composable
private fun StatusStrip(connectionInfo: ConnectionInfo) {
    val omniColors = LocalOmniPinColors.current
    val transportLabel = when {
        connectionInfo.transport.name == "NONE" -> "Not connected"
        else -> connectionInfo.transport.name.replace("_", " ")
    }
    val portInfo = connectionInfo.port?.let { " :$it" } ?: ""

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$transportLabel$portInfo",
                style = MaterialTheme.typography.labelSmall,
                color = omniColors.textTertiary,
                fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            if (connectionInfo.state == ConnectionState.CONNECTED && connectionInfo.latencyMs > 0) {
                Text(
                    text = "${connectionInfo.latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = omniColors.textTertiary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
