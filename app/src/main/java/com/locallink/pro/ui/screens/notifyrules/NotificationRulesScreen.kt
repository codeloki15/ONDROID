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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    val rules: StateFlow<List<NotificationRuleEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Launchable installed apps (label list) for the pickers. */
    private val _apps = MutableStateFlow<List<String>>(emptyList())
    val apps: StateFlow<List<String>> = _apps.asStateFlow()

    init {
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
                if (r.triggerType == "time") {
                    Icon(Icons.Outlined.Alarm, null, tint = OmniTextDim, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    if (r.triggerType == "time")
                        "Daily %02d:%02d".format(r.timeHour, r.timeMinute) +
                            (r.matchText.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: "")
                    else buildString {
                        append(r.appPackage.ifBlank { "Any app" })
                        if (r.matchText.isNotBlank()) append(" · contains “${r.matchText}”")
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
private fun AddTriggerDialog(
    apps: List<String>,
    onDismiss: () -> Unit,
    onSaveNotification: (app: String, match: String, isAgent: Boolean, task: String, target: String) -> Unit,
    onSaveTime: (h: Int, m: Int, note: String, isAgent: Boolean, task: String, target: String) -> Unit,
) {
    val context = LocalContext.current
    var isTime by remember { mutableStateOf(false) }
    var app by remember { mutableStateOf("") }
    var match by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf(8) }
    var minute by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var isAgent by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New trigger") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("When…", style = MaterialTheme.typography.labelLarge, color = OmniTextDim)
                Spacer(Modifier.height(6.dp))
                Row {
                    FilterChip(selected = !isTime, onClick = { isTime = false }, label = { Text("Notification") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = isTime, onClick = { isTime = true }, label = { Text("Time (daily)") })
                }
                Spacer(Modifier.height(10.dp))
                if (!isTime) {
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
                if (!isAgent && !isTime) {
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
                    if (isTime) onSaveTime(hour, minute, note, isAgent, task, target)
                    else onSaveNotification(app, match, isAgent, task, target)
                },
                enabled = !isAgent || task.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = OmniSurface2,
        titleContentColor = OmniText,
    )
}
