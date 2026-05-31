package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class DialTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "dial_number"

    override val description: String =
        "Open the phone dialer pre-filled with a phone number. Does not place the call automatically; " +
            "the user must press the call button. No CALL_PHONE permission required."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "number": {
              "type": "string",
              "description": "The phone number to dial, e.g. \"+14155552671\" or \"5551234\". May include +, digits, spaces, dashes, parentheses."
            }
          },
          "required": ["number"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val raw = args.optString("number", "").trim()
        if (raw.isEmpty()) {
            return "Error: 'number' is required and must be a non-empty string."
        }

        // Keep characters that are valid in a tel: URI; strip anything else.
        val sanitized = raw.filter { it.isDigit() || it in "+*#" }
        if (sanitized.isEmpty()) {
            return "Error: '$raw' does not contain any dialable digits."
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized"))
            context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Opened the dialer for $sanitized. The user must press call to dial."
        } catch (e: Exception) {
            "Error: could not open the dialer for '$sanitized': ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
