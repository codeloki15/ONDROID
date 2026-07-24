package com.locallink.pro.ui.screens.notifyrules

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.NotificationRuleDao
import com.locallink.pro.data.db.NotificationRuleEntity
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationRulesViewModel @Inject constructor(
    private val dao: NotificationRuleDao,
) : ViewModel() {
    val rules: StateFlow<List<NotificationRuleEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(appPackage: String, matchText: String, action: String, agentTask: String) =
        viewModelScope.launch {
            dao.upsert(
                NotificationRuleEntity(
                    appPackage = appPackage.trim(), matchText = matchText.trim(),
                    action = action, agentTask = agentTask.trim(),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }

    fun setEnabled(id: Long, on: Boolean) = viewModelScope.launch { dao.setEnabled(id, on) }
    fun delete(id: Long) = viewModelScope.launch { dao.delete(id) }
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * Notification triggers: "when a notification like X arrives, speak it / run a task".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRulesScreen(
    onBack: () -> Unit,
    vm: NotificationRulesViewModel = hiltViewModel(),
) {
    val rules by vm.rules.collectAsState()
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
                title = { Text("Notification triggers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                },
                actions = {
                    if (hasAccess) IconButton(onClick = { adding = true }) {
                        Icon(Icons.Outlined.Add, "Add rule", tint = OmniText)
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
                        "With notification access, Omni can react to incoming notifications — read " +
                            "them aloud or take action for you. Nothing is stored or sent anywhere.",
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
                        "Examples: read every WhatsApp message aloud, or speak incoming OTP codes " +
                            "so you never dig for them. Tap + to add one.",
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
                                Text(
                                    buildString {
                                        append(if (r.appPackage.isBlank()) "Any app" else r.appPackage)
                                        if (r.matchText.isNotBlank()) append(" · contains “${r.matchText}”")
                                    },
                                    style = MaterialTheme.typography.titleSmall, color = OmniText,
                                )
                                Text(
                                    if (r.action == "agent")
                                        "Run task: ${r.agentTask.ifBlank { "handle the notification" }}"
                                    else "Read aloud",
                                    style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                                )
                            }
                            Switch(
                                checked = r.enabled,
                                onCheckedChange = { vm.setEnabled(r.id, it) },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = AuroraViolet,
                                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                                ),
                            )
                            IconButton(onClick = { vm.delete(r.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, "Delete", tint = OmniTextFaint, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var pkg by remember { mutableStateOf("") }
        var contains by remember { mutableStateOf("") }
        var isAgent by remember { mutableStateOf(false) }
        var task by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New trigger") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pkg, onValueChange = { pkg = it }, singleLine = true,
                        label = { Text("App name (e.g. Gmail, WhatsApp — empty = any)") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = contains, onValueChange = { contains = it }, singleLine = true,
                        label = { Text("Text contains — use 'or' for alternatives (empty = any)") },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !isAgent, onClick = { isAgent = false }, label = { Text("Read aloud") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = isAgent, onClick = { isAgent = true }, label = { Text("Run a task") })
                    }
                    if (isAgent) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = task, onValueChange = { task = it },
                            label = { Text("Task ({app}/{title}/{text} available)") },
                            placeholder = { Text("e.g. Open WhatsApp and reply that I'm driving") },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.add(pkg, contains, if (isAgent) "agent" else "speak", task)
                    adding = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }
}
