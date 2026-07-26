package com.locallink.pro.service.pilot

import com.locallink.pro.data.local.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Text-only second opinion on where a step landed.
 *
 * Deliberately not a copy of the reasoner: no screenshot, no tool schema, a handful of screen
 * labels and a one-letter answer. The action call carries a downscaled JPEG and the whole element
 * list, so sending those again would double the cost of every navigation — the entire point of
 * this class is that a wrong page is legible from the text alone.
 *
 * It answers A or B only. "Nothing happened" is [Reflection.NO_CHANGE] and the controller already
 * knows it for free by comparing screen signatures, so offering it here would just invite a model
 * call to re-derive something mechanical.
 */
class OpenRouterPilotReflector(
    private val settings: SettingsPreferences,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) : PilotReflector {
    companion object {
        /** Labels per screen. Enough to recognise where you are; far short of the full tree. */
        private const val MAX_LABELS = 24
    }

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun reflect(
        task: String, actionNote: String, before: List<PilotElement>, after: List<PilotElement>,
    ): Reflection {
        val key = settings.loadOpenRouterApiKey()
        if (key.isBlank()) return Reflection.MATCHED
        val model = settings.loadOpenRouterModel()

        val prompt = buildString {
            append("You are checking one step of an Android automation.\n\n")
            append("Task: $task\n")
            append("Action just performed: $actionNote\n\n")
            append("Screen BEFORE:\n${labels(before)}\n\n")
            append("Screen AFTER:\n${labels(after)}\n\n")
            append("Did that action land somewhere this task needs to go?\n")
            append("A — yes. A sensible place for this task, INCLUDING an intermediate screen on ")
            append("the way to it (a search box, a list of results, a loading screen, a form).\n")
            append("B — no. Somewhere the task cannot continue from: an ad or promo interstitial, ")
            append("a sign-in or paywall the task never asked for, an unrelated app or tab, a ")
            append("dialog that must be dismissed first.\n\n")
            append("Reply with the single letter A or B and nothing else. ")
            append("If you are not sure, reply A.")
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0)
            .put("max_tokens", 4)
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Reflection.MATCHED
            val content = JSONObject(resp.body?.string().orEmpty())
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty()
            // Only an explicit B intervenes. Anything else — A, empty, a refusal, a model that
            // decided to explain itself — leaves the run alone.
            return if (content.trim().startsWith("B", ignoreCase = true)) Reflection.WRONG_PAGE
            else Reflection.MATCHED
        }
    }

    /** Distinct visible labels, which is what makes a screen recognisable in one line each. */
    private fun labels(elements: List<PilotElement>): String =
        elements.mapNotNull { e -> (e.text ?: e.desc)?.takeIf { it.isNotBlank() } }
            .distinct().take(MAX_LABELS).joinToString("\n") { "- $it" }
            .ifBlank { "(no labelled elements)" }
}
