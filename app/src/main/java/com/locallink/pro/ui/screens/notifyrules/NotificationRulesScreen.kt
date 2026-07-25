package com.locallink.pro.ui.screens.notifyrules

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.NotificationRuleDao
import com.locallink.pro.data.db.NotificationRuleEntity
import com.locallink.pro.service.notify.TriggerScheduler
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NotificationRulesViewModel @Inject constructor(
    private val dao: NotificationRuleDao,
    private val scheduler: TriggerScheduler,
    private val composio: com.locallink.pro.service.llm.ComposioClient,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    /** Surfaced when arming a cloud trigger fails, so the tap isn't silently ignored. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    /** Cloud trigger types the user's connected apps can fire; empty when Composio is off. */
    private val _cloudTriggers =
        MutableStateFlow<List<com.locallink.pro.service.llm.ComposioTriggerType>>(emptyList())
    val cloudTriggers: StateFlow<List<com.locallink.pro.service.llm.ComposioTriggerType>> =
        _cloudTriggers.asStateFlow()
    val rules: StateFlow<List<NotificationRuleEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Launchable installed apps (label list) for the pickers. */
    private val _apps = MutableStateFlow<List<String>>(emptyList())
    val apps: StateFlow<List<String>> = _apps.asStateFlow()

    init {
        viewModelScope.launch {
            _cloudTriggers.value = runCatching { composio.triggerTypes() }.getOrDefault(emptyList())
        }
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) {
                val pm = appContext.packageManager
                pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { pm.getApplicationLabel(it).toString() }
                    .distinct()
                    .sortedBy { it.lowercase() }
            }
        }
    }

    fun addNotificationRule(app: String, matchText: String, isAgent: Boolean, task: String, targetApp: String) =
        viewModelScope.launch {
            dao.upsert(
                NotificationRuleEntity(
                    appPackage = app.trim(), matchText = matchText.trim(),
                    action = if (isAgent) "agent" else "speak",
                    agentTask = task.trim(), targetApp = targetApp.trim(),
                    triggerType = "notification",
                    createdAt = System.currentTimeMillis(),
                )
            )
        }

    /**
     * Save a cloud-trigger rule AND subscribe to it on Composio — without the subscription the
     * socket stays connected and simply never receives this event, which looks identical to a
     * broken rule. The service is (re)started so the connection exists to receive it.
     */
    fun addCloudRule(slug: String, isAgent: Boolean, task: String, targetApp: String) =
        viewModelScope.launch {
            val instance = composio.enableTrigger(slug)
            if (instance == null) {
                // Some events need per-trigger configuration (a channel, a sheet, a label) that
                // this dialog doesn't collect. Say so instead of saving a rule that can't fire.
                android.util.Log.w("NotifyRules", "could not enable cloud trigger $slug")
                _error.value = "Couldn't switch on ${cloudLabelOf(slug)}. It may need extra setup " +
                    "in your Composio dashboard, or the app may need reconnecting."
                return@launch
            }
            dao.upsert(
                NotificationRuleEntity(
                    appPackage = slug,              // the trigger slug; see composioEnabled()
                    matchText = "",
                    action = if (isAgent) "agent" else "speak",
                    agentTask = task,
                    createdAt = System.currentTimeMillis(),
                    triggerType = "composio",
                    targetApp = targetApp,
                )
            )
            com.locallink.pro.service.notify.ComposioTriggerService.start(appContext)
        }

    fun addTimeRule(hour: Int, minute: Int, note: String, isAgent: Boolean, task: String, targetApp: String) =
        viewModelScope.launch {
            val id = dao.upsert(
                NotificationRuleEntity(
                    matchText = note.trim(),
                    action = if (isAgent) "agent" else "speak",
                    agentTask = task.trim(), targetApp = targetApp.trim(),
                    triggerType = "time", timeHour = hour, timeMinute = minute,
                    createdAt = System.currentTimeMillis(),
                )
            )
            scheduler.schedule(id, hour, minute)
        }

    fun setEnabled(r: NotificationRuleEntity, on: Boolean) = viewModelScope.launch {
        dao.setEnabled(r.id, on)
        if (r.triggerType == "time") {
            if (on) scheduler.schedule(r.id, r.timeHour, r.timeMinute) else scheduler.cancel(r.id)
        }
    }

    fun delete(r: NotificationRuleEntity) = viewModelScope.launch {
        if (r.triggerType == "time") scheduler.cancel(r.id)
        dao.delete(r.id)
    }
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * IFTTT-style triggers: notification or daily-time conditions → announce (app name
 * only — content is never spoken) or run an Automate task in a chosen app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRulesScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit = {},
    vm: NotificationRulesViewModel = hiltViewModel(),
) {
    val rules by vm.rules.collectAsState()
    val apps by vm.apps.collectAsState()
    val cloudTriggers by vm.cloudTriggers.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var adding by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        hasAccess = hasNotificationAccess(context)
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text("Triggers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Outlined.History, "History", tint = OmniText)
                    }
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Outlined.Add, "Add trigger", tint = OmniText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniBg, titleContentColor = OmniText,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!hasAccess) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Outlined.NotificationsActive, null,
                        tint = AuroraViolet, modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Let Omni see notifications", style = MaterialTheme.typography.titleLarge, color = OmniText)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Notification triggers need notification access. Time-based triggers work " +
                            "without it. Content is never read aloud or sent anywhere.",
                        style = MaterialTheme.typography.bodyMedium, color = OmniTextDim,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    GradientPill("Grant notification access", onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    })
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { adding = true }) { Text("Add a time trigger anyway") }
                }
            } else if (rules.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No triggers yet", style = MaterialTheme.typography.titleLarge, color = OmniText)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Examples: announce Gmail notifications, reply to a WhatsApp contact, " +
                            "or run a task every morning at 8. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium, color = OmniTextDim,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rules, key = { it.id }) { r ->
                        RuleRow(
                            r = r,
                            onToggle = { vm.setEnabled(r, it) },
                            onDelete = { vm.delete(r) },
                        )
                    }
                }
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Couldn't arm that trigger") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }

    if (adding) {
        AddTriggerDialog(
            apps = apps,
            onDismiss = { adding = false },
            onSaveNotification = { app, match, isAgent, task, target ->
                vm.addNotificationRule(app, match, isAgent, task, target); adding = false
            },
            onSaveTime = { h, m, note, isAgent, task, target ->
                vm.addTimeRule(h, m, note, isAgent, task, target); adding = false
            },
            cloudTriggers = cloudTriggers,
            onSaveCloud = { slug, isAgent, task, target ->
                vm.addCloudRule(slug, isAgent, task, target); adding = false
            },
        )
    }
}

