package com.locallink.pro.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.SessionDao
import com.locallink.pro.data.db.TriggerRunDao
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** How a piece of activity was set in motion. */
enum class ActivityKind(val label: String) {
    /** Ran because it was scheduled — a daily trigger or a scheduled routine. */
    PLANNED("Planned"),

    /** Ran because something happened — a notification matched a rule. */
    EVENT("Event"),

    /** The user asked for it directly, by voice or by typing. */
    ADHOC("Ad-hoc"),
}

/** One row of the feed, whatever its origin. */
data class ActivityItem(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val detail: String,
    val at: Long,
    /** null = no outcome recorded (an ordinary conversation); true/false = succeeded/failed. */
    val success: Boolean?,
    /** Chat session to open, when this row came from one. */
    val sessionId: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    sessions: SessionDao,
    runs: TriggerRunDao,
) : ViewModel() {

    val items: StateFlow<List<ActivityItem>> =
        combine(sessions.observeActivity(), runs.observeRecent()) { activity, triggerRuns ->
            val fromTriggers = triggerRuns.map { r ->
                ActivityItem(
                    id = "run-${r.id}",
                    // Time triggers describe themselves as "Daily HH:MM → …"; everything else
                    // in this table was set off by a notification.
                    kind = if (r.description.startsWith("Daily")) ActivityKind.PLANNED
                    else ActivityKind.EVENT,
                    title = r.description,
                    detail = r.detail,
                    at = r.startedAt,
                    success = when (r.status) {
                        "success" -> true
                        "failed" -> false
                        else -> null
                    },
                )
            }
            val fromSessions = activity.map { s ->
                ActivityItem(
                    id = "session-${s.id}",
                    kind = ActivityKind.ADHOC,
                    title = s.title.ifBlank { "Conversation" },
                    detail = buildString {
                        append(if (s.voice) "Voice" else "Typed")
                        if (s.agent) append(" · automated the phone")
                    },
                    at = s.updatedAt,
                    success = null,
                    sessionId = s.id,
                )
            }
            (fromTriggers + fromSessions).sortedByDescending { it.at }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * Everything Omni has done, in one place.
 *
 * Work started outside the app — by voice, by a notification rule, by a schedule — used to leave
 * no trace the user could find: trigger runs lived on their own screen and a voice-invoked run
 * became an unlabelled row in the chat list. This merges both sources and says, for each one,
 * what ran, what set it off, and how it finished.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
) {
    val all by vm.items.collectAsState()
    var filter by remember { mutableStateOf<ActivityKind?>(null) }
    val shown = remember(all, filter) { all.filter { filter == null || it.kind == filter } }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniBg, titleContentColor = OmniText,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(null, filter, "All") { filter = it }
                ActivityKind.entries.forEach { k ->
                    FilterChip(k, filter, k.label) { filter = it }
                }
            }

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (all.isEmpty())
                            "Nothing yet. Anything Omni does — asked out loud, triggered by a " +
                                "notification, or run on a schedule — shows up here."
                        else "Nothing in this category yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OmniTextDim,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { item ->
                        ActivityRow(item) { item.sessionId?.let(onOpenSession) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    kind: ActivityKind?,
    selected: ActivityKind?,
    label: String,
    onSelect: (ActivityKind?) -> Unit,
) {
    val on = kind == selected
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) Color.White else OmniTextDim,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) OmniText else OmniSurface)
            .border(1.dp, OmniBorder, RoundedCornerShape(50))
            .clickable { onSelect(kind) }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun ActivityRow(item: ActivityItem, onClick: () -> Unit) {
    val accent = when (item.kind) {
        ActivityKind.PLANNED -> AuroraViolet
        ActivityKind.EVENT -> AuroraPeach
        ActivityKind.ADHOC -> AuroraPink
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OmniSurface)
            .border(1.dp, OmniBorder, RoundedCornerShape(16.dp))
            .clickable(enabled = item.sessionId != null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall, color = OmniText,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(item.at)))
                    if (item.detail.isNotBlank()) append(" · ${item.detail}")
                },
                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        item.success?.let { ok ->
            Spacer(Modifier.width(10.dp))
            Text(
                if (ok) "Success" else "Failed",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (ok) OmniSuccess else OmniError)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
