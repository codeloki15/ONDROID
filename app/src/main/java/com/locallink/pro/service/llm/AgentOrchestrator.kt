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
) {
    companion object {
        private const val TAG = "AgentOrchestrator"
        private const val MAX_HOPS = 4
        private const val TOOL_TEMPERATURE = 0.2f
        private const val CHAT_TEMPERATURE = 0.8f
    }

    /**
     * @param confirm invoked for non-read-only tool calls; return true to execute, false to decline.
     */
    fun run(
        history: List<Pair<String, String>>,
        userText: String,
        confirm: suspend (name: String, argsJson: String) -> Boolean,
    ): Flow<AgentEvent> = flow {
        val tools = registry.handlers()
        // Running transcript that grows as the loop adds assistant tool-calls + tool results.
        var transcript = QwenChatTemplate.buildPrompt(
            system = null, history = history, userTurn = userText, tools = tools,
        )

        var hop = 0
        while (hop < MAX_HOPS) {
            hop++
            // Lower temp while tools are available so tag/JSON output is deterministic.
            val temp = if (tools.isEmpty()) CHAT_TEMPERATURE else TOOL_TEMPERATURE
            val raw = engine.generateRaw(transcript, temp).toList().joinToString("")
            val cleaned = QwenChatTemplate.cleanOutput(raw)

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
                    // Append the model's tool-call turn + the tool results, then re-prompt.
                    transcript = QwenChatTemplate.appendToolResultTurn(transcript + cleaned, results)
                }
            }
        }

        Log.w(TAG, "Hit MAX_HOPS without a final text reply")
        emit(AgentEvent.Final("(Stopped after $MAX_HOPS tool steps. Try rephrasing.)"))
    }
}
