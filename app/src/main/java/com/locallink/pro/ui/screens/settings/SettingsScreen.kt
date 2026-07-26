package com.locallink.pro.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.service.voice.SttModelState
import com.locallink.pro.service.voice.VoicePreviewPhrases
import com.locallink.pro.ui.components.GhostPill
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.components.SearchPill
import com.locallink.pro.ui.theme.*
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageApps: () -> Unit = {},
    onOpenRoutines: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenNotifyRules: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showModelSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.saveSuccess.collectLatest { success ->
            snackbarHostState.showSnackbar(
                message = if (success) "Settings saved" else "Failed to save settings",
                duration = SnackbarDuration.Short
            )
        }
    }

    AuroraBackground(glow = 0.35f) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = OmniText)
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    GradientPill(
                        "Save changes",
                        onClick = { viewModel.saveSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // ── Voice ────────────────────────────────────────────────
                SettingsSection(title = "Voice") {
                    SettingsToggle(
                        title = "Speech-to-Text",
                        subtitle = "Voice input via microphone",
                        icon = Icons.Outlined.Mic,
                        checked = uiState.sttEnabled,
                        onCheckedChange = viewModel::toggleSttEnabled
                    )
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    SettingsToggle(
                        title = "Hands-free “Hey Omni”",
                        subtitle = "Always listen for the wake word",
                        icon = Icons.Outlined.RecordVoiceOver,
                        checked = uiState.handsFree,
                        onCheckedChange = { on ->
                            viewModel.setHandsFree(on)
                            if (on) com.locallink.pro.service.voice.VoiceLoopService.start(ctx)
                            else com.locallink.pro.service.voice.VoiceLoopService.stop(ctx)
                        }
                    )
                    SettingsToggle(
                        title = "On-device recognition",
                        subtitle = "Parakeet v3 — private, offline, no cloud",
                        icon = Icons.Outlined.Mic,
                        checked = uiState.sttOnDevice,
                        onCheckedChange = viewModel::setSttOnDevice
                    )
                    if (uiState.sttOnDevice) {
                        SttModelBlock(
                            state = uiState.sttModelState,
                            onDownload = viewModel::downloadSttModel,
                            onCancel = viewModel::cancelSttDownload,
                            onDelete = viewModel::deleteSttModel,
                            onReady = viewModel::onSttModelReady,
                        )
                    }
                    SettingsToggle(
                        title = "Speak replies",
                        subtitle = "Read AI responses aloud",
                        icon = Icons.AutoMirrored.Outlined.VolumeUp,
                        checked = uiState.autoTts,
                        onCheckedChange = viewModel::toggleAutoTts
                    )

                    if (uiState.numSpeakers > 1) {
                        SpeakerPicker(
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
                        title = "Speech speed",
                        value = uiState.ttsSpeed,
                        onValueChange = viewModel::setTtsSpeed,
                        valueRange = 0.5f..2.0f,
                        icon = Icons.Outlined.Speed
                    )
                    SettingsSlider(
                        title = "Speech pitch",
                        value = uiState.ttsPitch,
                        onValueChange = viewModel::setTtsPitch,
                        valueRange = 0.5f..2.0f,
                        icon = Icons.Outlined.Tune
                    )
                }

                // ── AI model ─────────────────────────────────────────────
                SettingsSection(title = "AI model") {
                    Text("Engine", style = MaterialTheme.typography.titleSmall, color = OmniText)
                    Spacer(Modifier.height(10.dp))
                    EngineModeSelector(
                        selected = uiState.engineMode,
                        onSelect = viewModel::setEngineMode,
                    )
                    Text(
                        when (uiState.engineMode) {
                            com.locallink.pro.data.local.EngineMode.AUTO ->
                                "Cloud first, falls back to the on-device model when the cloud is unavailable."
                            com.locallink.pro.data.local.EngineMode.CLOUD_ONLY ->
                                "Cloud model only — errors instead of falling back."
                            com.locallink.pro.data.local.EngineMode.LOCAL_ONLY ->
                                "On-device only. Fully offline, never calls the cloud."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OmniTextFaint,
                        modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
                    )

                    OmniTextField(
                        value = uiState.apiKey,
                        onValueChange = viewModel::setApiKey,
                        label = "OpenRouter API key",
                        placeholder = "sk-or-v1-…",
                        secret = true,
                        leadingIcon = Icons.Outlined.Key,
                    )
                    LinkRow(label = "Get an OpenRouter API key", url = "https://openrouter.ai/keys")

                    Spacer(Modifier.height(14.dp))
                    Text("Model", style = MaterialTheme.typography.titleSmall, color = OmniText)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                            .clickable { showModelSheet = true; if (uiState.models.isEmpty()) viewModel.fetchModels() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            uiState.selectedModel,
                            style = MaterialTheme.typography.bodyMedium, color = OmniText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Outlined.ExpandMore, null, tint = OmniTextFaint, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        if (uiState.apiKey.isBlank()) "Using the on-device model (offline)"
                        else "Cloud model via OpenRouter",
                        style = MaterialTheme.typography.bodySmall,
                        color = OmniTextFaint,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // ── Connected apps (Composio — powers chat & voice tools) ─
                SettingsSection(title = "Connected apps") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onManageApps)
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(Icons.Outlined.Apps)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Composio apps", style = MaterialTheme.typography.titleSmall, color = OmniText)
                            Text(
                                "Add your key, connect Gmail, Slack & more — used by chat and voice",
                                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            tint = OmniTextFaint, modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // ── Memory ───────────────────────────────────────────────
                SettingsSection(title = "Memory") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenRoutines)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Learned routines", style = MaterialTheme.typography.titleSmall, color = OmniText)
                            Text(
                                if (uiState.experienceCount == 0) "Nothing learned yet — successful phone tasks are remembered and replayed exactly"
                                else "${uiState.experienceCount} task${if (uiState.experienceCount == 1) "" else "s"} Omni can repeat — tap to manage & schedule",
                                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            tint = OmniTextFaint, modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenMemory)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("What Omni knows about you", style = MaterialTheme.typography.titleSmall, color = OmniText)
                            Text(
                                "Personal facts used in every conversation — add, review, or forget them",
                                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            tint = OmniTextFaint, modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // ── Triggers ─────────────────────────────────────────────
                SettingsSection(title = "Triggers") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenNotifyRules)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Notification triggers", style = MaterialTheme.typography.titleSmall, color = OmniText)
                            Text(
                                "React to incoming notifications — read them aloud or run a task",
                                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            tint = OmniTextFaint, modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // ── Data ─────────────────────────────────────────────────
                SettingsSection(title = "Data") {
                    SettingsAction(
                        title = "Clear all chats",
                        subtitle = "Delete every conversation on this device",
                        icon = Icons.Outlined.DeleteSweep,
                        onClick = viewModel::clearAllChats
                    )
                }

                // ── About ────────────────────────────────────────────────
                SettingsSection(title = "About") {
                    InfoRow("Version", "1.0.0")
                    InfoRow("App", "OmniPro")
                }

                Spacer(Modifier.height(6.dp))
            }
        }
    }

    // ── Model picker sheet ──────────────────────────────────────────────
    if (showModelSheet) {
        var modelQuery by remember { mutableStateOf("") }
        val filtered = remember(uiState.models, uiState.freeOnly, modelQuery) {
            uiState.models
                .filter { !uiState.freeOnly || it.free }
                .filter { modelQuery.isBlank() || it.name.contains(modelQuery, true) || it.id.contains(modelQuery, true) }
        }
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            containerColor = OmniSurface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = OmniTextFaint) },
        ) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .fillMaxHeight(0.85f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose a model", style = MaterialTheme.typography.titleLarge, color = OmniText, modifier = Modifier.weight(1f))
                    Text("Free only", style = MaterialTheme.typography.labelMedium, color = OmniTextDim)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = uiState.freeOnly,
                        onCheckedChange = { viewModel.toggleFreeOnly() },
                        colors = omniSwitchColors(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                SearchPill(value = modelQuery, onValueChange = { modelQuery = it }, placeholder = "Search models…")
                Spacer(Modifier.height(10.dp))
                when {
                    uiState.loadingModels -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AuroraVioletHi, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    }
                    uiState.modelsError != null -> Column(Modifier.padding(vertical = 16.dp)) {
                        Text("Couldn't load models: ${uiState.modelsError}", color = OmniError, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        GradientPill("Retry", onClick = { viewModel.fetchModels() }, height = 44.dp)
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
                        items(filtered.take(200), key = { it.id }) { m ->
                            val active = m.id == uiState.selectedModel
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (active) OmniAccentContainer.copy(alpha = 0.55f) else Color.Transparent)
                                    .clickable { viewModel.selectModel(m.id); showModelSheet = false }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        m.name, style = MaterialTheme.typography.bodyMedium, color = OmniText,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (m.free) Text("Free", style = MaterialTheme.typography.labelSmall, color = OmniSuccess)
                                }
                                if (active) Icon(Icons.Outlined.Check, null, tint = AuroraVioletHi, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Building blocks ─────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = OmniTextFaint,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(bottom = 10.dp, start = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(OmniSurface.copy(alpha = 0.92f))
                .padding(18.dp),
        ) { content() }
    }
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(OmniSurface3.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = OmniTextDim, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun omniSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = AuroraViolet,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = OmniTextDim,
    uncheckedTrackColor = OmniSurface3,
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OmniText)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OmniTextFaint)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = omniSwitchColors())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(modifier = Modifier.padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.width(13.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = OmniText)
            Spacer(Modifier.weight(1f))
            Text(
                String.format("%.1fx", value),
                style = MaterialTheme.typography.labelLarge,
                color = AuroraVioletHi
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            thumb = {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(1.dp, GlassBorder, CircleShape)
                )
            },
            colors = SliderDefaults.colors(
                activeTrackColor = AuroraViolet,
                inactiveTrackColor = OmniSurface3,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.padding(start = 51.dp)
        )
    }
}

@Composable
private fun SpeakerPicker(
    selectedSpeaker: Int,
    numSpeakers: Int,
    isPreviewPlaying: Boolean,
    previewingSpeakerId: Int?,
    onSpeakerSelected: (Int) -> Unit,
    onPreviewSpeaker: (Int) -> Unit,
    onStopPreview: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(Icons.Outlined.RecordVoiceOver)
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Voice", style = MaterialTheme.typography.titleSmall, color = OmniText)
            Text(
                VoicePreviewPhrases.getSpeakerPersonality(selectedSpeaker),
                style = MaterialTheme.typography.bodySmall,
                color = OmniTextFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Speaker ${selectedSpeaker + 1}", style = MaterialTheme.typography.labelLarge, color = AuroraVioletHi)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.ExpandMore, null, tint = OmniTextFaint, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
                                    color = OmniTextDim
                                )
                            }
                        },
                        onClick = { onSpeakerSelected(id); expanded = false },
                        leadingIcon = if (id == selectedSpeaker) {
                            { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp), tint = AuroraVioletHi) }
                        } else null,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (isPreviewPlaying && previewingSpeakerId == id) onStopPreview()
                                    else onPreviewSpeaker(id)
                                }
                            ) {
                                Icon(
                                    if (isPreviewPlaying && previewingSpeakerId == id) Icons.Outlined.Stop
                                    else Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (isPreviewPlaying && previewingSpeakerId == id) OmniError else AuroraVioletHi
                                )
                            }
                        }
                    )
                }
            }
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
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = OmniText)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OmniTextFaint)
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = OmniText)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = OmniTextFaint)
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(top = 8.dp, bottom = 2.dp, start = 2.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(14.dp), tint = AuroraVioletHi)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = AuroraVioletHi)
    }
}

