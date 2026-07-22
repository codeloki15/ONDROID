package com.locallink.pro.service.llm

import com.locallink.pro.service.llm.tools.ToolCall
import org.json.JSONObject

/**
 * Parses FunctionGemma's tool-call output. FunctionGemma is a *router*: on each on-device
 * turn it runs first and either emits one or more function calls, or nothing (→ route to the
 * chat model). See the project_fg_router design.
 *
 * FunctionGemma's format (Pythonic, NOT JSON):
 *   <start_function_call>call:NAME{arg:<escape>value<escape>, n:<escape>5<escape>}<end_function_call>
 *
 * Values are wrapped in <escape>…<escape>; unwrapped tokens (numbers/bools) also appear.
 * This is defensive: a 270M model will produce minor format drift.
 */
object FgToolParser {

    sealed interface FgDecision {
        /** One or more tool calls to execute. */
        data class Calls(val calls: List<ToolCall>) : FgDecision
        /** No tool needed — hand the turn to the chat model. */
        data object NoTool : FgDecision
    }

    private val CALL_BLOCK = Regex(
        "<start_function_call>\\s*(?:call:)?\\s*([A-Za-z0-9_]+)\\s*(\\{.*?\\})\\s*<end_function_call>",
        RegexOption.DOT_MATCHES_ALL,
    )
    // Fallback when the closing tag is missing.
    private val CALL_OPEN = Regex(
        "<start_function_call>\\s*(?:call:)?\\s*([A-Za-z0-9_]+)\\s*(\\{.*)",
        RegexOption.DOT_MATCHES_ALL,
    )
    // One `key: <escape>value<escape>` or `key: value` pair inside the brace body.
    private val ARG = Regex(
        "([A-Za-z0-9_]+)\\s*[:=]\\s*(?:<escape>(.*?)<escape>|\"([^\"]*)\"|([^,}]+))",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parse(rawIn: String): FgDecision {
        val raw = rawIn.trim()
        if (raw.isBlank()) return FgDecision.NoTool

        val calls = mutableListOf<ToolCall>()
        CALL_BLOCK.findAll(raw).forEach { m ->
            toCall(m.groupValues[1], m.groupValues[2])?.let { calls += it }
        }
        if (calls.isEmpty()) {
            CALL_OPEN.find(raw)?.let { m ->
                val body = balancedBraces(m.groupValues[2]) ?: m.groupValues[2]
                toCall(m.groupValues[1], body)?.let { calls += it }
            }
        }
        return if (calls.isNotEmpty()) FgDecision.Calls(calls) else FgDecision.NoTool
    }

    private fun toCall(name: String, braceBody: String): ToolCall? {
        val n = name.trim()
        // Treat explicit no-op sentinels as "no tool".
        if (n.isBlank() || n.equals("none", true) || n.equals("no_tool", true) ||
            n.equals("chat", true) || n.equals("respond", true)
        ) return null

        val args = JSONObject()
        ARG.findAll(braceBody).forEach { m ->
            val key = m.groupValues[1].trim()
            val value = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4])
                .firstOrNull { it.isNotEmpty() }?.trim() ?: ""
            if (key.isNotEmpty()) args.put(key, coerce(value))
        }
        return ToolCall(n, args)
    }

    /** Best-effort scalar coercion so numbers/bools land as proper JSON types. */
    private fun coerce(v: String): Any = when {
        v.equals("true", true) -> true
        v.equals("false", true) -> false
        v.toLongOrNull() != null -> v.toLong()
        v.toDoubleOrNull() != null -> v.toDouble()
        else -> v
    }

    private fun balancedBraces(s: String): String? {
        val start = s.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }
}
