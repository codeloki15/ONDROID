package com.locallink.pro.service.llm.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class ReadContactsTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "read_contacts"

    override val description: String =
        "Resolve a contact name to phone number(s). Searches the device contacts for entries " +
            "whose display name contains the given name and returns up to 5 matches as a JSON " +
            "array of {name, number} objects."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "name": {
              "type": "string",
              "description": "Full or partial contact name to search for."
            }
          },
          "required": ["name"]
        }
    """.trimIndent()

    override val readOnly: Boolean = true

    override suspend fun execute(args: JSONObject): String {
        val name = args.optString("name", "").trim()
        if (name.isEmpty()) {
            return "Error: 'name' is required and must be a non-empty string."
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Error: permission android.permission.READ_CONTACTS not granted."
        }

        var cursor: Cursor? = null
        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            if (cursor == null) {
                return "Error: unable to query contacts (null cursor)."
            }

            val results = JSONArray()
            val seen = HashSet<String>()
            val nameIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val numberIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            while (cursor.moveToNext() && results.length() < 5) {
                val contactName = if (nameIndex >= 0) {
                    cursor.getString(nameIndex) ?: ""
                } else {
                    ""
                }
                val contactNumber = if (numberIndex >= 0) {
                    cursor.getString(numberIndex) ?: ""
                } else {
                    ""
                }

                if (contactNumber.isEmpty()) {
                    continue
                }

                val dedupeKey = "$contactName|$contactNumber"
                if (!seen.add(dedupeKey)) {
                    continue
                }

                val entry = JSONObject()
                entry.put("name", contactName)
                entry.put("number", contactNumber)
                results.put(entry)
            }

            if (results.length() == 0) {
                "No contacts found matching \"$name\"."
            } else {
                results.toString()
            }
        } catch (e: SecurityException) {
            "Error: permission android.permission.READ_CONTACTS not granted."
        } catch (e: Exception) {
            "Error reading contacts: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try {
                cursor?.close()
            } catch (_: Exception) {
            }
        }
    }
}
