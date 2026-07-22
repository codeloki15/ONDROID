package com.locallink.pro.service.pilot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button

/** A floating STOP button added via TYPE_ACCESSIBILITY_OVERLAY so it sits above every app. */
class PilotOverlay(private val ctx: Context, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    fun show() {
        if (view != null) return
        val btn = Button(ctx).apply {
            text = "STOP"
            setBackgroundColor(Color.parseColor("#CC3B30"))
            setTextColor(Color.WHITE)
            setOnClickListener { onStop() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 120 }
        wm.addView(btn, lp)
        view = btn
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
