package com.locallink.pro.service.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.locallink.pro.R
import com.locallink.pro.data.db.NotificationRuleDao
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.llm.ComposioRealtimeClient
import com.locallink.pro.service.llm.ComposioTriggerEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the Composio realtime connection and runs the rules a cloud trigger fires.
 *
 * Foreground, deliberately. A trigger is only worth arming if it fires when the app is closed,
 * and a background socket on this class of device (ColorOS kills aggressively — the same reason
 * the accessibility service needs re-enabling after every reinstall) would be dropped within
 * minutes. The persistent notification is the honest cost of "it actually works"; the whole
 * service only runs when the user has turned Composio triggers on.
 *
 * Execution reuses [TriggerExecutor], so a cloud trigger and a notification trigger behave
 * identically from here on — same announce/agent actions, same history rows, same dashboard.
 */
@AndroidEntryPoint
class ComposioTriggerService : Service() {

    companion object {
        private const val TAG = "ComposioTriggerSvc"
        private const val CHANNEL_ID = "composio_triggers"
        private const val NOTIF_ID = 4211
        /** How long to wait before retrying a dropped connection, and the ceiling for backoff. */
        private const val RETRY_MS = 15_000L
        private const val RETRY_MAX_MS = 5 * 60_000L
        /** How long to let the Pusher handshake complete before calling it a failure. */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val POLL_MS = 500L

        fun start(context: Context) {
            val intent = Intent(context, ComposioTriggerService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { Log.w(TAG, "could not start", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ComposioTriggerService::class.java)) }
        }
    }

    @Inject lateinit var realtime: ComposioRealtimeClient
    @Inject lateinit var rules: NotificationRuleDao
    @Inject lateinit var executor: TriggerExecutor
    @Inject lateinit var settings: SettingsPreferences

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        scope.launch { connectWithRetry() }
    }

    /**
     * Keep the socket up. Composio's realtime endpoints are internal and undocumented, so treat
     * a drop as routine rather than exceptional: back off and try again instead of dying.
     */
    private suspend fun connectWithRetry() {
        var wait = RETRY_MS
        while (true) {
            if (!settings.loadComposioApiKey().isNotBlank()) {
                Log.i(TAG, "no Composio key — stopping")
                stopSelf(); return
            }
            val started = realtime.connect { event -> scope.launch { dispatch(event) } }
            // Pusher connects asynchronously, so connect() returning true only means the socket
            // was ASKED to open. Checking isConnected straight away always saw false, logged a
            // drop and reconnected immediately — a tight loop that tore down the socket each
            // time. Give the handshake a moment to land before believing it failed.
            var waited = 0L
            while (started && !realtime.isConnected && waited < CONNECT_TIMEOUT_MS) {
                delay(POLL_MS)
                waited += POLL_MS
            }

            if (started && realtime.isConnected) {
                wait = RETRY_MS
                Log.i(TAG, "realtime connected")
                // Connected. Poll gently so a silent drop is noticed and re-established.
                while (realtime.isConnected) delay(30_000)
                Log.i(TAG, "realtime connection dropped — reconnecting")
            } else {
                Log.w(TAG, "connect failed; retrying in ${wait / 1000}s")
                delay(wait)
                wait = (wait * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    /** Run every enabled rule whose trigger slug matches this event. */
    private suspend fun dispatch(event: ComposioTriggerEvent) {
        val matched = runCatching { rules.composioEnabled() }.getOrDefault(emptyList())
            .filter { rule ->
                // appPackage carries the trigger slug; blank means "any trigger from Composio".
                rule.appPackage.isBlank() ||
                    rule.appPackage.equals(event.slug, ignoreCase = true) ||
                    rule.appPackage.equals(event.toolkit, ignoreCase = true)
            }
        if (matched.isEmpty()) return
        Log.i(TAG, "${event.slug} matched ${matched.size} rule(s)")

        // Give the agent something to act on: a Gmail trigger's payload carries the subject and
        // sender, and without them a task like "reply to it" has no idea what "it" is.
        val summary = summarise(event)
        for (rule in matched) {
            runCatching {
                executor.execute(rule, "${event.toolkit.ifBlank { "Composio" }} trigger", summary)
            }.onFailure { Log.e(TAG, "composio trigger execution failed", it) }
        }
    }

    /** (app, title, text) pulled from a trigger payload, matching TriggerExecutor's context. */
    private fun summarise(event: ComposioTriggerEvent): Triple<String, String, String> {
        val p = event.payload
        val title = listOf("subject", "title", "name", "summary")
            .firstNotNullOfOrNull { p.optString(it).takeIf { s -> s.isNotBlank() } }
            ?: event.slug
        val text = listOf("snippet", "body", "message", "text", "description", "preview")
            .firstNotNullOfOrNull { p.optString(it).takeIf { s -> s.isNotBlank() } }
            ?: p.toString().take(400)
        val app = event.toolkit.replaceFirstChar { it.uppercase() }.ifBlank { "Composio" }
        return Triple(app, title, text)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cloud triggers", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Keeps Omni listening for Gmail, Slack and other app events." }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni is watching your apps")
            .setContentText("Listening for cloud triggers")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        realtime.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
