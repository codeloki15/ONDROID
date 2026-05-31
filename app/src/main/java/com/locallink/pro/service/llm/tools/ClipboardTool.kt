package com.locallink.pro.service.llm.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "clipboard"

    override val description: String =
        "Read or write the device clipboard. Use action 'get' to read the current clipboard " +
            "text, or action 'set' with a 'text' value to copy text to the clipboard."

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "Either 'get' to read the clipboard or 'set' to write to it.")
                put("enum", org.json.JSONArray().apply {
                    put("get")
                    put("set")
                })
            })
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "The text to copy to the clipboard. Required when action is 'set'.")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("action")
        })
    }.toString()

    // 'set' mutates device state, so the whole tool requires confirmation.
    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val action = args.optString("action", "get").trim().lowercase()

        val clipboard = try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            null
        } ?: return "Error: clipboard service is unavailable on this device."

        return when (action) {
            "get" -> getClipboard(clipboard)
            "set" -> setClipboard(clipboard, args)
            else -> "Error: unknown action '$action'. Use 'get' or 'set'."
        }
    }

    private fun getClipboard(clipboard: ClipboardManager): String {
        return try {
            if (!clipboard.hasPrimaryClip()) {
                return "Clipboard is empty."
            }
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return "Clipboard is empty."
            }
            val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
            if (text.isNullOrEmpty()) {
                "Clipboard is empty."
            } else {
                "Clipboard contents: $text"
            }
        } catch (e: Exception) {
            "Error reading clipboard: ${e.message ?: "unknown error"}"
        }
    }

    private fun setClipboard(clipboard: ClipboardManager, args: JSONObject): String {
        val text = args.optString("text", "")
        if (text.isEmpty()) {
            return "Error: 'text' is required and must be non-empty when action is 'set'."
        }
        return try {
            val clip = ClipData.newPlainText("LocalLink Pro", text)
            clipboard.setPrimaryClip(clip)
            "Copied ${text.length} character(s) to the clipboard."
        } catch (e: Exception) {
            "Error writing to clipboard: ${e.message ?: "unknown error"}"
        }
    }
}
