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
