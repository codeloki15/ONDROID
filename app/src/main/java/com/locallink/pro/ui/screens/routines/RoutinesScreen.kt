package com.locallink.pro.ui.screens.routines

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.data.db.ExperienceEntity
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.theme.*
import org.json.JSONArray

/**
 * The routine library: everything Omni has learned to do deterministically.
 * Rename, run now, schedule daily, or delete each learned routine.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    onBack: () -> Unit,
    onTeach: (String) -> Unit = {},
    vm: RoutinesViewModel = hiltViewModel(),
) {
    val routines by vm.routines.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.toast.collect { snackbar.showSnackbar(it) } }

    var renaming by remember { mutableStateOf<ExperienceEntity?>(null) }
    // Teaching runs in three beats: name it, demonstrate it, save it.
    var teaching by remember { mutableStateOf(false) }
    var teachName by remember { mutableStateOf("") }
    var needsA11y by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        containerColor = OmniBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Routines") },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { teaching = true },
                containerColor = OmniText,
                contentColor = androidx.compose.ui.graphics.Color.White,
            ) {
                Icon(Icons.Outlined.School, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Teach a routine")
            }
        },
    ) { pad ->
        if (routines.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GradientOrb(size = 64.dp, glow = true)
                Spacer(Modifier.height(16.dp))
                Text("No routines yet", style = MaterialTheme.typography.titleLarge, color = OmniText)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Every phone task Omni completes in \"Automate my phone\" is learned here " +
                        "and replayed instantly the next time you ask \u2014 or teach one " +
                        "yourself by showing Omni once.",
                    style = MaterialTheme.typography.bodyMedium, color = OmniTextDim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(routines, key = { it.id }) { r ->
                    RoutineCard(
                        r = r,
                        onRun = { vm.runNow(r) },
                        onRename = { renaming = r },
                        onSchedule = { h, m -> vm.schedule(r, h, m) },
                        onUnschedule = { vm.unschedule(r) },
                        onDelete = { vm.delete(r) },
                    )
                }
            }
        }
    }

    if (needsA11y) {
        AlertDialog(
            onDismissRequest = { needsA11y = false },
            title = { Text("Turn on Automate first") },
            text = {
                Text(
                    "Teaching works by watching what you do, which needs OmniPro's accessibility " +
                        "service switched on. Enable it, then try again.",
                    style = MaterialTheme.typography.bodyMedium, color = OmniTextDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needsA11y = false
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { needsA11y = false }) { Text("Not now") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }

    // 1. Name the routine. The name is also how it's invoked later ("run <name>"), so it's
    //    collected up front rather than after the fact.
    if (teaching) {
        AlertDialog(
            onDismissRequest = { teaching = false },
            title = { Text("Teach a routine") },
            text = {
                Column {
                    Text(
                        "Give it a name, then describe it one step at a time. Omni carries out " +
                            "each step and reports back, and the steps that work become the " +
                            "routine — ask for it or schedule it later.",
                        style = MaterialTheme.typography.bodyMedium, color = OmniTextDim,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = teachName,
                        onValueChange = { teachName = it },
                        singleLine = true,
                        label = { Text("Call it…") },
                        placeholder = { Text("e.g. play my morning playlist") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = teachName.isNotBlank(),
                    onClick = {
                        // Omni performs each step itself on the next screen, so teaching no
                        // longer depends on the accessibility service seeing the user's taps.
                        val name = teachName
                        teaching = false
                        teachName = ""
                        onTeach(name)
                    },
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { teaching = false }) { Text("Cancel") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }



    renaming?.let { r ->
        var text by remember(r.id) { mutableStateOf(r.displayName()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename routine") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.rename(r, text); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }
}

@Composable
private fun RoutineCard(
    r: ExperienceEntity,
    onRun: () -> Unit,
    onRename: () -> Unit,
    onSchedule: (Int, Int) -> Unit,
    onUnschedule: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val steps = remember(r.stepsJson) { runCatching { JSONArray(r.stepsJson).length() }.getOrDefault(0) }
    val scheduled = r.scheduleHour >= 0

    fun pickTime() {
        val h = if (scheduled) r.scheduleHour else 8
        val m = if (scheduled) r.scheduleMinute else 0
        TimePickerDialog(context, { _, hh, mm -> onSchedule(hh, mm) }, h, m, true).show()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(OmniSurface)
            .border(1.dp, if (scheduled) AuroraViolet.copy(alpha = 0.35f) else OmniBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.displayName(),
                    style = MaterialTheme.typography.titleSmall, color = OmniText,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append("$steps steps · replayed ${r.successCount}×")
                        if (r.slotResidual.isNotBlank()) append(" · adapts to what you ask")
                    },
                    style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                )
            }
            // Run now
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(InkPill).clickable(onClick = onRun),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PlayArrow, "Run now", tint = Color.White, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(6.dp))
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "More", tint = OmniTextDim)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(
                        text = { Text(if (scheduled) "Change time" else "Schedule daily…") },
                        onClick = { menu = false; pickTime() },
                    )
                    if (scheduled) DropdownMenuItem(
                        text = { Text("Remove schedule") },
                        onClick = { menu = false; onUnschedule() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = OmniError) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
        if (scheduled) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(PastelLavender.copy(alpha = 0.6f))
                    .clickable { pickTime() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Alarm, null, tint = OmniText, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Daily at %02d:%02d".format(r.scheduleHour, r.scheduleMinute),
                    style = MaterialTheme.typography.labelMedium, color = OmniText,
                )
            }
        }
    }


    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete routine?") },
            text = { Text("Omni will forget how to do “${r.displayName()}” and will figure it out from scratch next time.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = OmniError) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
            textContentColor = OmniTextDim,
        )
    }
}
