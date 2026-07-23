package com.locallink.pro.service.pilot

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recorded action of a successful pilot run. Element-targeted steps carry the
 * target's durable identifiers (resource-id / text / desc) captured at execution
 * time, so replay can re-locate the element on a fresh screen — element ids are
 * per-perception indexes and never survive across runs.
 */
data class TraceStep(
    val action: String,            // tap | long_press | double_tap | type | clear | swipe | scroll | launch_app | back | home | recents | notifications | quick_settings | wait
    val arg: String? = null,       // swipe direction / launch query / typed text / wait ms
    val targetResId: String? = null,
    val targetText: String? = null,
    val targetDesc: String? = null,
    val targetCls: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        arg?.let { put("arg", it) }
        targetResId?.let { put("resId", it) }
        targetText?.let { put("text", it) }
        targetDesc?.let { put("desc", it) }
        targetCls?.let { put("cls", it) }
    }

    /** True if this step needs an on-screen element to be located first. */
    val needsTarget: Boolean
        get() = action in setOf("tap", "long_press", "double_tap", "type", "clear")

    companion object {
        fun fromJson(o: JSONObject): TraceStep = TraceStep(
            action = o.getString("action"),
            arg = o.optString("arg").ifBlank { null },
            targetResId = o.optString("resId").ifBlank { null },
            targetText = o.optString("text").ifBlank { null },
            targetDesc = o.optString("desc").ifBlank { null },
            targetCls = o.optString("cls").ifBlank { null },
        )

        fun listToJson(steps: List<TraceStep>): String =
            JSONArray().apply { steps.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(json: String): List<TraceStep> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

/**
 * Parameterized routine templates. "Play Believer on YouTube" and "Play Shape of You on
 * YouTube" are the SAME routine with a different slot value: the text the pilot typed.
 * A trace whose typed text comes from the task is generalized — the typed arg becomes
 * "{q}", and the task words minus the typed words become the template's residual key.
 * A new task matches when it contains all residual words; its leftover words fill {q}.
 */
object ExperienceTemplates {
    const val SLOT = "{q}"
    private const val MIN_RESIDUAL = 2   // template must keep some fixed shape
    private const val MAX_QUERY = 8      // slot values are short (song, contact, app…)

    data class Template(val residualTokens: List<String>, val steps: List<TraceStep>)

    /** Strip run-specific context suffixes so keys stay stable across runs. */
    fun baseKey(task: String): String = task
        .substringBefore("\n[Progress so far:")
        .replace(Regex("\\s*\\[user said:.*?]\\s*$", RegexOption.DOT_MATCHES_ALL), "")
        .trim()

    /**
     * Try to generalize a successful trace. Uses the first type-step whose words all come
     * from the task; every type-step with that text becomes the {q} slot.
     * @return null when the trace has no task-derived typed text (not parameterizable).
     */
    fun generalize(task: String, steps: List<TraceStep>): Template? {
        val taskTokens = TaskNorm.normalize(task).split(' ').filter { it.isNotBlank() }
        if (taskTokens.size < MIN_RESIDUAL + 1) return null
        val slotArg = steps.firstOrNull { s ->
            s.action == "type" && !s.arg.isNullOrBlank() &&
                TaskNorm.normalize(s.arg).split(' ').filter { it.isNotBlank() }
                    .let { it.isNotEmpty() && it.size <= MAX_QUERY && it.all { t -> t in taskTokens } }
        }?.arg ?: return null
        val slotTokens = TaskNorm.normalize(slotArg).split(' ').filter { it.isNotBlank() }.toSet()
        val residual = taskTokens.filter { it !in slotTokens }.distinct()
        if (residual.size < MIN_RESIDUAL) return null
        val templated = steps.map { s ->
            if (s.action == "type" && s.arg != null &&
                TaskNorm.normalize(s.arg).split(' ').filter { it.isNotBlank() }.toSet() == slotTokens
            ) s.copy(arg = SLOT) else s
        }
        return Template(residual, templated)
    }

    /**
     * Unify a new task against a template's residual. @return the slot value (the task's
     * words that are not part of the template shape) or null if the shapes don't match.
     */
    fun unify(residualTokens: List<String>, task: String): String? {
        val tokens = TaskNorm.normalize(task).split(' ').filter { it.isNotBlank() }
        if (residualTokens.isEmpty() || tokens.isEmpty()) return null
        val tokenSet = tokens.toSet()
        if (!residualTokens.all { it in tokenSet }) return null
        val residualSet = residualTokens.toSet()
        val q = tokens.filter { it !in residualSet }
        if (q.isEmpty() || q.size > MAX_QUERY) return null
        return q.joinToString(" ")
    }

    /** Fill the {q} slot of templated steps with a concrete value. */
    fun instantiate(steps: List<TraceStep>, q: String): List<TraceStep> =
        steps.map { if (it.action == "type" && it.arg == SLOT) it.copy(arg = q) else it }
}

/** Task-text normalization + matching for experience lookup. */
object TaskNorm {
    /** Lowercase, strip punctuation, collapse whitespace — the experience match key. */
    fun normalize(task: String): String = task
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Same-task check for near-identical phrasings ("play lofi on youtube" vs
     * "play lofi on youtube please"). Deliberately strict — determinism over recall:
     * exact normalized match, or token-set Jaccard ≥ 0.9.
     */
    fun matches(a: String, b: String): Boolean {
        val na = normalize(a); val nb = normalize(b)
        if (na == nb) return true
        val ta = na.split(' ').filter { it.isNotBlank() }.toSet()
        val tb = nb.split(' ').filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val jaccard = ta.intersect(tb).size.toDouble() / ta.union(tb).size.toDouble()
        return jaccard >= 0.9
    }
}
