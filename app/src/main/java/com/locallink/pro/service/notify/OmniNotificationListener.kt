package com.locallink.pro.service.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.locallink.pro.data.db.NotificationRuleDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Notification-triggered Omni: matches incoming notifications against the user's
 * rules and reacts — reads them aloud or hands a task to the Automate agent.
 * Requires the system "notification access" special permission (granted in the
 * rules screen). Skips ongoing/silent/group-summary notifications and its own.
 */
@AndroidEntryPoint
class OmniNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "OmniNotify"
        private const val DEDUPE_WINDOW_MS = 60_000L
    }

    @Inject lateinit var rules: NotificationRuleDao
    @Inject lateinit var executor: TriggerExecutor

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val recent = HashMap<String, Long>() // dedupe: content hash → last handled

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return                                   // never react to ourselves
        if (sbn.isOngoing) return                                        // media/foreground services
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        // Debounce identical content (apps repost the same notification constantly).
        val key = "$pkg|$title|$text".hashCode().toString()
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val last = recent[key]
            if (last != null && now - last < DEDUPE_WINDOW_MS) return
            recent[key] = now
            if (recent.size > 64) recent.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
        }

        scope.launch {
            // Resolve the human app name BEFORE matching: users type "Gmail", but the
            // package is com.google.android.gm — package-substring alone can never match.
            val appLabel = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrDefault(pkg)

            val matched = runCatching { rules.enabled() }.getOrDefault(emptyList()).filter { r ->
                val appOk = r.appPackage.isBlank() ||
                    pkg.contains(r.appPackage, ignoreCase = true) ||
                    appLabel.contains(r.appPackage, ignoreCase = true)
                // "lokesh or codelokiyt", "otp, code" — any alternative may match.
                val textOk = r.matchText.isBlank() || r.matchText
                    .split(Regex("\\s+or\\s+|,|\\|", RegexOption.IGNORE_CASE))
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .any { alt ->
                        title.contains(alt, ignoreCase = true) || text.contains(alt, ignoreCase = true)
                    }
                appOk && textOk
            }
            if (matched.isEmpty()) return@launch
            Log.i(TAG, "notification from $appLabel matched ${matched.size} rule(s)")

            for (r in matched) {
                runCatching {
                    executor.execute(r, "$appLabel notification", Triple(appLabel, title, text))
                }.onFailure { Log.e(TAG, "trigger execution failed", it) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
