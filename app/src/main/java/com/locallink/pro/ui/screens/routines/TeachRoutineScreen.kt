package com.locallink.pro.ui.screens.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.locallink.pro.data.repository.TeachStepResult
import com.locallink.pro.service.pilot.GuidedTeachingSession
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Anything that stopped a step from running at all, as opposed to a step that ran and
     * failed — the latter lands in the step list. Kept separate because swallowing it is how
     * "tap Send and nothing whatsoever happens" happened.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun clearError() { _error.value = null }

    /** Start fresh, unless this same routine is already part-taught (returning to the screen). */
    fun beginIfNeeded(routineName: String) {
        if (session.name.value != routineName) session.start(routineName)
    }

    /** Hand one instruction to Omni; it performs it and reports back. */
    fun runStep(instruction: String) = viewModelScope.launch {
        if (instruction.isBlank()) return@launch
        val result = runCatching { chat.teachStep(instruction) }
            .getOrElse { TeachStepResult.Blocked(it.message ?: "That step failed unexpectedly.") }
        if (result is TeachStepResult.Blocked) _error.value = result.reason
    }

    fun undoLast() = session.undoLast()

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val saved = runCatching { chat.saveTaughtRoutine() }.getOrDefault(false)
        if (saved) onDone()
        else _error.value = "There's nothing to save yet — add a step that Omni completes first."
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
    val error by vm.error.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-checked on every resume: the service is switched off by Android after each reinstall,
    // and teaching silently does nothing without it.
    var automateOn by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        automateOn = com.locallink.pro.service.pilot.OmniAccessibilityService.instance != null
        onPauseOrDispose { }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Couldn't run that step") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }

    // Resume an in-flight session rather than wiping it: a step navigates through other apps,
    // and coming back must not discard what's been taught.
    LaunchedEffect(routineName) { vm.beginIfNeeded(routineName) }

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
                        singleLine = true,
                        label = { Text(if (steps.isEmpty()) "First step…" else "Next step…") },
                        placeholder = { Text("e.g. search for Lavazza Gusto Crema") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = { if (draft.isNotBlank()) { vm.runStep(draft); draft = "" } },
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    val canSend = !running && draft.isNotBlank()
                    // Clickable Box rather than an IconButton nested inside one: the inner
                    // button's own 48dp touch target got constrained by the wrapper and taps
                    // on the circle did nothing at all.
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (canSend) OmniText else OmniBorder)
                            // enabled flag, NOT a conditional .then(): swapping clickable in and
                            // out as the draft changes rebuilds the modifier chain and the
                            // pointer input can end up detached, so the circle stops responding.
                            .clickable(enabled = canSend) { vm.runStep(draft); draft = "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (running) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp), color = OmniTextDim, strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send, "Run step",
                                tint = Color.White, modifier = Modifier.size(20.dp),
                            )
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
                            onClick = { vm.save { onSaved() } },
                            colors = ButtonDefaults.buttonColors(containerColor = OmniText),
                        ) { Text("Save routine (${trace.size} actions)") }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
        if (!automateOn) {
            // Stated up front rather than after a step vanishes into nothing.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(OmniSurface)
                    .border(1.dp, OmniError, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Automate is switched off",
                        style = MaterialTheme.typography.titleSmall, color = OmniText,
                    )
                    Text(
                        "Omni performs each step itself, so it needs the accessibility service on.",
                        style = MaterialTheme.typography.bodySmall, color = OmniTextDim,
                    )
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Turn on") }
            }
        }
        if (steps.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
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
                Modifier.fillMaxSize(),
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
}
