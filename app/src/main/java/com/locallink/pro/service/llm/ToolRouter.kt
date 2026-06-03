package com.locallink.pro.service.llm

import com.locallink.pro.service.llm.tools.ToolCall
import com.locallink.pro.service.llm.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unifies on-device tools ([ToolRegistry], snake_case) with cloud Composio tools
 * (UPPERCASE slugs). Presents one merged OpenAI tool list to the LLM and dispatches each
 * call to the right executor by name. Composio tools are only included when a Composio key
 * is set; otherwise this is a thin pass-through to the local registry.
 */
@Singleton
class ToolRouter @Inject constructor(
    private val registry: ToolRegistry,
    private val composio: ComposioClient,
) {
    /** Merged OpenAI tools[] array: local tools + (if enabled) Composio tools. */
    suspend fun schemas(): JSONArray {
        val merged = JSONArray()
        val local = registry.openAiToolsArray()
        for (i in 0 until local.length()) merged.put(local.get(i))
        if (composio.isEnabled()) {
            val cloud = composio.toolSchemas()
            for (i in 0 until cloud.length()) merged.put(cloud.get(i))
        }
        return merged
    }

    /** A tool needs confirmation if it's local-and-mutating, OR any Composio (cloud) tool. */
    suspend fun requiresConfirmation(name: String): Boolean {
        val localHandler = registry.get(name)
        if (localHandler != null) return registry.requiresConfirmation(name)
        // Composio cloud actions touch the user's accounts → always confirm.
        return true
    }

    /** Execute by source: local registry, else Composio. */
    suspend fun execute(name: String, args: JSONObject): String {
        return if (registry.get(name) != null) {
            registry.execute(ToolCall(name, args))
        } else if (composio.isEnabled() && composio.isComposioTool(name)) {
            composio.execute(name, args)
        } else {
            JSONObject().put("error", "No such tool '$name'").toString()
        }
    }
}
