package com.locallink.pro.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.service.voice.VoicePreviewPhrases
import com.locallink.pro.ui.theme.*
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageApps: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val omniColors = LocalOmniPinColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Listen for save success events
    LaunchedEffect(Unit) {
        viewModel.saveSuccess.collectLatest { success ->
            snackbarHostState.showSnackbar(
                message = if (success) "Settings saved successfully" else "Failed to save settings",
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(22.dp),
                            tint = OmniTextSecondary
                        )
                    }
                    Text(
                        "Settings",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                HorizontalDivider(color = omniColors.border, thickness = 0.5.dp)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Save button at the bottom
            Surface(
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    HorizontalDivider(color = omniColors.border, thickness = 0.5.dp)
                    Button(
                        onClick = { viewModel.saveSettings() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Save Settings", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Speech-to-Text Settings
            SettingsSection(title = "Voice") {
                SettingsToggle(
                    title = "Speech-to-Text",
                    subtitle = "Enable voice input via microphone",
                    icon = Icons.Default.Mic,
                    checked = uiState.sttEnabled,
                    onCheckedChange = viewModel::toggleSttEnabled
                )
                val ctx = androidx.compose.ui.platform.LocalContext.current
                SettingsToggle(
                    title = "Hands-free (Hey Omni)",
                    subtitle = "Always listen for the wake word and reply by voice",
                    icon = Icons.Default.RecordVoiceOver,
                    checked = uiState.handsFree,
                    onCheckedChange = { on ->
                        viewModel.setHandsFree(on)
                        if (on) com.locallink.pro.service.voice.VoiceLoopService.start(ctx)
                        else com.locallink.pro.service.voice.VoiceLoopService.stop(ctx)
                    }
                )
            }

            // Text-to-Speech Settings
            SettingsSection(title = "Text-to-Speech") {
                SettingsToggle(
                    title = "Auto Text-to-Speech",
                    subtitle = "Automatically read AI responses aloud",
                    icon = Icons.Default.VolumeUp,
                    checked = uiState.autoTts,
                    onCheckedChange = viewModel::toggleAutoTts
                )

                if (uiState.numSpeakers > 1) {
                    SettingsSpeakerPicker(
                        selectedSpeaker = uiState.selectedSpeaker,
                        numSpeakers = uiState.numSpeakers,
                        isPreviewPlaying = uiState.isPreviewPlaying,
                        previewingSpeakerId = uiState.previewingSpeakerId,
                        onSpeakerSelected = viewModel::selectSpeaker,
                        onPreviewSpeaker = viewModel::previewSpeaker,
                        onStopPreview = viewModel::stopPreview
                    )
                }

                SettingsSlider(
                    title = "Speech Speed",
                    value = uiState.ttsSpeed,
                    onValueChange = viewModel::setTtsSpeed,
                    valueRange = 0.5f..2.0f,
                    icon = Icons.Default.Speed
                )

                SettingsSlider(
                    title = "Speech Pitch",
                    value = uiState.ttsPitch,
                    onValueChange = viewModel::setTtsPitch,
                    valueRange = 0.5f..2.0f,
                    icon = Icons.Default.Tune
                )
            }

            // AI Model — OpenRouter cloud (pick any tool-capable model)
            SettingsSection(title = "AI Model") {
                Text(
                    "Add an OpenRouter API key to use a cloud model for chat + function calling. " +
                        "Leave blank to use the on-device model (offline). Get a key at openrouter.ai/keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OmniTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::setApiKey,
                    label = { Text("OpenRouter API key") },
                    placeholder = { Text("sk-or-v1-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Model picker (tool-capable models from OpenRouter)
                var expanded by remember { mutableStateOf(false) }
                val filtered = remember(uiState.models, uiState.freeOnly) {
                    if (uiState.freeOnly) uiState.models.filter { it.free } else uiState.models
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Model", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("Free only", style = MaterialTheme.typography.labelMedium, color = OmniTextSecondary)
                    Switch(checked = uiState.freeOnly, onCheckedChange = { viewModel.toggleFreeOnly() })
                }
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(uiState.selectedModel, maxLines = 1)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (uiState.loadingModels) {
                        DropdownMenuItem(text = { Text("Loading models…") }, onClick = {})
                    }
                    uiState.modelsError?.let { err ->
                        DropdownMenuItem(
                            text = { Text("Error: $err — tap to retry") },
                            onClick = { viewModel.fetchModels() }
                        )
                    }
                    filtered.take(80).forEach { m ->
                        DropdownMenuItem(
                            text = { Text((if (m.free) "🆓 " else "") + m.name, maxLines = 1) },
                            onClick = { viewModel.selectModel(m.id); expanded = false }
                        )
                    }
                }
                Text(
                    if (uiState.apiKey.isBlank()) "Using on-device model (offline)"
                    else "Using ${uiState.selectedModel} (OpenRouter)",
                    style = MaterialTheme.typography.labelSmall,
                    color = OmniTextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Connected Apps — Composio (cloud SaaS tools, beta)
            SettingsSection(title = "Connected Apps (beta)") {
                Text(
                    "Add your Composio API key to let the assistant act in cloud apps (Gmail, Slack, " +
                        "GitHub…) alongside on-device tools. Connect accounts at app.composio.dev. " +
                        "Note: this is a project-wide key — only use your own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OmniTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = uiState.composioApiKey,
                    onValueChange = viewModel::setComposioApiKey,
                    label = { Text("Composio API key") },
                    placeholder = { Text("comp_...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.composioUserId,
                    onValueChange = viewModel::setComposioUserId,
                    label = { Text("Composio user id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.composioTools,
                    onValueChange = viewModel::setComposioTools,
                    label = { Text("Enabled tool slugs (comma-separated)") },
                    placeholder = { Text("GMAIL_SEND_EMAIL,SLACK_SEND_MESSAGE") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (uiState.composioApiKey.isBlank()) "Disabled (no key)"
                    else "Enabled — cloud tools added to the assistant",
                    style = MaterialTheme.typography.labelSmall,
                    color = OmniTextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onManageApps,
                    enabled = uiState.composioApiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Apps, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage connected apps")
                }
            }

            // Data
            SettingsSection(title = "Data") {
                SettingsToggle(
                    title = "Speech-to-Text",
                    subtitle = "Enable voice input",
                    icon = Icons.Default.Mic,
                    checked = uiState.sttEnabled,
                    onCheckedChange = viewModel::toggleSttEnabled
                )
                SettingsAction(
                    title = "Clear all chats",
                    subtitle = "Delete all conversations from this device",
                    icon = Icons.Default.DeleteSweep,
                    onClick = viewModel::clearAllChats
                )
            }

            // App Info
            SettingsSection(title = "About") {
                SettingsInfo(
                    title = "Version",
                    value = "1.0.0"
                )
                SettingsInfo(
                    title = "App",
                    value = "OmniPro"
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val omniColors = LocalOmniPinColors.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = omniColors.textTertiary,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, omniColors.borderLight),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = OmniTextTertiary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OmniTextSecondary
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = OmniTextTertiary
            )
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                String.format("%.1fx", value),
                style = MaterialTheme.typography.bodyMedium,
                color = OmniAccent
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(start = 34.dp)
        )
    }
}

@Composable
private fun SettingsSpeakerPicker(
    selectedSpeaker: Int,
    numSpeakers: Int,
    isPreviewPlaying: Boolean,
    previewingSpeakerId: Int?,
    onSpeakerSelected: (Int) -> Unit,
    onPreviewSpeaker: (Int) -> Unit,
    onStopPreview: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = OmniTextTertiary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Choose the TTS speaker voice",
                    style = MaterialTheme.typography.bodySmall,
                    color = OmniTextSecondary
                )
            }
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { expanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Speaker ${selectedSpeaker + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OmniAccent
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = OmniTextTertiary
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    (0 until numSpeakers).forEach { id ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        "Speaker ${id + 1}",
                                        fontWeight = if (id == selectedSpeaker) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        VoicePreviewPhrases.getSpeakerPersonality(id),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OmniTextSecondary
                                    )
                                }
                            },
                            onClick = {
                                onSpeakerSelected(id)
                                expanded = false
                            },
                            leadingIcon = if (id == selectedSpeaker) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (isPreviewPlaying && previewingSpeakerId == id) {
                                            onStopPreview()
                                        } else {
                                            onPreviewSpeaker(id)
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (isPreviewPlaying && previewingSpeakerId == id)
                                            Icons.Default.Stop
                                        else
                                            Icons.Default.PlayArrow,
                                        contentDescription = if (isPreviewPlaying && previewingSpeakerId == id)
                                            "Stop preview"
                                        else
                                            "Preview voice",
                                        modifier = Modifier.size(20.dp),
                                        tint = if (isPreviewPlaying && previewingSpeakerId == id)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        // Show personality label for selected speaker
        if (numSpeakers > 0) {
            Text(
                VoicePreviewPhrases.getSpeakerPersonality(selectedSpeaker),
                style = MaterialTheme.typography.bodySmall,
                color = OmniTextTertiary,
                modifier = Modifier.padding(start = 34.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsAction(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = OmniTextTertiary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OmniTextSecondary
            )
        }
    }
}

@Composable
private fun SettingsInfo(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = OmniTextSecondary
        )
    }
}
