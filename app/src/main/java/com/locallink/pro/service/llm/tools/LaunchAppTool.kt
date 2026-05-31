package com.locallink.pro.service.llm.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchAppTool @Inject constructor(
    @ApplicationContext private val context: Context
) : ToolHandler {

    override val name: String = "launch_app"

    override val description: String =
        "Launch an installed app by name (fuzzy/partial match, case-insensitive). " +
            "Provide the app's display name (e.g. 'maps', 'calculator', 'chrome')."

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "app": {
              "type": "string",
              "description": "The name (or partial name) of the app to launch, e.g. 'Calculator' or 'maps'."
            }
          },
          "required": ["app"]
        }
    """.trimIndent()

    override val readOnly: Boolean = false

    override suspend fun execute(args: JSONObject): String {
        val query = args.optString("app", "").trim()
        if (query.isEmpty()) {
            return "Error: 'app' name is required."
        }

        val pm = context.packageManager

        val installed: List<ApplicationInfo> = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            return "Error: failed to query installed applications: ${e.message ?: "unknown error"}"
        }

        if (installed.isEmpty()) {
            return "No installed apps were visible to query. On Android 11+ the system may hide " +
                "apps unless declared in the manifest <queries>. Could not launch \"$query\"."
        }

        val lowerQuery = query.lowercase()

        // Collect candidates that have a launch intent and whose label matches the query.
        var exactPackage: ApplicationInfo? = null
        var exactLabel: Pair<ApplicationInfo, String>? = null
        var bestContains: Pair<ApplicationInfo, String>? = null

        for (appInfo in installed) {
            // Skip apps that cannot be launched.
            val launchIntent = try {
                pm.getLaunchIntentForPackage(appInfo.packageName)
            } catch (e: Exception) {
                null
            } ?: continue

            val label = try {
                appInfo.loadLabel(pm)?.toString() ?: appInfo.packageName
            } catch (e: Exception) {
                appInfo.packageName
            }
            val lowerLabel = label.lowercase()

            when {
                lowerLabel == lowerQuery && exactLabel == null -> exactLabel = appInfo to label
                appInfo.packageName.equals(query, ignoreCase = true) && exactPackage == null ->
                    exactPackage = appInfo
                lowerLabel.contains(lowerQuery) && bestContains == null ->
                    bestContains = appInfo to label
            }
        }

        val match: Pair<ApplicationInfo, String>? = when {
            exactLabel != null -> exactLabel
            exactPackage != null -> {
                val info = exactPackage
                val lbl = try {
                    info.loadLabel(pm)?.toString() ?: info.packageName
                } catch (e: Exception) {
                    info.packageName
                }
                info to lbl
            }
            bestContains != null -> bestContains
            else -> null
        }

        if (match == null) {
            return "No match: no launchable installed app found matching \"$query\"."
        }

        val (appInfo, label) = match

        val launchIntent = try {
            pm.getLaunchIntentForPackage(appInfo.packageName)
        } catch (e: Exception) {
            null
        }

        if (launchIntent == null) {
            return "Found \"$label\" (${appInfo.packageName}) but it has no launchable activity."
        }

        return try {
            context.startActivity(launchIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            "Launched \"$label\" (${appInfo.packageName})."
        } catch (e: Exception) {
            "Error: failed to launch \"$label\" (${appInfo.packageName}): ${e.message ?: "unknown error"}"
        }
    }
}
