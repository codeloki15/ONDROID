package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent

/**
 * How far an automation has got, in the words a notification can show.
 *
 * Kept free of Android so the awkward parts — what counts as a step, when an estimate is honest
 * enough to display — can be tested. [AutomationNotifier] is the thin shell that draws it.
 *
 * Two shapes of run feed this. A planned run emits [AgentEvent.Plan] and then a
 * [AgentEvent.TodoStatus] per leg, which gives a real denominator. A pilot-direct run (voice, a
 * replayed routine) emits neither, so there is nothing to count against and the bar stays
 * indeterminate — but the tool calls still say what is happening right now, which is the part the
 * user actually asked for.
 */
class RunProgress(
    val task: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = now()
    private var total = 0                 // planned legs; 0 = we don't know yet
    private var completed = 0
    private var stepStartedAt = startedAt
    private val stepMillis = ArrayList<Long>()
    private var currentStep: String? = null
    private var currentAction: String? = null

    /** Runs waiting behind this one on the single screen. */
    var queued: Int = 0

    fun onEvent(e: AgentEvent) {
        when (e) {
            is AgentEvent.Plan -> {
                total = e.todos.size
                currentStep = e.todos.firstOrNull()?.text
            }
            is AgentEvent.TodoStatus -> if (e.done) {
                completed = maxOf(completed, e.index + 1)
                stepMillis.add(now() - stepStartedAt)
                stepStartedAt = now()
                currentAction = null
            } else {
                currentStep = e.text
            }
            // Pilot-direct runs have no plan; the actions are the only sign of life.
            is AgentEvent.ToolCall -> currentAction = humanize(e.name)
            else -> {}
        }
    }

    fun elapsedSeconds(): Long = (now() - startedAt) / 1000

    /** 0-100, or null when there is no denominator and the bar must stay indeterminate. */
    fun percent(): Int? =
        if (total <= 0) null else (completed * 100 / total).coerceIn(0, 100)

    /**
     * Rough seconds remaining, or null when a number would be a guess dressed up as knowledge.
     *
     * Needs a denominator and at least two finished legs, because legs vary enormously — one is a
     * single intent, the next is a dozen screens — and a single sample would swing the estimate
     * wildly. The median rather than the mean, for the same reason.
     */
    fun etaSeconds(): Long? {
        if (total <= 0 || stepMillis.size < 2) return null
        val remaining = total - completed
        if (remaining <= 0) return null
        val sorted = stepMillis.sorted()
        val median = sorted[sorted.size / 2]
        return (median * remaining) / 1000
    }

    /** The one line that answers "what is it doing right now?". */
    fun detail(): String {
        val where = when {
            total > 0 -> "Step ${minOf(completed + 1, total)} of $total"
            else -> "Working"
        }
        val what = currentStep?.trim()?.takeIf { it.isNotBlank() }?.let { shorten(it) }
        val doing = currentAction
        return listOfNotNull(where, what, doing).joinToString(" · ")
    }

    /** Elapsed, an estimate when one is warranted, and anything waiting its turn. */
    fun status(): String = buildString {
        append(clock(elapsedSeconds()))
        etaSeconds()?.let { append(" · about ${clock(it)} left") }
        if (queued > 0) append(" · $queued waiting")
    }

    private fun shorten(s: String): String =
        if (s.length <= 48) s else s.take(45).trimEnd() + "…"

    private fun clock(seconds: Long): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

    private fun humanize(tool: String): String = when (tool) {
        "launch_app" -> "opening an app"
        "press_enter" -> "submitting"
        "find" -> "looking for something on screen"
        "replay_routine" -> "replaying a learned routine"
        "reflect" -> "double-checking where it landed"
        "recover" -> "getting unstuck"
        else -> tool.replace('_', ' ')
    }
}
