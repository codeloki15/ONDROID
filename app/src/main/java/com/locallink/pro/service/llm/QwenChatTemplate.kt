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

    /**
     * The Qwen2.5 Hermes tool-declaration block. Each tool's JSON is MINIFIED (whitespace
     * stripped) and descriptions kept short — a 1.5B model with a bounded context can't
     * absorb 23 pretty-printed schemas, so compactness directly improves tool-calling.
     */
    private fun toolsBlock(tools: List<ToolHandler>): String = buildString {
        append("\n\n# Tools\n\n")
        append("You may call one or more functions to assist with the user query.\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n")
        append("<tools>")
        for (t in tools) {
            append("\n")
            append("{\"type\":\"function\",\"function\":{\"name\":\"")
            append(t.name)
            append("\",\"description\":\"")
            append(minify(t.description).replace("\"", "\\\""))
            append("\",\"parameters\":")
            append(minify(t.parametersJson))
            append("}}")
        }
        append("\n</tools>\n\n")
        append("For each function call, return a json object with function name and arguments ")
        append("within <tool_call></tool_call> XML tags:\n")
        append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
    }

    /** Collapse runs of whitespace/newlines (from pretty-printed schemas) into single spaces. */
    private fun minify(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

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

    /**
     * Prompt for the FunctionGemma-router path: a tool already ran (decided by FG); ask the
     * chat model to phrase the result(s) for the user in one short sentence. No tool schemas —
     * keeps the prompt tiny so the on-device model stays within budget.
     */
    fun buildToolSummaryPrompt(userTurn: String, results: List<Pair<String, String>>): String = buildString {
        append(IM_START).append("system\n")
        append("You are Omni. A device action was just performed for the user. Reply in one short, ")
        append("natural sentence confirming what happened. Do not mention JSON or function names.")
        append(IM_END).append("\n")
        append(IM_START).append("user\n").append(userTurn).append(IM_END).append("\n")
        append(IM_START).append("user\n")
        append("Action results:")
        for ((name, res) in results) append("\n- ").append(name).append(": ").append(res)
        append(IM_END).append("\n")
        append(IM_START).append("assistant\n")
    }

    /** Strip a trailing EOS / role markers the model may emit. */
    fun cleanOutput(raw: String): String =
        raw.replace(EOS, "").substringBefore(IM_START).trim()
}
