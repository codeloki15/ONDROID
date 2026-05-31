package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreateContactTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "create_contact"

    override val description: String =
        "Open the system contact editor pre-filled to create a new contact. " +
            "Requires a name; phone and email are optional. The user reviews and saves the contact."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Full display name of the contact"
            },
            "phone": {
              "type": "string",
              "description": "Phone number for the contact (optional)"
            },
            "email": {
              "type": "string",
              "description": "Email address for the contact (optional)"
            }
          },
          "required": ["name"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val name = args.optString("name").trim()
        val phone = args.optString("phone").trim()
        val email = args.optString("email").trim()

        if (name.isEmpty() && phone.isEmpty() && email.isEmpty()) {
            return "Error: provide at least a name, phone, or email to create a contact."
        }

        return try {
            val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                if (name.isNotEmpty()) {
                    putExtra(ContactsContract.Intents.Insert.NAME, name)
                }
                if (phone.isNotEmpty()) {
                    putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                }
                if (email.isNotEmpty()) {
                    putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return "Error: no app available to create contacts on this device."
            }

            context.startActivity(intent)

            val details = buildList {
                if (name.isNotEmpty()) add("name=$name")
                if (phone.isNotEmpty()) add("phone=$phone")
                if (email.isNotEmpty()) add("email=$email")
            }.joinToString(", ")

            "Opened the contact editor to create a new contact ($details). " +
                "The user must confirm and save it."
        } catch (e: Exception) {
            "Error: failed to open the contact editor (${e.message ?: e.javaClass.simpleName})."
        }
    }
}
