package com.locallink.pro.ui.screens.connect

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.locallink.pro.service.llm.ComposioApp
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onBack: () -> Unit,
    vm: ConnectViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    // Open OAuth in a Custom Tab when requested.
    LaunchedEffect(ui.openUrl) {
        ui.openUrl?.let { url ->
            runCatching {
                CustomTabsIntent.Builder().setShowTitle(true).build()
                    .launchUrl(context, url.toUri())
            }
            vm.urlOpened()
        }
    }
    // Re-check the pending connection whenever we come back to this screen (deep-link or manual return).
    LifecycleResumeEffect(Unit) {
        vm.pollPendingConnection()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text("Composio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = OmniText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniBg, titleContentColor = OmniText,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // ── Account (BYO Composio API key) ───────────────────────────
            // First run shows the full setup card; once a key is saved it collapses to a
            // one-line status row with an Edit toggle. Tools reached here power the CHAT and
            // VOICE modes only — "Automate my phone" never routes through Composio.
            var showSetup by remember { mutableStateOf(false) }
            if (!ui.hasKey || showSetup) {
                SetupCard(
                    apiKey = ui.apiKey,
                    userId = ui.userId,
                    onApiKey = vm::setApiKey,
                    onUserId = vm::setUserId,
                    onSave = { showSetup = false; vm.saveAndLoad() },
                )
            } else {
                KeyStatusRow(userId = ui.userId, onEdit = { showSetup = true })
            }
            if (!ui.hasKey) {
                ui.error?.let {
                    Text(it, color = OmniError, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                return@Column
            }

            // Search
            OutlinedTextField(
                value = ui.search,
                onValueChange = { vm.setSearch(it); },
                placeholder = { Text("Search apps", color = OmniTextFaint) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = OmniTextFaint) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OmniBorderFocus, unfocusedBorderColor = OmniBorder,
                    focusedContainerColor = OmniSurface2, unfocusedContainerColor = OmniSurface2,
                    focusedTextColor = OmniText, unfocusedTextColor = OmniText,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { vm.load() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            ui.error?.let {
                Text(it, color = OmniError, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            if (ui.loading && ui.apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OmniAccent)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(ui.apps, key = { it.slug }) { app ->
                        AppCard(
                            app = app,
                            connecting = ui.connectingSlug == app.slug,
                            removing = ui.removingSlug == app.slug,
                            onConnect = { vm.connect(app) },
                            onDisconnect = { vm.disconnect(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    app: ComposioApp,
    connecting: Boolean,
    removing: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // A connected app (with a real connection) can be removed; no-auth apps have nothing to remove.
    val removable = app.connected && !app.noAuth && app.connectedAccountId != null
    var confirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(OmniSurface)
            .border(
                1.dp,
                if (app.connected || app.noAuth) OmniSuccess.copy(alpha = 0.4f) else OmniBorder,
                RoundedCornerShape(18.dp),
            )
            .clickable(enabled = !app.connected && !app.noAuth && !connecting, onClick = onConnect)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(OmniSurface3),
                contentAlignment = Alignment.Center,
            ) {
                // Letter fallback always behind; logo (white-on-dark) layered on top when it loads.
                Text(app.name.take(1).uppercase(), color = OmniTextDim, style = MaterialTheme.typography.titleMedium)
                if (app.logo.isNotBlank()) {
                    AsyncImage(
                        model = app.logo, contentDescription = null,
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            when {
                removing -> CircularProgressIndicator(color = OmniError, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                removable -> IconButton(onClick = { confirm = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Disconnect", tint = OmniTextFaint, modifier = Modifier.size(18.dp))
                }
                app.connected || app.noAuth -> Box(
                    Modifier.size(24.dp).clip(CircleShape).background(OmniSuccess),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, "Ready", tint = OmniBg, modifier = Modifier.size(15.dp)) }
                connecting -> CircularProgressIndicator(color = OmniAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(app.name, color = OmniText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                app.noAuth -> "Ready · no login"
                app.connected -> "Connected · tap ✕ to remove"
                else -> "${app.toolsCount} tools · tap to connect"
            },
            color = if (app.connected || app.noAuth) OmniSuccess else OmniTextFaint,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect ${app.name}?") },
            text = { Text("This removes the connection and its tools. You can re-add it anytime.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; onDisconnect() }) {
                    Text("Disconnect", color = OmniError)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
            textContentColor = OmniTextDim,
        )
    }
}

/** First-run setup: paste the BYO Composio API key (and optional user id), then load the grid. */
@Composable
private fun SetupCard(
    apiKey: String,
    userId: String,
    onApiKey: (String) -> Unit,
    onUserId: (String) -> Unit,
    onSave: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(OmniSurface)
            .border(1.dp, OmniBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text("Set up Composio", color = OmniText, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Composio powers cloud app tools — Gmail, Slack, Calendar and hundreds more — in " +
                "chat and voice chat. Bring your own (free) API key.",
            color = OmniTextDim, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            label = { Text("Composio API key") },
            placeholder = { Text("ak_…", color = OmniTextFaint) },
            leadingIcon = { Icon(Icons.Outlined.Key, null, tint = OmniTextFaint, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp),
            colors = setupFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = userId,
            onValueChange = onUserId,
            label = { Text("User id") },
            placeholder = { Text("default", color = OmniTextFaint) },
            supportingText = { Text("Connections are stored under this id — leave as \"default\".", color = OmniTextFaint) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = setupFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { runCatching { uriHandler.openUri("https://app.composio.dev") } }
                .padding(top = 10.dp, bottom = 4.dp, start = 2.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(14.dp), tint = OmniAccent)
            Spacer(Modifier.width(6.dp))
            Text("Get a key at app.composio.dev", style = MaterialTheme.typography.labelMedium, color = OmniAccent)
        }
        Spacer(Modifier.height(12.dp))
        GradientPill(
            "Save & load apps",
            onClick = onSave,
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Collapsed account state once a key is saved: green dot + user id + Edit toggle. */
@Composable
private fun KeyStatusRow(userId: String, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(OmniSuccess))
        Spacer(Modifier.width(8.dp))
        Text(
            "API key set · user \"$userId\"",
            color = OmniTextDim, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit) { Text("Edit", color = OmniAccent) }
    }
}

@Composable
private fun setupFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OmniBorderFocus, unfocusedBorderColor = OmniBorder,
    focusedContainerColor = OmniSurface2, unfocusedContainerColor = OmniSurface2,
    focusedTextColor = OmniText, unfocusedTextColor = OmniText,
    focusedLabelColor = OmniAccent, unfocusedLabelColor = OmniTextFaint,
    cursorColor = OmniAccent,
)
