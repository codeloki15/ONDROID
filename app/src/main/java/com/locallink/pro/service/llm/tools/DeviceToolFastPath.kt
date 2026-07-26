package com.locallink.pro.service.llm.tools

import android.util.Log
import com.locallink.pro.service.llm.OpenRouterClient
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves an Automate task with ONE on-device function call instead of driving the screen.
 *
 * The pilot is the right tool for genuinely visual work (posting on LinkedIn, replying inside
 * an app). It is the wrong tool for "set an alarm for 7am": that takes a planner pass, an app
 * launch, and a screenshot→reason→tap loop — tens of seconds, and it breaks on every OEM skin —
 * to do what [android.provider.AlarmClock] does with one intent, deterministically.
 *
 * So before planning, we ask the model once whether a single local function completes the task.
 * A tool call means we're done in one round trip; anything else falls through to the pilot
 * untouched. Because these tools are plain intents, this path also works when the accessibility
 * service is off — which is most of the time after a reinstall on ColorOS.
 */
@Singleton
class DeviceToolFastPath @Inject constructor(
    private val registry: ToolRegistry,
    private val openRouter: OpenRouterClient,
) {
    companion object {
        private const val TAG = "DeviceToolFast"

        /**
         * Tools this path may never auto-run.
         *
         * Everything else is safe to fire unattended: it either changes local device state
         * reversibly (alarm, timer, torch, volume) or opens a composer the user must confirm —
         * SendSms/SendEmail use ACTION_SENDTO and Dial uses ACTION_DIAL, so nothing leaves the
         * phone without a deliberate tap. `make_phone_call` is the exception: it uses ACTION_CALL
         * and would place a call with no confirmation, which this app deliberately never does.
         */
        private val NEVER_AUTO_RUN = setOf("make_phone_call")

        /**
         * Tools that only *navigate* somewhere — they open a surface but complete nothing.
         *
         * These are excluded because a successful call does not mean the task is done, and this
         * path reports completion. Observed live: "open LinkedIn and comment on the top post"
         * matched launch_app, which opened the app and reported success, so the commenting never
         * happened and the pilot never got its turn. The prompt alone did not prevent it — the
         * model reasonably considers "open LinkedIn" satisfied.
         *
         * Nothing is lost: the pilot performs each of these as a single native action, and it
         * keeps control afterwards to carry out the rest of the request.
         */
        private val NAVIGATION_ONLY = setOf("launch_app", "open_settings", "open_url", "web_search")

        /** Tools this path will not choose, for either reason. */
        private val EXCLUDED = NEVER_AUTO_RUN + NAVIGATION_ONLY

        private const val SYSTEM =
            "You route one phone-assistant request to a single on-device function.\n\n" +
            "Call a function ONLY when that one call fully completes the ENTIRE request.\n" +
            "Otherwise reply with a short plain-text sentence and NO function call — in " +
            "particular when the request needs operating another app's screen (posting, " +
            "browsing, reading or replying inside an app, or anything multi-step), or when " +
            "no available function fits.\n\n" +
            "A request with several parts is never satisfied by a function that does only the " +
            "first part. \"Open X and do Y\" is NOT complete when you have merely opened X — " +
            "make no function call for it.\n\n" +
            "Never invent argument values the user didn't give you. If a required argument is " +
            "missing or ambiguous, make no function call."
    }

    /** A task handled entirely by [toolName]; [reply] is the user-facing result. */
    data class Outcome(val reply: String, val toolName: String)

    /**
     * @return the outcome when one local function handled the whole task, or null to tell the
     *   caller to carry on with the planner/pilot as before.
     */
    suspend fun tryHandle(task: String): Outcome? {
        val tools = toolsArray()
        if (tools.length() == 0) return null

        val msg = openRouter.singleToolTurn(SYSTEM, task, tools) ?: return null
        val calls = msg.optJSONArray("tool_calls")
        if (calls == null || calls.length() == 0) return null   // model declined → let the pilot run

        val fn = calls.optJSONObject(0)?.optJSONObject("function") ?: return null
        val name = fn.optString("name")
        if (name.isBlank() || name in EXCLUDED) return null
        if (registry.get(name) == null) {
            Log.w(TAG, "model asked for unknown tool '$name' — falling through to the pilot")
            return null
        }

        val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
            .getOrDefault(JSONObject())
        Log.i(TAG, "fast path: $name($args)")

        // ToolRegistry.execute never throws — it converts failures into readable text.
        val result = registry.execute(ToolCall(name, args))
        if (looksLikeFailure(result)) {
            Log.w(TAG, "tool '$name' reported failure, falling through to the pilot: $result")
            return null
        }
        return Outcome(reply = humanReply(result), toolName = name)
    }

    /**
     * What the user reads. Prefers a tool's own `summary` over its machine payload.
     *
     * Tools return JSON because the chat tool-loop consumes it, but on this path there is no
     * second model turn to phrase it — the tool result IS the reply. So "turn the volume up"
     * answered with `{"ok":true,"stream":"music","percent":23,...}` and "what time zone am I in"
     * with a raw ISO-8601 dump. Same defect as PlanExecutor's bare "Done.": the work was right and
     * the presentation threw it away.
     *
     * A `summary` field rather than a phrasing round-trip, because a second LLM call would cost
     * more than the whole hop this path exists to save. Tools without one are unchanged.
     */
    private fun humanReply(result: String): String {
        if (!result.trimStart().startsWith("{")) return result
        val summary = runCatching { JSONObject(result).optString("summary") }.getOrNull()
        return summary?.takeIf { it.isNotBlank() } ?: result
    }

    /** Every registered tool the model is allowed to pick from, as OpenAI function schemas. */
    private fun toolsArray(): JSONArray {
        val arr = JSONArray()
        for (t in registry.handlers()) {
            if (t.name in EXCLUDED) continue
            val params = runCatching { JSONObject(t.parametersJson) }.getOrNull() ?: continue
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", params),
                ),
            )
        }
        return arr
    }

    /**
     * Tools report problems as prose ("Error: 'hour' must be…") or as {"error": …} from the
     * registry. Treat those as "not handled" so the pilot still gets its chance rather than the
     * user being told the task is done when it isn't.
     */
    private fun looksLikeFailure(result: String): Boolean {
        val t = result.trim()
        if (t.isEmpty()) return true
        if (t.startsWith("Error", ignoreCase = true)) return true
        if (t.startsWith("Cannot", ignoreCase = true)) return true
        return runCatching { JSONObject(t).has("error") }.getOrDefault(false)
    }
}
