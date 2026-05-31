package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class SendEmailTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "send_email"

    override val description: String =
        "Open the email composer pre-filled with recipient, subject, and body. " +
            "The user reviews and sends the email manually."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "to": {
              "type": "string",
              "description": "Recipient email address (optional)."
            },
            "subject": {
              "type": "string",
              "description": "Email subject line (optional)."
            },
            "body": {
              "type": "string",
              "description": "Email body text (optional)."
            }
          },
          "required": []
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        return try {
            val to = args.optString("to", "").trim()
            val subject = args.optString("subject", "").trim()
            val body = args.optString("body", "").trim()

            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                if (to.isNotEmpty()) {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                }
                if (subject.isNotEmpty()) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
                if (body.isNotEmpty()) {
                    putExtra(Intent.EXTRA_TEXT, body)
                }
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return "Failed to open email composer: no email app is available on this device."
            }

            context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })

            val target = if (to.isNotEmpty()) " to $to" else ""
            "Opened the email composer$target. The user must review and send the email."
        } catch (e: Exception) {
            "Failed to open email composer: ${e.message ?: "unknown error"}"
        }
    }
}
