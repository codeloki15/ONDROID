package com.locallink.pro.service.call

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Re-registers the call-state listener after READ_PHONE_STATE is granted at runtime. */
object CallAssistUtil {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun controller(): CallAssistController
    }

    fun restart(context: Context) {
        runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
                .controller().start()
        }
    }
}
