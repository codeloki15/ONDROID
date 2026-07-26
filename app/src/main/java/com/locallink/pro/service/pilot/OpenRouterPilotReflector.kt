package com.locallink.pro.service.pilot

import android.util.Log
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
        private const val TAG = "PilotReflect"
        /** Labels per screen. Enough to recognise where you are; far short of the full tree. */
        private const val MAX_LABELS = 24

        /**
         * Read a verdict out of whatever the model actually said.
         *
         * Only an explicit B intervenes — an empty answer, a refusal, org.json's literal "null"
         * for a JSON null, or a model that ignored the format all leave the run alone. Models
         * that reason out loud despite the instruction tend to close with their answer, so the
         * LAST standalone A or B wins: "this is not B, it is A" resolves to A, as it should.
         */
        internal fun verdictOf(content: String): Reflection {
            val said = content.trim()
            if (said.isEmpty() || said == "null") return Reflection.MATCHED
            val last = Regex("""\b[AB]\b""").findAll(said).lastOrNull()?.value
            return if (last == "B") Reflection.WRONG_PAGE else Reflection.MATCHED
        }
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
            // Room for a reasoning model to think and still answer.
            //
            // This was 4 — one letter is all the answer needs. On device that produced an empty
            // `content` on every single call: the default model reasons first, spent the whole
            // budget there, and stopped at finish_reason "length" before writing anything. The
            // safe default meant every verdict came back MATCHED, so reflection was paying for a
            // call that could never return B. A cap is not a cost for a model that answers in one
            // token; it is only a ceiling for one that thinks first.
            //
            // 300 still truncated on roughly one navigation in five, so each of those was a call
            // bought and thrown away. 700 is sized to stop that rather than to be tidy.
            .put("max_tokens", 700)
            // Keep that thinking short where the model supports the knob. OpenRouter drops it for
            // models that don't, so this is safe to send unconditionally.
            .put("reasoning", JSONObject().put("effort", "low"))
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} — treating as MATCHED")
                return Reflection.MATCHED
            }
            val choice = JSONObject(resp.body?.string().orEmpty())
                .optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.optString("content").orEmpty()
            val verdict = verdictOf(content)
            // A subsystem whose healthy outcome is silence is undebuggable: with no line here,
            // "reflection never fired" and "reflection fired and approved" look identical from
            // the outside. finish_reason is in the line because "length" is what exposed the
            // empty-content bug above; the landing labels are here because the first device run
            // gave the SAME navigation an A and then a B, and without them there is no way to
            // tell an unstable judge from two genuinely different screens.
            Log.d(TAG, "$verdict [${choice?.optString("finish_reason")}] " +
                "said \"${content.trim().take(24)}\" after: $actionNote " +
                "→ landed on: ${labels(after).replace("\n- ", " · ").removePrefix("- ").take(90)}")
            return verdict
        }
    }

    /** Distinct visible labels, which is what makes a screen recognisable in one line each. */
    private fun labels(elements: List<PilotElement>): String =
        elements.mapNotNull { e -> (e.text ?: e.desc)?.takeIf { it.isNotBlank() } }
            .distinct().take(MAX_LABELS).joinToString("\n") { "- $it" }
            .ifBlank { "(no labelled elements)" }
}
