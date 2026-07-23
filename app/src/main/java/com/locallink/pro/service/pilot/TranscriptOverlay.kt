package com.locallink.pro.service.pilot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.locallink.pro.R

/**
 * "Hey Omni" live-transcription floater — appears over ANY app the moment the wake word
 * fires, streams the partial transcript while the user speaks, then hands off. Drawn via
 * TYPE_ACCESSIBILITY_OVERLAY like the STOP pill and input floater.
 */
class TranscriptOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var textView: TextView? = null

    private fun dp(v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun font(res: Int): Typeface? = runCatching { ResourcesCompat.getFont(ctx, res) }.getOrNull()

    fun show() {
        if (view != null) return
        val semibold = font(R.font.epilogue_semibold)
        val regular = font(R.font.epilogue_regular)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.parseColor("#EE221F20"))
                setStroke(dp(1f), Color.parseColor("#66AC1ED6"))
            }
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            elevation = dp(10f).toFloat()
        }
        root.addView(TextView(ctx).apply {
            text = "● Hey Omni"
            setTextColor(Color.parseColor("#E47FBE")); textSize = 13f
            typeface = semibold
        })
        textView = TextView(ctx).apply {
            text = "Listening…"
            setTextColor(Color.parseColor("#F7F3F5")); textSize = 16f
            typeface = regular
            setPadding(0, dp(6f), 0, 0)
            maxLines = 3
        }
        root.addView(textView)

        val lp = WindowManager.LayoutParams(
            dp(320f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(52f) }
        runCatching { wm.addView(root, lp) }
        view = root
    }

    fun update(text: String) {
        textView?.post { textView?.text = text.ifBlank { "Listening…" } }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        textView = null
    }
}
