package com.locallink.pro.service.llm

import com.locallink.pro.service.llm.tools.ToolHandler

/**
 * Builds the Qwen2.5 ChatML prompt string manually (MediaPipe LlmInference is plain
 * text-in/out, so we apply the chat template ourselves). Tools are declared in the
 * system turn using Qwen's verbatim Hermes wrapper; the model replies with
 * <tool_call>{json}</tool_call> blocks which ToolCallParser extracts.
 *
 * Pure functions, no Android deps — unit-testable.
 */
object QwenChatTemplate {

    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    const val EOS = IM_END

    private const val DEFAULT_SYSTEM =
        "You are Omni, a concise, helpful on-device assistant."

    /**
     * @param history prior turns as (role, text) where role in {"user","assistant"}.
     *   For tool turns, the orchestrator appends raw ChatML itself (see [appendToolResultTurn]).
     */
    fun buildPrompt(
        system: String?,
        history: List<Pair<String, String>>,
        userTurn: String,
        tools: List<ToolHandler> = emptyList(),
    ): String = buildString {
        append(IM_START).append("system\n")
        append(system?.ifBlank { DEFAULT_SYSTEM } ?: DEFAULT_SYSTEM)
        if (tools.isNotEmpty()) append(toolsBlock(tools))
        append(IM_END).append("\n")

        for ((role, text) in history) {
            val r = if (role == "assistant") "assistant" else "user"
            append(IM_START).append(r).append("\n").append(text).append(IM_END).append("\n")
        }

        append(IM_START).append("user\n").append(userTurn).append(IM_END).append("\n")
        append(IM_START).append("assistant\n")
    }

    /** The verbatim Qwen2.5 Hermes tool-declaration block, appended after the system text. */
    private fun toolsBlock(tools: List<ToolHandler>): String = buildString {
        append("\n\n# Tools\n\n")
        append("You may call one or more functions to assist with the user query.\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n")
        append("<tools>")
        for (t in tools) {
            append("\n")
            append("{\"type\": \"function\", \"function\": {\"name\": \"")
            append(t.name)
            append("\", \"description\": \"")
            append(t.description.replace("\"", "\\\""))
            append("\", \"parameters\": ")
            append(t.parametersJson)
            append("}}")
        }
        append("\n</tools>\n\n")
        append("For each function call, return a json object with function name and arguments ")
        append("within <tool_call></tool_call> XML tags:\n")
        append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
    }

    /**
     * Continuation prompt for the agent loop: the running transcript already ends with the
     * assistant's tool-call text; append the tool result(s) as a user turn with <tool_response>
     * blocks, then re-open the assistant turn.
     */
    fun appendToolResultTurn(transcriptEndingInAssistantCall: String, toolResults: List<String>): String =
        buildString {
            append(transcriptEndingInAssistantCall)
            append(IM_END).append("\n")
            append(IM_START).append("user")
            for (res in toolResults) {
                append("\n<tool_response>\n").append(res).append("\n</tool_response>")
            }
            append(IM_END).append("\n")
            append(IM_START).append("assistant\n")
        }

    /** Strip a trailing EOS / role markers the model may emit. */
    fun cleanOutput(raw: String): String =
        raw.replace(EOS, "").substringBefore(IM_START).trim()
}
