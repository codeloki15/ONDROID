package com.locallink.pro.service.pilot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.locallink.pro.R

/** A floating STOP pill added via TYPE_ACCESSIBILITY_OVERLAY so it sits above every app. */
class PilotOverlay(private val ctx: Context, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    private fun dp(v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (view != null) return
        val semibold: Typeface? =
            runCatching { ResourcesCompat.getFont(ctx, R.font.epilogue_semibold) }.getOrNull()
        val btn = TextView(ctx).apply {
            text = "■  STOP"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = semibold
            letterSpacing = 0.06f
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#E5484D"), Color.parseColor("#C42B45")),
            ).apply { cornerRadius = dp(24f).toFloat() }
            setPadding(dp(18f), dp(11f), dp(18f), dp(11f))
            elevation = dp(8f).toFloat()
            setOnClickListener { onStop() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 120; x = dp(16f) }
        wm.addView(btn, lp)
        view = btn
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