/** Dark outlined text field tuned to the Aurora Ink language. */
@Composable
private fun OmniTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    secret: Boolean = false,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = OmniTextFaint) },
        singleLine = true,
        leadingIcon = leadingIcon?.let { { Icon(it, null, tint = OmniTextFaint, modifier = Modifier.size(18.dp)) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AuroraViolet,
            unfocusedBorderColor = GlassBorder,
            focusedLabelColor = AuroraVioletHi,
            unfocusedLabelColor = OmniTextFaint,
            cursorColor = AuroraVioletHi,
            focusedTextColor = OmniText,
            unfocusedTextColor = OmniText,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Download / status block for the on-device parakeet STT model (~670 MB). */
@Composable
private fun SttModelBlock(
    state: SttModelState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onReady: () -> Unit,
) {
    // Hot-load the engine the moment the download completes.
    LaunchedEffect(state) { if (state is SttModelState.Ready) onReady() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 51.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(OmniSurface3.copy(alpha = 0.35f))
            .padding(14.dp),
    ) {
        when (state) {
            is SttModelState.Missing -> {
                Text("Speech model not downloaded", style = MaterialTheme.typography.bodyMedium, color = OmniText)
                Text(
                    "nvidia/parakeet-tdt-0.6b-v3 · ~670 MB · 25 languages",
                    style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                )
                Spacer(Modifier.height(10.dp))
                GradientPill("Download model", onClick = onDownload, height = 42.dp)
                Text(
                    "Until then the mic uses Android's recognizer.",
                    style = MaterialTheme.typography.labelSmall, color = OmniTextFaint,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is SttModelState.Downloading -> {
                val pct = if (state.total > 0) (state.downloaded * 100 / state.total).toInt() else 0
                Text("Downloading… $pct%", style = MaterialTheme.typography.bodyMedium, color = OmniText)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.downloaded.toFloat() / state.total else 0f },
                    color = AuroraViolet,
                    trackColor = OmniSurface3,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.downloaded / 1_000_000} / ${state.total / 1_000_000} MB",
                    style = MaterialTheme.typography.labelSmall, color = OmniTextFaint,
                )
                Spacer(Modifier.height(10.dp))
                GhostPill("Pause", onClick = onCancel, height = 38.dp)
            }
            is SttModelState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, null, tint = OmniSuccess, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text("On-device model ready", style = MaterialTheme.typography.bodyMedium, color = OmniText)
                        Text(
                            "The mic now transcribes fully offline.",
                            style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                        )
                    }
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.labelLarge, color = OmniError,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            is SttModelState.Error -> {
                Text("Download failed", style = MaterialTheme.typography.bodyMedium, color = OmniError)
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = OmniTextFaint)
                Spacer(Modifier.height(10.dp))
                GradientPill("Resume download", onClick = onDownload, height = 42.dp)
            }
        }
    }
}

/** Segmented 3-way selector for the LLM engine mode. */
@Composable
private fun EngineModeSelector(
    selected: com.locallink.pro.data.local.EngineMode,
    onSelect: (com.locallink.pro.data.local.EngineMode) -> Unit,
) {
    val options = listOf(
        com.locallink.pro.data.local.EngineMode.AUTO to "Auto",
        com.locallink.pro.data.local.EngineMode.CLOUD_ONLY to "Cloud",
        com.locallink.pro.data.local.EngineMode.LOCAL_ONLY to "On-device",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OmniSurface3.copy(alpha = 0.55f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (mode, label) ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) AuroraBrush else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color.White else OmniTextDim,
                )
            }
        }
    }
}
