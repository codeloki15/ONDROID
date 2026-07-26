package com.locallink.pro.service.pilot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.locallink.pro.R
import com.locallink.pro.service.llm.AgentEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An ongoing notification for the automation that is currently driving the phone.
 *
 * Automation takes over the screen and then leaves the app it came from, so until now the only
 * sign it was alive was a floating STOP pill that said nothing about what it was doing or how far
 * along it was. The shade is the one surface still reachable from inside whatever app the pilot
 * has wandered into.
 *
 * Not a foreground service: the accessibility service is bound for the whole run and already
 * keeps the process alive, so an ordinary ongoing notification is enough and avoids a second
 * FGS type.
 */
@Singleton
class AutomationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val CHANNEL_ID = "automation_progress"
        private const val NOTIF_ID = 4712
        const val ACTION_STOP = "com.locallink.pro.STOP_AUTOMATION"
    }

    private var progress: RunProgress? = null

    fun start(task: String, queued: Int) {
        progress = RunProgress(task).also { it.queued = queued }
        ensureChannel()
        ensureStopReceiver()
        show()
    }

    fun onEvent(event: AgentEvent) {
        val p = progress ?: return
        p.onEvent(event)
        show()
    }

    fun stop() {
        progress = null
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun show() {
        val p = progress ?: return
        // Missing POST_NOTIFICATIONS is normal on API 33+ until the user grants it. Progress is a
        // convenience, so it degrades to silence rather than taking the run down with it.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = p.percent()
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(p.task)
            .setContentText(p.detail())
            .setSubText(p.status())
            .setStyle(NotificationCompat.BigTextStyle().bigText("${p.detail()}\n${p.status()}"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)          // it updates every step; it must never buzz
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, "Stop", stop)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automation progress", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows what Omni is doing while it drives your phone." },
        )
    }

    private var receiverRegistered = false

    /**
     * Stop from the shade, routed to the same flag the floating pill sets — one way to cancel, so
     * the two can't disagree about whether a run is still wanted.
     */
    private fun ensureStopReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                OmniAccessibilityService.instance?.cancelFlag?.set(true)
            }
        }
        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }
}
