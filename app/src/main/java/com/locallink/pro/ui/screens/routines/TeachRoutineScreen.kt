package com.locallink.pro.ui.screens.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.pilot.GuidedTeachingSession
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TeachRoutineViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val session: GuidedTeachingSession,
) : ViewModel() {

    val name = session.name
    val steps = session.steps
    val running = session.running
    val trace = session.trace

    fun begin(routineName: String) = session.start(routineName)

    /** Hand one instruction to Omni; it performs it and reports back. */
    fun runStep(instruction: String) = viewModelScope.launch {
        if (instruction.isBlank()) return@launch
        runCatching { chat.teachStep(instruction) }
    }

    fun undoLast() = session.undoLast()

    fun save(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        onDone(runCatching { chat.saveTaughtRoutine() }.getOrDefault(false))
    }

    fun discard() = session.clear()
}

/**
 * Teach a routine by describing it a step at a time.
 *
 * Omni carries out each instruction as you add it, so you see the step land before adding the
 * next, and everything saved is an action Omni can perform itself. That's the difference from
 * demonstration recording, which captured whatever the user's own taps happened to look like —
 * including a home-screen icon tap that could never replay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeachRoutineScreen(
    routineName: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: TeachRoutineViewModel = hiltViewModel(),
) {
    val name by vm.name.collectAsState()
    val steps by vm.steps.collectAsState()
    val running by vm.running.collectAsState()
    val trace by vm.trace.collectAsState()
    var draft by remember { mutableStateOf("") }

    // Resume an in-flight session rather than wiping it: a step navigates through other apps,
    // and coming back must not discard what's been taught.
    LaunchedEffect(routineName) {
        if (name.isBlank()) vm.begin(routineName)
    }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text(name.ifBlank { routineName }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                },
                actions = {
                    if (steps.isNotEmpty() && !running) {
                        IconButton(onClick = vm::undoLast) {
                            Icon(Icons.Outlined.Undo, "Undo last step", tint = OmniTextDim)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniBg, titleContentColor = OmniText,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.background(OmniBg).navigationBarsPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = !running,
                        label = { Text(if (steps.isEmpty()) "First step…" else "Next step…") },
                        placeholder = { Text("e.g. search for Lavazza Gusto Crema") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (running || draft.isBlank()) OmniBorder else OmniText),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp), color = OmniTextDim, strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(
                                enabled = draft.isNotBlank(),
                                onClick = { vm.runStep(draft); draft = "" },
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Send, "Run step", tint = Color.White)
                            }
                        }
                    }
                }
                if (steps.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = { vm.discard(); onBack() }) { Text("Discard") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = trace.isNotEmpty() && !running,
                            onClick = { vm.save { ok -> if (ok) onSaved() } },
                            colors = ButtonDefaults.buttonColors(containerColor = OmniText),
                        ) { Text("Save routine (${trace.size} actions)") }
                    }
                }
            }
        },
    ) { pad ->
        if (steps.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Tell Omni the first step. It'll do it on your phone and report back, then " +
                        "you add the next one — the steps that work become the routine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OmniTextDim,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(steps) { i, s ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(OmniSurface)
                            .border(1.dp, OmniBorder, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(22.dp).clip(CircleShape)
                                    .background(if (s.succeeded) OmniSuccess else OmniError),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                s.instruction,
                                style = MaterialTheme.typography.titleSmall, color = OmniText,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.report,
                            style = MaterialTheme.typography.bodySmall, color = OmniTextDim,
                        )
                        if (s.stepCount > 0) {
                            Text(
                                "${s.stepCount} action${if (s.stepCount == 1) "" else "s"} recorded",
                                style = MaterialTheme.typography.labelSmall, color = OmniTextFaint,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
