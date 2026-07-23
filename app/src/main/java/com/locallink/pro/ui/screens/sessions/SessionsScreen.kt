package com.locallink.pro.ui.screens.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.components.GradientPill
import com.locallink.pro.ui.components.OrbPalette
import com.locallink.pro.ui.components.SearchPill
import com.locallink.pro.ui.theme.*
import kotlin.math.abs

@Composable
fun SessionsScreen(
    onOpenSession: (String?) -> Unit,
    onOpenChat: () -> Unit = { onOpenSession(null) },
    onOpenVoice: () -> Unit = { onOpenSession(null) },
    onOpenAutomate: () -> Unit = { onOpenSession(null) },
    onOpenSettings: () -> Unit,
    vm: SessionsViewModel = hiltViewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }

    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }
    val visible = if (showAll || query.isNotBlank()) filtered else filtered.take(6)

    AuroraBackground(glow = 1f) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 120.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GradientOrb(size = 42.dp, glow = true)
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(OmniSurface.copy(alpha = 0.65f))
                                .border(1.dp, OmniText.copy(alpha = 0.15f), CircleShape)
                                .clickable(onClick = onOpenSettings),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Settings, "Settings", tint = OmniText, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                item {
                    Text(
                        "Ask, automate,\nbe inspired",
                        style = MaterialTheme.typography.displayLarge,
                        color = OmniText,
                        modifier = Modifier.padding(top = 30.dp, bottom = 26.dp),
                    )
                }
                item {
                    SearchPill(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search chats…",
                    )
                }
                item {
                    LazyRow(
                        Modifier.padding(top = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { ActionCard("New\nchat", onClick = onOpenChat) }
                        item { ActionCard("Voice\nchat", onClick = onOpenVoice) }
                        item { ActionCard("Automate\nmy phone", onClick = onOpenAutomate) }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("History", style = MaterialTheme.typography.headlineSmall, color = OmniText)
                        Spacer(Modifier.weight(1f))
                        if (filtered.size > 6 && query.isBlank()) {
                            Text(
                                if (showAll) "Show less" else "See all",
                                style = MaterialTheme.typography.labelLarge,
                                color = OmniTextDim,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showAll = !showAll }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (sessions.isEmpty()) {
                    item { EmptyHistory(onNewChat = { onOpenSession(null) }) }
                } else if (visible.isEmpty()) {
                    item {
                        Text(
                            "No chats match “${query.trim()}”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OmniTextFaint,
                            modifier = Modifier.padding(vertical = 18.dp),
                        )
                    }
                } else {
                    items(visible, key = { it.id }) { s ->
                        HistoryRow(
                            s,
                            onClick = { onOpenSession(s.id) },
                            onDelete = { vm.delete(s.id) },
                        )
                    }
                }
            }

            // Primary action, thumb-reachable.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 22.dp, bottom = 24.dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(AuroraBrush)
                    .clickable { onOpenSession(null) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, "New chat", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Outlined quick-action card with an ↗ arrow, per the reference. */
@Composable
private fun ActionCard(title: String, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 140.dp, height = 146.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, OmniText.copy(alpha = 0.16f), RoundedCornerShape(26.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = OmniText,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ArrowOutward, null,
            tint = OmniText,
            modifier = Modifier.align(Alignment.End).size(22.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(s: SessionEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // Mostly violet, with the occasional ember/rose — like the reference history list.
    val palette = remember(s.id) {
        when (abs(s.id.hashCode()) % 6) {
            4 -> OrbPalette.Ember
            5 -> OrbPalette.Rose
            else -> OrbPalette.Violet
        }
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(vertical = 13.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GradientOrb(size = 42.dp, palette = palette)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.title.ifBlank { "New chat" },
                    color = OmniText, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    relativeTime(s.updatedAt),
                    color = OmniTextFaint, style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight, null,
                tint = OmniTextFaint, modifier = Modifier.size(22.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Delete chat") },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = OmniError) },
                onClick = { onDelete(); menu = false },
            )
        }
    }
}

@Composable
private fun EmptyHistory(onNewChat: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 }) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GradientOrb(size = 72.dp, glow = true)
            Spacer(Modifier.height(18.dp))
            Text("Nothing here yet", color = OmniText, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask a question, or let Omni drive your phone.",
                color = OmniTextFaint, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(22.dp))
            GradientPill("Start chatting", onClick = onNewChat)
        }
    }
}

/** "now" → "5m" → "2h" → "3d" → "Jul 12" */
private fun relativeTime(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    val m = d / 60_000
    return when {
        m < 1 -> "Just now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        m < 60 * 24 * 7 -> "${m / (60 * 24)}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(ts))
    }
}
