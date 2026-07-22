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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import com.locallink.pro.service.llm.ComposioApp
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
                title = { Text("Connected Apps") },
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
            if (!ui.hasKey) {
                EmptyHint("Add your Composio API key in Settings → Connected Apps to browse and connect apps.")
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

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = OmniTextDim, style = MaterialTheme.typography.bodyMedium)
    }
}
