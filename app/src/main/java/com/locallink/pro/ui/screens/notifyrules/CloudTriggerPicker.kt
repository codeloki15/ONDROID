package com.locallink.pro.ui.screens.notifyrules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.locallink.pro.service.llm.ComposioTriggerType
import com.locallink.pro.ui.theme.*

/**
 * Plain-English label for a cloud event.
 *
 * The API's `name` is written for people ("New Gmail Message"); the slug
 * ("GMAIL_NEW_GMAIL_MESSAGE") is not, and showing it made the picker unreadable. Fall back to
 * de-slugging only when a trigger has no name, and drop the app prefix and TRIGGER suffix that
 * every slug repeats — the app is already the section header.
 */
fun ComposioTriggerType.label(): String = name.ifBlank {
    slug.removePrefix(toolkit.uppercase() + "_")
        .removeSuffix("_TRIGGER")
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}
    // Composio's own names often end in "Trigger" ("New Gmail Message Received Trigger"), which
    // reads as noise in a list where every row is one.
    .removeSuffix(" Trigger")
    .removeSuffix(" trigger")
    .trim()

/**
 * Readable "App · Event" for a saved rule, derived from the slug alone.
 *
 * A saved rule stores only the slug, and the trigger catalogue isn't loaded when the list first
 * draws — so the list has to be able to render `GMAIL_NEW_GMAIL_MESSAGE` without it.
 */
fun cloudLabelOf(slug: String): String {
    if (slug.isBlank()) return "Any cloud event"
    val toolkit = slug.substringBefore('_').lowercase()
    val event = slug.removePrefix(slug.substringBefore('_') + "_")
        .removeSuffix("_TRIGGER")
        .split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
    return "${appLabelOf(toolkit)} · ${event.ifBlank { slug }}"
}

/** Human app name from a toolkit slug: "googlecalendar" -> "Google Calendar". */
fun appLabelOf(toolkit: String): String = when (toolkit.lowercase()) {
    "gmail" -> "Gmail"
    "googlecalendar" -> "Google Calendar"
    "googlesheets" -> "Google Sheets"
    "googletasks" -> "Google Tasks"
    "googlesuper" -> "Google Workspace"
    "linkedin" -> "LinkedIn"
    "notion" -> "Notion"
    else -> toolkit.replaceFirstChar { it.uppercase() }
}

/**
 * Choose a cloud event to trigger on.
 *
 * A flat alphabetical dropdown of 86 raw slugs is not a choice a person can make, so this
 * groups by app, labels each event in plain English with its own description, and filters as
 * you type — the long tail (Workspace alone exposes hundreds of obscure ones) is reachable by
 * search rather than scrolling.
 */
@Composable
fun CloudTriggerPicker(
    triggers: List<ComposioTriggerType>,
    onPick: (ComposioTriggerType) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val grouped = remember(triggers, query) {
        val q = query.trim()
        triggers
            .filter {
                q.isBlank() ||
                    it.label().contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true) ||
                    appLabelOf(it.toolkit).contains(q, ignoreCase = true)
            }
            // Apps with fewer, more meaningful events first; Workspace's long tail last.
            .groupBy { it.toolkit }
            .toList()
            .sortedBy { (_, list) -> list.size }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a cloud event") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("Search events…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (grouped.isEmpty()) {
                    Text(
                        if (triggers.isEmpty())
                            "No cloud events available. Connect an app in Settings → Connected apps first."
                        else "Nothing matches “$query”.",
                        style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        grouped.forEach { (toolkit, list) ->
                            item(key = "hdr-$toolkit") {
                                Text(
                                    appLabelOf(toolkit).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AuroraVioletHi,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(list, key = { it.slug }) { t ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onPick(t) }
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                ) {
                                    Text(
                                        t.label(),
                                        style = MaterialTheme.typography.bodyLarge, color = OmniText,
                                    )
                                    if (t.description.isNotBlank()) {
                                        Text(
                                            t.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OmniTextFaint,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = OmniSurface2,
        titleContentColor = OmniText,
    )
}
