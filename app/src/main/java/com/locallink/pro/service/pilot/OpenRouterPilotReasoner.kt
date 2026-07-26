package com.locallink.pro.service.pilot

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenRouterPilotReasoner(
    private val settings: SettingsPreferences,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
) : PilotReasoner {
    companion object {
        private const val TAG = "PilotTiming"
        /**
         * How many on-screen elements go into one prompt.
         *
         * Enough to cover a normal screen with room to spare; the tail of a very dense one is
         * reachable through find(text) instead of being pasted into every step.
         */
        private const val MAX_ELEMENTS = 80

        /**
         * What to say when the element list is capped — and pointedly NOT "scroll".
         *
         * The old wording was "N more elements are on this screen but not listed — scroll or use
         * find(text)". On a feed that is a trap with no exit. Scrolling a LinkedIn timeline
         * reveals fresh elements, so the count never drops, so the hint fires again, so it
         * scrolls again. Observed exactly that: 31 steps of nothing but Scroll, on screens
         * reporting 80/109 and 80/96, never once tapping a comment button.
         *
         * The list is capped, not cut off at the bottom, and saying so is the whole fix: the
         * missing elements are not "further down", so scrolling cannot be the way to reach them.
         */
        fun omissionNote(shown: Int, total: Int): String {
            if (total <= shown) return ""
            return "(This screen has $total interactive elements; only $shown are listed. The " +
                "list is CAPPED, not cut off at the bottom — scrolling will not reveal the rest " +
                "and on a feed it just loads more. To reach something specific by name, use " +
                "find(text).)\n"
        }
    }

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun nextAction(
        task: String, elements: List<PilotElement>, screenshot: ByteArray?, history: List<String>,
    ): Pair<String, String> {
        val key = settings.loadOpenRouterApiKey()
        val model = settings.loadOpenRouterModel()
        // Cap what goes into the prompt. The whole interactive tree was being sent on every
        // step, up to 60 steps — a dense screen costs real tokens and buries the few elements
        // that matter. Interactive things come first because those are what an action targets;
        // the count of what was dropped tells the model to scroll rather than assume it has
        // seen everything.
        val shown = elements.sortedByDescending { (it.clickable || it.editable) }.take(MAX_ELEMENTS)
        val omitted = elements.size - shown.size
        val elementsJson = JSONArray().apply { shown.forEach { put(it.toJson()) } }
        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put(
                "text",
                "Task: $task\n\nHistory:\n${history.joinToString("\n").ifBlank { "(none)" }}\n\n" +
                    "On-screen elements:\n$elementsJson\n" +
                        omissionNote(shown.size, elements.size) +
                        "\nChoose ONE action.",
            ))
            if (screenshot != null) {
                val b64 = Base64.encodeToString(screenshot, Base64.NO_WRAP)
                put(JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", PilotActionSchema.SYSTEM))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model).put("messages", messages)
            .put("tools", PilotActionSchema.toolsJson())
            .put("tool_choice", "required").put("temperature", 0.2)
            // Think hard. Choosing which of eighty elements advances the task is the one place in
            // this app where deliberation earns its keep, so slowness is not paid for out of
            // reasoning quality — it is paid for out of how fast the tokens come back.
            .put("reasoning", JSONObject().put("effort", "high"))
            // Which provider serves the request dominates everything else. Measured on one run,
            // same model, same prompt size:
            //   Wafer      78 out /  2.1s, 346 out / 6.1s  →  37-57 tok/s
            //   Fireworks 143 out / 16.0s, 154 out / 13.2s →   9-12 tok/s
            // A 4-5x spread, and `sort: throughput` alone still landed on the slow one twice —
            // ranking prefers but does not exclude. preferred_min_throughput is what actually
            // pushes past a provider that cannot keep up, and it matters MORE with high reasoning
            // effort, not less, because every reasoning token is an output token to be generated.
            //
            // A floor rather than a named allow-list: naming Wafer would be faster today and
            // broken the day it is busy. require_parameters is the guard that keeps a
            // speed-ranked pool from including someone who ignores `tools` — this whole loop is
            // function calling. Fallbacks stay on, so a thin pool degrades instead of failing.
            .put(
                "provider",
                JSONObject()
                    .put("sort", "throughput")
                    .put("require_parameters", true)
                    .put("preferred_min_throughput", JSONObject().put("p90", 40))
                    // A named preference on top of the statistical one, because the statistical
                    // one demonstrably did not bite: after adding sort+floor, three more runs
                    // still routed to Fireworks at 12 tok/s. A floor ranks on a provider's
                    // PUBLISHED p90, which is not a promise about this request.
                    //
                    // `order` with fallbacks left on, not `ignore`: this prefers the provider
                    // measured fastest for the configured model and quietly falls through when it
                    // is busy or does not serve that model at all. An exclusion list would be
                    // faster right up until the day it made the app unusable.
                    .put("order", org.json.JSONArray().put("Wafer")),
            )
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()
        val startedAt = SystemClock.uptimeMillis()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            // Where a step's wall-clock actually goes. The reasoner call dominates by so much
            // that trimming settle delays or element counts is rounding error next to it.
            // Token counts, not just wall-clock: they are how you tell "the provider is slow" from
            // "we asked the model to think for 800 tokens before answering". reasoning_tokens is
            // the one that settles it.
            val u = runCatching { JSONObject(text).optJSONObject("usage") }.getOrNull()
            val reasoning = u?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens")
            val served = runCatching { JSONObject(text).optString("provider") }.getOrNull()
            Log.d(TAG, "reason ${SystemClock.uptimeMillis() - startedAt}ms " +
                "elements=${shown.size}/${elements.size} shot=${screenshot?.size ?: 0}B " +
                "history=${history.size} model=$model via=$served " +
                "in=${u?.optInt("prompt_tokens")} out=${u?.optInt("completion_tokens")} " +
                "reasoning=$reasoning")
            if (!resp.isSuccessful) return "ask" to """{"question":"Cloud error ${resp.code}; retry?"}"""
            val msg = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return "ask" to """{"question":"No response; retry?"}"""
            val call = msg.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
                ?: return "done" to JSONObject().put("result", msg.optString("content")).toString()
            return call.optString("name") to call.optString("arguments", "{}")
        }
    }
}
