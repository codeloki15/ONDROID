package com.locallink.pro.service.pilot

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records what the user does, so a routine can be TAUGHT rather than inferred.
 *
 * Routines were only ever learned as a side effect: the pilot did a task successfully and its
 * trace was saved. That works when the pilot can already do the thing — which is exactly the
 * case where you need it least. Demonstrating is the other direction: you do it once yourself,
 * and Omni keeps the steps.
 *
 * The output is [TraceStep], the same type [ExperienceReplayer] already replays, so a taught
 * routine and a learned one are indistinguishable downstream — same replay, same library, same
 * scheduling.
 *
 * A singleton, and deliberately NOT reached through the accessibility service's static instance:
 * teaching spans leaving the app and coming back, and a nullable static made the UI collect a
 * flow that could be swapped out mid-session, leaving the save prompt stuck on stale values.
 * One injected instance means the screen and the service always observe the same state.
 */
@Singleton
class RoutineRecorder @Inject constructor() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _steps = MutableStateFlow<List<TraceStep>>(emptyList())
    val steps: StateFlow<List<TraceStep>> = _steps.asStateFlow()

    /**
     * Name the user gave the routine before demonstrating.
     *
     * Held here, not in the screen: teaching means leaving the app, and Compose state doesn't
     * survive that. Anchoring the whole flow to the service is what lets the user come back to
     * a still-valid "save this recording" prompt.
     */
    private val _pendingName = MutableStateFlow("")
    val pendingName: StateFlow<String> = _pendingName.asStateFlow()

    /** Package the recording is currently "in", so app switches become launch_app steps. */
    private var currentPackage: String? = null

    /** Our own package — the user opening Omni to stop recording isn't part of the routine. */
    private var selfPackage: String = ""

    fun start(ownPackage: String, name: String) {
        selfPackage = ownPackage
        currentPackage = null
        _pendingName.value = name
        _steps.value = emptyList()
        _isRecording.value = true
    }

    /** Stop and hand back what was captured. */
    fun stop(): List<TraceStep> {
        _isRecording.value = false
        return _steps.value
    }

    /** Discard the recording entirely — nothing is kept and the prompt goes away. */
    fun cancel() {
        _isRecording.value = false
        _steps.value = emptyList()
        _pendingName.value = ""
    }

    fun removeAt(index: Int) {
        _steps.value = _steps.value.filterIndexed { i, _ -> i != index }
    }

    /**
     * Fold one accessibility event into the recording.
     *
     * Called for every event while recording, so it must stay cheap and must ignore anything
     * that isn't a deliberate user action.
     */
    fun onEvent(event: AccessibilityEvent) {
        if (!_isRecording.value) return
        val pkg = event.packageName?.toString().orEmpty()
        // Omni's own UI is the recording controls, never part of what's being taught.
        if (pkg == selfPackage) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg.isNotBlank() && pkg != currentPackage && looksLikeApp(pkg)) {
                    currentPackage = pkg
                    add(TraceStep(action = "launch_app", arg = pkg))
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source ?: return
                add(stepFrom("tap", node))
                node.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = event.source ?: return
                add(stepFrom("long_press", node))
                node.recycle()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val typed = event.text?.joinToString("")?.takeIf { it.isNotBlank() } ?: return
                val node = event.source
                val step = (node?.let { stepFrom("type", it) } ?: TraceStep("type"))
                    .copy(action = "type", arg = typed)
                node?.recycle()
                // Typing fires per keystroke. Replace the previous entry for the same field so a
                // ten-character word becomes one step with the final text, not ten steps.
                val last = _steps.value.lastOrNull()
                if (last?.action == "type" && sameTarget(last, step)) {
                    _steps.value = _steps.value.dropLast(1) + step
                } else {
                    add(step)
                }
            }
        }
    }

    private fun add(step: TraceStep) {
        // Guard against a runaway recording filling memory if the user forgets to stop.
        if (_steps.value.size >= MAX_STEPS) return
        _steps.value = _steps.value + step
    }

    private fun stepFrom(action: String, node: AccessibilityNodeInfo) = TraceStep(
        action = action,
        targetResId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
        targetText = node.text?.toString()?.takeIf { it.isNotBlank() },
        targetDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
        targetCls = node.className?.toString()?.takeIf { it.isNotBlank() },
    )

    private fun sameTarget(a: TraceStep, b: TraceStep): Boolean =
        a.targetResId == b.targetResId && a.targetCls == b.targetCls

    /** Filters out launchers and system UI, which are transitions rather than steps. */
    private fun looksLikeApp(pkg: String): Boolean =
        !pkg.startsWith("com.android.systemui") &&
            !pkg.contains("launcher", ignoreCase = true) &&
            !pkg.contains("inputmethod", ignoreCase = true)

    companion object {
        private const val MAX_STEPS = 200
    }
}
