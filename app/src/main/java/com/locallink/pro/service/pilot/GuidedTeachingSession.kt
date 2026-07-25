package com.locallink.pro.service.pilot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One taught step: what the user asked for, and what Omni reported back. */
data class TaughtStep(
    val instruction: String,
    val report: String,
    val succeeded: Boolean,
    val stepCount: Int,
)

/**
 * A routine taught one instruction at a time.
 *
 * The demonstration recorder watched the user's own taps, which sounds ideal and isn't: it
 * depends on every app emitting proper accessibility click events, and it records things that
 * can't be replayed — a taught Amazon routine failed on step 1 because "tap the home-screen
 * icon" had been captured as a tap on `com.android.launcher:id/icon`.
 *
 * Here Omni performs each step itself, so what gets saved is always in its own action
 * vocabulary and is replayable by construction. The user checks each step landed before adding
 * the next, which is also the natural moment to catch a misunderstanding.
 *
 * Singleton, and held outside any screen: a step can take a while and moves through other apps,
 * so the session has to outlive whatever Compose is doing.
 */
@Singleton
class GuidedTeachingSession @Inject constructor() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _steps = MutableStateFlow<List<TaughtStep>>(emptyList())
    val steps: StateFlow<List<TaughtStep>> = _steps.asStateFlow()

    /** Actions Omni actually performed, accumulated across steps — this is what gets saved. */
    private val _trace = MutableStateFlow<List<TraceStep>>(emptyList())
    val trace: StateFlow<List<TraceStep>> = _trace.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val isActive: Boolean get() = _name.value.isNotBlank()

    fun start(routineName: String) {
        _name.value = routineName.trim()
        _steps.value = emptyList()
        _trace.value = emptyList()
        _running.value = false
    }

    fun markRunning(running: Boolean) { _running.value = running }

    /** Record the outcome of one instruction, plus the actions it produced. */
    fun addStep(instruction: String, report: String, succeeded: Boolean, actions: List<TraceStep>) {
        _trace.value = _trace.value + actions
        _steps.value = _steps.value + TaughtStep(
            instruction = instruction,
            report = report,
            succeeded = succeeded,
            stepCount = actions.size,
        )
    }

    /**
     * Drop the last step and the actions it contributed, for when a step goes somewhere the user
     * didn't intend — otherwise a single wrong turn would mean starting the whole routine again.
     */
    fun undoLast() {
        val last = _steps.value.lastOrNull() ?: return
        _steps.value = _steps.value.dropLast(1)
        if (last.stepCount > 0) _trace.value = _trace.value.dropLast(last.stepCount)
    }

    fun clear() {
        _name.value = ""
        _steps.value = emptyList()
        _trace.value = emptyList()
        _running.value = false
    }
}
