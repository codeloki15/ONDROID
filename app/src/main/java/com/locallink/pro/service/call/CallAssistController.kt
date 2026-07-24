package com.locallink.pro.service.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.llm.OpenRouterClient
import com.locallink.pro.service.voice.VoiceService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-call assistant (BETA): when enabled and a call goes off-hook, flips on the
 * speakerphone and runs a listen → think → speak loop so Omni can converse on the
 * user's behalf. Android offers no API to inject audio into the call uplink, so this
 * uses the classic speakerphone bridge: the far side hears the phone's TTS through
 * the mic; the mic hears the far side through the loudspeaker.
 *
 * Constraints: works only on speakerphone; STT quality depends on room acoustics;
 * the mic is single-owner (hands-free wake listening pauses during a call).
 */
@Singleton
class CallAssistController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsPreferences,
    private val voice: VoiceService,
    private val openRouter: OpenRouterClient,
) {
    companion object {
        private const val TAG = "CallAssist"
        private const val PERSONA =
            "You are Omni, speaking ALOUD on a live phone call on behalf of your user. " +
            "Reply with ONLY the words to say — short, natural, polite, one or two sentences. " +
            "If the caller asks something you can't answer for the user, say you'll pass it on."
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loopJob: Job? = null
    @Volatile private var registered = false

    /** Idempotent; safe without READ_PHONE_STATE (it just won't hear call events). */
    fun start() {
        if (registered) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "READ_PHONE_STATE not granted — call assist idle")
            return
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                tm.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) = onState(state)
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                tm.listen(object : PhoneStateListener() {
                    @Deprecated("deprecated in API 31")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
                }, PhoneStateListener.LISTEN_CALL_STATE)
            }
            registered = true
            Log.i(TAG, "call-state listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "register failed", e)
        }
    }

    private fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> scope.launch {
                if (!settings.loadCallAssist()) return@launch
                startLoop()
            }
            TelephonyManager.CALL_STATE_IDLE -> stopLoop()
            else -> {}
        }
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        Log.i(TAG, "call off-hook — starting in-call assistant")
        loopJob = scope.launch {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.isSpeakerphoneOn = true }
            delay(1500) // let the call audio settle
            voice.speakWhenReady("Hello, this is Omni, the assistant. How can I help?")
            while (loopJob?.isActive == true) {
                // wait for TTS to finish before opening the mic
                withTimeoutOrNull(20_000) { voice.isSpeaking.first { !it } }
                voice.startListening()
                val heard = withTimeoutOrNull(30_000) { voice.finalResult.first() }
                voice.stopListening()
                if (heard.isNullOrBlank()) continue
                Log.i(TAG, "call heard: $heard")
                val reply = runCatching {
                    openRouter.plainChat("$PERSONA\n\nThe caller just said: \"$heard\"")
                }.getOrDefault("")
                if (reply.isNotBlank()) voice.speak(reply)
            }
        }
    }

    private fun stopLoop() {
        val j = loopJob ?: return
        loopJob = null
        j.cancel()
        voice.stopListening()
        voice.stopSpeaking()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { am.isSpeakerphoneOn = false }
        Log.i(TAG, "call ended — assistant stopped")
    }
}
