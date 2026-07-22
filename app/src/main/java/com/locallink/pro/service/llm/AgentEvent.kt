package com.locallink.pro.service.llm

/** Events emitted by [AgentOrchestrator] as it runs the chat + tool loop. */
sealed interface AgentEvent {
    /** A partial chunk of the assistant's natural-language reply (for live streaming UI). */
    data class Token(val text: String) : AgentEvent

    /** The model requested a tool. [readOnly] tools auto-run; others await confirmation. */
    data class ToolCall(val id: String, val name: String, val argsJson: String, val readOnly: Boolean) : AgentEvent

    /** A tool finished executing. */
    data class ToolResult(val id: String, val name: String, val result: String, val success: Boolean) : AgentEvent

    /** Composio needs the user to authorize an app — open this OAuth URL (Custom Tab). */
    data class OpenAuthUrl(val url: String) : AgentEvent

    /** The final natural-language reply; loop is done. */
    data class Final(val text: String) : AgentEvent
}
