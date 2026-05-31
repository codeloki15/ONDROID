package com.locallink.pro.service.llm.tools

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSearchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "web_search"

    override val description: String =
        "Launch a web search in the device browser or search app for the given query."

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query text to look up on the web."
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return "Error: 'query' is required and must be a non-empty string."
        }

        // Primary attempt: ACTION_WEB_SEARCH
        val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (searchIntent.resolveActivity(context.packageManager) != null) {
            try {
                context.startActivity(searchIntent)
                return "Launched web search for: \"$query\"."
            } catch (e: ActivityNotFoundException) {
                // Fall through to browser fallback.
            } catch (e: Exception) {
                // Fall through to browser fallback.
            }
        }

        // Fallback: open Google search results in a browser via ACTION_VIEW.
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.google.com/search?q=$encoded"
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (viewIntent.resolveActivity(context.packageManager) == null) {
                return "Error: no app available to handle a web search or open a browser."
            }
            context.startActivity(viewIntent)
            "Launched web search for: \"$query\" in the browser."
        } catch (e: Exception) {
            "Error: failed to launch web search for \"$query\": ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
