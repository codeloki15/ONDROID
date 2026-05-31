package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class SendSmsTool @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "send_sms"

    override val description: String =
        "Open the device's SMS app pre-filled with a recipient number and message body. " +
            "This does NOT send the message automatically; the user must tap send. " +
            "Use when the user wants to text/SMS someone."

    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("number", JSONObject().apply {
                put("type", "string")
                put("description", "Recipient phone number, e.g. +15551234567 or 5551234567.")
            })
            put("body", JSONObject().apply {
                put("type", "string")
                put("description", "The message text to pre-fill in the SMS app.")
            })
        })
        put("required", org.json.JSONArray().apply { put("number") })
    }.toString()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val number = args.optString("number", "").trim()
        val body = args.optString("body", "")

        if (number.isEmpty()) {
            return "Error: 'number' is required to compose an SMS."
        }

        return try {
            val sanitized = Uri.encode(number)
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$sanitized")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened SMS composer for $number${if (body.isNotEmpty()) " with a prefilled message" else ""}. The user must tap send to deliver it."
        } catch (e: Exception) {
            "Error: could not open SMS composer for $number (${e.message ?: e.javaClass.simpleName}). No SMS app may be available."
        }
    }
}
