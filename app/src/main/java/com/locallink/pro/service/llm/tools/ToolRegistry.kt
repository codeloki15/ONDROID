package com.locallink.pro.service.llm.tools

import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds all registered [ToolHandler]s and dispatches calls by name with schema-aware
 * safety. Never reflects a model-supplied name into code (allowlist dispatch only).
 */
@Singleton
class ToolRegistry @Inject constructor(
    handlers: Set<@JvmSuppressWildcards ToolHandler>,
) {
    private val byName: Map<String, ToolHandler> = handlers.associateBy { it.name }

    fun handlers(): List<ToolHandler> = byName.values.sortedBy { it.name }

    fun get(name: String): ToolHandler? = byName[name]

    fun requiresConfirmation(name: String): Boolean = byName[name]?.readOnly != true

    /** Execute a parsed call. Returns a result string; turns any failure into model-readable feedback. */
    suspend fun execute(call: ToolCall): String {
        val handler = byName[call.name]
            ?: return errorResult("No such tool '${call.name}'. Available: ${byName.keys.joinToString(", ")}")
        return try {
            handler.execute(call.arguments)
        } catch (e: Throwable) {
            Log.e("ToolRegistry", "Tool '${call.name}' failed", e)
            errorResult("Tool '${call.name}' failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun errorResult(msg: String): String =
        JSONObject().put("error", msg).toString()
}
