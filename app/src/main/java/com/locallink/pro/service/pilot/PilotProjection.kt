package com.locallink.pro.service.pilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One-way signal from the Pilot loop (no Activity ref) to [MainActivity]: "a run wants vision but
 * no projection is active — please prompt for screen-capture consent." The Activity observes
 * [requests] and launches the consent dialog. Best-effort: if the Activity isn't foreground, the
 * run simply proceeds tree-only.
 */
object PilotProjectionRequest {
    private val _requests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val requests: SharedFlow<Unit> = _requests
    fun request() { _requests.tryEmit(Unit) }
}

/**
 * Session-scoped holder for the screen-capture pipeline. MediaProjection consent is granted once
 * (via [PilotProjectionService.start] from an Activity) and kept alive here for the rest of the
 * session, so Pilot can grab a screenshot each step without re-prompting.
 */
object PilotProjectionHolder {
    private const val TAG = "PilotProjection"

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var capturer: ScreenCapturer? = null

    val isReady: Boolean get() = projection != null && capturer != null

    fun set(mp: MediaProjection, metrics: DisplayMetrics) {
        // Stop capturing if the projection is torn down by the system/user.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { clear() }
        }, null)
        projection = mp
        capturer = ScreenCapturer(metrics)
        Log.d(TAG, "projection ready")
    }

    /** Grab one JPEG of the current screen, or null if not available/failed. */
    suspend fun capture(): ByteArray? {
        val mp = projection ?: return null
        val cap = capturer ?: return null
        return runCatching { cap.capture(mp) }.getOrNull()
    }

    fun clear() {
        runCatching { projection?.stop() }
        projection = null
        capturer = null
        Log.d(TAG, "projection cleared")
    }
}

/**
 * Foreground service of type mediaProjection — required on API 29+ before a MediaProjection can be
 * used. Started by [MainActivity] with the consent result; it builds the projection, hands it to
 * [PilotProjectionHolder], and keeps a foreground notification so the capture stays legal.
 */
class PilotProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else it.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        startInForeground()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) { stopSelf(); return START_NOT_STICKY }
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        PilotProjectionHolder.set(mp, metrics)
        return START_STICKY
    }

    override fun onDestroy() {
        PilotProjectionHolder.clear()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "Omni Pilot screen access", NotificationManager.IMPORTANCE_LOW,
            ))
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Omni Pilot")
            .setContentText("Screen access is active while Pilot runs.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "pilot_projection"
        private const val NOTIF_ID = 4711
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        /** Start the projection service with an Activity's screen-capture consent result. */
        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, PilotProjectionService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
