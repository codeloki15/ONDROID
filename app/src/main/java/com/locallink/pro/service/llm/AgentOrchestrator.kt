package com.locallink.pro.service.llm

import android.util.Log
import com.locallink.pro.service.llm.tools.ParseResult
import com.locallink.pro.service.llm.tools.ToolCall
import com.locallink.pro.service.llm.tools.ToolCallParser
import com.locallink.pro.service.llm.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the chat + tool loop: build Qwen ChatML prompt with tool declarations → generate →
 * parse for <tool_call> → execute (auto for read-only, confirm for mutating) → feed result
 * back → generate again, until the model replies with plain text or the hop cap is hit.
 */
@Singleton
class AgentOrchestrator @Inject constructor(
    private val engine: LlmEngine,
    private val registry: ToolRegistry,
    private val fgRouter: FgRouterEngine,
    private val toolRouter: ToolRouter,
) {
    companion object {
        private const val TAG = "AgentOrchestrator"
        private const val MAX_HOPS = 4
        private const val TOOL_TEMPERATURE = 0.2f
        private const val CHAT_TEMPERATURE = 0.8f

        // A small on-device model (Qwen-1.5B) can't absorb all 23 tool schemas — the prompt
        // blows its context (~3.8k tokens) and it returns empty ("no response"). So we only
        // attach tools when the message actually looks like a device action; plain chat runs
        // a tiny tool-less prompt. (FunctionGemma will later handle the tool phase properly.)
        private val TOOL_HINTS = Regex(
            "\\b(timer|alarm|remind|reminder|flashlight|torch|volume|brightness|" +
                "call|dial|phone|text|sms|message|email|mail|" +
                "contact|calendar|event|meeting|note|clipboard|copy|" +
                "battery|wifi|bluetooth|settings|open |launch |install|uninstall|" +
                "search|google|weather|map|navigate|directions|" +
                "play |pause|volume|brightness|screenshot|" +
                "schedule|set a|set an|set the|turn on|turn off|create )\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Heuristic: does this turn plausibly need a device tool? Errs toward plain chat. */
        fun mightNeedTools(userText: String): Boolean = TOOL_HINTS.containsMatchIn(userText)

        // Fallback path (no FunctionGemma model) caps how many tool schemas reach Qwen-1.5B:
        // all 23 overflow it (promptLen ~13k → degenerate "sorry sorry / with with" loops).
        // A few keyword-matched tools fit its budget and let tool-calling actually work.
        private const val MAX_FALLBACK_TOOLS = 6

        /** Tools whose name/description share a word with the message, most-relevant first. */
        fun relevantTools(
            userText: String,
            all: List<com.locallink.pro.service.llm.tools.ToolHandler>,
        ): List<com.locallink.pro.service.llm.tools.ToolHandler> {
            val words = Regex("[a-z]{3,}").findAll(userText.lowercase()).map { it.value }.toSet()
            if (words.isEmpty()) return all.take(MAX_FALLBACK_TOOLS)
            val scored = all.map { t ->
                val hay = (t.name.replace('_', ' ') + " " + t.description).lowercase()
                t to words.count { hay.contains(it) }
            }
            val matched = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
            return (if (matched.isNotEmpty()) matched else all).take(MAX_FALLBACK_TOOLS)
        }

        // The mobile-actions FunctionGemma .litertlm is built with ekv1024 (1024-token KV cache).
        // All 26 verbose tool declarations are ~5200 tokens → "Input is too long" → empty output.
        // So we send FG only the few most-relevant tools (each declaration ~150-200 tokens).
        private const val MAX_FG_TOOLS = 4

        /** Relevance-filter a merged OpenAI tools[] JSONArray down to the few most-relevant. */
        fun relevantSchemas(userText: String, all: org.json.JSONArray): org.json.JSONArray {
            val words = Regex("[a-z]{3,}").findAll(userText.lowercase()).map { it.value }.toSet()
            val scored = ArrayList<Pair<org.json.JSONObject, Int>>(all.length())
            for (i in 0 until all.length()) {
                val t = all.optJSONObject(i) ?: continue
                val fn = t.optJSONObject("function") ?: continue
                val hay = (fn.optString("name").replace('_', ' ') + " " + fn.optString("description")).lowercase()
                scored += t to words.count { hay.contains(it) }
            }
            val ordered = scored.sortedByDescending { it.second }
            // Prefer keyword-matched; if none match, fall back to the first few (so FG still gets options).
            val chosen = (ordered.filter { it.second > 0 }.ifEmpty { ordered }).take(MAX_FG_TOOLS)
            return org.json.JSONArray().apply { chosen.forEach { put(it.first) } }
        }
    }

    /**
     * @param confirm invoked for non-read-only tool calls; return true to execute, false to decline.
     */
    fun run(
        history: List<Pair<String, String>>,
        userText: String,
        confirm: suspend (name: String, argsJson: String) -> Boolean,
    ): Flow<AgentEvent> = flow {
        // ── FunctionGemma-first routing (when its model is present) ──────────────
        // FG runs FIRST and decides tool-vs-chat. If it picks tools, we execute them and let
        // Qwen phrase the result. If no tool, Qwen just chats. Qwen NEVER carries tool schemas
        // here, so its small context isn't overflowed. (project_fg_router design.)
        if (fgRouter.isAvailable()) {
            // Merged tool list: local (snake_case) + Composio cloud (UPPERCASE) when enabled.
            // Cap to the few most-relevant — the ekv1024 FG build can't hold all 26 (~5200 tokens).
            val allSchemas = toolRouter.schemas()
            val schemas = relevantSchemas(userText, allSchemas)
            val decision = fgRouter.decide(userText, schemas)
            Log.d(TAG, "FG decision: ${decision::class.simpleName} (sent ${schemas.length()}/${allSchemas.length()} tools)")
            when (decision) {
                is FgToolParser.FgDecision.Calls -> {
                    val results = mutableListOf<Pair<String, String>>() // name → result
                    for (call in decision.calls) {
                        val id = UUID.randomUUID().toString()
                        val readOnly = !toolRouter.requiresConfirmation(call.name)
                        val argsJson = call.arguments.toString()
                        emit(AgentEvent.ToolCall(id, call.name, argsJson, readOnly))
                        val result = if (readOnly || confirm(call.name, argsJson)) {
                            toolRouter.execute(call.name, call.arguments)
                        } else {
                            JSONObject().put("error", "User declined to run ${call.name}").toString()
                        }
                        emit(AgentEvent.ToolResult(id, call.name, result, !result.contains("\"error\"")))
                        results += call.name to result
                    }
                    // Qwen phrases the tool result(s) for the user (no schemas in the prompt).
                    val summaryPrompt = QwenChatTemplate.buildToolSummaryPrompt(userText, results)
                    val raw = engine.generateRaw(summaryPrompt, CHAT_TEMPERATURE).toList().joinToString("")
                    val text = QwenChatTemplate.cleanOutput(raw).ifBlank { defaultToolReply(results) }
                    emit(AgentEvent.Token(text)); emit(AgentEvent.Final(text))
                    return@flow
                }
                is FgToolParser.FgDecision.NoTool -> {
                    // Plain chat: tiny tool-less prompt → reliable on small model.
                    val prompt = QwenChatTemplate.buildPrompt(null, history, userText, emptyList())
                    val raw = engine.generateRaw(prompt, CHAT_TEMPERATURE).toList().joinToString("")
                    val text = QwenChatTemplate.cleanOutput(raw)
                    emit(AgentEvent.Token(text)); emit(AgentEvent.Final(text))
                    return@flow
                }
            }
        }

        // ── Fallback (no FunctionGemma model): keyword gate + single-model tool loop ──
        // Cap the tool schemas to a relevant few so Qwen-1.5B isn't overflowed (all 23 → garbage).
        val needTools = mightNeedTools(userText)
        val tools = if (needTools) relevantTools(userText, registry.handlers()) else emptyList()
        var transcript = QwenChatTemplate.buildPrompt(
            system = null, history = history, userTurn = userText, tools = tools,
        )
        Log.d(TAG, "run(fallback): needTools=$needTools, ${tools.size} tools, promptLen=${transcript.length}")

        var hop = 0
        while (hop < MAX_HOPS) {
            hop++
            val temp = if (tools.isEmpty()) CHAT_TEMPERATURE else TOOL_TEMPERATURE
            val raw = engine.generateRaw(transcript, temp).toList().joinToString("")
            val cleaned = QwenChatTemplate.cleanOutput(raw)
            Log.d(TAG, "hop=$hop RAW OUTPUT: ${raw.take(500)}")

            when (val parsed = ToolCallParser.parse(raw)) {
                is ParseResult.Text -> {
                    val text = parsed.text.ifBlank { cleaned }
                    emit(AgentEvent.Token(text))
                    emit(AgentEvent.Final(text))
                    return@flow
                }
                is ParseResult.Calls -> {
                    val results = mutableListOf<String>()
                    for (call in parsed.calls) {
                        val id = UUID.randomUUID().toString()
                        val readOnly = !registry.requiresConfirmation(call.name)
                        val argsJson = call.arguments.toString()
                        emit(AgentEvent.ToolCall(id, call.name, argsJson, readOnly))

                        val result: String = if (readOnly || confirm(call.name, argsJson)) {
                            registry.execute(call)
                        } else {
                            JSONObject().put("error", "User declined to run ${call.name}").toString()
                        }
                        val success = !result.contains("\"error\"")
                        emit(AgentEvent.ToolResult(id, call.name, result, success))
                        results += result
                    }
                    transcript = QwenChatTemplate.appendToolResultTurn(transcript + cleaned, results)
                }
            }
        }

        Log.w(TAG, "Hit MAX_HOPS without a final text reply")
        emit(AgentEvent.Final("(Stopped after $MAX_HOPS tool steps. Try rephrasing.)"))
    }

    /** Fallback user-facing text if Qwen produces nothing after a tool ran. */
    private fun defaultToolReply(results: List<Pair<String, String>>): String =
        results.joinToString("\n") { (name, _) -> "Done: $name." }
}
