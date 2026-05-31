package com.locallink.pro.service.llm.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class MakePhoneCallTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "make_phone_call"

    override val description: String =
        "Immediately place a real phone call to the given number using the device's phone app. " +
            "This dials and connects the call automatically without further user action, so use it " +
            "only when the user clearly wants to call right now. Requires the CALL_PHONE permission. " +
            "To merely open the dialer pre-filled (without calling), use dial_number instead."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "number": {
              "type": "string",
              "description": "The phone number to call, e.g. \"+14155552671\" or \"5551234\". May include +, digits, spaces, dashes, parentheses."
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

        // Keep only characters that are valid in a tel: URI; strip anything else.
        val sanitized = raw.filter { it.isDigit() || it in "+*#" }
        if (sanitized.isEmpty()) {
            return "Error: '$raw' does not contain any dialable digits."
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return "Cannot place the call: the Phone (CALL_PHONE) permission is not granted. " +
                "Please grant Phone permission in the app's settings and try again. " +
                "Alternatively, use dial_number to open the dialer without placing the call."
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitized"))
            context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Placing a call to $sanitized."
        } catch (e: SecurityException) {
            "Cannot place the call: the Phone (CALL_PHONE) permission is not granted. " +
                "Please grant Phone permission in the app's settings and try again."
        } catch (e: Exception) {
            "Error: could not place a call to '$sanitized': ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
