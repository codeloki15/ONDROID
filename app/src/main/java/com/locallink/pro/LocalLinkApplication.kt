package com.locallink.pro

import android.app.Application
import com.locallink.pro.service.call.CallAssistController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LocalLinkApplication : Application() {
    @Inject lateinit var callAssist: CallAssistController

    override fun onCreate() {
        super.onCreate()
        // Registers the call-state listener (no-op until READ_PHONE_STATE is granted
        // and the in-call assistant toggle is on).
        callAssist.start()
    }
}
