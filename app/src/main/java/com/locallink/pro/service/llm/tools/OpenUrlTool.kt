package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenUrlTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "open_url"

    override val description: String =
        "Opens a URL in the device's default web browser. If the URL has no scheme (http/https), https:// is prepended automatically."

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The URL to open. May omit the scheme (e.g. \"example.com\"); https:// will be added if missing."
                }
            },
            "required": ["url"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val rawUrl = args.optString("url", "").trim()
        if (rawUrl.isEmpty()) {
            return "Error: no URL provided. Please supply a non-empty 'url' argument."
        }

        val normalizedUrl = if (hasScheme(rawUrl)) rawUrl else "https://$rawUrl"

        val uri: Uri = try {
            Uri.parse(normalizedUrl)
        } catch (e: Exception) {
            return "Error: could not parse URL '$normalizedUrl': ${e.message ?: "invalid URL"}"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (intent.resolveActivity(context.packageManager) == null) {
                return "Error: no app available to open '$normalizedUrl'. A web browser may not be installed."
            }
            context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Opened '$normalizedUrl' in the browser."
        } catch (e: Exception) {
            "Error: failed to open '$normalizedUrl': ${e.message ?: "unknown error"}"
        }
    }

    private fun hasScheme(url: String): Boolean {
        val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        return schemeRegex.containsMatchIn(url)
    }
}
