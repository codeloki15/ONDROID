package com.locallink.pro.service.llm.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreateNoteTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "create_note"

    override val description: String =
        "Create a note saved on-device. Appends a timestamped note (title + body) to a local " +
            "notes file and copies the note to the clipboard. Returns a confirmation message."

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "Short title for the note.")
            })
            put("body", JSONObject().apply {
                put("type", "string")
                put("description", "The main content of the note.")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("title")
            put("body")
        })
    }.toString()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String = withContext(Dispatchers.IO) {
        val title = args.optString("title", "").trim()
        val body = args.optString("body", "").trim()

        if (title.isEmpty() && body.isEmpty()) {
            return@withContext "Error: cannot create an empty note. Provide a 'title' and/or 'body'."
        }

        val timestamp = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }

        val safeTitle = if (title.isEmpty()) "(untitled)" else title
        val entry = buildString {
            append("=== ")
            append(timestamp)
            append(" ===\n")
            append("Title: ")
            append(safeTitle)
            append('\n')
            if (body.isNotEmpty()) {
                append(body)
                append('\n')
            }
            append('\n')
        }

        // Append to notes file in filesDir/notes.txt
        val savedPath: String = try {
            val notesFile = File(context.filesDir, "notes.txt")
            notesFile.appendText(entry)
            notesFile.absolutePath
        } catch (e: Exception) {
            return@withContext "Error: failed to save note to file: ${e.message ?: "unknown error"}"
        }

        // Copy to clipboard (must run on main thread)
        val clipboardResult: String = try {
            withContext(Dispatchers.Main) {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    val clipText = if (body.isEmpty()) safeTitle else "$safeTitle\n$body"
                    clipboard.setPrimaryClip(ClipData.newPlainText("Note", clipText))
                    "copied to clipboard"
                } else {
                    "clipboard unavailable"
                }
            }
        } catch (e: Exception) {
            "clipboard copy failed (${e.message ?: "unknown error"})"
        }

        "Note saved (\"$safeTitle\") at $timestamp to $savedPath and $clipboardResult."
    }
}
