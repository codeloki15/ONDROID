package com.locallink.pro.service.llm.tools

import org.json.JSONObject

/**
 * One callable on-device function the LLM can invoke.
 *
 * The model decides *which* handler + arguments from natural language; the app
 * executes [execute] against a real Android API and feeds the result string back
 * to the model. See docs/ON_DEVICE_FUNCTIONS.md.
 */
interface ToolHandler {
    /** Function name the model calls, e.g. "set_timer". snake_case. */
    val name: String

    /** One-line description shown to the model so it knows when to use this tool. */
    val description: String

    /**
     * OpenAI-style JSON-schema "parameters" object as a JSON string:
     * {"type":"object","properties":{...},"required":[...]}
     */
    val parametersJson: String

    /** true => safe to auto-run; false => requires explicit user confirmation. */
    val readOnly: Boolean

    /** Execute with parsed arguments. Returns a short result string (plain or JSON) fed back to the LLM. Must never throw. */
    suspend fun execute(args: JSONObject): String
}
