package com.locallink.pro.service.llm

import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool surface for the (cloud) model. This app calls **Composio cloud tools ONLY** (Gmail,
 * Slack, …) — the on-device device tools are intentionally not exposed or executed.
 * (User decision 2026-06-05: "it should only call composio tools nothing else".)
 *
 * The on-device tool code (ToolRegistry + handlers) remains in the repo but is no longer
 * routed through here. To re-enable local tools, restore the registry merge/dispatch.
 */
@Singleton
class ToolRouter @Inject constructor(
    private val composio: ComposioClient,
) {
    /** Tools advertised to the model: Composio only (empty if no Composio key set). */
    suspend fun schemas(): JSONArray {
        val merged = JSONArray()
        if (composio.isEnabled()) {
            val cloud = composio.toolSchemas()
            for (i in 0 until cloud.length()) merged.put(cloud.get(i))
        }
        return merged
    }

    /** All Composio (cloud) actions touch the user's accounts → always confirm. */
    suspend fun requiresConfirmation(name: String): Boolean = true

    /** Execute a Composio tool by name. Refuses anything that isn't a known Composio tool. */
    suspend fun execute(name: String, args: JSONObject): String {
        return if (composio.isEnabled() && composio.isComposioTool(name)) {
            composio.execute(name, args)
        } else {
            JSONObject().put("error", "No such tool '$name' (only Composio tools are enabled).").toString()
        }
    }
}
