package com.locallink.pro.service.pilot

import kotlinx.coroutines.delay

/**
 * Deterministically replays a learned action trace: perceive → locate each step's target
 * by durable identifier (resource-id → exact text → content-desc, mobile-use style
 * fallback order) → execute. Any step whose target can't be found (after retries) aborts
 * the replay so the caller can fall back to the reasoning loop — replay never guesses.
 */
class ExperienceReplayer(
    private val actuator: PilotActuator,
    private val settleMs: Long = 700L,
    private val locateRetries: Int = 3,
    private val retryDelayMs: Long = 800L,
) {
    /** @return null on full success, else a human-readable abort reason. */
    suspend fun replay(steps: List<TraceStep>): String? {
        for ((i, step) in steps.withIndex()) {
            if (actuator.cancelled()) return "cancelled"
            val ok: Boolean = if (step.needsTarget) {
                val el = locate(step) ?: return "step ${i + 1}: target not on screen (${step.describeTarget()})"
                when (step.action) {
                    "tap" -> actuator.tap(el)
                    "long_press" -> actuator.longPress(el)
                    "double_tap" -> actuator.doubleTap(el)
                    "type" -> actuator.type(el, step.arg ?: "")
                    "clear" -> actuator.clear(el)
                    else -> false
                }
            } else {
                when (step.action) {
                    "swipe", "scroll" -> actuator.swipe(step.arg ?: "down")
                    "launch_app" -> actuator.launchApp(step.arg ?: return "step ${i + 1}: launch_app without app")
                    "back" -> actuator.back()
                    "home" -> actuator.home()
                    "recents" -> actuator.recents()
                    "notifications" -> actuator.notifications()
                    "quick_settings" -> actuator.quickSettings()
                    "wait" -> { delay(step.arg?.toLongOrNull()?.coerceIn(100, 10_000) ?: 800L); true }
                    else -> false
                }
            }
            if (!ok) return "step ${i + 1}: ${step.action} failed"
            delay(settleMs)
        }
        return null
    }

    /** Find the step's target on the current screen, retrying while the UI settles. */
    private suspend fun locate(step: TraceStep): PilotElement? {
        repeat(locateRetries) { attempt ->
            val elements = actuator.perceive()
            findTarget(step, elements)?.let { return it }
            if (attempt < locateRetries - 1) delay(retryDelayMs)
        }
        return null
    }

    companion object {
        /**
         * Locator fallback chain (deterministic, first hit wins):
         * exact resource-id → exact text → exact content-desc — each preferring an
         * element whose class also matches the recorded one.
         */
        fun findTarget(step: TraceStep, elements: List<PilotElement>): PilotElement? {
            fun best(candidates: List<PilotElement>): PilotElement? =
                candidates.firstOrNull { it.cls == step.targetCls } ?: candidates.firstOrNull()

            step.targetResId?.takeIf { it.isNotBlank() }?.let { res ->
                best(elements.filter { it.resId == res })?.let { return it }
            }
            step.targetText?.takeIf { it.isNotBlank() }?.let { txt ->
                best(elements.filter { it.text?.equals(txt, ignoreCase = true) == true })?.let { return it }
            }
            step.targetDesc?.takeIf { it.isNotBlank() }?.let { d ->
                best(elements.filter { it.desc?.equals(d, ignoreCase = true) == true })?.let { return it }
            }
            return null
        }
    }
}

internal fun TraceStep.describeTarget(): String =
    targetResId ?: targetText ?: targetDesc ?: targetCls ?: "?"
