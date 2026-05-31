package com.locallink.pro.service.llm.tools

import org.json.JSONObject

/** A single parsed tool call. */
data class ToolCall(val name: String, val arguments: JSONObject)

/** Result of parsing an assistant turn. */
sealed interface ParseResult {
    data class Text(val text: String) : ParseResult
    data class Calls(val calls: List<ToolCall>) : ParseResult
}

/**
 * Extracts Qwen Hermes-style <tool_call>{json}</tool_call> blocks from raw model output.
 * Defensive against the failure modes a 1.5B model produces on-device: missing close tag,
 * untagged JSON, markdown fences, malformed JSON.
 */
object ToolCallParser {

    private val TAGGED = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    // Fallback: open tag with no close — capture from '{' and brace-balance below.
    private val OPEN_ONLY = Regex("<tool_call>\\s*(\\{.*)", RegexOption.DOT_MATCHES_ALL)
    // Secondary (gated) pass: a bare {"name":...,"arguments":...} with no tags.
    private val BARE = Regex("\\{[^{}]*\"name\"\\s*:.*?\"arguments\"\\s*:.*?\\}", RegexOption.DOT_MATCHES_ALL)

    fun parse(rawIn: String): ParseResult {
        val raw = QwenCleanedOrSelf(rawIn)

        // Pass 1: strict tagged (supports parallel calls).
        val tagged = TAGGED.findAll(raw).mapNotNull { toCall(it.groupValues[1]) }.toList()
        if (tagged.isNotEmpty()) return ParseResult.Calls(tagged)

        // Pass 2: open tag, missing close → brace-balance scan.
        OPEN_ONLY.find(raw)?.let { m ->
            val obj = balancedObject(m.groupValues[1])
            if (obj != null) toCall(obj)?.let { return ParseResult.Calls(listOf(it)) }
        }

        // Pass 3 (gated): bare name/arguments object, no tags. Only if both keys present.
        if (raw.contains("\"name\"") && raw.contains("\"arguments\"")) {
            BARE.find(raw)?.let { m -> toCall(m.value)?.let { return ParseResult.Calls(listOf(it)) } }
        }

        return ParseResult.Text(raw.trim())
    }

    private fun QwenCleanedOrSelf(s: String): String =
        s.replace("<|im_end|>", "").substringBefore("<|im_start|>")

    /** Parse one JSON object string into a ToolCall, with light JSON healing. */
    private fun toCall(jsonStr: String): ToolCall? {
        val healed = heal(jsonStr)
        return try {
            val o = JSONObject(healed)
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            val args = o.optJSONObject("arguments") ?: JSONObject()
            ToolCall(name, args)
        } catch (_: Exception) {
            null
        }
    }

    /** Strip ```json fences, trailing commas, and Python-style literals. */
    private fun heal(s: String): String =
        s.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .replace(Regex(",\\s*([}\\]])"), "$1")
            .replace(Regex("\\bTrue\\b"), "true")
            .replace(Regex("\\bFalse\\b"), "false")
            .replace(Regex("\\bNone\\b"), "null")
            .trim()

    /** From a string starting with '{', return the substring up to the matching '}', else null. */
    private fun balancedObject(s: String): String? {
        var depth = 0
        var inStr = false
        var esc = false
        val start = s.indexOf('{')
        if (start < 0) return null
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
