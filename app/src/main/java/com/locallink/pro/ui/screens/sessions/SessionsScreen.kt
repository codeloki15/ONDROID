package com.locallink.pro.ui.screens.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenSession: (String?) -> Unit,
    vm: SessionsViewModel = hiltViewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text("Chats", style = MaterialTheme.typography.headlineMedium, color = OmniText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmniBg),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenSession(null) },
                containerColor = OmniAccent,
                contentColor = OmniTextOnAccent,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New chat") },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Filled.Chat, null, tint = OmniTextFaint, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No conversations yet", color = OmniTextDim, style = MaterialTheme.typography.bodyLarge)
                    Text("Tap “New chat” to start.", color = OmniTextFaint, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { s -> SessionRow(s, { onOpenSession(s.id) }, { vm.delete(s.id) }) }
            }
        }
    }
}

@Composable
private fun SessionRow(s: SessionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(OmniSurface).border(1.dp, OmniBorderSoft, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(OmniAccentContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Chat, null, tint = OmniAccentBright, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(
            s.title, color = OmniText, style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, "Delete", tint = OmniTextFaint, modifier = Modifier.size(20.dp))
        }
    }
}
