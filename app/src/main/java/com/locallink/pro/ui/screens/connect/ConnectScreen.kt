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
                        AppCard(app, connecting = ui.connectingSlug == app.slug) { vm.connect(app) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: ComposioApp, connecting: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(OmniSurface)
            .border(1.dp, if (app.connected) OmniSuccess.copy(alpha = 0.4f) else OmniBorder, RoundedCornerShape(18.dp))
            .clickable(enabled = !app.connected && !connecting, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(OmniSurface3),
                contentAlignment = Alignment.Center,
            ) {
                if (app.logo.isNotBlank()) {
                    AsyncImage(model = app.logo, contentDescription = null, modifier = Modifier.size(26.dp))
                } else {
                    Text(app.name.take(1).uppercase(), color = OmniText, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.weight(1f))
            when {
                app.connected -> Box(
                    Modifier.size(24.dp).clip(CircleShape).background(OmniSuccess),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, "Connected", tint = OmniBg, modifier = Modifier.size(15.dp)) }
                connecting -> CircularProgressIndicator(color = OmniAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(app.name, color = OmniText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (app.connected) "Connected" else "${app.toolsCount} tools",
            color = if (app.connected) OmniSuccess else OmniTextFaint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = OmniTextDim, style = MaterialTheme.typography.bodyMedium)
    }
}
