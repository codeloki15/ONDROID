package com.locallink.pro.service.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.locallink.pro.data.db.NotificationRuleDao
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.service.voice.VoiceService
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
    @Inject lateinit var voice: VoiceService
    @Inject lateinit var chat: ChatRepository

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
            val matched = runCatching { rules.enabled() }.getOrDefault(emptyList()).filter { r ->
                (r.appPackage.isBlank() || pkg.contains(r.appPackage, ignoreCase = true)) &&
                    (r.matchText.isBlank() ||
                        title.contains(r.matchText, ignoreCase = true) ||
                        text.contains(r.matchText, ignoreCase = true))
            }
            if (matched.isEmpty()) return@launch
            val appLabel = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrDefault(pkg)
            Log.i(TAG, "notification from $appLabel matched ${matched.size} rule(s)")

            for (r in matched) {
                when (r.action) {
                    "agent" -> {
                        val task = r.agentTask.ifBlank { "Handle this notification: {app}: {title} — {text}" }
                            .replace("{app}", appLabel).replace("{title}", title).replace("{text}", text)
                        runCatching { chat.runAgent(task) }
                            .onFailure { Log.e(TAG, "agent trigger failed", it) }
                    }
                    else -> { // "speak"
                        val line = buildString {
                            append("Notification from $appLabel. ")
                            if (title.isNotBlank()) append("$title. ")
                            if (text.isNotBlank()) append(text)
                        }.take(400)
                        runCatching { voice.speakWhenReady(line) }
                            .onFailure { Log.e(TAG, "speak trigger failed", it) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