@Composable
private fun RuleRow(
    r: NotificationRuleEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OmniSurface)
            .border(1.dp, OmniBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (r.triggerType) {
                    "time" -> {
                        Icon(Icons.Outlined.Alarm, null, tint = OmniTextDim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    "composio" -> {
                        Icon(Icons.Outlined.CloudQueue, null, tint = OmniTextDim, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                }
                Text(
                    when (r.triggerType) {
                        "time" -> "Daily %02d:%02d".format(r.timeHour, r.timeMinute) +
                            (r.matchText.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: "")
                        // appPackage holds the trigger slug — read it back the way the picker showed it.
                        "composio" -> cloudLabelOf(r.appPackage)
                        else -> buildString {
                            append(r.appPackage.ifBlank { "Any app" })
                            if (r.matchText.isNotBlank()) append(" · contains “${r.matchText}”")
                        }
                    },
                    style = MaterialTheme.typography.titleSmall, color = OmniText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (r.action == "agent") buildString {
                    append("Task")
                    if (r.targetApp.isNotBlank()) append(" in ${r.targetApp}")
                    append(": ${r.agentTask.ifBlank { "handle it" }}")
                } else "Announce only (no content is read out)",
                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = r.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AuroraViolet,
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, "Delete", tint = OmniTextFaint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Dropdown backed by the installed-app labels ("" = Any app entry when [allowAny]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDropdown(
    label: String,
    apps: List<String>,
    selected: String,
    allowAny: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.ifBlank { if (allowAny) "Any app" else "" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAny) DropdownMenuItem(
                text = { Text("Any app") },
                onClick = { onSelect(""); expanded = false },
            )
            apps.forEach { app ->
                DropdownMenuItem(text = { Text(app) }, onClick = { onSelect(app); expanded = false })
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun AddTriggerDialog(
    apps: List<String>,
    onDismiss: () -> Unit,
    onSaveNotification: (app: String, match: String, isAgent: Boolean, task: String, target: String) -> Unit,
    onSaveTime: (h: Int, m: Int, note: String, isAgent: Boolean, task: String, target: String) -> Unit,
    cloudTriggers: List<com.locallink.pro.service.llm.ComposioTriggerType>,
    onSaveCloud: (slug: String, isAgent: Boolean, task: String, target: String) -> Unit,
) {
    val context = LocalContext.current
    // "notification" | "time" | "cloud"
    var mode by remember { mutableStateOf("notification") }
    val isTime = mode == "time"
    val isCloud = mode == "cloud"
    var cloudSlug by remember { mutableStateOf("") }
    var pickingCloud by remember { mutableStateOf(false) }
    var app by remember { mutableStateOf("") }
    var match by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf(8) }
    var minute by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var isAgent by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    if (pickingCloud) {
        CloudTriggerPicker(
            triggers = cloudTriggers,
            onPick = { cloudSlug = it.slug; pickingCloud = false },
            onDismiss = { pickingCloud = false },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New trigger") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When…", style = MaterialTheme.typography.labelLarge, color = OmniTextDim)
                Spacer(Modifier.height(6.dp))
                // Wraps: three chips overflow a dialog's width on a phone, and the third was
                // being clipped off-screen entirely.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = mode == "notification", onClick = { mode = "notification" },
                        label = { Text("Notification") })
                    FilterChip(selected = isTime, onClick = { mode = "time" }, label = { Text("Time (daily)") })
                    FilterChip(selected = isCloud, onClick = { mode = "cloud" }, label = { Text("Cloud app") })
                }
                Spacer(Modifier.height(10.dp))
                if (isCloud) {
                    val chosen = cloudTriggers.firstOrNull { it.slug == cloudSlug }
                    OutlinedTextField(
                        value = chosen?.let { "${appLabelOf(it.toolkit)} · ${it.label()}" }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("When this happens") },
                        placeholder = { Text("Choose a cloud event") },
                        trailingIcon = {
                            TextButton(onClick = { pickingCloud = true }) {
                                Text(if (chosen == null) "Choose" else "Change")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        chosen?.description?.takeIf { it.isNotBlank() }
                            ?: "Events come from the apps you've connected — Omni reacts the moment one fires.",
                        style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (!isTime) {
                    AppDropdown("From app", apps, app, allowAny = true) { app = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = match, onValueChange = { match = it }, singleLine = true,
                        label = { Text("Text contains — 'or' for alternatives (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = "%02d:%02d".format(hour, minute),
                        onValueChange = {}, readOnly = true,
                        label = { Text("Every day at") },
                        trailingIcon = {
                            TextButton(onClick = {
                                TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute, true).show()
                            }) { Text("Change") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note, onValueChange = { note = it }, singleLine = true,
                        label = { Text("Note / reminder text") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Then…", style = MaterialTheme.typography.labelLarge, color = OmniTextDim)
                Spacer(Modifier.height(6.dp))
                Row {
                    FilterChip(selected = !isAgent, onClick = { isAgent = false }, label = { Text("Announce") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = isAgent, onClick = { isAgent = true }, label = { Text("Run a task") })
                }
                if (!isAgent && mode == "notification") {
                    Text(
                        "Says only “You have a notification from <app>” — content stays private.",
                        style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (isAgent) {
                    Spacer(Modifier.height(8.dp))
                    AppDropdown("Act in app (optional)", apps, target, allowAny = true) { target = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = task, onValueChange = { task = it },
                        label = { Text("What should Omni do?") },
                        placeholder = { Text("e.g. reply that I'm busy and will call back") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (mode) {
                        "time" -> onSaveTime(hour, minute, note, isAgent, task, target)
                        "cloud" -> onSaveCloud(cloudSlug, isAgent, task, target)
                        else -> onSaveNotification(app, match, isAgent, task, target)
                    }
                },
                enabled = (!isAgent || task.isNotBlank()) && (!isCloud || cloudSlug.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = OmniSurface2,
        titleContentColor = OmniText,
    )
}
